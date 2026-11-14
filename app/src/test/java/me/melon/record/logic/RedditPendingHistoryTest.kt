package me.melon.record.logic

import me.melon.record.data.model.PendingRecordSnapshot
import me.melon.record.data.model.RecordIdentity
import me.melon.record.data.model.RecordSnapshot
import me.melon.record.data.model.RedditPendingRecord
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toRecord
import me.melon.record.data.model.toSnapshot
import me.melon.record.parser.ParseDiagnostic
import me.melon.record.parser.ParseResult
import me.melon.record.parser.reddit.RedditReadBuffer
import org.junit.Assert.*
import org.junit.Test

class RedditPendingHistoryTest {
    private class History : RecordSnapshotStorage {
        val rows = linkedMapOf<String, RecordSnapshot>()
        val waiting = linkedMapOf<String, PendingRecordSnapshot>()
        var inTransaction = false
        override fun contains(key: String) = key in rows
        override fun hasIdentity(identity: RecordIdentity) = rows.values.any {
            it.platform == identity.platform && it.identityId == identity.contentId
        }
        override fun append(snapshot: RecordSnapshot) {
            check(inTransaction)
            check(snapshot.key !in rows)
            rows[snapshot.key] = snapshot.copy()
        }
        override fun findPending(key: String) = waiting[key]?.copy()
        override fun putPending(snapshot: PendingRecordSnapshot) {
            check(inTransaction)
            check(snapshot.key !in waiting)
            waiting[snapshot.key] = snapshot.copy()
        }
        override fun pending() = waiting.values.map { it.copy() }
        override fun removePending(snapshot: PendingRecordSnapshot) {
            check(inTransaction)
            waiting.remove(snapshot.key)
        }
    }
    private class Buffer : RedditReadBuffer.Storage {
        val rows = linkedMapOf<String, RedditPendingRecord>()
        var inTransaction = false
        override fun find(platformId: String) = rows[platformId]?.copy()
        override fun forPost(postId: String) = rows.values.filter { it.postId == postId }.map { it.copy() }
        override fun pending() = rows.values.map { it.copy() }
        override fun put(row: RedditPendingRecord) {
            check(inTransaction)
            rows[row.platformId] = row.copy()
        }
        override fun remove(row: RedditPendingRecord) {
            check(inTransaction)
            rows.remove(row.platformId)
        }
    }
    private class Harness {
        val current = MemoryRecordRepository()
        val history = History()
        val buffer = Buffer()
        val repository = object : RecordRepository by current {
            override val history get() = this@Harness.history
            override fun put(record: UniversalRecord) {
                check(history.inTransaction)
                archiveLegacyIndex(record, this, history)
                current.put(record)
            }
        }
        fun <T> transaction(action: () -> T): T {
            val oldCurrent = current.rows.mapValues { it.value.copy() }
            val oldHistory = history.rows.mapValues { it.value.copy() }
            val oldWaiting = history.waiting.mapValues { it.value.copy() }
            val oldBuffer = buffer.rows.mapValues { it.value.copy() }
            history.inTransaction = true
            buffer.inTransaction = true
            return try { action() } catch (error: Exception) {
                current.rows.clear(); current.rows.putAll(oldCurrent)
                history.rows.clear(); history.rows.putAll(oldHistory)
                history.waiting.clear(); history.waiting.putAll(oldWaiting)
                buffer.rows.clear(); buffer.rows.putAll(oldBuffer)
                throw error
            } finally {
                history.inTransaction = false
                buffer.inTransaction = false
            }
        }
        fun process(
            rawId: Long, records: List<UniversalRecord>, proof: Set<String> = emptySet(),
            partial: Boolean = false, readMode: Boolean = true
        ): LongArray {
            // New session collaborators, same durable stores, as on a retained raw's next processing run.
            val policy = RedditCapturePolicy(if (readMode) RedditReadBuffer(buffer) else null) { 1000 }
            val persistence = RecordPersistence(repository)
            val processing = CaptureProcessing(listOf(policy))
            val inputs = records.map { it.copy() }
            val result = if (partial) ParseResult.Partial(inputs, listOf(ParseDiagnostic("test", "bad fragment")))
                else ParseResult.Success(inputs)
            val capture = processing.complete(ParsedCapture(rawId, inputs, policy, readPosts = proof, result = result), persistence)
            return transaction { processing.persist(listOf(capture), persistence) }
        }
    }
    private fun post(time: Long = 10, body: String = "A body", id: String = "reddit_post_1") = UniversalRecord(
        platform = "reddit", recordType = "post", platformId = id, title = "post", content = body, captureTime = time
    )

    @Test fun partialThenConfirmationThenPartialRetryPreservesOriginalKeysAndNeverArchivesSyntheticValue() {
        val h = Harness()
        val a = post()
        assertArrayEquals(longArrayOf(), h.process(1, listOf(a), partial = true))
        assertTrue(h.history.rows.isEmpty())
        assertTrue(h.current.rows.isEmpty())
        val pendingA = h.history.waiting.values.single().copy()
        assertEquals(rawSnapshotKey(1, a, 0), pendingA.key)

        val b = post(time = 20, body = "")
        assertArrayEquals(longArrayOf(2), h.process(2, listOf(b), setOf(a.platformId)))
        assertTrue(h.history.waiting.isEmpty())
        assertEquals(2, h.history.rows.size)
        assertEquals(pendingA.recordJson, h.history.rows.getValue(pendingA.key).recordJson)
        assertEquals(a, h.history.rows.getValue(rawSnapshotKey(1, a, 0)).toRecord())
        assertEquals(b, h.history.rows.getValue(rawSnapshotKey(2, b, 0)).toRecord())
        assertEquals("A body", h.current.rows.getValue(a.platformId).content)
        assertEquals(20L, h.current.rows.getValue(a.platformId).captureTime)
        assertTrue(h.history.rows.values.none { it.legacy })

        val before = h.history.rows.mapValues { it.value.copy() }
        h.process(1, listOf(a.copy(captureTime = 99)), partial = true)
        assertEquals(before, h.history.rows)
        assertTrue(h.history.waiting.isEmpty())
        h.process(3, listOf(a))
        assertEquals(3, h.history.rows.size)
        assertEquals(a, h.history.rows.getValue(rawSnapshotKey(3, a, 0)).toRecord())
    }

    @Test fun confirmationReleasesAllOriginalCapturesForEachAdmittedIdentityButNotOtherPosts() {
        val h = Harness()
        val root = post()
        val comment = post().copy(recordType = "comment", platformId = "reddit_comment_c",
            extraMetadata = "{\"post_id\":\"reddit_post_1\"}")
        val other = post(id = "reddit_post_other")
        h.process(1, listOf(root, comment, other))
        h.process(2, listOf(root.copy(content = "second"), comment.copy(content = "second comment")))
        h.process(3, emptyList(), setOf(root.platformId))
        assertEquals(4, h.history.rows.size)
        assertEquals(setOf(root.platformId, comment.platformId), h.history.rows.values.map { it.toRecord().platformId }.toSet())
        assertEquals(other.platformId, h.history.waiting.values.single().platformId)
        assertFalse(other.platformId in h.current.rows)
    }

    @Test fun repeatedSameRoleInOneRawKeepsBothOriginalsDespiteReadBufferCollapsingThem() {
        val h = Harness()
        val a = post()
        h.process(1, listOf(a, a.copy()), setOf(a.platformId))
        assertEquals(setOf(rawSnapshotKey(1, a, 0), rawSnapshotKey(1, a, 1)), h.history.rows.keys)
        h.process(1, listOf(a, a.copy()), setOf(a.platformId))
        assertEquals(2, h.history.rows.size)
    }

    @Test fun oldSyntheticBufferWithoutPendingOriginalsIsExplicitlyLegacy() {
        val h = Harness()
        val old = post(body = "old merged buffer")
        h.transaction { RedditReadBuffer(h.buffer).accept(listOf(old), emptySet(), 1000) }
        h.process(2, emptyList(), setOf(old.platformId))
        val archived = h.history.rows.values.single()
        assertTrue(archived.legacy)
        assertTrue(archived.key.startsWith("legacy:"))
        assertEquals(old, archived.toRecord())
        assertEquals(old.content, h.current.rows.getValue(old.platformId).content)
    }

    @Test fun allModeStillCapturesImmediatelyWithoutUsingPendingHistory() {
        val h = Harness()
        val a = post()
        h.process(1, listOf(a), readMode = false)
        assertEquals(a, h.history.rows.values.single().toRecord())
        assertTrue(h.history.waiting.isEmpty())
        assertTrue(h.buffer.rows.isEmpty())
        assertEquals(a.content, h.current.rows.getValue(a.platformId).content)
    }

    @Test fun oldBufferReleaseIsLegacyEvenWhenTheIdentityAlreadyHasRealHistory() {
        val h = Harness()
        val a = post()
        h.process(1, listOf(a), readMode = false)
        val old = post(time = 20, body = "old synthetic")
        h.transaction { RedditReadBuffer(h.buffer).accept(listOf(old), emptySet(), 1000) }
        h.process(2, emptyList(), setOf(old.platformId))
        assertEquals(2, h.history.rows.size)
        assertEquals(old, h.history.rows.values.single { it.legacy }.toRecord())
        assertEquals(a, h.history.rows.values.single { !it.legacy }.toRecord())
    }

    @Test fun partialRetryDoesNotRefreshCreationTimeAndExpiredOriginalsArePruned() {
        val h = Harness()
        val persistence = RecordPersistence(h.repository)
        val a = post()
        val b = post(id = "reddit_post_2")
        h.transaction {
            persistence.stageRedditHistory(1, listOf(a), now = 100)
            persistence.stageRedditHistory(1, listOf(a.copy(content = "retry")), now = 200)
            persistence.stageRedditHistory(2, listOf(b), now = 200)
        }
        val pending = h.history.waiting.getValue(rawSnapshotKey(1, a, 0))
        assertEquals(100L, pending.createdAt)
        assertEquals("A body", pending.toSnapshot().toRecord().content)
        h.transaction { prunePendingHistory(h.history, 100 + PENDING_HISTORY_TTL_MILLIS) }
        assertEquals(2, h.history.waiting.size)
        h.transaction { prunePendingHistory(h.history, 101 + PENDING_HISTORY_TTL_MILLIS) }
        assertEquals(b.platformId, h.history.waiting.values.single().platformId)
        assertTrue(h.history.rows.isEmpty())
    }

    @Test fun pendingQueueHasDeterministicCountBoundAndEvictsOldestFirst() {
        val h = Harness()
        val persistence = RecordPersistence(h.repository)
        val oldest = post(id = "reddit_post_old")
        h.transaction {
            persistence.stageRedditHistory(1, listOf(oldest), now = 100)
            persistence.stageRedditHistory(2, List(MAX_PENDING_HISTORY + 1) { post(id = "reddit_post_$it") }, now = 101)
        }
        assertEquals(MAX_PENDING_HISTORY, h.history.waiting.size)
        assertFalse(rawSnapshotKey(1, oldest, 0) in h.history.waiting)
        assertTrue(h.history.rows.isEmpty())
        assertTrue(h.current.rows.isEmpty())
    }

    @Test fun transactionRollbackRestoresPendingOriginalsReadProofAndHistoryForRetry() {
        val h = Harness()
        val a = post()
        h.process(1, listOf(a), partial = true)
        val pending = h.history.waiting.mapValues { it.value.copy() }
        h.current.failOnPut = true
        assertThrows(IllegalStateException::class.java) { h.process(2, listOf(post(time = 20, body = "")), setOf(a.platformId)) }
        assertEquals(pending, h.history.waiting)
        assertTrue(h.history.rows.isEmpty())
        assertFalse(h.buffer.rows.getValue(a.platformId).confirmed)
        assertTrue(h.current.rows.isEmpty())
        h.current.failOnPut = false
        assertArrayEquals(longArrayOf(2), h.process(2, listOf(post(time = 20, body = "")), setOf(a.platformId)))
        assertEquals(2, h.history.rows.size)
        assertTrue(h.history.waiting.isEmpty())
    }

}
