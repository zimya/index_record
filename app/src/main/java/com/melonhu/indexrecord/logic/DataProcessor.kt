package com.melonhu.indexrecord.logic

import android.util.Log
import com.google.gson.JsonObject
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.reactive.DataSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RawData_
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.zhihu.ZhihuFeedParser
import com.melonhu.indexrecord.parser.zhihu.ZhihuItemV2Parser

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
    private val subscriptionLock = Any()
    private var observedStore: BoxStore? = null
    private var rawSubscription: DataSubscription? = null

    @Volatile
    private var lastObservedMaxRawId: Long = 0L

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

    /** Subscribes to newly inserted RawData so ingress providers need not depend on DataProcessor. */
    fun start(store: BoxStore = ObjectBox.store) {
        synchronized(subscriptionLock) {
            if (observedStore === store && rawSubscription != null) return
            rawSubscription?.cancel()
            observedStore = store
            lastObservedMaxRawId = 0L
            rawSubscription = store.subscribe(RawData::class.java).observer {
                onRawDataChanged(store)
            }
        }
        onRawDataChanged(store)
    }

    private fun onRawDataChanged(store: BoxStore) {
        val rawBox = store.boxFor(RawData::class.java)
        if (rawBox.count() == 0L) {
            lastObservedMaxRawId = 0L
            return
        }
        val maxId = latestRawId(rawBox)
        if (maxId > lastObservedMaxRawId) {
            lastObservedMaxRawId = maxId
            requestProcessing()
        }
    }

    private fun latestRawId(rawBox: Box<RawData>): Long {
        val query = rawBox.query().orderDesc(RawData_.id).build()
        return try {
            query.findFirst()?.id ?: 0L
        } finally {
            query.close()
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
            lastObservedMaxRawId = 0L
            _syncProgress.value = SyncProgress(isRunning = false, total = 0, processed = 0)
            return
        }

        Log.i(TAG, "Found $initialCount raw records. Starting chunked process...")

        var processed = 0
        var total = initialCount
        _syncProgress.value = SyncProgress(isRunning = true, total = total, processed = 0)

        val processing = objectBoxCaptureProcessing(CHUNK_SIZE)
        val recordRepository = ObjectBoxRecordRepository(recordBox)
        val persistence = RecordPersistence(recordRepository, persistenceContext)

        val query = rawBox.query().order(RawData_.id).build()
        var retainedOffset = 0L
        try {
            processing.prepare()
            while (true) {
                // 每次仅提取最多 CHUNK_SIZE 条，避免一次性加载所有巨型 JSON/HTML 导致 OOM
                // Failed/partial raws stay in ObjectBox. Skip them for this run, rather than
                // repeatedly selecting the first bad chunk and starving later captures.
                val chunk = query.find(retainedOffset, CHUNK_SIZE)
                if (chunk.isEmpty()) break

                val chunkMaxId = chunk.maxOf { it.id }
                if (chunkMaxId > lastObservedMaxRawId) {
                    lastObservedMaxRawId = chunkMaxId
                }

                val parsedItems = chunk.map { raw ->
                    processing.parse(raw, persistence) { e -> Log.e(TAG, "Failed to process raw ID: ${raw.id}", e) }
                }

                // 同一事务中写入有效记录，仅删除成功或主动忽略的 RawData。
                ObjectBox.store.runInTx {
                    val processedRawIds = processing.persist(parsedItems, persistence)
                    if (processedRawIds.isNotEmpty()) rawBox.remove(*processedRawIds)
                }
                RecordStorageEvents.notifyRecordsPersisted(recordRepository.drainPersisted())
                retainedOffset += parsedItems.count { !it.result.consumeRaw }
                parsedItems.filter { !it.result.consumeRaw }.forEach { item ->
                    val raw = chunk.firstOrNull { it.id == item.rawId }
                    Log.w(TAG, "Retained raw ID ${item.rawId}: ${item.result.javaClass.simpleName}; diagnostics=${item.result.diagnostics.map { "${it.stage}: ${it.message}" }}; url=${raw?.url}")
                }

                processed += chunk.size
                total = maxOf(total, processed + rawBox.count().toInt() - retainedOffset.toInt())
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
