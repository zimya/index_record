package me.melon.record.ui.home

import me.melon.record.ui.detail.comment.CommentDetailScreen
import me.melon.record.ui.detail.question.QuestionDetailScreen
import me.melon.record.ui.detail.record.RecordDetailScreen
import me.melon.record.ui.detail.subject.SubjectDetailScreen
import me.melon.record.ui.detail.tieba.TiebaDetailScreen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.FilterBottomSheet
import me.melon.record.viewmodel.HomeViewModel
import androidx.compose.runtime.saveable.rememberSaveable

internal fun usesRecordDetailForComment(platform: String): Boolean =
    platform in setOf("weibo", "threads", "reddit")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val records by viewModel.recordsFlow.collectAsState()
    val isInitialLoaded by viewModel.isInitialLoaded.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val availableApps by viewModel.availableAppsFlow.collectAsState(initial = emptyList())
    val availableTypes by viewModel.availableTypesFlow.collectAsState(initial = emptyList())
    val rawPendingCount by viewModel.rawPendingCountFlow.collectAsState(initial = 0L)
    val syncProgress by viewModel.syncProgress.collectAsState()

    val currentRecord = viewModel.navStack.lastOrNull()
    val lastNavAction = viewModel.lastNavAction
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    val hasDetail = currentRecord != null
    var lastNonNullRecord by remember { mutableStateOf<UniversalRecord?>(null) }
    if (currentRecord != null) {
        lastNonNullRecord = currentRecord
    }

    val homeTransition = updateTransition(targetState = hasDetail, label = "HomeTransition")
    val homeOffsetFraction by homeTransition.animateFloat(
        transitionSpec = { tween(durationMillis = 300, easing = FastOutSlowInEasing) },
        label = "HomeOffsetFraction"
    ) { showDetail ->
        if (showDetail) -0.35f else 0f
    }
    val homeAlpha by homeTransition.animateFloat(
        transitionSpec = { tween(durationMillis = 300, easing = FastOutSlowInEasing) },
        label = "HomeAlpha"
    ) { showDetail ->
        if (showDetail) 0.85f else 1f
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = size.width * homeOffsetFraction
                    alpha = homeAlpha
                }
        ) {
            PullToRefreshListScreen(
                records = records,
                recordsLoaded = isInitialLoaded,
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
                listState = viewModel.homeListState,
                rawPendingCount = rawPendingCount,
                syncProgress = syncProgress,
                onItemClick = { viewModel.pushDetail(it) }
            )
        }

        AnimatedVisibility(
            visible = hasDetail,
            enter = slideInHorizontally(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                initialOffsetX = { it }
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutHorizontally(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                targetOffsetX = { it }
            ) + fadeOut(animationSpec = tween(300))
        ) {
            val recordToShow = currentRecord ?: lastNonNullRecord
            if (recordToShow != null) {
                BackHandler(enabled = hasDetail) {
                    viewModel.popDetail()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    shadowElevation = 8.dp
                ) {
                    AnimatedContent(
                        targetState = recordToShow,
                        transitionSpec = {
                            if (lastNavAction == HomeViewModel.NavAction.PUSH) {
                                (slideInHorizontally(
                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                    initialOffsetX = { it }
                                ) + fadeIn(animationSpec = tween(300)))
                                    .togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                                            targetOffsetX = { -it / 2 }
                                        ) + fadeOut(animationSpec = tween(300))
                                    )
                            } else {
                                (slideInHorizontally(
                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                    initialOffsetX = { -it / 2 }
                                ) + fadeIn(animationSpec = tween(300)))
                                    .togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                                            targetOffsetX = { it }
                                        ) + fadeOut(animationSpec = tween(300))
                                    )
                            }.using(SizeTransform(clip = false))
                        },
                        label = "DetailStackTransition"
                    ) { targetRecord ->
                        if (targetRecord.platform == "tieba" && targetRecord.recordType in setOf("lou", "floor", "comment")) {
                            TiebaDetailScreen(
                                record = targetRecord,
                                onBack = { viewModel.popDetail() },
                                onOpenRecord = { viewModel.pushDetail(it) },
                                listState = viewModel.detailListState(targetRecord.platformId),
                                firstFloorListState = viewModel.detailListState("${targetRecord.platformId}:first_floor_replies")
                            )
                        } else if (targetRecord.recordType == "comment" && !usesRecordDetailForComment(targetRecord.platform)) {
                            CommentDetailScreen(
                                record = targetRecord,
                                onBack = { viewModel.popDetail() },
                                onOpenParent = { viewModel.pushDetail(it) }
                            )
                        } else if (targetRecord.recordType == "question") {
                            QuestionDetailScreen(
                                record = targetRecord,
                                onBack = { viewModel.popDetail() },
                                onOpenAnswer = { viewModel.pushDetail(it) },
                                listState = viewModel.detailListState(targetRecord.platformId)
                            )
                        } else if (targetRecord.recordType == "subject") {
                            SubjectDetailScreen(
                                record = targetRecord,
                                onBack = { viewModel.popDetail() },
                                onOpenRecord = { viewModel.pushDetail(it) },
                                listState = viewModel.detailListState(targetRecord.platformId)
                            )
                        } else {
                            RecordDetailScreen(
                                record = targetRecord,
                                onBack = { viewModel.popDetail() },
                                onOpenRecord = { viewModel.pushDetail(it) },
                                listState = viewModel.detailListState(targetRecord.platformId)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        FilterBottomSheet(
            filter = filter,
            availableApps = availableApps,
            availableTypes = availableTypes,
            onDismiss = { showFilterSheet = false },
            onApply = { start, end, apps, types ->
                val normalized = if (start != null && end != null && start > end) end to start else start to end
                viewModel.updateTimeRange(normalized.first, normalized.second)
                viewModel.updateApps(apps)
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
