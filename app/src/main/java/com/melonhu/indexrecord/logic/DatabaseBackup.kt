package com.melonhu.indexrecord.logic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.model.PendingRecordSnapshot
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RawData_
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.RecordIdentity
import com.melonhu.indexrecord.data.model.RecordSnapshot
import com.melonhu.indexrecord.data.model.RecordSnapshot_
import com.melonhu.indexrecord.data.model.RedditPendingRecord
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.UniversalRecord_
import com.melonhu.indexrecord.data.model.decodeSnapshotRecord
import com.melonhu.indexrecord.data.model.encodeSnapshotRecord
import com.melonhu.indexrecord.data.model.identity
import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal const val BACKUP_FORMAT = "index_record_backup"
internal const val BACKUP_VERSION = 1

private const val ENTRY_MANIFEST = "manifest.json"
private const val ENTRY_RECORDS = "records.jsonl"
private const val ENTRY_SNAPSHOTS = "snapshots.jsonl"
private const val ENTRY_RAW_DATA = "raw_data.jsonl"
private const val STREAM_CHUNK_SIZE = 500L

internal enum class BackupErrorReason {
    INVALID_ARCHIVE,
    UNSUPPORTED_VERSION,
    CORRUPT_DATA
}

internal class BackupException(
    val reason: BackupErrorReason,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

internal data class DatabaseOverview(
    val recordCount: Long,
    val snapshotCount: Long,
    val rawDataCount: Long
)

internal data class DatabaseExportResult(
    val recordCount: Long,
    val snapshotCount: Long,
    val rawDataCount: Long
)

internal data class DatabaseClearResult(
    val recordCount: Long,
    val snapshotCount: Long,
    val rawDataCount: Long
)

internal data class DatabaseImportResult(
    val recordsInserted: Int,
    val recordsMerged: Int,
    val recordsSkipped: Int,
    val snapshotsInserted: Int,
    val snapshotsSkipped: Int,
    val rawDataInserted: Int,
    val rawDataSkipped: Int
)

internal interface DatabaseBackupStorage {
    val repository: RecordRepository
    val history: RecordSnapshotStorage
    fun <T> readTx(block: () -> T): T
    fun <T> writeTx(block: () -> T): T
    fun countRecords(): Long
    fun countSnapshots(): Long
    fun countRawData(): Long
    fun streamRecords(consumer: (UniversalRecord) -> Unit)
    fun streamSnapshots(consumer: (RecordSnapshot) -> Unit)
    fun streamRawData(consumer: (RawData) -> Unit)
    fun hasMatchingRawData(raw: RawData): Boolean
    fun insertRawData(raw: RawData)
    fun clearAll()
}

internal class ObjectBoxBackupStorage(
    private val store: BoxStore = ObjectBox.store
) : DatabaseBackupStorage {
    private val recordBox = store.boxFor(UniversalRecord::class.java)
    private val snapshotBox = store.boxFor(RecordSnapshot::class.java)
    private val rawBox = store.boxFor(RawData::class.java)
    private val pendingSnapshotBox = store.boxFor(PendingRecordSnapshot::class.java)
    private val redditPendingBox = store.boxFor(RedditPendingRecord::class.java)

    override val repository: RecordRepository = ObjectBoxRecordRepository(recordBox)
    override val history: RecordSnapshotStorage = requireNotNull(repository.history)

    override fun <T> readTx(block: () -> T): T = store.callInReadTx(Callable { block() })

    override fun <T> writeTx(block: () -> T): T = store.callInTx(Callable { block() })

    override fun countRecords(): Long = recordBox.count()

    override fun countSnapshots(): Long = snapshotBox.count()

    override fun countRawData(): Long = rawBox.count()

    override fun streamRecords(consumer: (UniversalRecord) -> Unit) {
        val query = recordBox.query().order(UniversalRecord_.id).build()
        try {
            var offset = 0L
            while (true) {
                val chunk = query.find(offset, STREAM_CHUNK_SIZE)
                if (chunk.isEmpty()) break
                chunk.forEach(consumer)
                offset += chunk.size
            }
        } finally {
            query.close()
        }
    }

    override fun streamSnapshots(consumer: (RecordSnapshot) -> Unit) {
        val query = snapshotBox.query().order(RecordSnapshot_.id).build()
        try {
            var offset = 0L
            while (true) {
                val chunk = query.find(offset, STREAM_CHUNK_SIZE)
                if (chunk.isEmpty()) break
                chunk.forEach(consumer)
                offset += chunk.size
            }
        } finally {
            query.close()
        }
    }

    override fun streamRawData(consumer: (RawData) -> Unit) {
        val query = rawBox.query().order(RawData_.id).build()
        try {
            var offset = 0L
            while (true) {
                val chunk = query.find(offset, STREAM_CHUNK_SIZE)
                if (chunk.isEmpty()) break
                chunk.forEach(consumer)
                offset += chunk.size
            }
        } finally {
            query.close()
        }
    }

    override fun hasMatchingRawData(raw: RawData): Boolean {
        val query = rawBox.query()
            .equal(RawData_.timestamp, raw.timestamp)
            .equal(RawData_.hostApp, raw.hostApp, CASE_SENSITIVE)
            .equal(RawData_.url, raw.url, CASE_SENSITIVE)
            .build()
        return try {
            query.find().any { it.json == raw.json }
        } finally {
            query.close()
        }
    }

    override fun insertRawData(raw: RawData) {
        rawBox.put(raw.copy(id = 0L))
    }

    override fun clearAll() {
        recordBox.removeAll()
        snapshotBox.removeAll()
        rawBox.removeAll()
        pendingSnapshotBox.removeAll()
        redditPendingBox.removeAll()
    }
}

internal class DatabaseBackupManager(
    private val storage: DatabaseBackupStorage = ObjectBoxBackupStorage()
) {
    fun overview(): DatabaseOverview = storage.readTx {
        DatabaseOverview(
            recordCount = storage.countRecords(),
            snapshotCount = storage.countSnapshots(),
            rawDataCount = storage.countRawData()
        )
    }

    fun clearDatabase(): DatabaseClearResult = storage.writeTx {
        val cleared = DatabaseClearResult(
            recordCount = storage.countRecords(),
            snapshotCount = storage.countSnapshots(),
            rawDataCount = storage.countRawData()
        )
        storage.clearAll()
        cleared
    }.also {
        if (storage is ObjectBoxBackupStorage) {
            com.melonhu.indexrecord.search.SearchRuntime.clearAllIndexes()
        }
    }

    fun exportZip(
        outputStream: OutputStream,
        includeRawData: Boolean,
        appVersionName: String,
        appVersionCode: Int,
        now: Long = System.currentTimeMillis()
    ): DatabaseExportResult = storage.readTx {
        var exportedRecords = 0L
        var exportedSnapshots = 0L
        var exportedRawData = 0L

        val expectedRecords = storage.countRecords()
        val expectedSnapshots = storage.countSnapshots()
        val expectedRawData = if (includeRawData) storage.countRawData() else 0L

        ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
            val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))

            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            writer.write(encodeManifest(now, appVersionName, appVersionCode, expectedRecords, expectedSnapshots, expectedRawData))
            writer.flush()
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_RECORDS))
            storage.streamRecords { record ->
                writer.write(encodeSnapshotRecord(record))
                writer.newLine()
                exportedRecords++
            }
            writer.flush()
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_SNAPSHOTS))
            storage.streamSnapshots { snapshot ->
                writer.write(encodeBackupSnapshot(snapshot))
                writer.newLine()
                exportedSnapshots++
            }
            writer.flush()
            zip.closeEntry()

            if (includeRawData && expectedRawData > 0L) {
                zip.putNextEntry(ZipEntry(ENTRY_RAW_DATA))
                storage.streamRawData { raw ->
                    writer.write(encodeBackupRawData(raw))
                    writer.newLine()
                    exportedRawData++
                }
                writer.flush()
                zip.closeEntry()
            }
        }

        DatabaseExportResult(
            recordCount = exportedRecords,
            snapshotCount = exportedSnapshots,
            rawDataCount = exportedRawData
        )
    }

    fun importZip(openInputStream: () -> InputStream): DatabaseImportResult {
        validateArchive(openInputStream)

        return storage.writeTx {
            var recordsInserted = 0
            var recordsMerged = 0
            var recordsSkipped = 0
            var snapshotsInserted = 0
            var snapshotsSkipped = 0
            var rawDataInserted = 0
            var rawDataSkipped = 0

            val persistence = RecordPersistence(storage.repository)
            val newlyInsertedIdentities = mutableSetOf<RecordIdentity>()

            openInputStream().use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        try {
                            when (entry.name) {
                                ENTRY_RECORDS -> forEachJsonLine(reader) { line ->
                                    val record = decodeValidatedRecord(line)
                                    val hadIdentityBefore = storage.repository
                                        .findIds(record.identity.storageIds().toList())
                                        .any { it.identity == record.identity }
                                    when (persistence.mergeImportedRecord(record)) {
                                        ImportRecordOutcome.INSERTED -> {
                                            if (!hadIdentityBefore) newlyInsertedIdentities.add(record.identity)
                                            recordsInserted++
                                        }
                                        ImportRecordOutcome.MERGED -> recordsMerged++
                                        ImportRecordOutcome.UNCHANGED -> recordsSkipped++
                                    }
                                }
                                ENTRY_SNAPSHOTS -> forEachJsonLine(reader) { line ->
                                    val snapshot = decodeBackupSnapshot(line)
                                    val identity = RecordIdentity(snapshot.platform, snapshot.identityId)
                                    if (identity !in newlyInsertedIdentities && !storage.history.hasIdentity(identity)) {
                                        archiveLegacyIndexForIdentity(identity, storage.repository, storage.history)
                                    }
                                    if (importSnapshot(snapshot)) snapshotsInserted++ else snapshotsSkipped++
                                }
                                ENTRY_RAW_DATA -> forEachJsonLine(reader) { line ->
                                    val raw = decodeBackupRawData(line)
                                    if (storage.hasMatchingRawData(raw)) {
                                        rawDataSkipped++
                                    } else {
                                        storage.insertRawData(raw)
                                        rawDataInserted++
                                    }
                                }
                            }
                        } catch (e: BackupException) {
                            throw e
                        } catch (e: Exception) {
                            throw BackupException(BackupErrorReason.CORRUPT_DATA, "Failed to parse ${entry.name}: ${e.message}", e)
                        } finally {
                            zip.closeEntry()
                        }
                    }
                }
            }

            DatabaseImportResult(
                recordsInserted = recordsInserted,
                recordsMerged = recordsMerged,
                recordsSkipped = recordsSkipped,
                snapshotsInserted = snapshotsInserted,
                snapshotsSkipped = snapshotsSkipped,
                rawDataInserted = rawDataInserted,
                rawDataSkipped = rawDataSkipped
            )
        }.also {
            val repo = storage.repository
            if (repo is ObjectBoxRecordRepository) {
                com.melonhu.indexrecord.search.SearchRuntime.onRecordsPersisted(repo.drainPersisted())
            }
        }
    }

    private fun importSnapshot(incoming: RecordSnapshot): Boolean {
        val existing = storage.history.findByKey(incoming.key)
        if (existing == null) {
            storage.history.append(incoming.copy(id = 0L))
            return true
        }
        if (sameSnapshotContent(existing, incoming)) return false

        val baseKey = disambiguatedSnapshotKey(incoming)
        var candidateKey = baseKey
        var suffix = 2
        while (true) {
            val collision = storage.history.findByKey(candidateKey)
            if (collision == null) {
                storage.history.append(incoming.copy(id = 0L, key = candidateKey))
                return true
            }
            if (sameSnapshotContent(collision, incoming)) return false
            candidateKey = "$baseKey:$suffix"
            suffix++
        }
    }

    private fun validateArchive(openInputStream: () -> InputStream) {
        var foundManifest = false
        var hasDataEntry = false
        openInputStream().use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        when (entry.name) {
                            ENTRY_MANIFEST -> {
                                foundManifest = true
                                validateManifest(reader.readText())
                            }
                            ENTRY_RECORDS, ENTRY_SNAPSHOTS, ENTRY_RAW_DATA -> hasDataEntry = true
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        }
        if (!foundManifest || !hasDataEntry) {
            throw BackupException(BackupErrorReason.INVALID_ARCHIVE, "Missing backup manifest or data entries")
        }
    }

    private fun validateManifest(text: String) {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            throw BackupException(BackupErrorReason.INVALID_ARCHIVE, "Invalid backup manifest JSON", e)
        }
        if (json.get("format")?.asString != BACKUP_FORMAT) {
            throw BackupException(BackupErrorReason.INVALID_ARCHIVE, "Unsupported backup format")
        }
        val version = json.get("version")?.asInt
        if (version != BACKUP_VERSION) {
            throw BackupException(BackupErrorReason.UNSUPPORTED_VERSION, "Unsupported backup version: $version")
        }
    }

    private fun forEachJsonLine(reader: BufferedReader, consumer: (String) -> Unit) {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isNotBlank()) consumer(line)
        }
    }

    private fun decodeValidatedRecord(line: String): UniversalRecord {
        val record = decodeSnapshotRecord(line)
        require(record.platform.isNotBlank() && record.platformId.isNotBlank()) { "Record missing platform or platformId" }
        RecordBodyFormat.fromStorage(record.contentFormat)
        return record
    }

    private fun encodeManifest(
        exportedAt: Long,
        appVersionName: String,
        appVersionCode: Int,
        recordsCount: Long,
        snapshotsCount: Long,
        rawDataCount: Long
    ): String = JsonObject().apply {
        addProperty("format", BACKUP_FORMAT)
        addProperty("version", BACKUP_VERSION)
        addProperty("exportedAt", exportedAt)
        addProperty("appVersionName", appVersionName)
        addProperty("appVersionCode", appVersionCode)
        addProperty("recordsCount", recordsCount)
        addProperty("snapshotsCount", snapshotsCount)
        addProperty("rawDataCount", rawDataCount)
    }.toString()

    private fun encodeBackupSnapshot(snapshot: RecordSnapshot): String = JsonObject().apply {
        addProperty("version", 1)
        addProperty("key", snapshot.key)
        addProperty("platform", snapshot.platform)
        addProperty("identityId", snapshot.identityId)
        addProperty("captureTime", snapshot.captureTime)
        addProperty("legacy", snapshot.legacy)
        addProperty("recordJson", snapshot.recordJson)
    }.toString()

    private fun decodeBackupSnapshot(line: String): RecordSnapshot {
        val json = JsonParser.parseString(line).asJsonObject
        require(json.get("version")?.asInt == 1) { "Unsupported backup snapshot version" }
        val key = json.get("key").asString
        val platform = json.get("platform").asString
        val identityId = json.get("identityId").asString
        val recordJson = json.get("recordJson").asString
        require(key.isNotBlank() && platform.isNotBlank() && identityId.isNotBlank()) { "Snapshot missing key fields" }
        val decoded = decodeSnapshotRecord(recordJson)
        RecordBodyFormat.fromStorage(decoded.contentFormat)
        return RecordSnapshot(
            key = key,
            platform = platform,
            identityId = identityId,
            captureTime = json.get("captureTime").asLong,
            legacy = json.get("legacy").asBoolean,
            recordJson = recordJson
        )
    }

    private fun encodeBackupRawData(raw: RawData): String = JsonObject().apply {
        addProperty("version", 1)
        addProperty("url", raw.url)
        addProperty("json", raw.json)
        addProperty("hostApp", raw.hostApp)
        addProperty("timestamp", raw.timestamp)
        addProperty("isParsed", raw.isParsed)
    }.toString()

    private fun decodeBackupRawData(line: String): RawData {
        val json = JsonParser.parseString(line).asJsonObject
        require(json.get("version")?.asInt == 1) { "Unsupported backup raw data version" }
        val url = json.get("url").asString
        val payload = json.get("json").asString
        require(url.isNotBlank() || payload.isNotBlank()) { "RawData missing url and payload" }
        return RawData(
            url = url,
            json = payload,
            hostApp = json.get("hostApp").asString,
            timestamp = json.get("timestamp").asLong,
            isParsed = json.get("isParsed").asBoolean
        )
    }

    private fun sameSnapshotContent(a: RecordSnapshot, b: RecordSnapshot): Boolean =
        a.platform == b.platform &&
            a.identityId == b.identityId &&
            a.captureTime == b.captureTime &&
            a.legacy == b.legacy &&
            a.recordJson == b.recordJson

    private fun disambiguatedSnapshotKey(snapshot: RecordSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(snapshot.platform.toByteArray(Charsets.UTF_8))
            update(0)
            update(snapshot.identityId.toByteArray(Charsets.UTF_8))
            update(0)
            update(snapshot.captureTime.toString().toByteArray(Charsets.UTF_8))
            update(0)
            update(snapshot.legacy.toString().toByteArray(Charsets.UTF_8))
            update(0)
            update(snapshot.recordJson.toByteArray(Charsets.UTF_8))
        }.digest()
        val hex = digest.take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "import:$hex:${snapshot.key}"
    }
}
