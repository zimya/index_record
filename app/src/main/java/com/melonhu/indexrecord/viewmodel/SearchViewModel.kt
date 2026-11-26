package com.melonhu.indexrecord.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.repository.HomeRecordRepository
import com.melonhu.indexrecord.presentation.RecordContentPolicy
import com.melonhu.indexrecord.search.HybridSearchOutput
import com.melonhu.indexrecord.search.SearchFilter
import com.melonhu.indexrecord.search.SearchHistoryStore
import com.melonhu.indexrecord.search.SearchIndexingState
import com.melonhu.indexrecord.search.SearchMatchSource
import com.melonhu.indexrecord.search.SearchResultItem
import com.melonhu.indexrecord.search.SearchRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val highlightTerms: List<String> = emptyList(),
    val results: List<SearchResultItem> = emptyList(),
    val usedVectorSearch: Boolean = false,
    val searchError: String? = null
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val optionRepository = HomeRecordRepository()
    private val historyStore = SearchHistoryStore(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter())
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _searchHistory = MutableStateFlow(historyStore.load())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _searchTrigger = MutableStateFlow(0L)

    val indexingState: StateFlow<SearchIndexingState> = SearchRuntime.indexingState
    val availablePlatformsFlow = optionRepository.availablePlatforms
    val availableTypesFlow = optionRepository.availableTypes

    private val _uiState = MutableStateFlow(SearchUiState())

    init {
        SearchRuntime.init(application)
        SearchRuntime.boostForSearchScreen()
        viewModelScope.launch {
            indexingState
                .map { it.isModelReady }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (_query.value.isNotBlank()) {
                        submitSearch()
                    }
                }
        }
    }

    fun onSearchTabOpened() {
        SearchRuntime.boostForSearchScreen()
    }

    val searchFlow = combine(
        _query.debounce(180L),
        _filter,
        _searchTrigger
    ) { q, filterState, _ ->
        q.trim() to filterState
    }.flatMapLatest { (trimmedQuery, filterState) ->
        if (trimmedQuery.isEmpty()) {
            flowOf(
                SearchUiState(
                    query = "",
                    isSearching = false
                )
            )
        } else {
            flow {
                emit(
                    _uiState.value.copy(
                        query = trimmedQuery,
                        isSearching = true,
                        searchError = null
                    )
                )
                val engine = SearchRuntime.awaitEngine()
                if (engine == null) {
                    emit(
                        SearchUiState(
                            query = trimmedQuery,
                            isSearching = false
                        )
                    )
                    return@flow
                }
                val output: Result<HybridSearchOutput> = runCatching {
                    engine.search(trimmedQuery, filter = filterState)
                }
                output.fold(
                    onSuccess = { res ->
                        emit(
                            SearchUiState(
                                query = res.query,
                                isSearching = false,
                                highlightTerms = res.highlightTerms,
                                results = res.results,
                                usedVectorSearch = res.usedVectorSearch,
                                searchError = if (res.results.isEmpty()) res.errorMessage else null
                            )
                        )
                    },
                    onFailure = { err ->
                        emit(
                            SearchUiState(
                                query = trimmedQuery,
                                isSearching = false,
                                searchError = err.message ?: err.javaClass.simpleName
                            )
                        )
                    }
                )
            }
        }
    }

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
    }

    fun submitSearch() {
        recordSearchHistory(_query.value)
        _searchTrigger.value = _searchTrigger.value + 1L
    }

    fun recordSearchHistory(term: String = _query.value) {
        if (term.isBlank()) return
        _searchHistory.value = historyStore.add(term)
    }

    fun selectHistoryQuery(term: String) {
        _query.value = term
        recordSearchHistory(term)
        _searchTrigger.value = _searchTrigger.value + 1L
    }

    fun removeSearchHistory(term: String) {
        _searchHistory.value = historyStore.remove(term)
    }

    fun clearSearchHistory() {
        _searchHistory.value = historyStore.clear()
    }

    fun applyMetadataFilter(
        startMillis: Long?,
        endMillis: Long?,
        platforms: Set<String>,
        types: Set<String>
    ) {
        val normalized = if (startMillis != null && endMillis != null && startMillis > endMillis) {
            endMillis to startMillis
        } else {
            startMillis to endMillis
        }
        val validTypes = if (platforms.isEmpty()) types else {
            types.filter { RecordContentPolicy.platformFromFilterTypeKey(it) in platforms }.toSet()
        }
        _filter.value = _filter.value.copy(
            startMillis = normalized.first,
            endMillis = normalized.second,
            platforms = platforms,
            types = validTypes
        )
    }

    fun clearMetadataFilter() {
        _filter.value = _filter.value.copy(
            startMillis = null,
            endMillis = null,
            platforms = emptySet(),
            types = emptySet()
        )
    }

    fun setStartTime(startMillis: Long?) {
        val currentEnd = _filter.value.endMillis
        val normalized = if (startMillis != null && currentEnd != null && startMillis > currentEnd) {
            currentEnd to startMillis
        } else {
            startMillis to currentEnd
        }
        _filter.value = _filter.value.copy(
            startMillis = normalized.first,
            endMillis = normalized.second
        )
    }

    fun setEndTime(endMillis: Long?) {
        val currentStart = _filter.value.startMillis
        val normalized = if (currentStart != null && endMillis != null && currentStart > endMillis) {
            endMillis to currentStart
        } else {
            currentStart to endMillis
        }
        _filter.value = _filter.value.copy(
            startMillis = normalized.first,
            endMillis = normalized.second
        )
    }

    fun togglePlatform(platform: String) {
        val currentPlatforms = _filter.value.platforms
        val nextPlatforms = if (platform in currentPlatforms) currentPlatforms - platform else currentPlatforms + platform
        val validTypes = if (nextPlatforms.isEmpty()) _filter.value.types else {
            _filter.value.types.filter { RecordContentPolicy.platformFromFilterTypeKey(it) in nextPlatforms }.toSet()
        }
        _filter.value = _filter.value.copy(
            platforms = nextPlatforms,
            types = validTypes
        )
    }

    fun clearPlatforms() {
        _filter.value = _filter.value.copy(platforms = emptySet())
    }

    fun toggleType(typeKey: String) {
        val currentTypes = _filter.value.types
        val nextTypes = if (typeKey in currentTypes) currentTypes - typeKey else currentTypes + typeKey
        _filter.value = _filter.value.copy(types = nextTypes)
    }

    fun clearTypes() {
        _filter.value = _filter.value.copy(types = emptySet())
    }

    fun toggleSource(source: SearchMatchSource) {
        val current = _filter.value.sources
        val next = if (source in current) {
            val candidate = current - source
            if (candidate.isEmpty()) current else candidate
        } else {
            current + source
        }
        _filter.value = _filter.value.copy(sources = next)
    }

    fun pauseIndexing() {
        SearchRuntime.pauseIndexing()
    }

    fun resumeIndexing() {
        SearchRuntime.resumeIndexing()
    }

    fun retryFailedIndexing() {
        SearchRuntime.retryFailedIndexing()
    }

    fun clearImageCache() {
        SearchRuntime.clearImageCache()
    }

    fun startModelDownload() {
        SearchRuntime.startModelDownload()
    }

    fun pauseModelDownload() {
        SearchRuntime.pauseModelDownload()
    }

    fun cancelModelDownload() {
        SearchRuntime.cancelModelDownload()
    }

    fun deleteDownloadedModel() {
        SearchRuntime.deleteDownloadedModel()
    }
}
