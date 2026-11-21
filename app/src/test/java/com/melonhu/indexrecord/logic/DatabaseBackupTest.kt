package com.melonhu.indexrecord.logic

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordIdentity
import com.melonhu.indexrecord.data.model.RecordSnapshot
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.recordSnapshot
import com.melonhu.indexrecord.data.model.toRecord
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DatabaseBackupTest {
    private class MemoryBackupStorage : DatabaseBackupStorage {
        private val backingRecords = MemoryRecordRepository()
        val snapshots = linkedMapOf<String, RecordSnapshot>()
        val rawRows = mutableListOf<RawData>()
        private var nextSnapshotId = 1L
        private var nextRawId = 1L

        override val history = object : RecordSnapshotStorage {
            override fun contains(key: String) = key in snapshots
            override fun findByKey(key: String) = snapshots[key]?.copy()
            override fun hasIdentity(identity: RecordIdentity) = snapshots.values.any {
                it.platform == identity.platform && it.identityId == identity.contentId
            }
            override fun append(snapshot: RecordSnapshot) {
                check(snapshot.key !in snapshots) { "duplicate snapshot key: ${snapshot.key}" }
                val stored = snapshot.copy(id = nextSnapshotId++)
                snapshots[stored.key] = stored
            }
        }

        override val repository: RecordRepository = object : RecordRepository by backingRecords {
            override val history: RecordSnapshotStorage = this@MemoryBackupStorage.history
            override fun put(record: UniversalRecord) {
                archiveLegacyIndex(record, this, history)
                backingRecords.put(record)
            }
        }

        val records: MutableMap<String, UniversalRecord> get() = backingRecords.rows

        override fun <T> readTx(block: () -> T): T = block()

        override fun <T> writeTx(block: () -> T): T {
            val savedRecords = backingRecords.rows.mapValues { (_, v) -> v.copy() }
            val savedSnapshots = snapshots.mapValues { (_, v) -> v.copy() }
            val savedRaw = rawRows.map { it.copy() }
            return try {
                block()
            } catch (t: Throwable) {
                backingRecords.rows.clear()
                backingRecords.rows.putAll(savedRecords)
                snapshots.clear()
                snapshots.putAll(savedSnapshots)
                rawRows.clear()
                rawRows.addAll(savedRaw)
                throw t
            }
        }

        override fun countRecords(): Long = backingRecords.rows.size.toLong()
        override fun countSnapshots(): Long = snapshots.size.toLong()
        override fun countRawData(): Long = rawRows.size.toLong()
        override fun streamRecords(consumer: (UniversalRecord) -> Unit) =
            backingRecords.rows.values.map { it.copy() }.forEach(consumer)
        override fun streamSnapshots(consumer: (RecordSnapshot) -> Unit) =
            snapshots.values.map { it.copy() }.forEach(consumer)
        override fun streamRawData(consumer: (RawData) -> Unit) =
            rawRows.map { it.copy() }.forEach(consumer)
        override fun hasMatchingRawData(raw: RawData): Boolean = rawRows.any {
            it.timestamp == raw.timestamp && it.hostApp == raw.hostApp && it.url == raw.url && it.json == raw.json
        }
        override fun insertRawData(raw: RawData) {
            rawRows.add(raw.copy(id = nextRawId++))
        }
        override fun clearAll() {
            backingRecords.rows.clear()
            snapshots.clear()
            rawRows.clear()
        }
    }

    private fun sampleRecord(
        id: Long = 0L,
        platform: String = "zhihu",
        recordType: String = "answer",
        platformId: String = "zhihu_answer_1",
        title: String = "问题标题",
        content: String = "正文内容",
        captureTime: Long = 1000L,
        publishTime: Long = 900L,
        sourceApp: String? = "com.zhihu.android",
        parentId: String = "",
        extraMetadata: String = "{}"
    ) = UniversalRecord(
        id = id,
        platform = platform,
        recordType = recordType,
        sourceApp = sourceApp,
        platformId = platformId,
        title = title,
        content = content,
        authorId = "u1",
        authorName = "作者",
        authorAvatar = "https://avatar",
        sourceUrl = "https://zhihu.com/answer/1",
        parentId = parentId,
        publishTime = publishTime,
        captureTime = captureTime,
        extraMetadata = extraMetadata,
        contentFormat = "markdown"
    )

    @Test
    fun exportAndImportIntoEmptyDatabaseRoundTripsWithoutCreatingSpuriousLegacySnapshots() {
        val source = MemoryBackupStorage()
        val r1 = sampleRecord(id = 42, sourceApp = null)
        source.records[r1.platformId] = r1
        val snap = recordSnapshot("raw:42:5:zhihu:zhihu_answer_1:0", r1)
        source.history.append(snap)
        source.insertRawData(RawData(id = 9, url = "https://api.example/1", json = "{\"k\":1}", hostApp = "com.zhihu.android", timestamp = 555L))

        val bytesWithoutRaw = ByteArrayOutputStream().also {
            val exported = DatabaseBackupManager(source).exportZip(it, includeRawData = false, appVersionName = "0.7.0", appVersionCode = 10)
            assertEquals(1L, exported.recordCount)
            assertEquals(1L, exported.snapshotCount)
            assertEquals(0L, exported.rawDataCount)
        }.toByteArray()

        val target = MemoryBackupStorage()
        val imported = DatabaseBackupManager(target).importZip { ByteArrayInputStream(bytesWithoutRaw) }
        assertEquals(DatabaseImportResult(1, 0, 0, 1, 0, 0, 0), imported)
        assertEquals(1, target.records.size)
        assertEquals(1, target.snapshots.size)
        assertEquals(0, target.rawRows.size)
        val restored = target.records.getValue(r1.platformId)
        assertEquals(1L, restored.id)
        assertNull(restored.sourceApp)
        assertEquals("正文内容", restored.content)
        assertFalse(target.snapshots.values.any { it.legacy })

        val bytesWithRaw = ByteArrayOutputStream().also {
            val exported = DatabaseBackupManager(source).exportZip(it, includeRawData = true, appVersionName = "0.7.0", appVersionCode = 10)
            assertEquals(1L, exported.rawDataCount)
        }.toByteArray()

        val secondImport = DatabaseBackupManager(target).importZip { ByteArrayInputStream(bytesWithRaw) }
        assertEquals(DatabaseImportResult(0, 0, 1, 0, 1, 1, 0), secondImport)
        assertEquals(1, target.rawRows.size)
    }

    @Test
    fun crossDeviceIncrementalMergePreservesNewerContentAndFillsMissingFieldsBothWays() {
        val deviceA = MemoryBackupStorage()
        val deviceB = MemoryBackupStorage()

        // Record 1: Device A is newer (captureTime=2000) but lacks title & sourceApp; Device B is older (1000) with title & sourceApp
        val a1 = sampleRecord(id = 1, platformId = "zhihu_answer_1", title = "", content = "新版正文", captureTime = 2000L, sourceApp = null)
        val b1 = sampleRecord(id = 10, platformId = "zhihu_answer_1", title = "原标题", content = "旧版正文", captureTime = 1000L, sourceApp = "com.zhihu.android")
        deviceA.records[a1.platformId] = a1
        deviceA.history.append(recordSnapshot("raw:1:5:zhihu:zhihu_answer_1:0", a1))
        deviceB.records[b1.platformId] = b1
        deviceB.history.append(recordSnapshot("raw:1:5:zhihu:zhihu_answer_1:0", b1))

        // Record 2: Device A is OLDER (500) with full Weibo text; Device B is NEWER (3000) with truncated preview
        val a2 = sampleRecord(
            id = 2, platform = "weibo", recordType = "status", platformId = "weibo_2",
            title = "", content = "非常完整的长微博全文内容", captureTime = 500L,
            extraMetadata = """{"full_text":true,"reposts_count":10}"""
        )
        val b2 = sampleRecord(
            id = 11, platform = "weibo", recordType = "status", platformId = "weibo_2",
            title = "", content = "截断预览", captureTime = 3000L,
            extraMetadata = """{"full_text":false,"reposts_count":42}"""
        )
        deviceA.records[a2.platformId] = a2
        deviceB.records[b2.platformId] = b2

        // Record 3: only on Device A
        val a3 = sampleRecord(id = 3, platformId = "zhihu_answer_3", content = "仅A有", captureTime = 1500L)
        deviceA.records[a3.platformId] = a3
        deviceA.history.append(recordSnapshot("raw:3:5:zhihu:zhihu_answer_3:0", a3))

        // Record 4: only on Device B
        val b4 = sampleRecord(id = 12, platformId = "zhihu_answer_4", content = "仅B有", captureTime = 1600L)
        deviceB.records[b4.platformId] = b4

        val backupA = ByteArrayOutputStream().also {
            DatabaseBackupManager(deviceA).exportZip(it, includeRawData = false, "0.7.0", 1)
        }.toByteArray()

        val result1 = DatabaseBackupManager(deviceB).importZip { ByteArrayInputStream(backupA) }
        // Records: a3 inserted (1), a1 & a2 merged (2), b4 untouched
        assertEquals(1, result1.recordsInserted)
        assertEquals(2, result1.recordsMerged)
        assertEquals(0, result1.recordsSkipped)
        // Snapshots: a1 (disambiguated because key raw:1:... collided with b1!) + a3 inserted = 2
        assertEquals(2, result1.snapshotsInserted)
        assertEquals(0, result1.snapshotsSkipped)

        val merged1 = deviceB.records.getValue("zhihu_answer_1")
        assertEquals(10L, merged1.id)
        assertEquals("新版正文", merged1.content)
        assertEquals("原标题", merged1.title)
        assertEquals("com.zhihu.android", merged1.sourceApp)
        assertEquals(2000L, merged1.captureTime)

        val merged2 = deviceB.records.getValue("weibo_2")
        assertEquals("非常完整的长微博全文内容", merged2.content)
        assertEquals(3000L, merged2.captureTime)
        val merged2Meta = JsonParser.parseString(merged2.extraMetadata).asJsonObject
        assertEquals(42, merged2Meta.get("reposts_count").asInt)
        assertTrue(merged2Meta.get("full_text").asBoolean)

        assertTrue("zhihu_answer_3" in deviceB.records)
        assertTrue("zhihu_answer_4" in deviceB.records)

        // Both Device A and Device B snapshots for zhihu_answer_1 were retained!
        val answer1Snapshots = deviceB.snapshots.values.filter { it.identityId == "zhihu_answer_1" }
        assertEquals(2, answer1Snapshots.size)
        assertEquals(setOf(1000L, 2000L), answer1Snapshots.map { it.captureTime }.toSet())

        // Re-importing the exact same backup must be 100% idempotent
        val result2 = DatabaseBackupManager(deviceB).importZip { ByteArrayInputStream(backupA) }
        assertEquals(DatabaseImportResult(0, 0, 3, 0, 2, 0, 0), result2)
        assertEquals(2, deviceB.snapshots.values.count { it.identityId == "zhihu_answer_1" })
    }

    @Test
    fun preExistingUnsnapshottedLocalRecordIsFrozenBeforeImportedSnapshotCoversItsIdentity() {
        val source = MemoryBackupStorage()
        val remoteRecord = sampleRecord(id = 1, platformId = "zhihu_answer_9", content = "远端内容", captureTime = 2000L)
        source.records[remoteRecord.platformId] = remoteRecord
        source.history.append(recordSnapshot("raw:1:5:zhihu:zhihu_answer_9:0", remoteRecord))

        val zipBytes = ByteArrayOutputStream().also {
            DatabaseBackupManager(source).exportZip(it, includeRawData = false, "0.7.0", 1)
        }.toByteArray()

        val target = MemoryBackupStorage()
        // Target already has an old un-snapshotted UniversalRecord (identical or different)
        val localOld = sampleRecord(id = 55, platformId = "zhihu_answer_9", content = "远端内容", captureTime = 2000L)
        target.records[localOld.platformId] = localOld

        val imported = DatabaseBackupManager(target).importZip { ByteArrayInputStream(zipBytes) }
        assertEquals(0, imported.recordsInserted)
        assertEquals(0, imported.recordsMerged)
        assertEquals(1, imported.recordsSkipped)
        assertEquals(1, imported.snapshotsInserted)
        // Target's pre-existing un-snapshotted record was archived as a legacy snapshot AND remote snapshot was added
        assertEquals(2, target.snapshots.size)
        assertTrue(target.snapshots.values.any { it.legacy })
        assertTrue(target.snapshots.values.any { !it.legacy && it.toRecord().content == "远端内容" })
    }

    @Test
    fun snapshotsEntryBeforeRecordsEntryImportsCleanly() {
        val record = sampleRecord(id = 1, platformId = "zhihu_answer_7", content = "内容7", captureTime = 700L)
        val snap = recordSnapshot("raw:7:5:zhihu:zhihu_answer_7:0", record)
        val manifestJson = """{"format":"index_record_backup","version":1,"exportedAt":1,"appVersionName":"0.7.0","appVersionCode":1,"recordsCount":1,"snapshotsCount":1,"rawDataCount":0}"""
        val recordLine = """{"version":1,"id":1,"platform":"zhihu","recordType":"answer","sourceApp":"com.zhihu.android","platformId":"zhihu_answer_7","title":"问题标题","content":"内容7","authorId":"u1","authorName":"作者","authorAvatar":"https://avatar","sourceUrl":"https://zhihu.com/answer/1","parentId":"","publishTime":900,"captureTime":700,"extraMetadata":"{}","contentFormat":"markdown"}"""
        val snapLine = """{"version":1,"key":"${snap.key}","platform":"${snap.platform}","identityId":"${snap.identityId}","captureTime":700,"legacy":false,"recordJson":${JsonParser.parseString(com.google.gson.Gson().toJson(snap.recordJson))}}"""

        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("snapshots.jsonl"))
                zip.write((snapLine + "\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("records.jsonl"))
                zip.write((recordLine + "\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()

        val target = MemoryBackupStorage()
        val result = DatabaseBackupManager(target).importZip { ByteArrayInputStream(bytes) }
        assertEquals(DatabaseImportResult(1, 0, 0, 1, 0, 0, 0), result)
        assertEquals("内容7", target.records.getValue("zhihu_answer_7").content)
    }

    @Test
    fun corruptBackupRollsBackTransactionAndRejectsInvalidContentFormat() {
        val target = MemoryBackupStorage()
        val existing = sampleRecord(id = 1, platformId = "zhihu_answer_existing", content = "保留不变")
        target.records[existing.platformId] = existing

        // Archive with one valid record followed by one invalid contentFormat
        val manifest = """{"format":"index_record_backup","version":1,"exportedAt":1,"appVersionName":"0.7.0","appVersionCode":1,"recordsCount":2,"snapshotsCount":0,"rawDataCount":0}"""
        val validLine = """{"version":1,"id":1,"platform":"zhihu","recordType":"answer","sourceApp":null,"platformId":"zhihu_answer_new","title":"t","content":"c","authorId":"a","authorName":"n","authorAvatar":"","sourceUrl":"","parentId":"","publishTime":1,"captureTime":1,"extraMetadata":"{}","contentFormat":"plain_text"}"""
        val badFormatLine = """{"version":1,"id":2,"platform":"zhihu","recordType":"answer","sourceApp":null,"platformId":"zhihu_answer_bad","title":"t","content":"c","authorId":"a","authorName":"n","authorAvatar":"","sourceUrl":"","parentId":"","publishTime":1,"captureTime":1,"extraMetadata":"{}","contentFormat":"unknown_format"}"""

        val corruptZip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("records.jsonl"))
                zip.write((validLine + "\n" + badFormatLine + "\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()

        val error = assertThrows(BackupException::class.java) {
            DatabaseBackupManager(target).importZip { ByteArrayInputStream(corruptZip) }
        }
        assertEquals(BackupErrorReason.CORRUPT_DATA, error.reason)
        // Entire transaction rolled back: zhihu_answer_new was NOT persisted
        assertEquals(setOf("zhihu_answer_existing"), target.records.keys)

        // Also test invalid archive (not a zip) and unsupported version
        val invalidError = assertThrows(BackupException::class.java) {
            DatabaseBackupManager(target).importZip { ByteArrayInputStream("not a zip".toByteArray()) }
        }
        assertEquals(BackupErrorReason.INVALID_ARCHIVE, invalidError.reason)

        val unsupportedVersionZip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"format":"index_record_backup","version":99}""".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("records.jsonl"))
                zip.closeEntry()
            }
        }.toByteArray()
        val versionError = assertThrows(BackupException::class.java) {
            DatabaseBackupManager(target).importZip { ByteArrayInputStream(unsupportedVersionZip) }
        }
        assertEquals(BackupErrorReason.UNSUPPORTED_VERSION, versionError.reason)
    }

    @Test
    fun clearDatabaseRemovesAllRecordsSnapshotsAndRawDataAndReturnsClearedCounts() {
        val storage = MemoryBackupStorage()
        val r1 = sampleRecord(id = 1, platformId = "zhihu_answer_1")
        val r2 = sampleRecord(id = 2, platformId = "zhihu_answer_2")
        storage.records[r1.platformId] = r1
        storage.records[r2.platformId] = r2
        storage.history.append(recordSnapshot("raw:1:5:zhihu:zhihu_answer_1:0", r1))
        storage.insertRawData(RawData(url = "https://api.example/1", json = "{}", hostApp = "com.zhihu.android", timestamp = 1L))

        val manager = DatabaseBackupManager(storage)
        val cleared = manager.clearDatabase()
        assertEquals(DatabaseClearResult(2L, 1L, 1L), cleared)
        assertEquals(DatabaseOverview(0L, 0L, 0L), manager.overview())
    }
}
