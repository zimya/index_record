package com.melonhu.indexrecord.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.R
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeatmapSection(
    weeks: Int,
    columns: List<List<com.melonhu.indexrecord.logic.DayCell>>,
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
                    contentDescription = stringResource(R.string.stats_heatmap_content_desc),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.stats_heatmap_title, weeks),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (columns.isEmpty()) {
                Text(stringResource(R.string.common_no_data), color = MaterialTheme.colorScheme.outline)
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
