package me.melon.record.logic

internal enum class DiffKind { UNCHANGED, REMOVED, ADDED }
internal data class DiffPart(val kind: DiffKind, val text: String)
internal data class RecordDiff(val parts: List<DiffPart>, val coarse: Boolean)

/** Exact reconstruction, bounded LCS memory/work. Long Chinese lines need no spaces. */
internal fun recordDiff(old: String, new: String): RecordDiff {
    if (old == new) return RecordDiff(if (old.isEmpty()) emptyList() else listOf(DiffPart(DiffKind.UNCHANGED, old)), false)
    val a = diffTokens(old)
    val b = diffTokens(new)
    var start = 0
    while (start < minOf(a.size, b.size) && a[start] == b[start]) start++
    var end = 0
    while (end < minOf(a.size, b.size) - start && a[a.lastIndex - end] == b[b.lastIndex - end]) end++
    val n = a.size - start - end
    val m = b.size - start - end
    val parts = mutableListOf<DiffPart>()
    fun append(kind: DiffKind, tokens: List<String>, from: Int, until: Int) {
        for (i in from until until) parts += DiffPart(kind, tokens[i])
    }
    append(DiffKind.UNCHANGED, a, 0, start)
    val coarse = (n + 1L) * (m + 1L) > 250_000L
    if (coarse) {
        append(DiffKind.REMOVED, a, start, a.size - end)
        append(DiffKind.ADDED, b, start, b.size - end)
    } else {
        val width = m + 1
        val lengths = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lengths[i * width + j] = if (a[start + i] == b[start + j]) {
                1 + lengths[(i + 1) * width + j + 1]
            } else maxOf(lengths[(i + 1) * width + j], lengths[i * width + j + 1])
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && a[start + i] == b[start + j] -> {
                    parts += DiffPart(DiffKind.UNCHANGED, a[start + i]); i++; j++
                }
                i < n && (j == m || lengths[(i + 1) * width + j] >= lengths[i * width + j + 1]) -> {
                    parts += DiffPart(DiffKind.REMOVED, a[start + i]); i++
                }
                else -> { parts += DiffPart(DiffKind.ADDED, b[start + j]); j++ }
            }
        }
    }
    append(DiffKind.UNCHANGED, a, a.size - end, a.size)
    return RecordDiff(parts, coarse)
}

// Keep terminators and surrogate pairs intact; every displayed chunk is bounded.
private fun diffTokens(text: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var index = 0
    var points = 0
    while (index < text.length) {
        val point = Character.codePointAt(text, index)
        index += Character.charCount(point)
        points++
        if (point == 10 || points == 160) {
            result += text.substring(start, index)
            start = index
            points = 0
        }
    }
    if (start < text.length) result += text.substring(start)
    return result
}
