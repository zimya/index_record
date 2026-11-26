package com.melonhu.indexrecord.ui.home

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.ui.components.FilterBottomSheet
import com.melonhu.indexrecord.ui.detail.DetailOverlayHost
import com.melonhu.indexrecord.viewmodel.DetailNavigationViewModel
import com.melonhu.indexrecord.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val records by viewModel.recordsFlow.collectAsState()
    val isInitialLoaded by viewModel.isInitialLoaded.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val availablePlatforms by viewModel.availablePlatformsFlow.collectAsState(initial = emptyList())
    val availableTypes by viewModel.availableTypesFlow.collectAsState(initial = emptyList())
    val rawPendingCount by viewModel.rawPendingCountFlow.collectAsState(initial = 0L)
    val syncProgress by viewModel.syncProgress.collectAsState()

    val navigation: DetailNavigationViewModel = viewModel()
    val homeListState = rememberLazyListState()
    var showFilterSheet by remember { mutableStateOf(false) }

    DetailOverlayHost(navigation = navigation) {
        PullToRefreshListScreen(
            records = records,
            recordsLoaded = isInitialLoaded,
            filter = filter,
            onRefresh = { viewModel.refreshData() },
            onOpenFilter = { showFilterSheet = true },
            listState = homeListState,
            rawPendingCount = rawPendingCount,
            syncProgress = syncProgress,
            onItemClick = { navigation.push(it) }
        )
    }

    if (showFilterSheet) {
        FilterBottomSheet(
            filter = filter,
            availablePlatforms = availablePlatforms,
            availableTypes = availableTypes,
            onDismiss = { showFilterSheet = false },
            onApply = { start, end, platforms, types ->
                val normalized = if (start != null && end != null && start > end) end to start else start to end
                viewModel.updateTimeRange(normalized.first, normalized.second)
                viewModel.updatePlatforms(platforms)
                viewModel.updateTypes(types)
                showFilterSheet = false
            },
            onClear = {
                viewModel.clearFilters()
                showFilterSheet = false
            }
        )
    }
}
