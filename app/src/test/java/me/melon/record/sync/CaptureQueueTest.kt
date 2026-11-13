package me.melon.record.sync

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CaptureQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private val logs = mutableListOf<String>()
    private fun queue(dir: File) = CaptureQueue(dir) { message, error ->
        logs.add(message + (error?.message ?: ""))
    }
    private fun metas(dir: File) = dir.listFiles()!!.filter { it.name.endsWith(".meta") }
    private fun pair(dir: File, token: String, meta: String): File {
        File(dir, "$token.bin").writeBytes(byteArrayOf(0, -1, 10, 13))
        return File(dir, "$token.meta").apply { writeText(meta) }
    }

    @Test fun `enqueue preserves every byte and publishes complete metadata`() {
        val dir = temporary.newFolder()
        val bytes = ByteArray(65536) { it.toByte() }
        val queue = queue(dir)
        queue.enqueue("https://example/\npath", bytes, "application/octet-stream\r", "host", 123)
        val meta = metas(dir).single()
        val entry = CaptureQueue.readMeta(meta)
        assertArrayEquals(bytes, entry.payload.readBytes())
        assertEquals("https://example/ path", entry.url)
        assertEquals("application/octet-stream ", entry.contentType)
        assertEquals("host", entry.hostApp)
        assertEquals(123L, entry.timestamp)
        assertFalse(File(dir, "${entry.token}.meta.tmp").exists())
        var sent = false
        queue.sync(null, null) { capture ->
            assertArrayEquals(bytes, capture.payload.readBytes())
            sent = true
            true
        }
        assertTrue(sent)
        assertFalse(meta.exists())
        assertFalse(entry.payload.exists())
    }

    @Test fun `old two and three line metadata fall back to token timestamp`() {
        val dir = temporary.newFolder()
        for (text in listOf("url\nhost", "url\nhost\n", "url\nhost\ntype\n")) {
            val entry = CaptureQueue.readMeta(pair(dir, "1234_old", text))
            assertEquals("url", entry.url)
            assertEquals("host", entry.hostApp)
            assertEquals(1234L, entry.timestamp)
        }
    }

    @Test fun `invalid nonpositive and overflowing times fall back to token`() {
        val dir = temporary.newFolder()
        for (time in listOf("invalid", "0", "-1", "9999999999999999999999", "")) {
            assertEquals(4321L, CaptureQueue.readMeta(
                pair(dir, "4321_old", "url\nhost\ntype\n$time\n")
            ).timestamp)
        }
        assertEquals(9876L, CaptureQueue.readMeta(
            pair(dir, "bad_token", "url\nhost\ntype\n 9876 \n")
        ).timestamp)
        assertEquals(456L, CaptureQueue.readMeta(pair(dir, "456", "url\nhost")).timestamp)
    }

    @Test fun `invalid token falls back to current time`() {
        val dir = temporary.newFolder()
        for (token in listOf("invalid", "-1_old", "0_old")) {
            val start = System.currentTimeMillis()
            val timestamp = CaptureQueue.readMeta(pair(dir, token, "url\nhost")).timestamp
            assertTrue(timestamp in start..System.currentTimeMillis())
        }
    }

    @Test fun `same timestamp produces unique committed pairs`() {
        val dir = temporary.newFolder()
        val queue = queue(dir)
        repeat(50) { queue.enqueue("url", byteArrayOf(it.toByte()), null, "host", 1) }
        assertEquals(50, metas(dir).size)
        assertEquals(50, metas(dir).map { CaptureQueue.readMeta(it).payload.name }.toSet().size)
    }

    @Test fun `pending metadata and orphan payload are never consumed`() {
        val dir = temporary.newFolder()
        val meta = pair(dir, "1_pending", "url\nhost")
        val pending = File(dir, "1_pending.meta.tmp")
        assertTrue(meta.renameTo(pending))
        val orphan = File(dir, "2_orphan.bin").apply { writeText("incomplete") }
        val queue = queue(dir)
        queue.sync(null, null) { fail("Uncommitted capture consumed"); true }
        assertTrue(pending.exists())
        assertTrue(orphan.exists())
        assertTrue(File(dir, "1_pending.bin").exists())
        assertTrue(pending.renameTo(meta))
        var count = 0
        queue.sync(null, null) { count++; true }
        assertEquals(1, count)
    }

    @Test fun `failed enqueue cannot publish metadata`() {
        val blocker = temporary.newFile()
        assertThrows(IOException::class.java) {
            queue(blocker).enqueue("url", byteArrayOf(1), "type", "host", 1)
        }
        assertTrue(blocker.isFile)
    }

    @Test fun `bad metadata is logged and retained while later valid capture is sent`() {
        val dir = temporary.newFolder()
        val bad = pair(dir, "1_bad", "only one line\n")
        val badMetaBytes = bad.readBytes()
        val badPayload = File(dir, "1_bad.bin")
        val badPayloadBytes = badPayload.readBytes()
        val later = pair(dir, "2_later", "url\nhost")
        val sent = mutableListOf<String>()
        queue(dir).sync(null, null) { sent.add(it.token); true }
        assertEquals(listOf("2_later"), sent)
        assertArrayEquals(badMetaBytes, bad.readBytes())
        assertArrayEquals(badPayloadBytes, badPayload.readBytes())
        assertFalse(later.exists())
        assertFalse(File(dir, "2_later.bin").exists())
        assertTrue(logs.any { it.contains("Invalid capture metadata") })
    }

    @Test fun `captures are sent in filename order rather than creation or metadata time order`() {
        val dir = temporary.newFolder()
        for (token in listOf("3_last", "1_first", "2_middle")) {
            pair(dir, token, "url\nhost\ntype\n${4 - token.first().digitToInt()}\n")
        }
        val sent = mutableListOf<String>()
        queue(dir).sync(null, null) { sent.add(it.token); true }
        assertEquals(listOf("1_first", "2_middle", "3_last"), sent)
        assertTrue(metas(dir).isEmpty())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".bin") })
    }

    @Test fun `failure after success retains remaining pairs and retry resumes without resending success`() {
        for (mode in 0..2) {
            val dir = temporary.newFolder()
            val tokens = listOf("1_success", "2_failure", "3_pending")
            val originals = tokens.associateWith { token ->
                val meta = pair(dir, token, "url\nhost")
                meta.readBytes() to File(dir, "$token.bin").readBytes()
            }
            val queue = queue(dir)
            val sent = mutableListOf<String>()
            queue.sync(null, { payload ->
                if (mode == 0 && payload.name == "2_failure.bin") {
                    throw IOException("filter failure")
                }
                true
            }) {
                sent.add(it.token)
                if (it.token == "2_failure") {
                    if (mode == 2) throw IOException("transport failure")
                    false
                } else true
            }
            assertEquals(if (mode == 0) listOf("1_success") else tokens.take(2), sent)
            assertFalse(File(dir, "1_success.meta").exists())
            assertFalse(File(dir, "1_success.bin").exists())
            for (token in tokens.drop(1)) {
                assertArrayEquals(originals.getValue(token).first, File(dir, "$token.meta").readBytes())
                assertArrayEquals(originals.getValue(token).second, File(dir, "$token.bin").readBytes())
            }
            val retried = mutableListOf<String>()
            queue.sync(null, null) { retried.add(it.token); true }
            assertEquals(tokens.drop(1), retried)
            assertTrue(metas(dir).isEmpty())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".bin") })
        }
        assertEquals(3, logs.size)
    }

    @Test fun `missing payload is skipped without deleting metadata`() {
        val dir = temporary.newFolder()
        val missing = File(dir, "1_missing.meta").apply { writeText("broken") }
        pair(dir, "2_valid", "url\nhost")
        var count = 0
        queue(dir).sync(null, null) { count++; true }
        assertEquals(1, count)
        assertTrue(missing.exists())
    }

    @Test fun `false filter deletes pair without transmitting`() {
        val dir = temporary.newFolder()
        val meta = pair(dir, "1_filtered", "url\nhost")
        queue(dir).sync(null, { false }) { fail("Filtered payload sent"); true }
        assertFalse(meta.exists())
        assertFalse(File(dir, "1_filtered.bin").exists())
    }

    @Test fun `filter and transport failures retain pair and stop`() {
        for (mode in 0..2) {
            val dir = temporary.newFolder()
            val first = pair(dir, "1_first", "url\nhost")
            val second = pair(dir, "2_second", "url\nhost")
            var sent = 0
            queue(dir).sync(null, {
                if (mode == 0) throw IOException("filter failure")
                true
            }) {
                sent++
                if (mode == 2) throw IOException("transport failure")
                false
            }
            assertEquals(if (mode == 0) 0 else 1, sent)
            assertTrue(first.exists())
            assertTrue(second.exists())
            assertTrue(File(dir, "1_first.bin").exists())
        }
        assertEquals(3, logs.size)
    }

    @Test fun `preparation precedes every scan and failure preserves queue`() {
        val dir = temporary.newFolder()
        val queue = queue(dir)
        var prepared = 0
        var sent = 0
        repeat(2) {
            queue.sync({
                prepared++
                queue.enqueue("url", byteArrayOf(1), "type", "host", prepared.toLong())
            }, null) { sent++; true }
        }
        assertEquals(2, prepared)
        assertEquals(2, sent)
        val meta = pair(dir, "3_failed", "url\nhost")
        queue.sync({ throw IOException("prepare failure") }, null) { fail(); true }
        assertTrue(meta.exists())
        assertTrue(logs.any { it.contains("prepare failure") })
    }

    @Test fun `file lock excludes other consumers including their preparation`() {
        val dir = temporary.newFolder()
        val queue = queue(dir)
        RandomAccessFile(File(dir, ".sync.lock"), "rw").use { file ->
            file.channel.lock().use {
                queue.sync({ fail("Preparation ran without lock") }, null) { fail(); true }
            }
        }
        var prepared = false
        queue.sync({ prepared = true }, null) { true }
        assertTrue(prepared)
    }

    @Test fun `same instance reentry and second instance cannot enter preparation`() {
        val dir = temporary.newFolder()
        val queue = queue(dir)
        val other = queue(dir)
        queue.sync({
            queue.sync({ fail("Reentrant preparation") }, null) { fail(); true }
            other.sync({ fail("Second consumer preparation") }, null) { fail(); true }
        }, null) { true }
        assertTrue(logs.isEmpty())
    }

    @Test fun `concurrent sync does not enter preparation twice`() {
        val dir = temporary.newFolder()
        val queue = queue(dir)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val running = executor.submit {
                queue.sync({
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }, null) { true }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.sync({ fail("Concurrent preparation") }, null) { true }
            release.countDown()
            running.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
