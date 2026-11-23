package com.melonhu.indexrecord.search

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class ModelDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    READY,
    FAILED
}

internal data class ModelDownloadState(
    val status: ModelDownloadStatus = ModelDownloadStatus.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = EmbeddingModelManager.EXPECTED_MODEL_BYTES,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

internal class EmbeddingModelManager(private val context: Context) : AutoCloseable {

    private val inferenceMutex = Mutex()
    private val downloadMutex = Mutex()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: EmbeddingEngine? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeSession: DownloadSession? = null

    private class DownloadSession(val jobProvider: () -> Job?) {
        val stopMode = AtomicReference(StopMode.NONE)
        val activeCall = AtomicReference<Call?>(null)
        val stopped = AtomicBoolean(false)

        fun stop(mode: StopMode) {
            stopMode.compareAndSet(StopMode.NONE, mode)
            stopped.set(true)
            activeCall.get()?.cancel()
            jobProvider()?.cancel()
        }
    }

    private val _downloadState = MutableStateFlow(initialDownloadState())
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    @Volatile
    var activeBackend: String = BACKEND_UNINITIALIZED
        private set

    @Volatile
    var lastInitError: String? = null
        private set

    @Volatile
    var lastInferenceError: String? = null
        private set

    val lastError: String?
        get() = lastInferenceError ?: lastInitError

    private enum class StopMode {
        NONE,
        PAUSE,
        CANCEL
    }

    private fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    private fun modelFile(): File = File(modelsDir(), MODEL_ASSET_NAME)

    private fun partFile(): File = File(modelsDir(), "$MODEL_ASSET_NAME.part")

    private fun initialDownloadState(): ModelDownloadState {
        val target = modelFile()
        if (target.exists() && target.length() == EXPECTED_MODEL_BYTES) {
            return ModelDownloadState(
                status = ModelDownloadStatus.READY,
                downloadedBytes = EXPECTED_MODEL_BYTES,
                totalBytes = EXPECTED_MODEL_BYTES
            )
        }
        val part = partFile()
        val partialBytes = if (part.exists()) part.length().coerceIn(0L, EXPECTED_MODEL_BYTES) else 0L
        return if (partialBytes > 0L) {
            ModelDownloadState(
                status = ModelDownloadStatus.PAUSED,
                downloadedBytes = partialBytes,
                totalBytes = EXPECTED_MODEL_BYTES
            )
        } else {
            ModelDownloadState(
                status = ModelDownloadStatus.NOT_DOWNLOADED,
                downloadedBytes = 0L,
                totalBytes = EXPECTED_MODEL_BYTES
            )
        }
    }

    fun isModelDownloaded(): Boolean {
        val target = modelFile()
        return target.exists() && target.length() == EXPECTED_MODEL_BYTES
    }

    fun startDownload(onReady: (() -> Unit)? = null) {
        if (isModelDownloaded()) {
            _downloadState.value = ModelDownloadState(
                status = ModelDownloadStatus.READY,
                downloadedBytes = EXPECTED_MODEL_BYTES,
                totalBytes = EXPECTED_MODEL_BYTES
            )
            onReady?.invoke()
            return
        }
        val current = activeSession
        if (current != null && !current.stopped.get()) return

        var launchedJob: Job? = null
        val session = DownloadSession { launchedJob }
        activeSession = session
        launchedJob = downloadScope.launch {
            downloadMutex.withLock {
                if (session.stopped.get()) {
                    handleStopState(session.stopMode.get())
                    return@withLock
                }
                if (isModelDownloaded()) {
                    _downloadState.value = ModelDownloadState(
                        status = ModelDownloadStatus.READY,
                        downloadedBytes = EXPECTED_MODEL_BYTES,
                        totalBytes = EXPECTED_MODEL_BYTES
                    )
                    onReady?.invoke()
                    return@withLock
                }

                try {
                    downloadModelInternal(session)
                    if (isModelDownloaded()) {
                        onReady?.invoke()
                    }
                } catch (_: CancellationException) {
                    handleStopState(session.stopMode.get())
                } catch (t: Throwable) {
                    if (session.stopped.get()) {
                        handleStopState(session.stopMode.get())
                    } else {
                        Log.w(TAG, "Model download failed", t)
                        val partial = partFile().let { if (it.exists()) it.length() else 0L }
                        _downloadState.value = ModelDownloadState(
                            status = ModelDownloadStatus.FAILED,
                            downloadedBytes = partial.coerceIn(0L, EXPECTED_MODEL_BYTES),
                            totalBytes = EXPECTED_MODEL_BYTES,
                            errorMessage = t.message ?: t.javaClass.simpleName
                        )
                    }
                } finally {
                    session.activeCall.set(null)
                    if (activeSession === session) {
                        activeSession = null
                    }
                }
            }
        }
    }

    fun pauseDownload() {
        activeSession?.stop(StopMode.PAUSE)
        val partial = partFile().let { if (it.exists()) it.length().coerceIn(0L, EXPECTED_MODEL_BYTES) else 0L }
        _downloadState.value = ModelDownloadState(
            status = if (partial > 0L) ModelDownloadStatus.PAUSED else ModelDownloadStatus.NOT_DOWNLOADED,
            downloadedBytes = partial,
            totalBytes = EXPECTED_MODEL_BYTES
        )
    }

    fun cancelDownload() {
        activeSession?.stop(StopMode.CANCEL)
        partFile().delete()
        _downloadState.value = ModelDownloadState(
            status = ModelDownloadStatus.NOT_DOWNLOADED,
            downloadedBytes = 0L,
            totalBytes = EXPECTED_MODEL_BYTES
        )
    }

    suspend fun deleteDownloadedModel() {
        cancelDownload()
        downloadMutex.withLock {
            inferenceMutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching { engine?.close() }
                    engine = null
                    activeBackend = BACKEND_UNINITIALIZED
                    lastInitError = null
                    lastInferenceError = null
                    modelFile().delete()
                    partFile().delete()
                    File(context.cacheDir, "litert_cache").deleteRecursively()
                }
            }
            _downloadState.value = ModelDownloadState(
                status = ModelDownloadStatus.NOT_DOWNLOADED,
                downloadedBytes = 0L,
                totalBytes = EXPECTED_MODEL_BYTES
            )
        }
    }

    private fun handleStopState(mode: StopMode) {
        if (mode == StopMode.CANCEL) {
            partFile().delete()
            _downloadState.value = ModelDownloadState(
                status = ModelDownloadStatus.NOT_DOWNLOADED,
                downloadedBytes = 0L,
                totalBytes = EXPECTED_MODEL_BYTES
            )
        } else {
            val partial = partFile().let { if (it.exists()) it.length().coerceIn(0L, EXPECTED_MODEL_BYTES) else 0L }
            _downloadState.value = ModelDownloadState(
                status = if (partial > 0L) ModelDownloadStatus.PAUSED else ModelDownloadStatus.NOT_DOWNLOADED,
                downloadedBytes = partial,
                totalBytes = EXPECTED_MODEL_BYTES
            )
        }
    }

    private fun downloadModelInternal(session: DownloadSession) {
        val target = modelFile()
        val part = partFile()

        if (part.exists() && part.length() > EXPECTED_MODEL_BYTES) {
            part.delete()
        }

        var existingBytes = if (part.exists()) part.length() else 0L
        if (existingBytes == EXPECTED_MODEL_BYTES) {
            if (verifyAndPromotePart(part, target)) return
            existingBytes = 0L
        }

        _downloadState.value = ModelDownloadState(
            status = ModelDownloadStatus.DOWNLOADING,
            downloadedBytes = existingBytes,
            totalBytes = EXPECTED_MODEL_BYTES
        )

        val reqBuilder = Request.Builder().url(MODEL_DOWNLOAD_URL)
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        val call = httpClient.newCall(reqBuilder.build())
        session.activeCall.set(call)

        call.execute().use { response ->
            if (response.code == 416) {
                part.delete()
                throw IOException("Range mismatch (HTTP 416), please retry")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }

            val isAppend = response.code == 206 && existingBytes > 0L
            if (!isAppend && existingBytes > 0L) {
                part.delete()
                existingBytes = 0L
            }

            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            val expectedTotal = when {
                isAppend && contentLength > 0L -> existingBytes + contentLength
                !isAppend && contentLength > 0L -> contentLength
                else -> EXPECTED_MODEL_BYTES
            }

            var written = existingBytes
            var lastEmitBytes = written
            var lastEmitTime = System.currentTimeMillis()

            body.byteStream().use { input ->
                FileOutputStream(part, isAppend).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (session.stopped.get()) {
                            output.flush()
                            throw CancellationException("Download stopped by user")
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (written - lastEmitBytes >= 256 * 1024 || now - lastEmitTime >= 150L) {
                            lastEmitBytes = written
                            lastEmitTime = now
                            _downloadState.value = ModelDownloadState(
                                status = ModelDownloadStatus.DOWNLOADING,
                                downloadedBytes = written,
                                totalBytes = expectedTotal
                            )
                        }
                    }
                    output.fd.sync()
                }
            }
        }

        if (session.stopped.get()) {
            throw CancellationException("Download stopped by user")
        }

        if (!verifyAndPromotePart(part, target)) {
            throw IOException("Model SHA-256 verification failed")
        }
    }

    private fun verifyAndPromotePart(part: File, target: File): Boolean {
        if (!part.exists() || part.length() != EXPECTED_MODEL_BYTES) {
            part.delete()
            return false
        }
        _downloadState.value = ModelDownloadState(
            status = ModelDownloadStatus.VERIFYING,
            downloadedBytes = EXPECTED_MODEL_BYTES,
            totalBytes = EXPECTED_MODEL_BYTES
        )
        val digest = sha256(part)
        if (!digest.equals(EXPECTED_MODEL_SHA256, ignoreCase = true)) {
            Log.w(TAG, "SHA-256 mismatch: expected=$EXPECTED_MODEL_SHA256 actual=$digest")
            part.delete()
            return false
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        _downloadState.value = ModelDownloadState(
            status = ModelDownloadStatus.READY,
            downloadedBytes = EXPECTED_MODEL_BYTES,
            totalBytes = EXPECTED_MODEL_BYTES
        )
        return true
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun ensureInitialized(): Boolean = inferenceMutex.withLock {
        if (engine?.isInitialized() == true) return true
        if (!isModelDownloaded()) {
            lastInitError = null
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val modelFile = modelFile()
                val cacheDir = File(context.cacheDir, "litert_cache").apply { mkdirs() }
                val initialized = tryInitializeWithBackend(modelFile, cacheDir, useGpu = true)
                    ?: tryInitializeWithBackend(modelFile, cacheDir, useGpu = false)
                if (initialized != null) {
                    engine = initialized.first
                    activeBackend = initialized.second
                    lastInitError = null
                    Log.i(TAG, "EmbeddingGemma 2 initialized on backend=$activeBackend")
                    true
                } else {
                    false
                }
            } catch (t: Throwable) {
                lastInitError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "Failed to prepare EmbeddingGemma 2 model", t)
                false
            }
        }
    }

    private fun tryInitializeWithBackend(
        modelFile: File,
        cacheDir: File,
        useGpu: Boolean
    ): Pair<EmbeddingEngine, String>? {
        val label = if (useGpu) BACKEND_GPU else BACKEND_CPU
        val textBackend: Backend = if (useGpu) Backend.GPU() else Backend.CPU()
        val visionBackend: Backend = if (useGpu) Backend.GPU() else Backend.CPU()
        var candidate: EmbeddingEngine? = null
        return try {
            val config = EmbeddingEngineConfig(
                modelPath = modelFile.absolutePath,
                backend = textBackend,
                visionBackend = visionBackend,
                cacheDir = cacheDir.absolutePath
            )
            candidate = EmbeddingEngine(config)
            candidate.initialize()
            candidate to label
        } catch (t: Throwable) {
            lastInitError = "$label init failed: ${t.message ?: t.javaClass.simpleName}"
            Log.w(TAG, "EmbeddingEngine init failed for $label", t)
            runCatching { candidate?.close() }
            null
        }
    }

    suspend fun embedDocument(title: String, body: String): FloatArray? {
        if (!ensureInitialized()) return null
        val cleanTitle = title.trim().take(MAX_TITLE_CHARS).ifBlank { "none" }
        val cleanBody = body.trim()
        for (limit in intArrayOf(MAX_DOC_CHARS, 360, 160)) {
            val prompt = "title: $cleanTitle | text: ${cleanBody.take(limit)}"
            val result = computeSingle(listOf(InputData.Text(prompt)))
            if (result != null) return result
        }
        return null
    }

    suspend fun embedQuery(query: String): FloatArray? {
        val trimmed = query.trim().take(MAX_QUERY_CHARS)
        if (trimmed.isEmpty()) return null
        if (!ensureInitialized()) return null
        val prompt = "task: search query | query: $trimmed"
        return computeSingle(listOf(InputData.Text(prompt)))
    }

    suspend fun embedImage(imageBytes: ByteArray): FloatArray? {
        if (imageBytes.isEmpty()) return null
        if (!ensureInitialized()) return null
        return computeSingle(listOf(InputData.Image(imageBytes)))
    }

    private suspend fun computeSingle(inputs: List<InputData>): FloatArray? = inferenceMutex.withLock {
        val activeEngine = engine ?: return null
        withContext(Dispatchers.Default) {
            try {
                val options = EmbeddingOptions(normalize = true)
                val response = activeEngine.computeEmbedding(inputs, options)
                val vec = response.embedding.takeIf { it.isNotEmpty() }
                if (vec == null) {
                    lastInferenceError = "EmbeddingGemma 2 returned empty embedding"
                } else {
                    lastInferenceError = null
                }
                vec
            } catch (t: Throwable) {
                lastInferenceError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Embedding inference failed", t)
                null
            }
        }
    }

    override fun close() {
        activeSession?.stop(StopMode.PAUSE)
        downloadScope.cancel()
        runCatching { engine?.close() }
        engine = null
        activeBackend = BACKEND_UNINITIALIZED
    }

    companion object {
        private const val TAG = "EmbeddingModelManager"
        const val MODEL_ASSET_NAME = "embeddinggemma-2-text-vision-440m.litertlm"
        const val MODEL_DOWNLOAD_URL =
            "https://files.melonhu.cn/indexrecord/embeddinggemma-2-text-vision-440m.litertlm"
        const val EXPECTED_MODEL_BYTES = 387_710_976L
        const val EXPECTED_MODEL_SHA256 =
            "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0"
        private const val MAX_TITLE_CHARS = 120
        private const val MAX_DOC_CHARS = 700
        private const val MAX_QUERY_CHARS = 300

        const val BACKEND_UNINITIALIZED = "UNINITIALIZED"
        const val BACKEND_GPU = "GPU"
        const val BACKEND_CPU = "CPU"
    }
}
