package com.melonhu.indexrecord.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.security.MessageDigest

internal object IndexStatus {
    const val PENDING = 0
    const val DONE = 1
    const val FAILED = 2
}

internal data class FtsHit(
    val recordId: Long,
    val bm25Score: Double
)

internal data class MatchedOcrImage(
    val imageId: Long = 0L,
    val imageUrl: String,
    val ocrText: String
)

internal data class OcrFtsHit(
    val recordId: Long,
    val bm25Score: Double,
    val matchedImages: List<MatchedOcrImage> = emptyList()
) {
    constructor(recordId: Long, bm25Score: Double, ocrText: String, imageUrl: String) : this(
        recordId = recordId,
        bm25Score = bm25Score,
        matchedImages = if (imageUrl.isNotBlank() || ocrText.isNotBlank()) {
            listOf(MatchedOcrImage(0L, imageUrl, ocrText))
        } else emptyList()
    )

    val ocrText: String get() = matchedImages.firstOrNull()?.ocrText.orEmpty()
    val imageUrl: String get() = matchedImages.firstOrNull()?.imageUrl.orEmpty()
}

internal data class PendingImageTask(
    val imageId: Long,
    val recordId: Long,
    val imageUrl: String,
    val downloadStatus: Int,
    val ocrStatus: Int,
    val vecStatus: Int
)

internal data class IndexedImageInfo(
    val imageId: Long,
    val recordId: Long,
    val imageUrl: String,
    val ocrText: String
)

internal data class IndexingCounts(
    val pendingText: Int,
    val doneText: Int,
    val failedText: Int,
    val pendingDownload: Int,
    val doneDownload: Int,
    val failedDownload: Int,
    val pendingOcr: Int,
    val doneOcr: Int,
    val pendingImageVec: Int,
    val doneImageVec: Int,
    val failedImageVec: Int
) {
    val totalText: Int get() = pendingText + doneText + failedText
    val totalImages: Int get() = pendingDownload + doneDownload + failedDownload
    val totalPending: Int get() = pendingText + pendingDownload + pendingImageVec
}

internal data class RecordTextInput(
    val recordId: Long,
    val title: String,
    val author: String,
    val bodyText: String,
    val imageUrls: List<String>,
    val captureTime: Long = 0L,
    val platform: String = "",
    val recordType: String = "",
    val sourceApp: String = ""
)

internal data class RecordMetadataUpdate(
    val recordId: Long,
    val captureTime: Long,
    val platform: String,
    val recordType: String,
    val sourceApp: String
)

internal class SearchFtsStore(
    dbPath: String,
    driver: SQLiteDriver = BundledSQLiteDriver()
) : AutoCloseable {

    private val lock = Any()
    private val connection: SQLiteConnection

    init {
        if (dbPath != ":memory:") {
            File(dbPath).parentFile?.mkdirs()
        }
        connection = driver.open(dbPath)
        initSchema()
    }

    private fun initSchema() = synchronized(lock) {
        connection.exec("PRAGMA journal_mode=WAL;")
        connection.exec("PRAGMA synchronous=NORMAL;")
        connection.exec("PRAGMA busy_timeout=5000;")

        connection.exec(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS records_fts USING fts5(
                title_tokens,
                author_tokens,
                body_tokens,
                ocr_tokens,
                tokenize='unicode61'
            );
            """.trimIndent()
        )
        connection.exec(
            """
            CREATE TABLE IF NOT EXISTS record_index_state (
                record_id INTEGER PRIMARY KEY,
                text_hash TEXT NOT NULL,
                text_vec_status INTEGER NOT NULL DEFAULT 0,
                capture_time INTEGER NOT NULL DEFAULT 0,
                platform TEXT NOT NULL DEFAULT '',
                record_type TEXT NOT NULL DEFAULT '',
                source_app TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL
            );
            """.trimIndent()
        )
        connection.exec(
            """
            CREATE TABLE IF NOT EXISTS image_index_state (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                record_id INTEGER NOT NULL,
                image_url TEXT NOT NULL,
                ocr_text TEXT NOT NULL DEFAULT '',
                download_status INTEGER NOT NULL DEFAULT 0,
                ocr_status INTEGER NOT NULL DEFAULT 0,
                vec_status INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                UNIQUE(record_id, image_url)
            );
            """.trimIndent()
        )

        ensureRecordMetadataColumns()
        ensureDownloadStatusColumn()

        connection.exec("CREATE INDEX IF NOT EXISTS idx_record_vec_status ON record_index_state(text_vec_status);")
        connection.exec("CREATE INDEX IF NOT EXISTS idx_record_meta_time ON record_index_state(capture_time);")
        connection.exec("CREATE INDEX IF NOT EXISTS idx_record_meta_app ON record_index_state(source_app, platform, record_type);")
        connection.exec("CREATE INDEX IF NOT EXISTS idx_image_record_id ON image_index_state(record_id);")
        connection.exec("CREATE INDEX IF NOT EXISTS idx_image_download_ocr ON image_index_state(download_status, ocr_status);")
        connection.exec("CREATE INDEX IF NOT EXISTS idx_image_vec ON image_index_state(vec_status, download_status);")
    }

    private fun ensureRecordMetadataColumns() {
        val existingCols = HashSet<String>()
        connection.prepare("PRAGMA table_info(record_index_state)").use { stmt ->
            while (stmt.step()) {
                existingCols.add(stmt.getText(1))
            }
        }
        if ("capture_time" !in existingCols) {
            connection.exec("ALTER TABLE record_index_state ADD COLUMN capture_time INTEGER NOT NULL DEFAULT 0;")
        }
        if ("platform" !in existingCols) {
            connection.exec("ALTER TABLE record_index_state ADD COLUMN platform TEXT NOT NULL DEFAULT '';")
        }
        if ("record_type" !in existingCols) {
            connection.exec("ALTER TABLE record_index_state ADD COLUMN record_type TEXT NOT NULL DEFAULT '';")
        }
        if ("source_app" !in existingCols) {
            connection.exec("ALTER TABLE record_index_state ADD COLUMN source_app TEXT NOT NULL DEFAULT '';")
        }
    }

    private fun ensureDownloadStatusColumn() {
        var hasDownloadStatus = false
        connection.prepare("PRAGMA table_info(image_index_state)").use { stmt ->
            while (stmt.step()) {
                if (stmt.getText(1) == "download_status") {
                    hasDownloadStatus = true
                    break
                }
            }
        }
        if (!hasDownloadStatus) {
            connection.exec(
                "ALTER TABLE image_index_state ADD COLUMN download_status INTEGER NOT NULL DEFAULT 0;"
            )
            connection.exec(
                "UPDATE image_index_state SET download_status = CASE WHEN ocr_status = 1 OR vec_status = 1 THEN 1 WHEN ocr_status = 2 AND vec_status = 2 THEN 2 ELSE 0 END;"
            )
        }
    }

    fun recordIdsNeedingMetadata(limit: Int): List<Long> = synchronized(lock) {
        val ids = ArrayList<Long>(limit)
        connection.prepare(
            "SELECT record_id FROM record_index_state WHERE platform = '' LIMIT ?"
        ).use { stmt ->
            stmt.bindLong(1, limit.toLong())
            while (stmt.step()) {
                ids.add(stmt.getLong(0))
            }
        }
        ids
    }

    fun updateRecordMetadataBatch(updates: List<RecordMetadataUpdate>) {
        if (updates.isEmpty()) return
        synchronized(lock) {
            inTx {
                connection.prepare(
                    """
                    UPDATE record_index_state
                    SET capture_time = ?, platform = ?, record_type = ?, source_app = ?
                    WHERE record_id = ?
                    """.trimIndent()
                ).use { stmt ->
                    for (u in updates) {
                        stmt.reset()
                        stmt.bindLong(1, u.captureTime)
                        stmt.bindText(2, u.platform)
                        stmt.bindText(3, u.recordType)
                        stmt.bindText(4, u.sourceApp)
                        stmt.bindLong(5, u.recordId)
                        stmt.step()
                    }
                }
            }
        }
    }

    fun getAllIndexedRecordIds(): HashSet<Long> = synchronized(lock) {
        val ids = HashSet<Long>()
        connection.prepare("SELECT record_id FROM record_index_state").use { stmt ->
            while (stmt.step()) {
                ids.add(stmt.getLong(0))
            }
        }
        ids
    }

    fun upsertRecordsLight(items: List<RecordTextInput>, now: Long = System.currentTimeMillis()) {
        if (items.isEmpty()) return
        synchronized(lock) {
            inTx {
                for (item in items) {
                    upsertSingleInternal(item, now)
                }
            }
        }
    }

    fun upsertRecordLight(item: RecordTextInput, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            inTx {
                upsertSingleInternal(item, now)
            }
        }
    }

    private fun upsertSingleInternal(item: RecordTextInput, now: Long) {
        val hash = sha256("${item.title}\u0000${item.author}\u0000${item.bodyText}")
        val existingHash = querySingleText(
            "SELECT text_hash FROM record_index_state WHERE record_id = ?",
            item.recordId
        )

        if (existingHash != hash) {
            val titleTokens = SearchTokenizer.tokenizeForIndex(item.title)
            val authorTokens = SearchTokenizer.tokenizeForIndex(item.author)
            val bodyTokens = SearchTokenizer.tokenizeForIndex(item.bodyText)
            val ocrTokens = loadAggregatedOcrTokens(item.recordId)

            replaceFtsRow(item.recordId, titleTokens, authorTokens, bodyTokens, ocrTokens)

            connection.prepare(
                """
                INSERT INTO record_index_state(
                    record_id, text_hash, text_vec_status, capture_time, platform, record_type, source_app, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(record_id) DO UPDATE SET
                    text_hash = excluded.text_hash,
                    text_vec_status = ?,
                    capture_time = excluded.capture_time,
                    platform = excluded.platform,
                    record_type = excluded.record_type,
                    source_app = excluded.source_app,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { stmt ->
                stmt.bindLong(1, item.recordId)
                stmt.bindText(2, hash)
                stmt.bindLong(3, IndexStatus.PENDING.toLong())
                stmt.bindLong(4, item.captureTime)
                stmt.bindText(5, item.platform)
                stmt.bindText(6, item.recordType)
                stmt.bindText(7, item.sourceApp)
                stmt.bindLong(8, now)
                stmt.bindLong(9, IndexStatus.PENDING.toLong())
                stmt.step()
            }
        } else if (item.platform.isNotEmpty()) {
            connection.prepare(
                """
                UPDATE record_index_state
                SET capture_time = ?, platform = ?, record_type = ?, source_app = ?
                WHERE record_id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.bindLong(1, item.captureTime)
                stmt.bindText(2, item.platform)
                stmt.bindText(3, item.recordType)
                stmt.bindText(4, item.sourceApp)
                stmt.bindLong(5, item.recordId)
                stmt.step()
            }
        }

        for (rawUrl in item.imageUrls) {
            val url = rawUrl.trim()
            if (url.isEmpty()) continue
            connection.prepare(
                """
                INSERT OR IGNORE INTO image_index_state(
                    record_id, image_url, ocr_text, download_status, ocr_status, vec_status, updated_at
                ) VALUES (?, ?, '', 0, 0, 0, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.bindLong(1, item.recordId)
                stmt.bindText(2, url)
                stmt.bindLong(3, now)
                stmt.step()
            }
        }
    }

    fun updateTextVectorStatus(recordId: Long, status: Int, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            connection.prepare(
                "UPDATE record_index_state SET text_vec_status = ?, updated_at = ? WHERE record_id = ?"
            ).use { stmt ->
                stmt.bindLong(1, status.toLong())
                stmt.bindLong(2, now)
                stmt.bindLong(3, recordId)
                stmt.step()
            }
        }
    }

    fun markImageDownloaded(
        imageId: Long,
        downloadStatus: Int,
        markDownstreamFailedIfDownloadFailed: Boolean = true,
        now: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            if (downloadStatus == IndexStatus.FAILED && markDownstreamFailedIfDownloadFailed) {
                connection.prepare(
                    """
                    UPDATE image_index_state
                    SET download_status = 2,
                        ocr_status = CASE WHEN ocr_status = 0 THEN 2 ELSE ocr_status END,
                        vec_status = CASE WHEN vec_status = 0 THEN 2 ELSE vec_status END,
                        updated_at = ?
                    WHERE id = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.bindLong(1, now)
                    stmt.bindLong(2, imageId)
                    stmt.step()
                }
            } else {
                connection.prepare(
                    "UPDATE image_index_state SET download_status = ?, updated_at = ? WHERE id = ?"
                ).use { stmt ->
                    stmt.bindLong(1, downloadStatus.toLong())
                    stmt.bindLong(2, now)
                    stmt.bindLong(3, imageId)
                    stmt.step()
                }
            }
        }
    }

    fun markImageOcrResult(
        imageId: Long,
        recordId: Long,
        ocrText: String?,
        ocrStatus: Int,
        now: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            inTx {
                if (ocrText != null) {
                    connection.prepare(
                        """
                        UPDATE image_index_state
                        SET ocr_text = ?, ocr_status = ?, updated_at = ?
                        WHERE id = ?
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.bindText(1, ocrText)
                        stmt.bindLong(2, ocrStatus.toLong())
                        stmt.bindLong(3, now)
                        stmt.bindLong(4, imageId)
                        stmt.step()
                    }
                    refreshFtsOcrColumn(recordId)
                } else {
                    connection.prepare(
                        "UPDATE image_index_state SET ocr_status = ?, updated_at = ? WHERE id = ?"
                    ).use { stmt ->
                        stmt.bindLong(1, ocrStatus.toLong())
                        stmt.bindLong(2, now)
                        stmt.bindLong(3, imageId)
                        stmt.step()
                    }
                }
            }
        }
    }

    fun markImageVectorResult(
        imageId: Long,
        vecStatus: Int,
        now: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            connection.prepare(
                "UPDATE image_index_state SET vec_status = ?, updated_at = ? WHERE id = ?"
            ).use { stmt ->
                stmt.bindLong(1, vecStatus.toLong())
                stmt.bindLong(2, now)
                stmt.bindLong(3, imageId)
                stmt.step()
            }
        }
    }

    fun nextPendingTextRecordIds(limit: Int): List<Long> = synchronized(lock) {
        val result = ArrayList<Long>(limit)
        connection.prepare(
            "SELECT record_id FROM record_index_state WHERE text_vec_status = 0 ORDER BY record_id DESC LIMIT ?"
        ).use { stmt ->
            stmt.bindLong(1, limit.toLong())
            while (stmt.step()) {
                result.add(stmt.getLong(0))
            }
        }
        result
    }

    fun nextPendingDownloadOrOcrImages(limit: Int): List<PendingImageTask> = synchronized(lock) {
        val result = ArrayList<PendingImageTask>(limit)
        connection.prepare(
            """
            SELECT id, record_id, image_url, download_status, ocr_status, vec_status
            FROM image_index_state
            WHERE download_status = 0 OR (download_status = 1 AND ocr_status = 0)
            ORDER BY record_id DESC, id ASC
            LIMIT ?
            """.trimIndent()
        ).use { stmt ->
            stmt.bindLong(1, limit.toLong())
            while (stmt.step()) {
                result.add(stmt.readImageTask())
            }
        }
        result
    }

    fun nextPendingVectorImages(limit: Int): List<PendingImageTask> = synchronized(lock) {
        val result = ArrayList<PendingImageTask>(limit)
        connection.prepare(
            """
            SELECT id, record_id, image_url, download_status, ocr_status, vec_status
            FROM image_index_state
            WHERE vec_status = 0 AND download_status != 2
            ORDER BY CASE WHEN download_status = 1 THEN 0 ELSE 1 END, record_id DESC, id ASC
            LIMIT ?
            """.trimIndent()
        ).use { stmt ->
            stmt.bindLong(1, limit.toLong())
            while (stmt.step()) {
                result.add(stmt.readImageTask())
            }
        }
        result
    }

    private fun SQLiteStatement.readImageTask() = PendingImageTask(
        imageId = getLong(0),
        recordId = getLong(1),
        imageUrl = getText(2),
        downloadStatus = getLong(3).toInt(),
        ocrStatus = getLong(4).toInt(),
        vecStatus = getLong(5).toInt()
    )

    fun getIndexingCounts(): IndexingCounts = synchronized(lock) {
        var pendingText = 0
        var doneText = 0
        var failedText = 0
        connection.prepare(
            "SELECT text_vec_status, COUNT(*) FROM record_index_state GROUP BY text_vec_status"
        ).use { stmt ->
            while (stmt.step()) {
                val status = stmt.getLong(0).toInt()
                val count = stmt.getLong(1).toInt()
                when (status) {
                    IndexStatus.PENDING -> pendingText = count
                    IndexStatus.DONE -> doneText = count
                    IndexStatus.FAILED -> failedText = count
                }
            }
        }

        var pendingDownload = 0
        var doneDownload = 0
        var failedDownload = 0
        var pendingOcr = 0
        var doneOcr = 0
        var pendingImageVec = 0
        var doneImageVec = 0
        var failedImageVec = 0
        connection.prepare(
            """
            SELECT
                SUM(CASE WHEN download_status = 0 THEN 1 ELSE 0 END),
                SUM(CASE WHEN download_status = 1 THEN 1 ELSE 0 END),
                SUM(CASE WHEN download_status = 2 THEN 1 ELSE 0 END),
                SUM(CASE WHEN ocr_status = 0 THEN 1 ELSE 0 END),
                SUM(CASE WHEN ocr_status = 1 THEN 1 ELSE 0 END),
                SUM(CASE WHEN vec_status = 0 THEN 1 ELSE 0 END),
                SUM(CASE WHEN vec_status = 1 THEN 1 ELSE 0 END),
                SUM(CASE WHEN vec_status = 2 THEN 1 ELSE 0 END)
            FROM image_index_state
            """.trimIndent()
        ).use { stmt ->
            if (stmt.step()) {
                pendingDownload = stmt.getIntOrZero(0)
                doneDownload = stmt.getIntOrZero(1)
                failedDownload = stmt.getIntOrZero(2)
                pendingOcr = stmt.getIntOrZero(3)
                doneOcr = stmt.getIntOrZero(4)
                pendingImageVec = stmt.getIntOrZero(5)
                doneImageVec = stmt.getIntOrZero(6)
                failedImageVec = stmt.getIntOrZero(7)
            }
        }

        IndexingCounts(
            pendingText = pendingText,
            doneText = doneText,
            failedText = failedText,
            pendingDownload = pendingDownload,
            doneDownload = doneDownload,
            failedDownload = failedDownload,
            pendingOcr = pendingOcr,
            doneOcr = doneOcr,
            pendingImageVec = pendingImageVec,
            doneImageVec = doneImageVec,
            failedImageVec = failedImageVec
        )
    }

    private fun SQLiteStatement.getIntOrZero(index: Int): Int =
        if (isNull(index)) 0 else getLong(index).toInt()

    fun searchTextExact(
        query: String,
        limit: Int = 50,
        filter: SearchFilter = SearchFilter()
    ): List<FtsHit> {
        val ftsQuery = SearchTokenizer.buildTextColumnQuery(query) ?: return emptyList()
        return synchronized(lock) {
            val hits = ArrayList<FtsHit>(limit)
            val metaClause = buildMetadataClause(filter, alias = "s")
            val sql = if (metaClause == null) {
                """
                SELECT rowid, bm25(records_fts, 3.0, 2.0, 1.0, 0.0) AS score
                FROM records_fts
                WHERE records_fts MATCH ?
                ORDER BY score ASC
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT records_fts.rowid, bm25(records_fts, 3.0, 2.0, 1.0, 0.0) AS score
                FROM records_fts
                JOIN record_index_state s ON s.record_id = records_fts.rowid
                WHERE records_fts MATCH ? AND ${metaClause.sql}
                ORDER BY score ASC
                LIMIT ?
                """.trimIndent()
            }
            connection.prepare(sql).use { stmt ->
                var bindIdx = 1
                stmt.bindText(bindIdx++, ftsQuery)
                if (metaClause != null) {
                    bindIdx = metaClause.bindArgs(stmt, bindIdx)
                }
                stmt.bindLong(bindIdx, limit.toLong())
                while (stmt.step()) {
                    hits.add(FtsHit(recordId = stmt.getLong(0), bm25Score = stmt.getDouble(1)))
                }
            }
            hits
        }
    }

    fun searchOcrExact(
        query: String,
        limit: Int = 50,
        filter: SearchFilter = SearchFilter()
    ): List<OcrFtsHit> {
        val ftsQuery = SearchTokenizer.buildOcrColumnQuery(query) ?: return emptyList()
        return synchronized(lock) {
            val hits = ArrayList<FtsHit>(limit)
            val metaClause = buildMetadataClause(filter, alias = "s")
            val sql = if (metaClause == null) {
                """
                SELECT rowid, bm25(records_fts, 0.0, 0.0, 0.0, 1.0) AS score
                FROM records_fts
                WHERE records_fts MATCH ?
                ORDER BY score ASC
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT records_fts.rowid, bm25(records_fts, 0.0, 0.0, 0.0, 1.0) AS score
                FROM records_fts
                JOIN record_index_state s ON s.record_id = records_fts.rowid
                WHERE records_fts MATCH ? AND ${metaClause.sql}
                ORDER BY score ASC
                LIMIT ?
                """.trimIndent()
            }
            connection.prepare(sql).use { stmt ->
                var bindIdx = 1
                stmt.bindText(bindIdx++, ftsQuery)
                if (metaClause != null) {
                    bindIdx = metaClause.bindArgs(stmt, bindIdx)
                }
                stmt.bindLong(bindIdx, limit.toLong())
                while (stmt.step()) {
                    hits.add(FtsHit(recordId = stmt.getLong(0), bm25Score = stmt.getDouble(1)))
                }
            }
            if (hits.isEmpty()) return@synchronized emptyList()

            val ocrByRecord = loadOcrTextsInternal(hits.map { it.recordId })
            val terms = SearchTokenizer.extractHighlightTerms(query)
            hits.map { hit ->
                val images = ocrByRecord[hit.recordId].orEmpty()
                val matched = images.filter { info ->
                    terms.any { term -> info.ocrText.contains(term, ignoreCase = true) }
                }.ifEmpty {
                    images.take(1)
                }
                OcrFtsHit(
                    recordId = hit.recordId,
                    bm25Score = hit.bm25Score,
                    matchedImages = matched.map { MatchedOcrImage(it.imageId, it.imageUrl, it.ocrText) }
                )
            }
        }
    }

    fun filterRecordIds(recordIds: List<Long>, filter: SearchFilter): Set<Long> {
        if (recordIds.isEmpty()) return emptySet()
        val metaClause = buildMetadataClause(filter, alias = "s") ?: return recordIds.toSet()
        val distinctIds = recordIds.distinct()
        return synchronized(lock) {
            val placeholders = distinctIds.joinToString(",") { "?" }
            val matched = HashSet<Long>(distinctIds.size)
            connection.prepare(
                "SELECT s.record_id FROM record_index_state s WHERE s.record_id IN ($placeholders) AND ${metaClause.sql}"
            ).use { stmt ->
                var bindIdx = 1
                for (id in distinctIds) {
                    stmt.bindLong(bindIdx++, id)
                }
                metaClause.bindArgs(stmt, bindIdx)
                while (stmt.step()) {
                    matched.add(stmt.getLong(0))
                }
            }
            matched
        }
    }

    fun getMatchingRecordIds(filter: SearchFilter, limit: Int = 5000): List<Long> {
        if (!filter.hasMetadataFilter) return emptyList()
        val metaClause = buildMetadataClause(filter, alias = "s") ?: return emptyList()
        return synchronized(lock) {
            val result = ArrayList<Long>()
            connection.prepare(
                "SELECT s.record_id FROM record_index_state s WHERE s.text_vec_status = ${IndexStatus.DONE} AND ${metaClause.sql} ORDER BY s.capture_time DESC LIMIT $limit"
            ).use { stmt ->
                metaClause.bindArgs(stmt, 1)
                while (stmt.step()) {
                    result.add(stmt.getLong(0))
                }
            }
            result
        }
    }

    fun getMatchingImageIds(filter: SearchFilter, limit: Int = 5000): List<Long> {
        if (!filter.hasMetadataFilter) return emptyList()
        val metaClause = buildMetadataClause(filter, alias = "s") ?: return emptyList()
        return synchronized(lock) {
            val result = ArrayList<Long>()
            connection.prepare(
                "SELECT i.id FROM image_index_state i JOIN record_index_state s ON i.record_id = s.record_id WHERE i.vec_status = ${IndexStatus.DONE} AND ${metaClause.sql} ORDER BY s.capture_time DESC LIMIT $limit"
            ).use { stmt ->
                metaClause.bindArgs(stmt, 1)
                while (stmt.step()) {
                    result.add(stmt.getLong(0))
                }
            }
            result
        }
    }

    private class MetadataSqlClause(
        val sql: String,
        val bindArgs: (SQLiteStatement, Int) -> Int
    )

    private fun buildMetadataClause(filter: SearchFilter, alias: String): MetadataSqlClause? {
        if (!filter.hasMetadataFilter) return null
        val conditions = ArrayList<String>(4)
        val binders = ArrayList<(SQLiteStatement, Int) -> Int>()

        filter.startMillis?.let { start ->
            conditions.add("$alias.capture_time >= ?")
            binders.add { stmt, idx -> stmt.bindLong(idx, start); idx + 1 }
        }
        filter.endMillis?.let { end ->
            conditions.add("$alias.capture_time <= ?")
            binders.add { stmt, idx -> stmt.bindLong(idx, end); idx + 1 }
        }
        if (filter.platforms.isNotEmpty()) {
            val placeholders = filter.platforms.joinToString(",") { "?" }
            conditions.add("$alias.platform IN ($placeholders)")
            val platformList = filter.platforms.toList()
            binders.add { stmt, startIdx ->
                var idx = startIdx
                for (platform in platformList) stmt.bindText(idx++, platform)
                idx
            }
        }
        if (filter.types.isNotEmpty()) {
            val pairs = filter.types.mapNotNull { key ->
                if (':' !in key) null else key.substringBefore(':') to key.substringAfter(':')
            }
            if (pairs.isNotEmpty()) {
                val orExpr = pairs.joinToString(" OR ") { "($alias.platform = ? AND $alias.record_type = ?)" }
                conditions.add("($orExpr)")
                binders.add { stmt, startIdx ->
                    var idx = startIdx
                    for ((plat, type) in pairs) {
                        stmt.bindText(idx++, plat)
                        stmt.bindText(idx++, type)
                    }
                    idx
                }
            }
        }
        if (conditions.isEmpty()) return null
        val joined = conditions.joinToString(" AND ")
        return MetadataSqlClause(joined) { stmt, startIdx ->
            var idx = startIdx
            for (binder in binders) {
                idx = binder(stmt, idx)
            }
            idx
        }
    }

    fun getImageInfoByIds(imageIds: List<Long>): Map<Long, IndexedImageInfo> {
        if (imageIds.isEmpty()) return emptyMap()
        return synchronized(lock) {
            val placeholders = imageIds.joinToString(",") { "?" }
            val result = LinkedHashMap<Long, IndexedImageInfo>(imageIds.size)
            connection.prepare(
                "SELECT id, record_id, image_url, ocr_text FROM image_index_state WHERE id IN ($placeholders)"
            ).use { stmt ->
                imageIds.forEachIndexed { idx, id -> stmt.bindLong(idx + 1, id) }
                while (stmt.step()) {
                    val info = IndexedImageInfo(
                        imageId = stmt.getLong(0),
                        recordId = stmt.getLong(1),
                        imageUrl = stmt.getText(2),
                        ocrText = stmt.getText(3)
                    )
                    result[info.imageId] = info
                }
            }
            result
        }
    }

    fun resetFailedToPending() = synchronized(lock) {
        inTx {
            connection.exec("UPDATE record_index_state SET text_vec_status = 0 WHERE text_vec_status = 2;")
            connection.exec("UPDATE image_index_state SET download_status = 0 WHERE download_status = 2;")
            connection.exec("UPDATE image_index_state SET ocr_status = 0 WHERE ocr_status = 2;")
            connection.exec("UPDATE image_index_state SET vec_status = 0 WHERE vec_status = 2;")
        }
    }

    fun clearAll() = synchronized(lock) {
        inTx {
            connection.exec("DELETE FROM records_fts;")
            connection.exec("DELETE FROM record_index_state;")
            connection.exec("DELETE FROM image_index_state;")
        }
    }

    override fun close() {
        synchronized(lock) {
            connection.close()
        }
    }

    private fun refreshFtsOcrColumn(recordId: Long) {
        var titleTokens = ""
        var authorTokens = ""
        var bodyTokens = ""
        var found = false
        connection.prepare(
            "SELECT title_tokens, author_tokens, body_tokens FROM records_fts WHERE rowid = ?"
        ).use { stmt ->
            stmt.bindLong(1, recordId)
            if (stmt.step()) {
                found = true
                titleTokens = stmt.getText(0)
                authorTokens = stmt.getText(1)
                bodyTokens = stmt.getText(2)
            }
        }
        if (!found) return
        val ocrTokens = loadAggregatedOcrTokens(recordId)
        replaceFtsRow(recordId, titleTokens, authorTokens, bodyTokens, ocrTokens)
    }

    private fun replaceFtsRow(
        recordId: Long,
        titleTokens: String,
        authorTokens: String,
        bodyTokens: String,
        ocrTokens: String
    ) {
        connection.prepare("DELETE FROM records_fts WHERE rowid = ?").use { stmt ->
            stmt.bindLong(1, recordId)
            stmt.step()
        }
        connection.prepare(
            """
            INSERT INTO records_fts(rowid, title_tokens, author_tokens, body_tokens, ocr_tokens)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.bindLong(1, recordId)
            stmt.bindText(2, titleTokens)
            stmt.bindText(3, authorTokens)
            stmt.bindText(4, bodyTokens)
            stmt.bindText(5, ocrTokens)
            stmt.step()
        }
    }

    private fun loadAggregatedOcrTokens(recordId: Long): String {
        val parts = ArrayList<String>()
        connection.prepare(
            "SELECT ocr_text FROM image_index_state WHERE record_id = ? AND ocr_text != ''"
        ).use { stmt ->
            stmt.bindLong(1, recordId)
            while (stmt.step()) {
                val text = stmt.getText(0)
                if (text.isNotBlank()) parts.add(text)
            }
        }
        if (parts.isEmpty()) return ""
        return SearchTokenizer.tokenizeForIndex(parts.joinToString("\n"))
    }

    private fun loadOcrTextsInternal(recordIds: List<Long>): Map<Long, List<IndexedImageInfo>> {
        if (recordIds.isEmpty()) return emptyMap()
        val placeholders = recordIds.joinToString(",") { "?" }
        val result = LinkedHashMap<Long, MutableList<IndexedImageInfo>>()
        connection.prepare(
            """
            SELECT id, record_id, image_url, ocr_text
            FROM image_index_state
            WHERE record_id IN ($placeholders) AND ocr_text != ''
            """.trimIndent()
        ).use { stmt ->
            recordIds.forEachIndexed { idx, id -> stmt.bindLong(idx + 1, id) }
            while (stmt.step()) {
                val info = IndexedImageInfo(
                    imageId = stmt.getLong(0),
                    recordId = stmt.getLong(1),
                    imageUrl = stmt.getText(2),
                    ocrText = stmt.getText(3)
                )
                result.getOrPut(info.recordId) { ArrayList() }.add(info)
            }
        }
        return result
    }

    private fun querySingleText(sql: String, arg: Long): String? =
        connection.prepare(sql).use { stmt ->
            stmt.bindLong(1, arg)
            if (stmt.step()) stmt.getText(0) else null
        }

    private inline fun inTx(block: () -> Unit) {
        connection.exec("BEGIN IMMEDIATE;")
        try {
            block()
            connection.exec("COMMIT;")
        } catch (t: Throwable) {
            runCatching { connection.exec("ROLLBACK;") }
            throw t
        }
    }

    private fun SQLiteConnection.exec(sql: String) {
        prepare(sql).use { it.step() }
    }

    private inline fun <R> SQLiteStatement.use(block: (SQLiteStatement) -> R): R {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val hex = (b.toInt() and 0xFF).toString(16)
                if (hex.length == 1) append('0')
                append(hex)
            }
        }
    }
}
