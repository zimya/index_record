package me.melon.record.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.objectbox.android.AndroidScheduler
import io.objectbox.query.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class HomeFilter(
        val startMillis: Long? = null,
        val endMillis: Long? = null,
        val apps: Set<String> = emptySet()
    )

    init {
        // 1. 发送广播，通知宿主 App（如知乎）推送积压的数据
        val intent = Intent("me.melon.record.ACTION_SYNC")
        application.sendBroadcast(intent)

        // 2. 在应用启动（ViewModel初始化）时，处理所有本地已有的 RawData
        viewModelScope.launch(Dispatchers.IO) {
            try {
                me.melon.record.logic.DataProcessor.processPendingData()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val recordBox = ObjectBox.store.boxFor(UniversalRecord::class.java)

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

    fun clearFilters() {
        _filter.value = HomeFilter()
    }

    // UI State: 由于改为了后台自动解析，我们不再需要手动刷新控制
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val availableAppsFlow: Flow<List<String>> = callbackFlow {
        val query = recordBox.query().build()
        val subscription = query.subscribe()
            .on(AndroidScheduler.mainThread())
            .observer { data ->
                val apps = data.map { resolveSourceApp(it) }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                trySend(apps)
            }

        val initialApps = query.find()
            .map { resolveSourceApp(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        trySend(initialApps)

        awaitClose {
            subscription.cancel()
            query.close()
        }
    }.flowOn(Dispatchers.IO)

    // 监听 UniversalRecord 数据库变化，并支持搜索过滤
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recordsFlow: Flow<List<UniversalRecord>> = combine(_searchQuery, _filter) { q, f -> q to f }
        .flatMapLatest { (queryText, filterState) ->
        callbackFlow {
            val queryBuilder = recordBox.query()
            val query = queryBuilder.orderDesc(UniversalRecord_.captureTime).build()

            val subscription = query.subscribe()
                .on(AndroidScheduler.mainThread())
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

    // 如果用户非要下拉刷新，可以手动触发重试同步
    suspend fun refreshData() {
        _isRefreshing.value = true
        try {
            val intent = Intent("me.melon.record.ACTION_SYNC")
            getApplication<Application>().sendBroadcast(intent)
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                me.melon.record.logic.DataProcessor.processPendingData()
            }
        } finally {
            _isRefreshing.value = false
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

        return data.asSequence()
            .filter { record ->
                if (start != null && record.captureTime < start) return@filter false
                if (end != null && record.captureTime > end) return@filter false
                val sourceApp = resolveSourceApp(record)
                if (apps.isNotEmpty() && sourceApp !in apps) return@filter false
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
