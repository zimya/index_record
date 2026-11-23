package com.melonhu.indexrecord.search

import java.util.Locale

internal object SearchTokenizer {
    fun isCjkChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF
    }

    /**
     * Produces space-separated FTS5 index tokens:
     * - CJK runs emit unigrams + adjacent bigrams (e.g. "机器学习" -> "机 器 学 习 机器 器学 学习")
     * - Latin/digit runs emit lowercased full tokens plus letter/digit sub-splits (e.g. "iPhone16" -> "iphone16 iphone 16")
     */
    fun tokenizeForIndex(text: String): String {
        if (text.isBlank()) return ""
        val tokens = ArrayList<String>(text.length)
        var i = 0
        val len = text.length
        while (i < len) {
            val ch = text[i]
            when {
                isCjkChar(ch) -> {
                    val start = i
                    while (i < len && isCjkChar(text[i])) i++
                    val run = text.substring(start, i)
                    for (idx in run.indices) {
                        tokens.add(run[idx].toString())
                    }
                    for (idx in 0 until run.length - 1) {
                        tokens.add(run.substring(idx, idx + 2))
                    }
                }
                ch.isLetterOrDigit() -> {
                    val start = i
                    while (i < len && text[i].isLetterOrDigit() && !isCjkChar(text[i])) i++
                    val word = text.substring(start, i).lowercase(Locale.ROOT)
                    tokens.add(word)
                    emitAlphaNumericSplits(word, tokens)
                }
                else -> i++
            }
        }
        return tokens.joinToString(" ")
    }

    private fun emitAlphaNumericSplits(word: String, out: MutableList<String>) {
        if (word.length < 2) return
        var start = 0
        var prevDigit = word[0].isDigit()
        val parts = ArrayList<String>(3)
        for (i in 1 until word.length) {
            val isDigit = word[i].isDigit()
            if (isDigit != prevDigit) {
                parts.add(word.substring(start, i))
                start = i
                prevDigit = isDigit
            }
        }
        if (start > 0) {
            parts.add(word.substring(start))
            for (part in parts) {
                if (part.isNotEmpty() && part != word) {
                    out.add(part)
                }
            }
        }
    }

    /**
     * Builds a safe FTS5 MATCH expression for the user query, or null if the query
     * contains no searchable characters.
     */
    fun buildFtsQuery(query: String): String? {
        val clauses = ArrayList<String>()
        val rawTerms = query.trim().split(Regex("\\s+"))
        for (term in rawTerms) {
            if (term.isEmpty()) continue
            var i = 0
            val len = term.length
            while (i < len) {
                val ch = term[i]
                when {
                    isCjkChar(ch) -> {
                        val start = i
                        while (i < len && isCjkChar(term[i])) i++
                        val run = term.substring(start, i)
                        if (run.length == 1) {
                            clauses.add("\"$run\"")
                        } else {
                            for (idx in 0 until run.length - 1) {
                                clauses.add("\"${run.substring(idx, idx + 2)}\"")
                            }
                        }
                    }
                    ch.isLetterOrDigit() -> {
                        val start = i
                        while (i < len && term[i].isLetterOrDigit() && !isCjkChar(term[i])) i++
                        val word = term.substring(start, i).lowercase(Locale.ROOT)
                        if (word.isNotEmpty()) {
                            clauses.add("\"$word\"*")
                        }
                    }
                    else -> i++
                }
            }
        }
        if (clauses.isEmpty()) return null
        return clauses.joinToString(" ")
    }

    fun buildTextColumnQuery(query: String): String? {
        val expr = buildFtsQuery(query) ?: return null
        return "{title_tokens author_tokens body_tokens} : ($expr)"
    }

    fun buildOcrColumnQuery(query: String): String? {
        val expr = buildFtsQuery(query) ?: return null
        return "ocr_tokens : ($expr)"
    }

    /** Extracts individual highlight terms from a search query (split on whitespace). */
    fun extractHighlightTerms(query: String): List<String> =
        query.trim()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
}
