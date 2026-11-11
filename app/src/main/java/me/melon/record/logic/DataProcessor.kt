package me.melon.record.logic

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.melon.record.ObjectBox
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.parser.ParserRegistry
import me.melon.record.parser.x.XParser
import me.melon.record.settings.RecordSettings
import me.melon.record.parser.zhihu.ZhihuFeedParser
import me.melon.record.parser.zhihu.ZhihuItemV2Parser
import me.melon.record.parser.zhihu.ZhihuRelations

object DataProcessor {

    private const val TAG = "DataProcessor"
    private const val CHUNK_SIZE = 25L

    data class SyncProgress(
        val isRunning: Boolean,
        val total: Int,
        val processed: Int
    )

    private val _syncProgress = MutableStateFlow(SyncProgress(isRunning = false, total = 0, processed = 0))
    val syncProgress: StateFlow<SyncProgress> = _syncProgress

    private val processingRequests = Channel<Unit>(Channel.CONFLATED)
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        processingScope.launch {
            for (request in processingRequests) {
                try {
                    processPendingData()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process pending raw data; queued data is retained", e)
                }
            }
        }
    }

    // 请求属于进程而非页面生命周期；合并突发请求，并保留处理结束前到达的唤醒。
    fun requestProcessing() {
        processingRequests.trySend(Unit)
    }

    @Synchronized
    fun processPendingData() {
        val rawBox = ObjectBox.store.boxFor(RawData::class.java)
        val recordBox = ObjectBox.store.boxFor(UniversalRecord::class.java)

        val initialCount = rawBox.count().toInt()
        if (initialCount == 0) {
            _syncProgress.value = SyncProgress(isRunning = false, total = 0, processed = 0)
            return
        }

        Log.i(TAG, "Found $initialCount raw records. Starting chunked process...")

        var processed = 0
        var total = initialCount
        _syncProgress.value = SyncProgress(isRunning = true, total = total, processed = 0)

        data class ParsedItem(val rawId: Long, val cleanUrl: String, val records: List<UniversalRecord>?)

        val query = rawBox.query().build()
        try {
            while (true) {
                // 每次仅提取最多 CHUNK_SIZE 条，避免一次性加载所有巨型 JSON/HTML 导致 OOM
                val chunk = query.find(0, CHUNK_SIZE)
                if (chunk.isEmpty()) break

                val parsedItems = ArrayList<ParsedItem>(chunk.size)
                for (raw in chunk) {
                    val cleanUrl = if (raw.url.contains("?")) raw.url.substringBefore("?") else raw.url
                    try {
                        // 在本应用进程读取设置，避免 Hook 跨进程读取私有偏好设置。
                        val records = if (XParser.canHandle(cleanUrl, raw) &&
                            !RecordSettings.tweetRecordMode.shouldProcess(raw.url)
                        ) {
                            emptyList()
                        } else {
                            ParserRegistry.parse(raw, cleanUrl)
                        }
                        parsedItems.add(ParsedItem(raw.id, cleanUrl, records))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process raw ID: ${raw.id}", e)
                        parsedItems.add(ParsedItem(raw.id, cleanUrl, emptyList()))
                    }
                }

                // 批处理事务：写入 UniversalRecord 并删除该批次 RawData
                val processedRawIds = ArrayList<Long>(chunk.size)
                ObjectBox.store.runInTx {
                    for (item in parsedItems) {
                        val records = item.records
                        if (!records.isNullOrEmpty()) {
                            for (record in records) {
                                val existing = recordBox.query()
                                    .equal(UniversalRecord_.platformId, record.platformId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                                    .build()
                                    .findFirst()

                                if (existing != null) {
                                    record.id = existing.id
                                    if (record.parentId.isBlank()) record.parentId = existing.parentId
                                    if (record.title.isBlank()) record.title = existing.title
                                    if (record.content.isBlank() || (record.recordType == "subject" && record.content == record.title)) {
                                        if (existing.content.isNotBlank() && !(existing.recordType == "subject" && existing.content == existing.title)) {
                                            record.content = existing.content
                                        }
                                    }
                                    if (record.authorId.isBlank()) record.authorId = existing.authorId
                                    if (record.authorName.isBlank()) record.authorName = existing.authorName
                                    if (record.authorAvatar.isBlank()) record.authorAvatar = existing.authorAvatar
                                    if (record.sourceUrl.isBlank()) record.sourceUrl = existing.sourceUrl
                                    record.publishTime = maxOf(existing.publishTime, record.publishTime)
                                    record.captureTime = maxOf(existing.captureTime, record.captureTime)
                                    if (record.platform == "zhihu" && record.recordType == "question") {
                                        record.extraMetadata = ZhihuRelations.mergeQuestionExtra(existing.extraMetadata, record.extraMetadata)
                                    } else if (record.platform == "douban" && record.recordType == "subject") {
                                        record.extraMetadata = me.melon.record.parser.douban.DoubanRelations.mergeSubjectExtra(existing.extraMetadata, record.extraMetadata)
                                    } else if (record.platform == "tieba") {
                                        record.content = me.melon.record.parser.tieba.TiebaRelations.mergeRecordContent(existing, record)
                                        record.extraMetadata = me.melon.record.parser.tieba.TiebaRelations.mergeExtra(existing.extraMetadata, record.extraMetadata)
                                    } else if (record.platform == "twitter") {
                                        record.extraMetadata = me.melon.record.parser.x.XRelations.mergeTweetExtra(existing.extraMetadata, record.extraMetadata)
                                    }
                                }
                                recordBox.put(record)

                                if (record.platform == "zhihu" && record.recordType == "question") {
                                    val qid = try {
                                        JsonParser.parseString(record.extraMetadata).asJsonObject.get("question_id")?.asString ?: ""
                                    } catch (_: Exception) {
                                        ""
                                    }
                                    if (qid.isNotBlank()) {
                                        ZhihuRelations.linkExistingAnswersToQuestion(recordBox, qid, record.platformId)
                                    }
                                    val answerIds = try {
                                        ZhihuRelations.extractAnswerIdsFromQuestionExtra(record.extraMetadata)
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                    if (answerIds.isNotEmpty()) {
                                        ZhihuRelations.linkAnswersByIds(recordBox, record.platformId, answerIds)
                                    }
                                } else if (record.platform == "douban") {
                                    try {
                                        val meta = JsonParser.parseString(record.extraMetadata).asJsonObject
                                        val sId = meta.get("subject_id")?.asString ?: ""
                                        val sTitle = meta.get("subject_title")?.asString ?: ""
                                        val sType = meta.get("subject_type")?.asString ?: ""
                                        if (sId.isNotBlank() && sTitle.isNotBlank()) {
                                            me.melon.record.parser.douban.DoubanRelations.linkSubjectToInterests(
                                                recordBox, sType.ifBlank { "movie" }, sId, sTitle
                                            )
                                        }
                                    } catch (_: Exception) {}
                                } else if (record.platform == "twitter") {
                                    try {
                                        me.melon.record.parser.x.XRelations.linkTweetRelations(recordBox, record)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        processedRawIds.add(item.rawId)
                    }
                    if (processedRawIds.isNotEmpty()) {
                        rawBox.remove(*processedRawIds.toLongArray())
                    }
                }

                processed += chunk.size
                total = maxOf(total, processed + rawBox.count().toInt())
                _syncProgress.value = SyncProgress(isRunning = true, total = total, processed = processed.coerceAtMost(total))
            }
        } finally {
            query.close()
            _syncProgress.value = SyncProgress(isRunning = false, total = 0, processed = 0)
        }
        Log.i(TAG, "Completed processing raw data. Processed count: $processed")
    }

    // --- 兼容旧版调用与单测门面 ---
    internal fun parseZhihuItemV2(itemObj: JsonObject, raw: RawData, defaultType: String? = null): UniversalRecord? {
        return ZhihuItemV2Parser.parse(itemObj, raw, defaultType)
    }

    internal fun parseZhihuNextRender(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        return ZhihuFeedParser.parse(raw, cleanUrl)
    }
}
