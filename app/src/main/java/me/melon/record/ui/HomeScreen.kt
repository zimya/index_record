package me.melon.record.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import me.melon.record.viewmodel.HomeViewModel
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.saveable.rememberSaveable
import me.melon.record.logic.DataProcessor
import kotlin.math.max
import kotlin.math.roundToInt

private enum class NavAction { PUSH, POP }

private sealed interface HomeListItem {
    val key: String
}

private data class RecordListItem(val record: UniversalRecord) : HomeListItem {
    override val key: String = "record_${record.id}"
}

private data class AnswerGroupListItem(
    val questionKey: String,
    val questionTitle: String,
    val answers: List<UniversalRecord>,
    val firstId: Long
) : HomeListItem {
    override val key: String = "answer_group_${questionKey}_$firstId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val loadedRecords by viewModel.recordsFlow.collectAsState(initial = null)
    val records = loadedRecords.orEmpty()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val availableApps by viewModel.availableAppsFlow.collectAsState(initial = emptyList())
    val availablePlatforms by viewModel.availablePlatformsFlow.collectAsState(initial = emptyList())
    val availableTypes by viewModel.availableTypesFlow.collectAsState(initial = emptyList())
    val rawPendingCount by viewModel.rawPendingCountFlow.collectAsState(initial = 0L)
    val syncProgress by viewModel.syncProgress.collectAsState()

    val navStack = remember { mutableStateListOf<UniversalRecord>() }
    val currentRecord = navStack.lastOrNull()
    var lastNavAction by remember { mutableStateOf(NavAction.PUSH) }
    var isSearchActive by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    val homeListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

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
                recordsLoaded = loadedRecords != null,
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
                listState = homeListState,
                rawPendingCount = rawPendingCount,
                syncProgress = syncProgress,
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

    BackHandler(enabled = isSearchActive || searchQuery.isNotBlank()) {
        if (searchQuery.isNotBlank()) {
            onSearchQueryChange("")
        }
        onSearchActiveChange(false)
    }


    var syncSessionActive by remember { mutableStateOf(false) }
    var sessionLoadedCount by remember(searchQuery, filter) { mutableStateOf(0) }
    var acknowledgedLoadedCount by remember(searchQuery, filter) { mutableStateOf(0) }
    var previousRecordIds by remember(searchQuery, filter) { mutableStateOf<Set<Long>?>(null) }
    LaunchedEffect(records, recordsLoaded, searchQuery, filter) {
        if (!recordsLoaded) return@LaunchedEffect
        val ids = records.mapTo(HashSet()) { it.id }
        val previous = previousRecordIds
        previousRecordIds = ids
        if (previous == null) return@LaunchedEffect
        val addedCount = ids.count { it !in previous }
        if (addedCount > 0) {
            if (!syncSessionActive) {
                sessionLoadedCount = 0
                acknowledgedLoadedCount = 0
            }
            sessionLoadedCount += addedCount
            syncSessionActive = true
        }
    }

    // 宿主逐条转存时，队列可能在批次间归零；持续空闲后才结束本轮展示。
    LaunchedEffect(rawPendingCount, syncProgress, records, pullState.isRefreshing) {
        if (rawPendingCount > 0L || syncProgress.isRunning || pullState.isRefreshing) {
            if (!syncSessionActive) {
                sessionLoadedCount = 0
                acknowledgedLoadedCount = 0
            }
            syncSessionActive = true
        } else {
            delay(2500)
            syncSessionActive = false
        }
    }

    LaunchedEffect(syncSessionActive, sessionLoadedCount, isAtTop) {
        if (sessionLoadedCount == 0) return@LaunchedEffect
        if (sessionLoadedCount <= acknowledgedLoadedCount) {
            hintVisible = false
            return@LaunchedEffect
        }
        if (isAtTop) {
            acknowledgedLoadedCount = sessionLoadedCount
            if (hintShowArrow) {
                hintVisible = false
                hintShowArrow = false
                return@LaunchedEffect
            }
        }
        hintMessage = if (syncSessionActive) {
            "已加载 ${sessionLoadedCount} 条内容（后台同步中）"
        } else {
            "已加载 ${sessionLoadedCount} 条内容"
        }
        hintShowArrow = !isAtTop
        hintVisible = true
        hintNoNewToken = if (!syncSessionActive && isAtTop) System.currentTimeMillis() else 0L
    }

    LaunchedEffect(hintNoNewToken, syncSessionActive) {
        if (hintNoNewToken == 0L || syncSessionActive) return@LaunchedEffect
        delay(1800)
        hintVisible = false
    }

    LaunchedEffect(pullState.isRefreshing) {
        if (!pullState.isRefreshing) return@LaunchedEffect
        try {
            val loadedCount = onRefresh()
            // 新增数量统一由列表变化累计，刷新返回值不覆盖批次累计值。
            if (loadedCount == 0 && sessionLoadedCount == 0) {
                hintMessage = if (rawPendingCount > 0) "后台同步中…" else "暂未收到新内容，宿主后台同步可能延迟"
                hintShowArrow = false
                hintVisible = true
                hintNoNewToken = System.currentTimeMillis()
            }
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
            Column(modifier = Modifier.fillMaxSize()) {
                val showSync = syncSessionActive || rawPendingCount > 0L || syncProgress.isRunning
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
                            if (searchQuery.isNotEmpty()) "没有找到匹配的记录" else "暂无记录，去刷刷社交网络吧！",
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

private fun groupRecordsForHome(records: List<UniversalRecord>): List<HomeListItem> {
    val result = ArrayList<HomeListItem>(records.size)
    var i = 0
    while (i < records.size) {
        val r = records[i]
        val isZhihuAnswer = r.platform == "zhihu" && r.recordType == "answer"
        if (!isZhihuAnswer) {
            result.add(RecordListItem(r))
            i++
            continue
        }

        val questionKey = zhihuAnswerQuestionKey(r)
        if (questionKey.isBlank()) {
            result.add(RecordListItem(r))
            i++
            continue
        }

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
        } else {
            result.add(RecordListItem(r))
        }
        i = j
    }
    return result
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "知乎 · 回答",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
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
