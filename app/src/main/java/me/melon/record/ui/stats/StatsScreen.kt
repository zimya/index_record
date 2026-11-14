package me.melon.record.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.melon.record.logic.DistributionRange
import me.melon.record.viewmodel.StatsViewModel

@OptIn(ExperimentalMaterial3Api::class)
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

            // Text(
            //     text = if (uiState.includeRepliesAndComments) {
            //         "统计口径：主内容及已记录的回复、评论。"
            //     } else {
            //         "统计口径：仅主内容，不含回复和评论；可在设置中开启。"
            //     } + "按本地捕获时间统计条目、字数、分布和连续天数，不累加平台互动计数。",
            //     style = MaterialTheme.typography.bodySmall,
            //     color = MaterialTheme.colorScheme.onSurfaceVariant
            // )
            // Spacer(modifier = Modifier.height(12.dp))

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
