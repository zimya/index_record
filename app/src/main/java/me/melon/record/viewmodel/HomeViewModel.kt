package me.melon.record.viewmodel

import android.app.Application
import android.app.BroadcastOptions
import android.os.Build
import android.util.Log
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import me.melon.record.ObjectBox
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.logic.DataProcessor

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class HomeFilter(
        val startMillis: Long? = null,
        val endMillis: Long? = null,
        val apps: Set<String> = emptySet(),
        val platforms: Set<String> = emptySet(),
        val types: Set<String> = emptySet()
    ) {
        val isActive: Boolean
            get() = startMillis != null ||
                    endMillis != null ||
                    apps.isNotEmpty() ||
                    platforms.isNotEmpty() ||
                    types.isNotEmpty()
    }

    private val rawDataBox = ObjectBox.store.boxFor(RawData::class.java)
    private val recordBox = ObjectBox.store.boxFor(UniversalRecord::class.java)
    private val rawBox = ObjectBox.store.boxFor(RawData::class.java)

    init {
        // App 启动时向知乎等宿主进程发送唤醒同步广播，并自动检查清理未解析数据
        viewModelScope.launch(Dispatchers.IO) {
            DataProcessor.requestProcessing()
            sendSyncBroadcast()
        }
    }

    private fun sendSyncBroadcast() {
        val app = getApplication<Application>()
        me.melon.record.sync.RecordSyncBridge.init(app)
        val bundle = android.os.Bundle().apply {
            putBinder("sync_binder", me.melon.record.sync.RecordSyncBridge)
        }
        val targetPackages = listOf(
            "com.zhihu.android",
            "com.douban.frodo",
            "com.baidu.tieba",
            "com.xingin.xhs",
            "com.twitter.android"
        )
        val options = if (Build.VERSION.SDK_INT >= 34) {
            BroadcastOptions.makeBasic()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE)
                .toBundle()
        } else null
        for (pkg in targetPackages) {
            try {
                val intent = Intent("me.melon.record.ACTION_SYNC").apply {
                    setPackage(pkg)
                    putExtras(bundle)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                app.sendBroadcast(intent, null, options)
            } catch (e: Exception) {
                Log.w("HomeViewModel", "Failed to request sync from $pkg", e)
            }
        }
        try {
            val genericIntent = Intent("me.melon.record.ACTION_SYNC").apply {
                putExtras(bundle)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            app.sendBroadcast(genericIntent, null, options)
        } catch (e: Exception) {
            Log.w("HomeViewModel", "Failed to broadcast sync request", e)
        }
    }

    // Search query state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val _filter = MutableStateFlow(HomeFilter())
    val filter: StateFlow<HomeFilter> = _filter.asStateFlow()

    fun updateTimeRange(startMillis: Long?, endMillis: Long?) {
        _filter.value = _filter.value.copy(startMillis = startMillis, endMillis = endMillis)
    }

    fun updateApps(apps: Set<String>) {
        _filter.value = _filter.value.copy(apps = apps)
    }

    fun updatePlatforms(platforms: Set<String>) {
        _filter.value = _filter.value.copy(platforms = platforms)
    }

    fun updateTypes(types: Set<String>) {
        _filter.value = _filter.value.copy(types = types)
    }

    fun clearFilters() {
        _filter.value = HomeFilter()
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private fun queryAvailableApps(): List<String> {
        val query = recordBox.query().build()
        val directApps = query.property(UniversalRecord_.sourceApp).distinct().findStrings()
            ?.filter { !it.isNullOrBlank() } ?: emptyList()
        val platforms = query.property(UniversalRecord_.platform).distinct().findStrings()
            ?.filter { !it.isNullOrBlank() } ?: emptyList()
        query.close()
        val fallbackApps = platforms.map { p ->
            when (p) {
                "zhihu" -> "com.zhihu.android"
                "douban" -> "com.douban.frodo"
                "tieba" -> "com.baidu.tieba"
                "xiaohongshu" -> "com.xingin.xhs"
                "twitter" -> "com.twitter.android"
                else -> ""
            }
        }.filter { it.isNotBlank() }
        return (directApps + fallbackApps).distinct().sorted()
    }

    private fun queryAvailablePlatforms(): List<String> {
        val query = recordBox.query().build()
        val platforms = query.property(UniversalRecord_.platform).distinct().findStrings()
            ?.filter { !it.isNullOrBlank() }
            ?.sorted() ?: emptyList()
        query.close()
        return platforms
    }

    private fun queryAvailableTypes(): List<String> {
        val query = recordBox.query().build()
        val types = query.property(UniversalRecord_.recordType).distinct().findStrings()
            ?.filter { !it.isNullOrBlank() }
            ?.sorted() ?: emptyList()
        query.close()
        return types
    }

    val availableAppsFlow: Flow<List<String>> = callbackFlow {
        val subscription = ObjectBox.store.subscribe(UniversalRecord::class.java)
            .observer {
                trySend(queryAvailableApps())
            }

        trySend(queryAvailableApps())

        awaitClose {
            subscription.cancel()
        }
    }.flowOn(Dispatchers.IO)

    val availablePlatformsFlow: Flow<List<String>> = callbackFlow {
        val subscription = ObjectBox.store.subscribe(UniversalRecord::class.java)
            .observer {
                trySend(queryAvailablePlatforms())
            }

        trySend(queryAvailablePlatforms())

        awaitClose {
            subscription.cancel()
        }
    }.flowOn(Dispatchers.IO)

    val availableTypesFlow: Flow<List<String>> = callbackFlow {
        val subscription = ObjectBox.store.subscribe(UniversalRecord::class.java)
            .observer {
                trySend(queryAvailableTypes())
            }

        trySend(queryAvailableTypes())

        awaitClose {
            subscription.cancel()
        }
    }.flowOn(Dispatchers.IO)

    val rawPendingCountFlow: Flow<Long> = callbackFlow {
        // 轻量观察 RawData 表变动，直接利用原生 count()，不实例化任何巨型字符串
        val subscription = ObjectBox.store.subscribe(RawData::class.java)
            .observer {
                trySend(rawDataBox.count())
            }

        trySend(rawDataBox.count())

        awaitClose {
            subscription.cancel()
        }
    }.flowOn(Dispatchers.IO)

    val syncProgress: StateFlow<DataProcessor.SyncProgress> = DataProcessor.syncProgress

    enum class NavAction { PUSH, POP }

    val navStack = mutableStateListOf<UniversalRecord>()
    var lastNavAction by mutableStateOf(NavAction.PUSH)
    val homeListState = LazyListState()

    // 详情页滚动状态放在 ViewModel 中，返回后仍能恢复到原位置
    private val detailListStates = mutableMapOf<String, LazyListState>()

    fun detailListState(key: String): LazyListState =
        detailListStates.getOrPut(key) { LazyListState() }

    fun pushDetail(record: UniversalRecord) {
        if (navStack.isNotEmpty() && navStack.last().id == record.id && record.id != 0L) {
            return
        }
        lastNavAction = NavAction.PUSH
        navStack.add(record)
    }

    fun popDetail(): Boolean {
        if (navStack.isNotEmpty()) {
            lastNavAction = NavAction.POP
            navStack.removeAt(navStack.lastIndex)
            return true
        }
        return false
    }

    private val _isInitialLoaded = MutableStateFlow(false)
    val isInitialLoaded: StateFlow<Boolean> = _isInitialLoaded.asStateFlow()

    // 监听 UniversalRecord 数据库变化，在 IO 线程完成查询与过滤
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recordsFlow: StateFlow<List<UniversalRecord>> = combine(_searchQuery, _filter) { q, f -> q to f }
        .flatMapLatest { (queryText, filterState) ->
            callbackFlow {
                val queryBuilder = recordBox.query()
                val query = queryBuilder.orderDesc(UniversalRecord_.captureTime).build()

                val subscription = query.subscribe()
                    .observer { data ->
                        trySend(applyFilters(data, queryText, filterState))
                    }

                trySend(applyFilters(query.find(), queryText, filterState))

                awaitClose {
                    subscription.cancel()
                    query.close()
                }
            }.flowOn(Dispatchers.IO)
        }
        .onEach { _isInitialLoaded.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    suspend fun refreshData(): Int {
        if (_isRefreshing.value) return 0
        _isRefreshing.value = true
        try {
            val beforeCount = withContext(Dispatchers.IO) { recordBox.count() }
            sendSyncBroadcast()

            withContext(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                val initialProbeMs = 1500L
                val stableWindowMs = 800L
                val maxWaitMs = 15000L
                var hasReceivedAny = rawDataBox.count() > 0
                var lastPending = -1L
                var stableStart = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < maxWaitMs) {
                    me.melon.record.logic.DataProcessor.processPendingData()
                    val pending = rawDataBox.count()
                    val now = System.currentTimeMillis()

                    if (pending > 0) {
                        hasReceivedAny = true
                    }

                    if (pending != lastPending) {
                        lastPending = pending
                        stableStart = now
                    }

                    val elapsed = now - startTime
                    if (!hasReceivedAny) {
                        if (elapsed >= initialProbeMs && pending == 0L) {
                            break
                        }
                    } else {
                        if (pending == 0L && now - stableStart >= stableWindowMs) {
                            break
                        }
                    }
                    delay(150)
                }
                me.melon.record.logic.DataProcessor.processPendingData()
            }
            val afterCount = withContext(Dispatchers.IO) { recordBox.count() }
            return (afterCount - beforeCount).coerceAtLeast(0).toInt()
        } finally {
            _isRefreshing.value = false
            // 若仍有未解析的数据，在后台继续处理
            if (rawDataBox.count() > 0) {
                viewModelScope.launch(Dispatchers.IO) {
                    DataProcessor.processPendingData()
                }
            }
        }
    }

    private fun applyFilters(
        data: List<UniversalRecord>,
        queryText: String,
        filterState: HomeFilter
    ): List<UniversalRecord> {
        val trimmedQuery = queryText.trim()
        val start = filterState.startMillis
        val end = filterState.endMillis
        val apps = filterState.apps
        val platforms = filterState.platforms
        val types = filterState.types
        val allowComments = trimmedQuery.isNotEmpty() || ("comment" in types)

        return data.asSequence()
            .filter { record ->
                if (!allowComments && record.recordType == "comment") return@filter false
                if (start != null && record.captureTime < start) return@filter false
                if (end != null && record.captureTime > end) return@filter false
                val sourceApp = resolveSourceApp(record)
                if (apps.isNotEmpty() && sourceApp !in apps) return@filter false
                if (platforms.isNotEmpty() && record.platform !in platforms) return@filter false
                if (types.isNotEmpty() && record.recordType !in types) return@filter false
                true
            }
            .filter { record ->
                if (trimmedQuery.isEmpty()) return@filter true
                val q = trimmedQuery.lowercase()
                record.title.contains(q, ignoreCase = true) ||
                        record.content.contains(q, ignoreCase = true) ||
                        record.authorName.contains(q, ignoreCase = true) ||
                        record.platform.contains(q, ignoreCase = true) ||
                        record.recordType.contains(q, ignoreCase = true) ||
                        record.extraMetadata.contains(q, ignoreCase = true) ||
                        resolveSourceApp(record).contains(q, ignoreCase = true)
            }
            .toList()
    }

    private fun resolveSourceApp(record: UniversalRecord): String {
        val direct = record.sourceApp ?: ""
        if (direct.isNotBlank()) return direct
        return when (record.platform) {
            "zhihu" -> "com.zhihu.android"
            "douban" -> "com.douban.frodo"
            "tieba" -> "com.baidu.tieba"
            "xiaohongshu" -> "com.xingin.xhs"
            "twitter" -> "com.twitter.android"
            else -> ""
        }
    }
}
