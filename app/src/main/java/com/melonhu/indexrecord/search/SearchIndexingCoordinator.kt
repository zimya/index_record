package com.melonhu.indexrecord.search

import android.content.Context
import android.util.Log
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.UniversalRecord_
import com.melonhu.indexrecord.data.model.bodyPlainText
import com.melonhu.indexrecord.presentation.RecordContentPolicy
import com.melonhu.indexrecord.presentation.recordContext
import com.melonhu.indexrecord.presentation.recordDisplayTitle
import com.melonhu.indexrecord.presentation.recordPreviewText
import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal data class SearchIndexingState(
    val isPaused: Boolean = false,
    val isModelInitializing: Boolean = false,
    val isTextRunning: Boolean = false,
    val isDownloadRunning: Boolean = false,
    val isImageVecRunning: Boolean = false,
    val isBackfilling: Boolean = false,
    val activeBackend: String = EmbeddingModelManager.BACKEND_UNINITIALIZED,

    // 1. 文本语义
    val pendingTextCount: Int = 0,
    val doneTextCount: Int = 0,
    val failedTextCount: Int = 0,
    val totalTextCount: Int = 0,

    // 2. 图片下载 + OCR
    val pendingDownloadCount: Int = 0,
    val doneDownloadCount: Int = 0,
    val failedDownloadCount: Int = 0,
    val totalDownloadCount: Int = 0,
    val doneOcrCount: Int = 0,
    val pendingOcrCount: Int = 0,

    // 3. 图片语义（等文本语义跑完后再跑）
    val pendingImageVecCount: Int = 0,
    val doneImageVecCount: Int = 0,
    val failedImageVecCount: Int = 0,
    val totalImageVecCount: Int = 0,

    val imageCacheSizeBytes: Long = 0L,
    val modelDownloadState: ModelDownloadState = ModelDownloadState(),
    val lastError: String? = null
) {
    val isModelReady: Boolean
        get() = modelDownloadState.status == ModelDownloadStatus.READY

    val isModelDownloading: Boolean
        get() = modelDownloadState.status == ModelDownloadStatus.DOWNLOADING ||
            modelDownloadState.status == ModelDownloadStatus.VERIFYING

    val isRunning: Boolean
        get() = (!isPaused && (isTextRunning || isDownloadRunning || isImageVecRunning || isBackfilling || isModelInitializing)) ||
            isModelDownloading

    val textProgress: Float
        get() = if (totalTextCount == 0) 1f else ((doneTextCount + failedTextCount).toFloat() / totalTextCount).coerceIn(0f, 1f)

    val downloadProgress: Float
        get() = if (totalDownloadCount == 0) 1f else ((doneDownloadCount + failedDownloadCount).toFloat() / totalDownloadCount).coerceIn(0f, 1f)

    val imageVecProgress: Float
        get() = if (totalImageVecCount == 0) 1f else ((doneImageVecCount + failedImageVecCount).toFloat() / totalImageVecCount).coerceIn(0f, 1f)

    val pendingTotalCount: Int
        get() = if (isModelReady) {
            pendingTextCount + pendingDownloadCount + pendingImageVecCount
        } else {
            pendingDownloadCount
        }

    val overallProgressPercent: Float
        get() {
            if (!isModelReady) {
                if (isModelDownloading) {
                    return (modelDownloadState.progress * 100f).coerceIn(0f, 100f)
                }
                return if (totalDownloadCount == 0) 100f else (downloadProgress * 100f).coerceIn(0f, 100f)
            }
            val total = totalTextCount + totalDownloadCount + totalImageVecCount
            if (total == 0) return 100f
            val completed = (doneTextCount + failedTextCount) +
                (doneDownloadCount + failedDownloadCount) +
                (doneImageVecCount + failedImageVecCount)
            return (completed.toFloat() * 100f / total.toFloat()).coerceIn(0f, 100f)
        }

    val isWaitingForTextBeforeImageVec: Boolean
        get() = isModelReady && pendingTextCount > 0 && pendingImageVecCount > 0 && !isImageVecRunning
}

internal class SearchIndexingCoordinator(
    context: Context,
    private val store: BoxStore = ObjectBox.store,
    internal val ftsStore: SearchFtsStore,
    internal val vectorStore: VectorIndexStore,
    internal val modelManager: EmbeddingModelManager,
    internal val imageProcessor: ImageSearchProcessor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val backfillChannel = Channel<Unit>(Channel.CONFLATED)
    private val gpuWakeChannel = Channel<Unit>(Channel.CONFLATED)
    private val downloadWakeChannel = Channel<Unit>(Channel.CONFLATED)

    private val _isPaused = MutableStateFlow(false)
    private val _state = MutableStateFlow(SearchIndexingState())
    val state: StateFlow<SearchIndexingState> = _state.asStateFlow()

    private val isBackfillingFlag = AtomicBoolean(false)
    private val isTextRunningFlag = AtomicBoolean(false)
    private val isImageVecRunningFlag = AtomicBoolean(false)
    private val isDownloadRunningFlag = AtomicBoolean(false)

    private val downloadSemaphore = Semaphore(DOWNLOAD_CONCURRENCY)
    private val ocrSemaphore = Semaphore(OCR_CONCURRENCY)

    init {
        refreshCounts()
        scope.launch {
            modelManager.downloadState.collect { dlState ->
                _state.value = _state.value.copy(
                    modelDownloadState = dlState,
                    activeBackend = modelManager.activeBackend
                )
                if (dlState.status == ModelDownloadStatus.READY && !_isPaused.value) {
                    gpuWakeChannel.trySend(Unit)
                }
            }
        }
        scope.launch {
            for (signal in backfillChannel) {
                runBackfill()
            }
        }
        scope.launch {
            for (signal in gpuWakeChannel) {
                runGpuLoop()
            }
        }
        scope.launch {
            for (signal in downloadWakeChannel) {
                runDownloadAndOcrLoop()
            }
        }
    }

    fun start() {
        backfillChannel.trySend(Unit)
        gpuWakeChannel.trySend(Unit)
        downloadWakeChannel.trySend(Unit)
    }

    /** Called when user enters Search tab: ensures GPU & download pipelines are awake immediately. */
    fun boostForSearchScreen() {
        if (!_isPaused.value) {
            gpuWakeChannel.trySend(Unit)
            downloadWakeChannel.trySend(Unit)
        }
    }

    fun pause() {
        _isPaused.value = true
        refreshCounts()
    }

    fun resume() {
        _isPaused.value = false
        refreshCounts()
        gpuWakeChannel.trySend(Unit)
        downloadWakeChannel.trySend(Unit)
        backfillChannel.trySend(Unit)
    }

    fun retryFailed() {
        scope.launch {
            ftsStore.resetFailedToPending()
            refreshCounts()
            if (!_isPaused.value) {
                gpuWakeChannel.trySend(Unit)
                downloadWakeChannel.trySend(Unit)
            }
        }
    }

    fun clearImageCache() {
        scope.launch {
            imageProcessor.clearCache()
            refreshCounts()
        }
    }

    fun startModelDownload() {
        modelManager.startDownload {
            if (!_isPaused.value) {
                gpuWakeChannel.trySend(Unit)
            }
        }
    }

    fun pauseModelDownload() {
        modelManager.pauseDownload()
    }

    fun cancelModelDownload() {
        modelManager.cancelDownload()
    }

    fun deleteDownloadedModel() {
        scope.launch {
            modelManager.deleteDownloadedModel()
            refreshCounts()
        }
    }

    fun clearAllIndexes() {
        _isPaused.value = true
        scope.launch {
            ftsStore.clearAll()
            vectorStore.clear()
            imageProcessor.clearCache()
            _isPaused.value = false
            refreshCounts()
        }
    }

    /**
     * Lightweight incremental write on the caller's ingestion path:
     * writes FTS5 inverted index immediately and wakes async vector/download+OCR workers.
     */
    fun onRecordsPersisted(records: List<UniversalRecord>) {
        if (records.isEmpty()) return
        val inputs = records.mapNotNull { record ->
            if (record.id <= 0L) return@mapNotNull null
            toTextInput(record)
        }
        if (inputs.isEmpty()) return
        ftsStore.upsertRecordsLight(inputs)
        refreshCounts()
        if (!_isPaused.value) {
            gpuWakeChannel.trySend(Unit)
            downloadWakeChannel.trySend(Unit)
        }
    }

    private suspend fun awaitIfPaused() {
        if (!_isPaused.value) return
        vectorStore.flushIfDirty()
        refreshCounts()
        _isPaused.filter { !it }.first()
    }

    private suspend fun runBackfill() {
        isBackfillingFlag.set(true)
        refreshCounts()
        try {
            val recordBox = store.boxFor(UniversalRecord::class.java)

            // Fast metadata backfill for rows created before metadata columns existed.
            while (true) {
                awaitIfPaused()
                val needMetaIds = ftsStore.recordIdsNeedingMetadata(METADATA_BACKFILL_BATCH)
                if (needMetaIds.isEmpty()) break
                val records = recordBox.get(needMetaIds)
                val byId = records.associateBy { it.id }
                val updates = needMetaIds.map { id ->
                    val r = byId[id]
                    if (r != null) {
                        RecordMetadataUpdate(
                            recordId = id,
                            captureTime = r.captureTime,
                            platform = r.platform.ifBlank { "unknown" },
                            recordType = r.recordType,
                            sourceApp = RecordContentPolicy.sourceApp(r)
                        )
                    } else {
                        RecordMetadataUpdate(
                            recordId = id,
                            captureTime = 0L,
                            platform = "deleted",
                            recordType = "",
                            sourceApp = ""
                        )
                    }
                }
                ftsStore.updateRecordMetadataBatch(updates)
            }

            val existingIds = ftsStore.getAllIndexedRecordIds()
            val query = recordBox.query().orderDesc(UniversalRecord_.id).build()
            try {
                var offset = 0L
                while (true) {
                    awaitIfPaused()
                    val chunk = query.find(offset, BACKFILL_CHUNK_SIZE)
                    if (chunk.isEmpty()) break
                    val missing = ArrayList<RecordTextInput>(chunk.size)
                    for (record in chunk) {
                        if (record.id !in existingIds) {
                            missing.add(toTextInput(record))
                            existingIds.add(record.id)
                        }
                    }
                    if (missing.isNotEmpty()) {
                        ftsStore.upsertRecordsLight(missing)
                        refreshCounts()
                        if (!_isPaused.value) {
                            gpuWakeChannel.trySend(Unit)
                            downloadWakeChannel.trySend(Unit)
                        }
                    }
                    offset += chunk.size
                }
            } finally {
                query.close()
            }

            val specialRecords = recordBox.query(
                UniversalRecord_.recordType.equal("question")
                    .or(UniversalRecord_.recordType.equal("subject"))
            ).build().find()
            if (specialRecords.isNotEmpty()) {
                ftsStore.upsertRecordsLight(specialRecords.map { toTextInput(it) })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Backfill failed", t)
        } finally {
            isBackfillingFlag.set(false)
            refreshCounts()
        }
    }

    /**
     * Dedicated GPU inference worker:
     * 1. Runs text semantic embeddings first without waiting for any image download or OCR.
     * 2. Once all pending text records are embedded, runs image visual embeddings from cached files.
     */
    private suspend fun runGpuLoop() {
        try {
            awaitIfPaused()
            if (!modelManager.isModelDownloaded()) {
                return
            }
            val counts = ftsStore.getIndexingCounts()
            if (counts.pendingText == 0 && counts.pendingImageVec == 0) {
                return
            }

            _state.value = _state.value.copy(isModelInitializing = true)
            val modelReady = modelManager.ensureInitialized()
            _state.value = _state.value.copy(
                isModelInitializing = false,
                activeBackend = modelManager.activeBackend,
                lastError = if (!modelReady) modelManager.lastInitError else null
            )
            if (!modelReady) return

            val recordBox = store.boxFor(UniversalRecord::class.java)
            var dirtyCount = 0

            while (true) {
                awaitIfPaused()

                // Phase 1 (Strict Priority): Text semantic embeddings
                val pendingTextIds = ftsStore.nextPendingTextRecordIds(TEXT_BATCH_SIZE)
                if (pendingTextIds.isNotEmpty()) {
                    isTextRunningFlag.set(true)
                    isImageVecRunningFlag.set(false)
                    refreshCounts()

                    val records = recordBox.get(pendingTextIds)
                    val byId = records.associateBy { it.id }

                    for ((idx, recordId) in pendingTextIds.withIndex()) {
                        awaitIfPaused()
                        val record = byId[recordId]
                        if (record == null) {
                            ftsStore.updateTextVectorStatus(recordId, IndexStatus.FAILED)
                            continue
                        }
                        val title = recordDisplayTitle(record)
                        val body = buildSearchableBody(record)
                        val vector = if (title.isBlank() && body.isBlank()) {
                            null
                        } else {
                            modelManager.embedDocument(title, body)
                        }
                        if (vector != null) {
                            vectorStore.upsertTextVector(recordId, vector)
                            ftsStore.updateTextVectorStatus(recordId, IndexStatus.DONE)
                            dirtyCount++
                        } else {
                            ftsStore.updateTextVectorStatus(recordId, IndexStatus.FAILED)
                        }
                        if (dirtyCount >= FLUSH_EVERY_N) {
                            vectorStore.flushIfDirty()
                            dirtyCount = 0
                        }
                        if ((idx + 1) % UI_REFRESH_EVERY_N == 0) {
                            refreshCounts()
                        }
                    }
                    refreshCounts()
                    continue
                }

                isTextRunningFlag.set(false)

                // Phase 2: Image visual embeddings (only runs after all pending text is done)
                val readyImages = ftsStore.nextPendingVectorImages(IMAGE_VEC_BATCH_SIZE)
                    .filter { it.downloadStatus == IndexStatus.DONE }
                if (readyImages.isEmpty()) {
                    // Either all image vectors are done, or remaining images are still downloading in Pool A.
                    break
                }

                isImageVecRunningFlag.set(true)
                refreshCounts()

                for (task in readyImages) {
                    awaitIfPaused()
                    // Yield back immediately if new text records arrived while embedding images.
                    if (ftsStore.nextPendingTextRecordIds(1).isNotEmpty()) {
                        break
                    }

                    val cachedFile = imageProcessor.cachedFileFor(task.imageUrl)
                    val fileToEmbed = if (cachedFile.exists() && cachedFile.length() > 0L) {
                        cachedFile
                    } else {
                        // User cleared cache before image was vectorized; re-download on demand.
                        imageProcessor.downloadAndPrepare(task.imageUrl)
                    }

                    if (fileToEmbed == null) {
                        ftsStore.markImageVectorResult(task.imageId, IndexStatus.FAILED)
                        continue
                    }

                    val visionBytes = imageProcessor.encodeVisionBytes(fileToEmbed)
                    val imgVector = if (visionBytes != null) {
                        modelManager.embedImage(visionBytes)
                    } else {
                        null
                    }

                    if (imgVector != null) {
                        vectorStore.upsertImageVector(task.imageId, imgVector)
                        ftsStore.markImageVectorResult(task.imageId, IndexStatus.DONE)
                        dirtyCount++
                    } else {
                        ftsStore.markImageVectorResult(task.imageId, IndexStatus.FAILED)
                    }

                    if (dirtyCount >= FLUSH_EVERY_N) {
                        vectorStore.flushIfDirty()
                        dirtyCount = 0
                    }
                    refreshCounts()
                }
            }

            vectorStore.flushIfDirty()
        } catch (t: Throwable) {
            Log.e(TAG, "GPU indexing worker error", t)
            runCatching { vectorStore.flushIfDirty() }
            _state.value = _state.value.copy(
                isModelInitializing = false,
                lastError = t.message ?: t.javaClass.simpleName
            )
        } finally {
            isTextRunningFlag.set(false)
            isImageVecRunningFlag.set(false)
            refreshCounts()
        }
    }

    /**
     * Independent concurrent image download + ML Kit OCR pool:
     * - 5 concurrent HTTP downloads -> writes to cache immediately
     * - 2 concurrent ML Kit OCR workers -> writes OCR text immediately to FTS5
     * - Wakes GPU worker as images become ready on disk.
     */
    private suspend fun runDownloadAndOcrLoop() {
        isDownloadRunningFlag.set(true)
        refreshCounts()
        try {
            while (true) {
                awaitIfPaused()
                val batch = ftsStore.nextPendingDownloadOrOcrImages(DOWNLOAD_BATCH_SIZE)
                if (batch.isEmpty()) break

                coroutineScope {
                    batch.map { task ->
                        async(Dispatchers.IO) {
                            awaitIfPaused()

                            val cachedFile = if (task.downloadStatus == IndexStatus.DONE) {
                                val existing = imageProcessor.cachedFileFor(task.imageUrl)
                                if (existing.exists() && existing.length() > 0L) existing else null
                            } else {
                                null
                            } ?: downloadSemaphore.withPermit {
                                awaitIfPaused()
                                val downloaded = imageProcessor.downloadAndPrepare(task.imageUrl)
                                if (downloaded != null) {
                                    ftsStore.markImageDownloaded(task.imageId, IndexStatus.DONE)
                                    refreshCounts()
                                    if (!_isPaused.value) {
                                        gpuWakeChannel.trySend(Unit)
                                    }
                                    downloaded
                                } else {
                                    ftsStore.markImageDownloaded(task.imageId, IndexStatus.FAILED)
                                    refreshCounts()
                                    null
                                }
                            }

                            if (cachedFile != null && task.ocrStatus == IndexStatus.PENDING) {
                                ocrSemaphore.withPermit {
                                    awaitIfPaused()
                                    val ocrText = imageProcessor.extractOcrText(cachedFile)
                                    if (ocrText != null) {
                                        ftsStore.markImageOcrResult(
                                            imageId = task.imageId,
                                            recordId = task.recordId,
                                            ocrText = ocrText,
                                            ocrStatus = IndexStatus.DONE
                                        )
                                    } else {
                                        ftsStore.markImageOcrResult(
                                            imageId = task.imageId,
                                            recordId = task.recordId,
                                            ocrText = null,
                                            ocrStatus = IndexStatus.FAILED
                                        )
                                    }
                                    refreshCounts()
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Image download/OCR pool error", t)
            _state.value = _state.value.copy(
                lastError = t.message ?: t.javaClass.simpleName
            )
        } finally {
            isDownloadRunningFlag.set(false)
            refreshCounts()
        }
    }

    private fun refreshCounts() {
        val counts = ftsStore.getIndexingCounts()
        _state.value = _state.value.copy(
            isPaused = _isPaused.value,
            isTextRunning = !_isPaused.value && isTextRunningFlag.get(),
            isDownloadRunning = !_isPaused.value && isDownloadRunningFlag.get(),
            isImageVecRunning = !_isPaused.value && isImageVecRunningFlag.get(),
            isBackfilling = !_isPaused.value && isBackfillingFlag.get(),
            activeBackend = modelManager.activeBackend,
            pendingTextCount = counts.pendingText,
            doneTextCount = counts.doneText,
            failedTextCount = counts.failedText,
            totalTextCount = counts.totalText,
            pendingDownloadCount = counts.pendingDownload,
            doneDownloadCount = counts.doneDownload,
            failedDownloadCount = counts.failedDownload,
            totalDownloadCount = counts.totalImages,
            doneOcrCount = counts.doneOcr,
            pendingOcrCount = counts.pendingOcr,
            pendingImageVecCount = counts.pendingImageVec,
            doneImageVecCount = counts.doneImageVec,
            failedImageVecCount = counts.failedImageVec,
            totalImageVecCount = counts.totalImages,
            imageCacheSizeBytes = imageProcessor.getCacheSizeBytes(),
            modelDownloadState = modelManager.downloadState.value
        )
    }

    private fun toTextInput(record: UniversalRecord): RecordTextInput = RecordTextInput(
        recordId = record.id,
        title = recordDisplayTitle(record),
        author = record.authorName,
        bodyText = buildSearchableBody(record),
        imageUrls = ImageSearchProcessor.extractImageUrls(record),
        captureTime = record.captureTime,
        platform = record.platform.ifBlank { "unknown" },
        recordType = record.recordType,
        sourceApp = RecordContentPolicy.sourceApp(record)
    )

    private fun buildSearchableBody(record: UniversalRecord): String {
        val contextText = recordContext(record)
        val preview = recordPreviewText(record)
        return buildString {
            if (contextText.isNotBlank()) append(contextText).append('\n')
            if (preview.isNotBlank()) append(preview)
        }.trim()
    }

    companion object {
        private const val TAG = "SearchIndexing"
        private const val BACKFILL_CHUNK_SIZE = 200L
        private const val METADATA_BACKFILL_BATCH = 500
        private const val TEXT_BATCH_SIZE = 25
        private const val IMAGE_VEC_BATCH_SIZE = 12
        private const val DOWNLOAD_BATCH_SIZE = 18
        private const val DOWNLOAD_CONCURRENCY = 5
        private const val OCR_CONCURRENCY = 2
        private const val FLUSH_EVERY_N = 20
        private const val UI_REFRESH_EVERY_N = 5

        fun create(context: Context, store: BoxStore = ObjectBox.store): SearchIndexingCoordinator {
            val appCtx = context.applicationContext
            val ftsDbFile = File(appCtx.filesDir, "search_index.db")
            val vectorFile = File(appCtx.filesDir, VectorIndexStore.VECTOR_INDEX_FILENAME)
            return SearchIndexingCoordinator(
                context = appCtx,
                store = store,
                ftsStore = SearchFtsStore(ftsDbFile.absolutePath),
                vectorStore = VectorIndexStore(vectorFile),
                modelManager = EmbeddingModelManager(appCtx),
                imageProcessor = ImageSearchProcessor(appCtx)
            )
        }
    }
}
