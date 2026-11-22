package com.melonhu.indexrecord.ui.stats


import com.melonhu.indexrecord.logic.BarData
import com.melonhu.indexrecord.logic.DistributionRange
import com.melonhu.indexrecord.presentation.formatCompactNumber as formatCompactNumberPresentation
import java.util.Locale

internal fun formatNumber(num: Long): String {
    return String.format(Locale.getDefault(), "%,d", num)
}

internal fun formatCompactNumber(num: Long): String {
    return formatCompactNumberPresentation(num)
}

internal fun distributionLabelIndices(
    bars: List<BarData>,
    selectedRange: DistributionRange
): Set<Int> {
    val n = bars.size
    return when (selectedRange) {
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

internal fun aggregateChartApps(bars: List<BarData>): List<Map.Entry<String, Int>> {
    val appMap = HashMap<String, Int>()
    for (b in bars) {
        for ((pkg, count) in b.appCounts) {
            appMap[pkg] = (appMap[pkg] ?: 0) + count
        }
    }
    return appMap.entries.sortedByDescending { it.value }
}
