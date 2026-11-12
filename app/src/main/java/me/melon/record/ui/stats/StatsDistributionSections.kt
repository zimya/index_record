package me.melon.record.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.melon.record.logic.BarData
import me.melon.record.logic.DistributionRange

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DistributionChartSection(
    selectedRange: DistributionRange,
    onRangeSelected: (DistributionRange) -> Unit,
    bars: List<BarData>,
    maxCount: Int
) {
    var selectedBarIndex by remember(bars, selectedRange) {
        mutableStateOf<Int?>(bars.lastIndex.takeIf { it >= 0 })
    }

    val showLabelIndices = remember(bars, selectedRange) {
        distributionLabelIndices(bars, selectedRange)
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
internal fun HourlyDistributionSection(
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
