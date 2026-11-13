package me.melon.record.presentation

import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat

/**
 * 数字紧凑格式化：例如 12057 -> 1.2万, 399205 -> 39.9万, 1160320 -> 116.0万
 */
fun formatCount(count: Long): String {
    if (count < 0) return "-" + formatCountUnsignedMagnitude(count)
    return when {
        count >= 100_000_000 -> String.format(Locale.getDefault(), "%.1f亿", count / 100_000_000.0)
        count >= 10_000 -> String.format(Locale.getDefault(), "%.1f万", count / 10_000.0)
        count >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", count / 1_000.0)
        count > 0 -> count.toString()
        else -> "0"
    }
}

private fun formatCountUnsignedMagnitude(count: Long): String {
    // Avoid abs(Long.MIN_VALUE), which overflows back to a negative number.
    val magnitude = -count.toDouble()
    return when {
        magnitude >= 100_000_000 -> String.format(Locale.getDefault(), "%.1f亿", magnitude / 100_000_000)
        magnitude >= 10_000 -> String.format(Locale.getDefault(), "%.1f万", magnitude / 10_000)
        magnitude >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", magnitude / 1_000)
        else -> magnitude.toLong().toString()
    }
}

fun displayAuthor(author: String): String = author.trim().ifEmpty { "作者未知" }

/** Stored times are milliseconds; absent timestamps must not turn into January 1970. */
fun displayTime(timestamp: Long, pattern: String = "yyyy-MM-dd HH:mm"): String =
    if (timestamp <= 0) "时间未知"
    else SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))

fun displayCaptureTime(timestamp: Long): String =
    if (timestamp <= 0) "浏览时间未知" else "浏览于 ${displayTime(timestamp)}"

fun displayPublishTime(timestamp: Long): String =
    if (timestamp <= 0) "发布时间未知" else "发布于 ${displayTime(timestamp)}"
