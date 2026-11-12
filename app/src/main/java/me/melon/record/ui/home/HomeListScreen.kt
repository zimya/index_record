package me.melon.record.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*

import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.RecordItem
import me.melon.record.ui.components.RefreshResultHint
import me.melon.record.viewmodel.HomeViewModel
import androidx.compose.runtime.saveable.rememberSaveable
import me.melon.record.logic.DataProcessor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshListScreen(
    records: List<UniversalRecord>,
    recordsLoaded: Boolean,
    searchQuery: String,
    filter: HomeViewModel.HomeFilter,
    availableApps: List<String>,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    onRefresh: suspend () -> Int,
    onOpenFilter: () -> Unit,
    listState: LazyListState,
    rawPendingCount: Long,
    syncProgress: DataProcessor.SyncProgress,
    onItemClick: (UniversalRecord) -> Unit
) {
    val pullState = rememberPullToRefreshState()
    val listItems = remember(records) { groupRecordsForHome(records) }
    val scope = rememberCoroutineScope()
    var hintVisible by remember { mutableStateOf(false) }
    var hintMessage by remember { mutableStateOf("") }
    var hintShowArrow by remember { mutableStateOf(false) }
    var hintNoNewToken by remember { mutableLongStateOf(0L) }
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val isSearching = isSearchActive || searchQuery.isNotBlank()

    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(searchQuery, TextRange(searchQuery.length)))
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery != textFieldValue.text) {
            textFieldValue = TextFieldValue(
                text = searchQuery,
                selection = TextRange(searchQuery.length)
            )
        }
    }

    BackHandler(enabled = isSearching) {
        keyboardController?.hide()
        if (searchQuery.isNotBlank()) {
            onSearchQueryChange("")
        }
        onSearchActiveChange(false)
    }

    var syncSessionActive by remember { mutableStateOf(false) }
    val showSync = (syncSessionActive || rawPendingCount > 0L || syncProgress.isRunning) && !isSearching
    var sessionLoadedCount by remember { mutableStateOf(0) }
    var acknowledgedLoadedCount by remember { mutableStateOf(0) }
    var previousRecordIds by remember { mutableStateOf<Set<Long>?>(null) }

    LaunchedEffect(records, recordsLoaded) {
        if (!recordsLoaded) return@LaunchedEffect
        val ids = records.mapTo(HashSet()) { it.id }
        val previous = previousRecordIds
        previousRecordIds = ids
        if (previous == null) return@LaunchedEffect
        // 只有在真实的同步或刷新会话中、且非搜索状态下，列表新增项才计入本轮加载数
        if (syncSessionActive && !isSearching) {
            val addedCount = ids.count { it !in previous }
            if (addedCount > 0) {
                sessionLoadedCount += addedCount
            }
        }
    }

    // 当用户进入搜索时，立即清理提示和历史统计
    LaunchedEffect(isSearching) {
        if (isSearching) {
            hintVisible = false
            sessionLoadedCount = 0
            acknowledgedLoadedCount = 0
        }
    }

    // 宿主逐条转存时，队列可能在批次间归零；持续空闲后才结束本轮展示。
    LaunchedEffect(rawPendingCount, syncProgress.isRunning, pullState.isRefreshing) {
        if (rawPendingCount > 0L || syncProgress.isRunning || pullState.isRefreshing) {
            if (!syncSessionActive) {
                sessionLoadedCount = 0
                acknowledgedLoadedCount = 0
            }
            syncSessionActive = true
        } else if (syncSessionActive) {
            delay(2500)
            syncSessionActive = false
        }
    }

    LaunchedEffect(showSync, sessionLoadedCount, isAtTop, isSearching) {
        if (isSearching || showSync) {
            hintVisible = false
            return@LaunchedEffect
        }
        if (sessionLoadedCount == 0 || sessionLoadedCount <= acknowledgedLoadedCount) {
            hintVisible = false
            return@LaunchedEffect
        }
        if (isAtTop && hintShowArrow) {
            acknowledgedLoadedCount = sessionLoadedCount
            hintVisible = false
            hintShowArrow = false
            return@LaunchedEffect
        }
        hintMessage = "已加载 ${sessionLoadedCount} 条内容"
        hintShowArrow = !isAtTop
        hintVisible = true
        if (isAtTop) {
            acknowledgedLoadedCount = sessionLoadedCount
            hintNoNewToken = System.currentTimeMillis()
        } else {
            hintNoNewToken = 0L
        }
    }

    LaunchedEffect(hintNoNewToken, showSync) {
        if (hintNoNewToken == 0L || showSync) return@LaunchedEffect
        delay(1800)
        hintVisible = false
    }

    LaunchedEffect(pullState.isRefreshing) {
        if (!pullState.isRefreshing) return@LaunchedEffect
        try {
            val loadedCount = onRefresh()
            // 新增数量统一由列表变化累计，刷新返回值不覆盖批次累计值。
            if (loadedCount == 0 && sessionLoadedCount == 0 && !showSync) {
                hintMessage = "暂未收到新内容"
                hintShowArrow = false
                hintVisible = true
                hintNoNewToken = System.currentTimeMillis()
            } else if (loadedCount > 0 && sessionLoadedCount == 0) {
                sessionLoadedCount = loadedCount
            }
        } finally {
            pullState.endRefresh()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            HomeTopBar(
                isSearchActive = isSearchActive,
                textFieldValue = textFieldValue,
                onTextFieldValueChange = { textFieldValue = it },
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                onSearchActiveChange = onSearchActiveChange,
                filter = filter,
                onOpenFilter = onOpenFilter
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = showSync,
                    enter = expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = tween(300)
                    ) + fadeIn(animationSpec = tween(300)),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = tween(250)
                    ) + fadeOut(animationSpec = tween(250))
                ) {
                    HomeSyncProgress(
                        syncProgress = syncProgress,
                        rawPendingCount = rawPendingCount,
                        sessionLoadedCount = sessionLoadedCount
                    )
                }

                if (records.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (searchQuery.isNotEmpty()) "没有找到匹配的记录" else if (!recordsLoaded) "" else "暂无记录，去刷刷社交网络吧！",
                            color = Color.Gray
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState
                    ) {
                        items(listItems, key = { it.key }) { item ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                when (item) {
                                    is RecordListItem -> {
                                        RecordItem(item.record, searchQuery) {
                                            onItemClick(item.record)
                                        }
                                    }
                                    is AnswerGroupListItem -> {
                                        AnswerGroupItem(
                                            questionTitle = item.questionTitle,
                                            answers = item.answers,
                                            searchQuery = searchQuery,
                                            onOpenAnswer = onItemClick
                                        )
                                    }
                                    is InterestGroupListItem -> {
                                        InterestGroupItem(
                                            subjectTitle = item.subjectTitle,
                                            typeLabel = item.typeLabel,
                                            interests = item.interests,
                                            searchQuery = searchQuery,
                                            onOpenInterest = onItemClick
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (listItems.size >= 2) {
                DateFastScroller(
                    items = listItems,
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(end = 0.dp, top = 10.dp, bottom = 10.dp)
                )
            }

            RefreshResultHint(
                visible = hintVisible,
                message = hintMessage,
                showArrow = hintShowArrow,
                onClick = if (hintShowArrow) {
                    {
                        acknowledgedLoadedCount = sessionLoadedCount
                        hintVisible = false
                        hintShowArrow = false
                        scope.launch {
                            if (listState.firstVisibleItemIndex > 15) {
                                listState.scrollToItem(15)
                            }
                            listState.animateScrollToItem(0)
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )

            HomePullRefreshBar(
                state = pullState,
                loading = pullState.isRefreshing || showSync,
                syncFraction = if (showSync && syncProgress.total > 0) {
                    syncProgress.processed.toFloat() / syncProgress.total.toFloat()
                } else {
                    null
                },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
