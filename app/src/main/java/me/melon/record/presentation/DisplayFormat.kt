package me.melon.record.presentation

import java.util.Locale

/**
 * 数字紧凑格式化：例如 12057 -> 1.2万, 399205 -> 39.9万, 1160320 -> 116.0万
 */
fun formatCount(count: Long): String {
    return when {
        count >= 100_000_000 -> String.format(Locale.getDefault(), "%.1f亿", count / 100_000_000.0)
        count >= 10_000 -> String.format(Locale.getDefault(), "%.1f万", count / 10_000.0)
        count >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", count / 1_000.0)
        count > 0 -> count.toString()
        else -> "0"
    }
}
