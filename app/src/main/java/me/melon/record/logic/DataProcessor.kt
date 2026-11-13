package me.melon.record.logic

import android.util.Log
import com.google.gson.JsonObject
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
import me.melon.record.parser.zhihu.ZhihuFeedParser
import me.melon.record.parser.zhihu.ZhihuItemV2Parser

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

    // Completion state has process lifetime, not run lifetime; parsers remain stateless.
    private val persistenceContext = RecordPersistenceContext()
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

        val processing = objectBoxCaptureProcessing(CHUNK_SIZE)
        val persistence = RecordPersistence(ObjectBoxRecordRepository(recordBox), persistenceContext)

        val query = rawBox.query().build()
        try {
            processing.prepare()
            while (true) {
                // 每次仅提取最多 CHUNK_SIZE 条，避免一次性加载所有巨型 JSON/HTML 导致 OOM
                val chunk = query.find(0, CHUNK_SIZE)
                if (chunk.isEmpty()) break

                val parsedItems = chunk.map { raw ->
                    processing.parse(raw, persistence) { e -> Log.e(TAG, "Failed to process raw ID: ${raw.id}", e) }
                }

                // 批处理事务：写入 UniversalRecord 并删除该批次 RawData
                ObjectBox.store.runInTx {
                    val processedRawIds = processing.persist(parsedItems, persistence)
                    if (processedRawIds.isNotEmpty()) rawBox.remove(*processedRawIds)
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
