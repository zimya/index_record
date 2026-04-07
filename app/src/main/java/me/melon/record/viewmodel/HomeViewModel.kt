package me.melon.record.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import io.objectbox.android.AndroidScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import me.melon.record.ObjectBox
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class HomeFilter(
        val startMillis: Long? = null,
        val endMillis: Long? = null,
        val apps: Set<String> = emptySet(),
        val platforms: Set<String> = emptySet(),
        val types: Set<String> = emptySet()
    )

    private val rawDataBox = ObjectBox.store.boxFor(RawData::class.java)
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

    val availablePlatformsFlow: Flow<List<String>> = callbackFlow {
        val query = recordBox.query().build()
        val subscription = query.subscribe()
            .on(AndroidScheduler.mainThread())
            .observer { data ->
                val platforms = data.map { it.platform }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                trySend(platforms)
            }

        val initial = query.find()
            .map { it.platform }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        trySend(initial)

        awaitClose {
            subscription.cancel()
            query.close()
        }
    }.flowOn(Dispatchers.IO)

    val availableTypesFlow: Flow<List<String>> = callbackFlow {
        val query = recordBox.query().build()
        val subscription = query.subscribe()
            .on(AndroidScheduler.mainThread())
            .observer { data ->
                val types = data.map { it.recordType }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                trySend(types)
            }

        val initial = query.find()
            .map { it.recordType }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        trySend(initial)

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

    suspend fun refreshData(): Int {
        if (_isRefreshing.value) return 0
        _isRefreshing.value = true
        try {
            val beforeCount = withContext(Dispatchers.IO) { recordBox.count() }
            val intent = Intent("me.melon.record.ACTION_SYNC")
            getApplication<Application>().sendBroadcast(intent)

            withContext(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                val minWaitMs = 1200L
                val stableWindowMs = 700L
                val maxWaitMs = 10000L
                var lastPending = -1L
                var stableStart = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < maxWaitMs) {
                    me.melon.record.logic.DataProcessor.processPendingData()
                    delay(180)
                    val pending = rawDataBox.count()
                    val now = System.currentTimeMillis()
                    if (pending != lastPending) {
                        lastPending = pending
                        stableStart = now
                    }
                    if (
                        now - startTime >= minWaitMs &&
                        pending == 0L &&
                        now - stableStart >= stableWindowMs
                    ) {
                        break
                    }
                }
                me.melon.record.logic.DataProcessor.processPendingData()
            }
            val afterCount = withContext(Dispatchers.IO) { recordBox.count() }
            return (afterCount - beforeCount).coerceAtLeast(0).toInt()
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
        val platforms = filterState.platforms
        val types = filterState.types

        return data.asSequence()
            .filter { record ->
                if (trimmedQuery.isEmpty() && record.recordType == "comment") return@filter false
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
