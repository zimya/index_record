package me.melon.record.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.melon.record.viewmodel.StatsViewModel
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("统计") },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("最近 ${state.weeks} 周浏览量", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            if (state.columns.isEmpty()) {
                Text("暂无数据", color = MaterialTheme.colorScheme.outline)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (col in state.columns) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (cell in col) {
                                HeatCell(
                                    date = cell.date,
                                    appCounts = cell.appCounts,
                                    maxTotal = state.maxTotalPerDay
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (state.topApps.isNotEmpty()) {
                    Text("App 图例", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (pair in state.topApps) {
                            val color = colorFromPackage(pair.first)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Canvas(modifier = Modifier.size(10.dp)) {
                                    drawRect(color)
                                }
                                Text("${appLabel(pair.first)} ${pair.second}")
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
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
                topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                size = androidx.compose.ui.geometry.Size(w, size.height)
            )
            x += w
        }
    }
}

private fun colorFromPackage(pkg: String): Color {
    val h = (pkg.hashCode().toLong().ushr(1) % 360L).toFloat()
    return Color.hsv(h, 0.65f, 0.90f)
}

private fun appLabel(pkg: String): String {
    return when (pkg) {
        "com.zhihu.android" -> "知乎"
        "com.douban.frodo" -> "豆瓣"
        "com.baidu.tieba" -> "贴吧"
        "com.xingin.xhs" -> "小红书"
        "com.twitter.android" -> "Twitter(X)"
        else -> pkg
    }
}
