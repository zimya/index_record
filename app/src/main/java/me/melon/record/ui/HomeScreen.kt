package me.melon.record.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.FilterBottomSheet
import me.melon.record.ui.components.RecordItem
import me.melon.record.viewmodel.HomeViewModel

private enum class NavAction { PUSH, POP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val records by viewModel.recordsFlow.collectAsState(initial = emptyList())
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val availableApps by viewModel.availableAppsFlow.collectAsState(initial = emptyList())
    val availablePlatforms by viewModel.availablePlatformsFlow.collectAsState(initial = emptyList())
    val availableTypes by viewModel.availableTypesFlow.collectAsState(initial = emptyList())

    val navStack = remember { mutableStateListOf<UniversalRecord>() }
    val currentRecord = navStack.lastOrNull()
    var lastNavAction by remember { mutableStateOf(NavAction.PUSH) }
    var isSearchActive by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = currentRecord,
        transitionSpec = {
            if (lastNavAction == NavAction.PUSH) {
                (slideInHorizontally { it } + fadeIn()).togetherWith(slideOutHorizontally { -it / 2 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 2 } + fadeIn()).togetherWith(slideOutHorizontally { it } + fadeOut())
            }.using(SizeTransform(clip = false))
        },
        label = "ScreenTransition"
    ) { targetRecord ->
        if (targetRecord != null) {
            BackHandler {
                if (navStack.isNotEmpty()) {
                    lastNavAction = NavAction.POP
                    navStack.removeAt(navStack.lastIndex)
                }
            }
            if (targetRecord.recordType == "comment") {
                CommentDetailScreen(
                    record = targetRecord,
                    onBack = {
                        if (navStack.isNotEmpty()) {
                            lastNavAction = NavAction.POP
                            navStack.removeAt(navStack.lastIndex)
                        }
                    },
                    onOpenParent = {
                        lastNavAction = NavAction.PUSH
                        navStack.add(it)
                    }
                )
            } else if (targetRecord.recordType == "question") {
                QuestionDetailScreen(
                    record = targetRecord,
                    onBack = {
                        if (navStack.isNotEmpty()) {
                            lastNavAction = NavAction.POP
                            navStack.removeAt(navStack.lastIndex)
                        }
                    },
                    onOpenAnswer = {
                        lastNavAction = NavAction.PUSH
                        navStack.add(it)
                    }
                )
            } else {
                RecordDetailScreen(
                    record = targetRecord,
                    onBack = {
                        if (navStack.isNotEmpty()) {
                            lastNavAction = NavAction.POP
                            navStack.removeAt(navStack.lastIndex)
                        }
                    },
                    onOpenRecord = {
                        lastNavAction = NavAction.PUSH
                        navStack.add(it)
                    }
                )
            }
        } else {
            PullToRefreshListScreen(
                records = records,
                searchQuery = searchQuery,
                filter = filter,
                availableApps = availableApps,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                isSearchActive = isSearchActive,
                onSearchActiveChange = {
                    isSearchActive = it
                    if (!it) viewModel.updateSearchQuery("")
                },
                onRefresh = { viewModel.refreshData() },
                onOpenFilter = { showFilterSheet = true },
                onItemClick = {
                    lastNavAction = NavAction.PUSH
                    navStack.add(it)
                }
            )
        }
    }

    if (showFilterSheet) {
        FilterBottomSheet(
            filter = filter,
            availableApps = availableApps,
            availablePlatforms = availablePlatforms,
            availableTypes = availableTypes,
            onDismiss = { showFilterSheet = false },
            onApply = { start, end, apps, platforms, types ->
                val normalized = if (start != null && end != null && start > end) end to start else start to end
                viewModel.updateTimeRange(normalized.first, normalized.second)
                viewModel.updateApps(apps)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshListScreen(
    records: List<UniversalRecord>,
    searchQuery: String,
    filter: HomeViewModel.HomeFilter,
    availableApps: List<String>,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    onRefresh: suspend () -> Unit,
    onOpenFilter: () -> Unit,
    onItemClick: (UniversalRecord) -> Unit
) {
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(pullState.isRefreshing) {
        if (!pullState.isRefreshing) return@LaunchedEffect
        try {
            onRefresh()
        } finally {
            pullState.endRefresh()
        }
    }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = isSearchActive,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                            scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)))
                        .togetherWith(fadeOut(animationSpec = tween(90)))
                },
                label = "TopBarTransition"
            ) { active ->
                if (active) {
                    TopAppBar(
                        title = {
                            TextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("搜索内容、作者、平台...") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { onSearchQueryChange("") }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear")
                                        }
                                    }
                                }
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { onSearchActiveChange(false) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                } else {
                    TopAppBar(
                        title = { Text("小茵记录") },
                        actions = {
                            IconButton(onClick = { onSearchActiveChange(true) }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = onOpenFilter) {
                                Icon(Icons.Default.FilterList, contentDescription = "Filter")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.primary,
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            if (records.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchQuery.isNotEmpty()) "没有找到匹配的记录" else "暂无记录，去刷刷社交网络吧！",
                        color = Color.Gray
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(records, key = { it.id }) { record ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        RecordItem(record, searchQuery) {
                            onItemClick(record)
                        }
                    }
                }
            }

            if (pullState.isRefreshing || pullState.verticalOffset > 0) {
                PullToRefreshContainer(
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}
