package me.melon.record.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.melon.record.logic.BarData
import me.melon.record.logic.DistributionRange
import me.melon.record.ui.theme.DoubanGreen
import me.melon.record.ui.theme.TiebaBlue
import me.melon.record.ui.theme.XBlack
import me.melon.record.ui.theme.XiaohongshuRed
import me.melon.record.ui.theme.ZhihuBlue
import me.melon.record.viewmodel.StatsViewModel
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel()) {
    val uiState by viewModel.state.collectAsState()
    val stats = uiState.stats
    val scrollState = rememberScrollState()
    val hasAnimated = viewModel.hasAnimatedNumbers
    val onAnimationEnd = remember(viewModel) {
        { viewModel.hasAnimatedNumbers = true }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "统计",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 1. 今日阅读字数 & 统计小组件卡片
            TodayReadingCard(
                todayWords = stats.todayWordCount,
                todayRecords = stats.todayRecordCount,
                hasAnimated = hasAnimated,
                onAnimationEnd = onAnimationEnd
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 4个核心指标小组件: 累计条目、累计字数、连续天数、最高连续天数
            MetricsRow(
                totalRecords = stats.totalRecordCount,
                totalWords = stats.totalWordCount,
                currentStreak = stats.currentStreakDays,
                maxStreak = stats.maxStreakDays,
                hasAnimated = hasAnimated,
                onAnimationEnd = onAnimationEnd
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 2. 条目统计分布图 (近30天 / 全年 / 全部时间)
            val currentDistribution = when (uiState.selectedRange) {
                DistributionRange.LAST_30_DAYS -> stats.distribution30Days
                DistributionRange.FULL_YEAR -> stats.distributionFullYear
                DistributionRange.ALL_TIME -> stats.distributionAllTime
            }

            DistributionChartSection(
                selectedRange = uiState.selectedRange,
                onRangeSelected = { viewModel.selectRange(it) },
                bars = currentDistribution.bars,
                maxCount = currentDistribution.maxCount
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 3. 时段分布 (0-24点)
            HourlyDistributionSection(
                bars = stats.hourlyDistribution,
                maxCount = stats.maxHourlyCount,
                peakHourRange = stats.peakHourRange
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 4. 最近 52 周浏览热力图
            HeatmapSection(
                weeks = stats.weeks,
                columns = stats.columns,
                maxTotalPerDay = stats.maxTotalPerDay,
                topApps = stats.topApps
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// -------------------- 1. 今日阅读卡片 & 统计小组件 --------------------

@Composable
private fun animateCount(
    targetValue: Long,
    hasAnimated: Boolean,
    onAnimationEnd: () -> Unit,
    durationMillis: Int = 850
): Long {
    val animatable = remember { Animatable(if (hasAnimated) targetValue.toFloat() else 0f) }

    LaunchedEffect(targetValue, hasAnimated) {
        if (!hasAnimated) {
            if (targetValue > 0L) {
                animatable.animateTo(
                    targetValue = targetValue.toFloat(),
                    animationSpec = tween(
                        durationMillis = durationMillis,
                        easing = FastOutSlowInEasing
                    )
                )
                onAnimationEnd()
            } else {
                animatable.snapTo(0f)
            }
        } else {
            if (animatable.value != targetValue.toFloat()) {
                animatable.snapTo(targetValue.toFloat())
            }
        }
    }
    return animatable.value.toLong()
}

@Composable
private fun TodayReadingCard(
    todayWords: Long,
    todayRecords: Int,
    hasAnimated: Boolean,
    onAnimationEnd: () -> Unit
) {
    val animatedWords = animateCount(
        targetValue = todayWords,
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    )
    val animatedRecords = animateCount(
        targetValue = todayRecords.toLong(),
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    ).toInt()

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = "今日阅读",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = "今日阅读",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = if (todayRecords > 0) "已打卡" else "待阅读",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (todayRecords > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "阅读字数",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = formatNumber(animatedWords),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = " 字",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "阅读条目",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$animatedRecords",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = " 条",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricsRow(
    totalRecords: Long,
    totalWords: Long,
    currentStreak: Int,
    maxStreak: Int,
    hasAnimated: Boolean,
    onAnimationEnd: () -> Unit
) {
    val animRecords = animateCount(
        targetValue = totalRecords,
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    )
    val animWords = animateCount(
        targetValue = totalWords,
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    )
    val animCurrentStreak = animateCount(
        targetValue = currentStreak.toLong(),
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    ).toInt()
    val animMaxStreak = animateCount(
        targetValue = maxStreak.toLong(),
        hasAnimated = hasAnimated,
        onAnimationEnd = onAnimationEnd
    ).toInt()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CompactMetricCard(
            modifier = Modifier.weight(1f),
            title = "累计条目",
            value = formatNumber(animRecords),
            unit = "条"
        )
        CompactMetricCard(
            modifier = Modifier.weight(1f),
            title = "累计字数",
            value = formatCompactNumber(animWords),
            unit = "字"
        )
        CompactMetricCard(
            modifier = Modifier.weight(1f),
            title = "连续天数",
            value = "$animCurrentStreak",
            unit = "天"
        )
        CompactMetricCard(
            modifier = Modifier.weight(1f),
            title = "最高连续",
            value = "$animMaxStreak",
            unit = "天"
        )
    }
}

@Composable
private fun CompactMetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    unit: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
    }
}

// -------------------- 2. 条目统计分布图 --------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DistributionChartSection(
    selectedRange: DistributionRange,
    onRangeSelected: (DistributionRange) -> Unit,
    bars: List<BarData>,
    maxCount: Int
) {
    var selectedBarIndex by remember(bars, selectedRange) {
        mutableStateOf<Int?>(bars.lastIndex.takeIf { it >= 0 })
    }

    val showLabelIndices = remember(bars, selectedRange) {
        val n = bars.size
        when (selectedRange) {
            DistributionRange.LAST_30_DAYS -> {
                // 30天均匀显示 5 个核心锚点日期: 第1天、第8天、第15天、第22天、第30天 (今天)
                setOf(0, 7, 14, 21, n - 1).filter { it in 0 until n }.toSet()
            }
            DistributionRange.FULL_YEAR -> {
                bars.indices.toSet()
            }
            DistributionRange.ALL_TIME -> {
                if (n <= 12) {
                    bars.indices.toSet()
                } else {
                    val step = maxOf(1, n / 5)
                    (0 until n step step).toMutableSet().apply { add(n - 1) }
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = "条目分布",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "条目统计分布",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "总量 ${bars.sumOf { it.totalCount }} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 范围选择 FilterChips (近30天、全年、全部时间)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DistributionRange.values().forEach { range ->
                    FilterChip(
                        selected = selectedRange == range,
                        onClick = {
                            if (selectedRange != range) {
                                onRangeSelected(range)
                            }
                        },
                        label = { Text(range.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (bars.isEmpty()) {
                Text(
                    "暂无数据",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                StackedBarChart(
                    bars = bars,
                    maxCount = maxCount,
                    selectedIndex = selectedBarIndex,
                    onSelectIndex = { selectedBarIndex = it },
                    showLabelIndices = showLabelIndices,
                    chartHeight = 175.dp
                )

                // 选中详情卡片
                AnimatedVisibility(
                    visible = selectedBarIndex != null && selectedBarIndex in bars.indices,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    selectedBarIndex?.let { idx ->
                        if (idx in bars.indices) {
                            BarDetailCard(
                                bar = bars[idx],
                                onClose = { selectedBarIndex = null }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // App 图例
                ChartAppLegend(bars = bars)
            }
        }
    }
}

// -------------------- 3. 时段分布 (0-24点) --------------------

@Composable
private fun HourlyDistributionSection(
    bars: List<BarData>,
    maxCount: Int,
    peakHourRange: String
) {
    var selectedHourIndex by remember(bars) {
        val peakIdx = bars.indices.maxByOrNull { bars[it].totalCount }
        mutableStateOf<Int?>(if (peakIdx != null && bars[peakIdx].totalCount > 0) peakIdx else null)
    }

    val showLabelIndices = remember(bars) {
        setOf(0, 3, 6, 9, 12, 15, 18, 21, 23).filter { it in bars.indices }.toSet()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "时段分布",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "时段分布 (0-24点)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            ) {
                Text(
                    text = peakHourRange,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (bars.isEmpty()) {
                Text("暂无数据", color = MaterialTheme.colorScheme.outline)
            } else {
                StackedBarChart(
                    bars = bars,
                    maxCount = maxCount,
                    selectedIndex = selectedHourIndex,
                    onSelectIndex = { selectedHourIndex = it },
                    showLabelIndices = showLabelIndices,
                    chartHeight = 175.dp
                )

                // 选中详情卡片
                AnimatedVisibility(
                    visible = selectedHourIndex != null && selectedHourIndex in bars.indices,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    selectedHourIndex?.let { idx ->
                        if (idx in bars.indices) {
                            BarDetailCard(
                                bar = bars[idx],
                                onClose = { selectedHourIndex = null }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                ChartAppLegend(bars = bars)
            }
        }
    }
}

// -------------------- 堆叠柱状图组件 (StackedBarChart) --------------------

@Composable
private fun StackedBarChart(
    bars: List<BarData>,
    maxCount: Int,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    showLabelIndices: Set<Int>,
    chartHeight: Dp = 175.dp
) {
    if (bars.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val labelTextStyle = MaterialTheme.typography.labelSmall
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val labelColor = MaterialTheme.colorScheme.outline
    val activeLabelColor = MaterialTheme.colorScheme.primary
    val selectionBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val emptyBarColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)

    val n = bars.size
    val safeMax = if (maxCount <= 0) 1 else maxCount

    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelectIndex by rememberUpdatedState(onSelectIndex)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
            .pointerInput(bars) {
                detectTapGestures { offset ->
                    val slotW = size.width / n.toFloat()
                    val tappedIdx = (offset.x / slotW).toInt().coerceIn(0, n - 1)
                    if (currentSelectedIndex == tappedIdx) {
                        currentOnSelectIndex(null)
                    } else {
                        currentOnSelectIndex(tappedIdx)
                    }
                }
            }
            .pointerInput(bars) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val slotW = size.width / n.toFloat()
                        val tappedIdx = (offset.x / slotW).toInt().coerceIn(0, n - 1)
                        currentOnSelectIndex(tappedIdx)
                    },
                    onHorizontalDrag = { change, _ ->
                        val slotW = size.width / n.toFloat()
                        val tappedIdx = (change.position.x / slotW).toInt().coerceIn(0, n - 1)
                        currentOnSelectIndex(tappedIdx)
                    }
                )
            }
    ) {
        val canvasW = size.width
        val canvasH = size.height
        // 底部预留 25dp 绘制对齐的 X 轴文本
        val labelAreaHeight = 25.dp.toPx()
        val barsH = canvasH - labelAreaHeight

        val slotW = canvasW / n.toFloat()
        // 柱子宽度占槽位的 65%，边界保护在 3dp 到 22dp 之间
        val barW = (slotW * 0.65f).coerceIn(3.dp.toPx(), 22.dp.toPx())

        // 1. 绘制水平刻度参考线
        val midY = barsH / 2f
        drawLine(
            color = outlineColor,
            start = Offset(0f, 0f),
            end = Offset(canvasW, 0f),
            strokeWidth = 1f
        )
        drawLine(
            color = outlineColor.copy(alpha = 0.25f),
            start = Offset(0f, midY),
            end = Offset(canvasW, midY),
            strokeWidth = 1f
        )
        drawLine(
            color = outlineColor,
            start = Offset(0f, barsH - 1f),
            end = Offset(canvasW, barsH - 1f),
            strokeWidth = 1.5f
        )

        // 2. 绘制每个柱体和选中高亮
        for (i in 0 until n) {
            val bar = bars[i]
            val centerX = (i + 0.5f) * slotW
            val x = centerX - barW / 2f
            val isSelected = selectedIndex == i

            // 绘制当前选中柱子的槽位浅色高亮
            if (isSelected) {
                drawRoundRect(
                    color = selectionBgColor,
                    topLeft = Offset(i * slotW, 0f),
                    size = Size(slotW, barsH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }

            if (bar.totalCount == 0) {
                // 0 条目时画一个低矮小药丸标记
                drawRoundRect(
                    color = emptyBarColor,
                    topLeft = Offset(x, barsH - 3.dp.toPx()),
                    size = Size(barW, 3.dp.toPx()),
                    cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                )
            } else {
                val totalH = (bar.totalCount.toFloat() / safeMax.toFloat()) * (barsH - 8.dp.toPx())
                    .coerceAtLeast(4.dp.toPx())

                val barPath = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = x,
                            top = barsH - totalH,
                            right = x + barW,
                            bottom = barsH,
                            topLeftCornerRadius = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx()),
                            topRightCornerRadius = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx()),
                            bottomLeftCornerRadius = CornerRadius.Zero,
                            bottomRightCornerRadius = CornerRadius.Zero
                        )
                    )
                }

                clipPath(barPath) {
                    var currentBottom = barsH
                    val sortedApps = bar.appCounts.entries.sortedByDescending { it.value }
                    for ((pkg, count) in sortedApps) {
                        val segmentH = (count.toFloat() / bar.totalCount.toFloat()) * totalH
                        val topY = currentBottom - segmentH
                        drawRect(
                            color = colorFromPackage(pkg),
                            topLeft = Offset(x, topY),
                            size = Size(barW, segmentH)
                        )
                        currentBottom = topY
                    }
                }
            }
        }

        // 3. 绘制完美对齐的 X 轴文本
        for (i in showLabelIndices) {
            if (i !in 0 until n) continue
            val bar = bars[i]
            val isSelected = selectedIndex == i
            val textLayoutResult = textMeasurer.measure(
                text = bar.label,
                style = labelTextStyle.copy(
                    color = if (isSelected) activeLabelColor else labelColor,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            )
            val textW = textLayoutResult.size.width
            val centerX = (i + 0.5f) * slotW
            // 文本以 centerX 为中心对齐，并在画布边界进行安全夹紧，防止两边截断
            val textX = (centerX - textW / 2f).coerceIn(0f, (canvasW - textW).coerceAtLeast(0f))
            val textY = barsH + 6.dp.toPx()

            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(textX, textY)
            )
        }
    }
}

// -------------------- 柱状图选中详情卡片 --------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarDetailCard(
    bar: BarData,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${bar.fullLabel} · 共 ${bar.totalCount} 条",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "关闭",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onClose() }
                )
            }

            if (bar.totalCount == 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "此时段无阅读记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val sorted = bar.appCounts.entries.sortedByDescending { it.value }
                    for ((pkg, count) in sorted) {
                        val pct = if (bar.totalCount > 0) (count * 100 / bar.totalCount) else 0
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Canvas(modifier = Modifier.size(8.dp)) {
                                drawCircle(color = colorFromPackage(pkg))
                            }
                            Text(
                                text = "${appLabel(pkg)}: $count 条 ($pct%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------- App 图例组件 --------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartAppLegend(bars: List<BarData>) {
    val presentApps = remember(bars) {
        val appMap = HashMap<String, Int>()
        for (b in bars) {
            for ((pkg, count) in b.appCounts) {
                appMap[pkg] = (appMap[pkg] ?: 0) + count
            }
        }
        appMap.entries.sortedByDescending { it.value }
    }

    if (presentApps.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((pkg, total) in presentApps) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = colorFromPackage(pkg))
                    }
                    Text(
                        text = "${appLabel(pkg)} $total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

// -------------------- 4. 热力图组件 --------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeatmapSection(
    weeks: Int,
    columns: List<List<me.melon.record.logic.DayCell>>,
    maxTotalPerDay: Int,
    topApps: List<Pair<String, Int>>
) {
    val hScroll = rememberScrollState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = "热力图",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "最近 $weeks 周阅读热力图",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (columns.isEmpty()) {
                Text("暂无数据", color = MaterialTheme.colorScheme.outline)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(hScroll),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (col in columns) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (cell in col) {
                                HeatCell(
                                    date = cell.date,
                                    appCounts = cell.appCounts,
                                    maxTotal = maxTotalPerDay
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (topApps.isNotEmpty()) {
                    Text(
                        "应用分布榜单",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for ((pkg, count) in topApps) {
                            val color = colorFromPackage(pkg)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Canvas(modifier = Modifier.size(10.dp)) {
                                    drawRect(color)
                                }
                                Text(
                                    "${appLabel(pkg)} $count",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatCell(
    date: LocalDate,
    appCounts: Map<String, Int>,
    maxTotal: Int
) {
    val total = appCounts.values.sum()
    val intensity = if (maxTotal <= 0) 0f else (total.toFloat() / maxTotal.toFloat()).coerceIn(0f, 1f)
    val baseAlpha = if (total == 0) 0f else (0.25f + 0.75f * intensity).coerceIn(0.25f, 1f)
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    Canvas(modifier = Modifier.size(12.dp)) {
        if (total == 0) {
            drawRect(emptyColor)
            return@Canvas
        }

        val sorted = appCounts.entries
            .sortedByDescending { it.value }
        val top = if (sorted.size <= 4) sorted else sorted.take(4)
        val topTotal = top.sumOf { it.value }.coerceAtLeast(1)

        var x = 0f
        for ((pkg, count) in top) {
            val w = size.width * (count.toFloat() / topTotal.toFloat())
            drawRect(
                color = colorFromPackage(pkg).copy(alpha = baseAlpha),
                topLeft = Offset(x, 0f),
                size = Size(w, size.height)
            )
            x += w
        }
    }
}

// -------------------- 实用工具函数与颜色映射 --------------------

fun colorFromPackage(pkg: String): Color {
    return when (pkg.lowercase()) {
        "com.zhihu.android", "zhihu" -> ZhihuBlue
        "com.douban.frodo", "douban" -> DoubanGreen
        "com.baidu.tieba", "tieba" -> TiebaBlue
        "com.xingin.xhs", "xiaohongshu" -> XiaohongshuRed
        "com.twitter.android", "twitter" -> XBlack
        else -> {
            val h = (pkg.hashCode().toLong().ushr(1) % 360L).toFloat()
            Color.hsv(h, 0.65f, 0.90f)
        }
    }
}

fun appLabel(pkg: String): String {
    return when (pkg) {
        "com.zhihu.android", "zhihu" -> "知乎"
        "com.douban.frodo", "douban" -> "豆瓣"
        "com.baidu.tieba", "tieba" -> "贴吧"
        "com.xingin.xhs", "xiaohongshu" -> "小红书"
        "com.twitter.android", "twitter" -> "Twitter(X)"
        else -> if (pkg.isBlank() || pkg == "unknown") "其他" else pkg
    }
}

private fun formatNumber(num: Long): String {
    return String.format(Locale.getDefault(), "%,d", num)
}

private fun formatCompactNumber(num: Long): String {
    return if (num < 10000) {
        String.format(Locale.getDefault(), "%,d", num)
    } else {
        String.format(Locale.getDefault(), "%.1f万", num / 10000.0)
    }
}
