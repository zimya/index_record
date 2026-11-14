package com.melonhu.indexrecord.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.logic.BarData

@Composable
internal fun StackedBarChart(
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
internal fun BarDetailCard(
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
internal fun ChartAppLegend(bars: List<BarData>) {
    val presentApps = remember(bars) {
        aggregateChartApps(bars)
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
