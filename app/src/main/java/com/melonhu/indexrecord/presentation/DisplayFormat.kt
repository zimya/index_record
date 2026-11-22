package com.melonhu.indexrecord.presentation

import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat

fun isChineseLocale(locale: Locale = Locale.getDefault()): Boolean =
    locale.language.equals("zh", ignoreCase = true)

fun isTraditionalChinese(locale: Locale = Locale.getDefault()): Boolean {
    if (!isChineseLocale(locale)) return false
    val country = locale.country.uppercase(Locale.ROOT)
    val script = locale.script.uppercase(Locale.ROOT)
    return country in setOf("TW", "HK", "MO") || script == "HANT"
}

/**
 * 数字紧凑格式化：
 * 中文：例如 12057 -> 1.2万, 399205 -> 39.9万, 1160320 -> 116.0万, 100000000 -> 1.0亿
 * 英文：例如 12057 -> 12.1k, 399205 -> 399.2k, 1160320 -> 1.2M, 1000000000 -> 1.0B
 */
fun formatCount(count: Long, locale: Locale = Locale.getDefault()): String {
    if (count < 0) return "-" + formatCountMagnitude(-count.toDouble(), locale)
    return formatCountMagnitude(count.toDouble(), locale)
}

private fun formatCountMagnitude(magnitude: Double, locale: Locale): String {
    if (isChineseLocale(locale)) {
        val (wan, yi) = if (isTraditionalChinese(locale)) "萬" to "億" else "万" to "亿"
        return when {
            magnitude >= 100_000_000 -> String.format(locale, "%.1f%s", magnitude / 100_000_000, yi)
            magnitude >= 10_000 -> String.format(locale, "%.1f%s", magnitude / 10_000, wan)
            magnitude >= 1_000 -> String.format(locale, "%.1fk", magnitude / 1_000)
            magnitude > 0 -> magnitude.toLong().toString()
            else -> "0"
        }
    } else {
        return when {
            magnitude >= 1_000_000_000 -> String.format(locale, "%.1fB", magnitude / 1_000_000_000)
            magnitude >= 1_000_000 -> String.format(locale, "%.1fM", magnitude / 1_000_000)
            magnitude >= 1_000 -> String.format(locale, "%.1fk", magnitude / 1_000)
            magnitude > 0 -> magnitude.toLong().toString()
            else -> "0"
        }
    }
}

/**
 * 统计大数字紧凑格式化（例如统计总字数）：
 * 中文：< 10000 保留千位分隔符 (例如 8,680)，>= 10000 转为万/亿 (例如 134.6万)
 * 英文：< 10000 保留千位分隔符 (例如 8,680)，>= 10000 转为 k/M/B (例如 86.9k, 1.3M)
 */
fun formatCompactNumber(num: Long, locale: Locale = Locale.getDefault()): String {
    if (num < 0) return "-" + formatCompactNumberMagnitude(-num.toDouble(), locale)
    return formatCompactNumberMagnitude(num.toDouble(), locale)
}

private fun formatCompactNumberMagnitude(magnitude: Double, locale: Locale): String {
    if (isChineseLocale(locale)) {
        val (wan, yi) = if (isTraditionalChinese(locale)) "萬" to "億" else "万" to "亿"
        return when {
            magnitude >= 100_000_000 -> String.format(locale, "%.1f%s", magnitude / 100_000_000, yi)
            magnitude >= 10_000 -> String.format(locale, "%.1f%s", magnitude / 10_000, wan)
            else -> String.format(locale, "%,d", magnitude.toLong())
        }
    } else {
        return when {
            magnitude >= 1_000_000_000 -> String.format(locale, "%.1fB", magnitude / 1_000_000_000)
            magnitude >= 1_000_000 -> String.format(locale, "%.1fM", magnitude / 1_000_000)
            magnitude >= 10_000 -> String.format(locale, "%.1fk", magnitude / 1_000)
            else -> String.format(locale, "%,d", magnitude.toLong())
        }
    }
}

fun displayAuthor(author: String): String = author.trim().ifEmpty { "作者未知" }

/** Stored times are milliseconds; absent timestamps must not turn into January 1970. */
fun displayTime(timestamp: Long, pattern: String = "yyyy-MM-dd HH:mm"): String =
    if (timestamp <= 0) "时间未知"
    else SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))

fun displayCaptureTime(timestamp: Long): String =
    if (timestamp <= 0) "阅读时间未知" else "阅读于 ${displayTime(timestamp)}"

fun displayPublishTime(timestamp: Long): String =
    if (timestamp <= 0) "发布时间未知" else "发布于 ${displayTime(timestamp)}"
