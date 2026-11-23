package com.melonhu.indexrecord.search

import android.content.Context
import android.util.Log
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.model.UniversalRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped search & indexing runtime initialized off the main thread.
 * Safe to invoke from unit tests without Android context (no-op when uninitialized).
 */
internal object SearchRuntime {

    private const val TAG = "SearchRuntime"
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var coordinator: SearchIndexingCoordinator? = null

    @Volatile
    private var engine: HybridSearchEngine? = null

    @Volatile
    private var initDeferred: CompletableDeferred<HybridSearchEngine?>? = null

    private val _indexingState = MutableStateFlow(SearchIndexingState())
    val indexingState: StateFlow<SearchIndexingState> = _indexingState.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val deferred = CompletableDeferred<HybridSearchEngine?>()
        initDeferred = deferred

        runtimeScope.launch {
            try {
                val coord = SearchIndexingCoordinator.create(ctx, ObjectBox.store)
                val eng = HybridSearchEngine(
                    ftsStore = coord.ftsStore,
                    vectorStore = coord.vectorStore,
                    modelManager = coord.modelManager,
                    store = ObjectBox.store
                )
                coordinator = coord
                engine = eng
                deferred.complete(eng)
                coord.start()
                coord.state.collect { state ->
                    _indexingState.value = state
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize search runtime", t)
                _indexingState.value = _indexingState.value.copy(
                    lastError = t.message ?: t.javaClass.simpleName
                )
                deferred.complete(null)
            }
        }
    }

    suspend fun awaitEngine(): HybridSearchEngine? {
        engine?.let { return it }
        return initDeferred?.await()
    }

    fun onRecordsPersisted(records: List<UniversalRecord>) {
        if (records.isEmpty()) return
        val coord = coordinator
        if (coord != null) {
            coord.onRecordsPersisted(records)
        } else {
            runtimeScope.launch {
                awaitEngine()
                coordinator?.onRecordsPersisted(records)
            }
        }
    }

    fun boostForSearchScreen() {
        val coord = coordinator
        if (coord != null) {
            coord.boostForSearchScreen()
        } else {
            runtimeScope.launch {
                awaitEngine()
                coordinator?.boostForSearchScreen()
            }
        }
    }

    fun pauseIndexing() {
        coordinator?.pause()
    }

    fun resumeIndexing() {
        coordinator?.resume()
    }

    fun retryFailedIndexing() {
        coordinator?.retryFailed()
    }

    fun clearImageCache() {
        coordinator?.clearImageCache()
    }

    fun startModelDownload() {
        val coord = coordinator
        if (coord != null) {
            coord.startModelDownload()
        } else {
            runtimeScope.launch {
                awaitEngine()
                coordinator?.startModelDownload()
            }
        }
    }

    fun pauseModelDownload() {
        coordinator?.pauseModelDownload()
    }

    fun cancelModelDownload() {
        coordinator?.cancelModelDownload()
    }

    fun deleteDownloadedModel() {
        coordinator?.deleteDownloadedModel()
    }

    fun clearAllIndexes() {
        val coord = coordinator
        if (coord != null) {
            coord.clearAllIndexes()
        } else {
            runtimeScope.launch {
                awaitEngine()
                coordinator?.clearAllIndexes()
            }
        }
    }
}
