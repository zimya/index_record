package me.melon.record.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.FilterBottomSheet
import me.melon.record.ui.components.RecordItem
import me.melon.record.ui.components.RefreshResultHint
import me.melon.record.ui.theme.getPlatformThemeColor
import me.melon.record.ui.theme.isAppInDarkTheme
import me.melon.record.viewmodel.HomeViewModel
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.saveable.rememberSaveable
import me.melon.record.logic.DataProcessor
import kotlin.math.max
import kotlin.math.roundToInt

internal sealed interface HomeListItem {
    val key: String
}

internal data class RecordListItem(val record: UniversalRecord) : HomeListItem {
    override val key: String = "record_${record.id}"
}

internal data class AnswerGroupListItem(
    val questionKey: String,
    val questionTitle: String,
    val answers: List<UniversalRecord>,
    val firstId: Long
) : HomeListItem {
    override val key: String = "answer_group_${questionKey}_$firstId"
}

internal data class InterestGroupListItem(
    val subjectKey: String,
    val subjectTitle: String,
    val typeLabel: String,
    val interests: List<UniversalRecord>,
    val firstId: Long
) : HomeListItem {
    override val key: String = "interest_group_${subjectKey}_$firstId"
}

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
                        if (targetRecord.recordType == "comment") {
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
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        runCatching { focusRequester.requestFocus() }
                        delay(100)
                        runCatching { focusRequester.requestFocus() }
                        keyboardController?.show()
                    }

                    TopAppBar(
                        title = {
                            TextField(
                                value = textFieldValue,
                                onValueChange = { newValue ->
                                    textFieldValue = newValue
                                    if (newValue.text != searchQuery) {
                                        onSearchQueryChange(newValue.text)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                placeholder = { Text("搜索内容、作者、平台...") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    keyboardController?.hide()
                                }),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                trailingIcon = {
                                    if (textFieldValue.text.isNotEmpty()) {
                                        IconButton(onClick = {
                                            textFieldValue = TextFieldValue("", TextRange.Zero)
                                            onSearchQueryChange("")
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear")
                                        }
                                    }
                                }
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                keyboardController?.hide()
                                onSearchActiveChange(false)
                            }) {
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
                                val hasFilter = filter.isActive
                                Icon(
                                    imageVector = if (hasFilter) Icons.Filled.FilterList else Icons.Outlined.FilterList,
                                    contentDescription = "Filter",
                                    tint = if (hasFilter) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
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
            Column(modifier = Modifier.fillMaxSize()) {
                if (showSync) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            val determinate = syncProgress.total > 0
                            val progress = if (determinate) {
                                (syncProgress.processed.toFloat() / syncProgress.total.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            if (determinate) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val syncLabel = when {
                                syncProgress.isRunning ->
                                    "同步中：剩余 ${rawPendingCount} 条 · 本轮已加载 ${sessionLoadedCount} 条"
                                rawPendingCount > 0L ->
                                    "等待处理：剩余 ${rawPendingCount} 条 · 本轮已加载 ${sessionLoadedCount} 条"
                                else ->
                                    "同步中：本轮已加载 ${sessionLoadedCount} 条 · 等待后续数据…"
                            }
                            Text(
                                text = syncLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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

            if (pullState.isRefreshing || pullState.verticalOffset > 0) {
                PullToRefreshContainer(
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}

private fun homeItemTimeMillis(item: HomeListItem): Long {
    return when (item) {
        is RecordListItem -> item.record.captureTime
        is AnswerGroupListItem -> item.answers.firstOrNull()?.captureTime ?: 0L
        is InterestGroupListItem -> item.interests.firstOrNull()?.captureTime ?: 0L
    }
}

@Composable
private fun DateFastScroller(
    items: List<HomeListItem>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val sdf = remember { SimpleDateFormat("yyyy年M月d日", Locale.getDefault()) }
    val density = LocalDensity.current

    var heightPx by remember { mutableStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragYPx by remember { mutableStateOf(0f) }
    var hoverIndex by remember { mutableStateOf(0) }

    val currentIndex = listState.firstVisibleItemIndex.coerceIn(0, items.lastIndex)
    val thumbFraction = if (items.size <= 1) 0f else currentIndex.toFloat() / (items.size - 1).toFloat()
    val thumbYPx = if (isDragging) dragYPx else thumbFraction * heightPx.toFloat()

    val trackWidth = 26.dp
    val edgePadding = 0.dp          // 想更贴边就用 0.dp
    val bubbleGap = 8.dp            // 气泡离滚动条的距离

    fun scrollToY(y: Float) {
        val safeY = y.coerceIn(0f, heightPx.toFloat())
        val fraction = if (heightPx <= 1) 0f else safeY / heightPx.toFloat()
        val idx = ((items.size - 1) * fraction).roundToInt().coerceIn(0, items.lastIndex)
        hoverIndex = idx
        dragYPx = safeY
        scope.launch {
            listState.scrollToItem(idx)
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = edgePadding)
                .fillMaxHeight()
                .width(trackWidth)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(items.size, heightPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            scrollToY(it.y)
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            scrollToY(change.position.y)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .width(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(3.dp)
                    )
            )

            val thumbRadiusPx = with(density) { 6.dp.toPx() }
            val thumbMaxY = max(0f, heightPx.toFloat() - 2 * thumbRadiusPx)
            val thumbY = (thumbYPx - thumbRadiusPx).coerceIn(0f, thumbMaxY)

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, thumbY.roundToInt()) }
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }

        if (isDragging) {
            val ts = homeItemTimeMillis(items[hoverIndex])
            val label = if (ts > 0) sdf.format(Date(ts)) else ""

            if (label.isNotBlank()) {
                val y = (thumbYPx - with(density) { 18.dp.toPx() })
                    .coerceIn(0f, max(0f, heightPx.toFloat() - with(density) { 40.dp.toPx() }))
                    .roundToInt()

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(
                                x = -with(density) { (trackWidth + bubbleGap).toPx() }.roundToInt(),
                                y = y
                            )
                        }
                        .widthIn(min = 128.dp)
                        .onSizeChanged { /* 不一定需要测量宽高了 */ },
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 3.dp
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

internal fun groupRecordsForHome(records: List<UniversalRecord>): List<HomeListItem> {
    val result = ArrayList<HomeListItem>(records.size)
    var i = 0
    while (i < records.size) {
        val r = records[i]
        val isZhihuAnswer = r.platform == "zhihu" && r.recordType == "answer"
        if (isZhihuAnswer) {
            val questionKey = zhihuAnswerQuestionKey(r)
            if (questionKey.isNotBlank()) {
                val group = mutableListOf<UniversalRecord>()
                var j = i
                while (j < records.size) {
                    val rr = records[j]
                    if (rr.platform != "zhihu" || rr.recordType != "answer") break
                    if (zhihuAnswerQuestionKey(rr) != questionKey) break
                    group.add(rr)
                    j++
                }

                if (group.size >= 2) {
                    val title = group.first().title.ifBlank { "无标题" }
                    result.add(
                        AnswerGroupListItem(
                            questionKey = questionKey,
                            questionTitle = title,
                            answers = group,
                            firstId = group.first().id
                        )
                    )
                    i = j
                    continue
                }
            }
        }

        val isDoubanInterest = r.platform == "douban" && r.recordType == "interest"
        if (isDoubanInterest) {
            val subjectKey = doubanInterestSubjectKey(r)
            if (subjectKey.isNotBlank()) {
                val group = mutableListOf<UniversalRecord>()
                var j = i
                while (j < records.size) {
                    val rr = records[j]
                    if (rr.platform != "douban" || rr.recordType != "interest") break
                    if (doubanInterestSubjectKey(rr) != subjectKey) break
                    group.add(rr)
                    j++
                }

                if (group.size >= 2) {
                    val (title, typeLabel) = doubanInterestTitleAndType(group.first())
                    result.add(
                        InterestGroupListItem(
                            subjectKey = subjectKey,
                            subjectTitle = title,
                            typeLabel = typeLabel,
                            interests = group,
                            firstId = group.first().id
                        )
                    )
                    i = j
                    continue
                }
            }
        }

        result.add(RecordListItem(r))
        i++
    }
    return result
}

private fun doubanInterestSubjectKey(record: UniversalRecord): String {
    if (record.platform != "douban" || record.recordType != "interest") return ""
    if (record.parentId.isNotBlank() && record.parentId.startsWith("douban_")) {
        return record.parentId
    }
    return try {
        val obj = JsonParser.parseString(record.extraMetadata).asJsonObject
        val type = if (obj.has("subject_type") && !obj.get("subject_type").isJsonNull) obj.get("subject_type").asString else ""
        val id = if (obj.has("subject_id") && !obj.get("subject_id").isJsonNull) obj.get("subject_id").asString else ""
        if (id.isNotBlank()) {
            if (type.isNotBlank()) "douban_${type}_$id" else "douban_$id"
        } else {
            record.title.ifBlank { "" }
        }
    } catch (_: Exception) {
        record.title.ifBlank { "" }
    }
}

private fun doubanInterestTitleAndType(record: UniversalRecord): Pair<String, String> {
    var subjectTitle = ""
    var subjectType = ""
    try {
        val obj = JsonParser.parseString(record.extraMetadata).asJsonObject
        if (obj.has("subject_title") && !obj.get("subject_title").isJsonNull) {
            subjectTitle = obj.get("subject_title").asString.trim()
        }
        if (obj.has("subject_type") && !obj.get("subject_type").isJsonNull) {
            subjectType = obj.get("subject_type").asString.trim()
        }
    } catch (_: Exception) {}

    val typeLabel = when (subjectType) {
        "movie" -> "电影短评"
        "book" -> "图书短评"
        "music" -> "音乐短评"
        "game" -> "游戏短评"
        "tv" -> "剧集短评"
        "drama" -> "舞台剧短评"
        else -> {
            if (record.title.contains("：")) {
                record.title.substringBefore("：").trim()
            } else {
                "短评"
            }
        }
    }

    val displayTitle = if (subjectTitle.isNotBlank()) {
        subjectTitle
    } else if (record.title.contains("：")) {
        record.title.substringAfter("：").trim()
    } else {
        record.title.ifBlank { "无标题" }
    }

    return Pair(displayTitle, typeLabel)
}

private fun zhihuAnswerQuestionKey(record: UniversalRecord): String {
    if (record.platform != "zhihu" || record.recordType != "answer") return ""
    if (record.parentId.startsWith("zhihu_question_")) return record.parentId
    return try {
        val obj = JsonParser.parseString(record.extraMetadata).asJsonObject
        if (obj.has("question_id") && !obj.get("question_id").isJsonNull) "zhihu_question_${obj.get("question_id").asString}" else ""
    } catch (_: Exception) {
        ""
    }
}

@Composable
private fun AnswerGroupItem(
    questionTitle: String,
    answers: List<UniversalRecord>,
    searchQuery: String,
    onOpenAnswer: (UniversalRecord) -> Unit
) {
    val isDark = isAppInDarkTheme()
    val platformColor = getPlatformThemeColor("zhihu", isDark)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Surface(
                color = platformColor.container,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "知乎 · 回答",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = platformColor.text,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = highlightText(questionTitle.ifBlank { "无标题" }, searchQuery),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
            answers.forEachIndexed { index, answer ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAnswer(answer) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = highlightText(answer.authorName.ifBlank { "未知作者" }, searchQuery),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "浏览于 ${sdf.format(Date(answer.captureTime))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    val previewText = previewFromRecordContent(answer.content)
                    if (previewText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = highlightText(previewText, searchQuery),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InterestGroupItem(
    subjectTitle: String,
    typeLabel: String,
    interests: List<UniversalRecord>,
    searchQuery: String,
    onOpenInterest: (UniversalRecord) -> Unit
) {
    val isDark = isAppInDarkTheme()
    val platformColor = getPlatformThemeColor("douban", isDark)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Surface(
                color = platformColor.container,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "豆瓣 · $typeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = platformColor.text,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = highlightText(subjectTitle.ifBlank { "无标题" }, searchQuery),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
            interests.forEachIndexed { index, interest ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenInterest(interest) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = highlightText(interest.authorName.ifBlank { "豆瓣用户" }, searchQuery),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "浏览于 ${sdf.format(Date(interest.captureTime))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    val previewText = previewFromRecordContent(interest.content)
                    if (previewText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = highlightText(previewText, searchQuery),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun previewFromRecordContent(content: String): String {
    if (content.isBlank()) return ""
    val text = try {
        Jsoup.parse(content).text()
    } catch (_: Exception) {
        content
    }
    return text.replace('\n', ' ').replace('\r', ' ').trim()
}

@Composable
private fun highlightText(text: String, query: String): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(query, start, ignoreCase = true)
            if (index == -1) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            withStyle(
                style = SpanStyle(
                    background = MaterialTheme.colorScheme.primaryContainer,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(text.substring(index, index + query.length))
            }
            start = index + query.length
        }
    }
}
