package com.melonhu.indexrecord.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

import kotlinx.coroutines.flow.*
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.repository.HomeRecordRepository
import com.melonhu.indexrecord.presentation.HomeQueryFilter
import com.melonhu.indexrecord.presentation.RecordContentPolicy
import com.melonhu.indexrecord.sync.HomeSyncCoordinator

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class HomeFilter(
        val startMillis: Long? = null,
        val endMillis: Long? = null,
        val apps: Set<String> = emptySet(),
        val types: Set<String> = emptySet()
    ) {
        internal fun queryFilter() = HomeQueryFilter(startMillis, endMillis, apps, types)
        val isActive: Boolean
            get() = queryFilter().isActive
    }

    private val repository = HomeRecordRepository()
    private val syncCoordinator = HomeSyncCoordinator(application, viewModelScope)

    init {
        syncCoordinator.start()
    }

    private val _filter = MutableStateFlow(HomeFilter())
    val filter: StateFlow<HomeFilter> = _filter.asStateFlow()

    fun updateTimeRange(startMillis: Long?, endMillis: Long?) {
        _filter.value = _filter.value.copy(startMillis = startMillis, endMillis = endMillis)
    }

    fun updateApps(apps: Set<String>) {
        val platforms = apps.mapNotNull { PlatformCatalog.platformForPackage(it) }.toSet()
        val validTypes = if (platforms.isEmpty()) emptySet() else {
            _filter.value.types.filter { RecordContentPolicy.platformFromFilterTypeKey(it) in platforms }.toSet()
        }
        _filter.value = _filter.value.copy(apps = apps, types = validTypes)
    }

    fun updateTypes(types: Set<String>) {
        _filter.value = _filter.value.copy(types = types)
    }

    fun clearFilters() {
        _filter.value = HomeFilter()
    }

    val isRefreshing = syncCoordinator.isRefreshing
    val availableAppsFlow = repository.availableApps
    val availableTypesFlow = repository.availableTypes
    val rawPendingCountFlow = repository.rawPendingCount
    val syncProgress = syncCoordinator.syncProgress


    private val _isInitialLoaded = MutableStateFlow(false)
    val isInitialLoaded: StateFlow<Boolean> = _isInitialLoaded.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recordsFlow: StateFlow<List<UniversalRecord>> = _filter
        .flatMapLatest { filterState ->
            repository.records(filterState.queryFilter())
        }
        .onEach { _isInitialLoaded.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    suspend fun refreshData(): Int = syncCoordinator.refresh()
}
