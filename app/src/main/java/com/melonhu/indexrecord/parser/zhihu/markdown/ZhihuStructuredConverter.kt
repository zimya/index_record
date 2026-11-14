package com.melonhu.indexrecord.parser.zhihu.markdown

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 将知乎卡片模式（Card Mode）的 structured_content JSON 转换为 Markdown 字符串。
 */
fun zhihuStructuredContentToMarkdown(scElement: JsonElement?): String {
    if (scElement == null || !scElement.isJsonObject) return ""
    val scObj = scElement.asJsonObject
    val segments = scObj.getAsJsonArray("segments") ?: return ""

    val blocks = mutableListOf<String>()
    val footnotes = mutableMapOf<Int, Pair<String, String>>()

    for (segElem in segments) {
        if (!segElem.isJsonObject) continue
        val seg = segElem.asJsonObject
        val type = seg.safeString("type")

        when (type) {
            "paragraph" -> {
                val p = seg.getAsJsonObject("paragraph")
                val text = p?.safeString("text") ?: ""
                val marks = p?.getAsJsonArray("marks")
                val formatted = formatZhihuTextMarks(text, marks, footnotes)
                if (formatted.isNotBlank()) blocks.add(formatted.trim())
            }
            "heading" -> {
                val h = seg.getAsJsonObject("heading")
                val level = h?.get("level")?.asInt ?: 2
                val text = h?.safeString("text") ?: ""
                val marks = h?.getAsJsonArray("marks")
                val formatted = formatZhihuTextMarks(text, marks, footnotes)
                val prefix = "#".repeat(level.coerceIn(1, 6))
                if (formatted.isNotBlank()) blocks.add("$prefix ${formatted.trim()}")
            }
            "blockquote" -> {
                val bq = seg.getAsJsonObject("blockquote")
                val text = bq?.safeString("text") ?: ""
                val marks = bq?.getAsJsonArray("marks")
                val formatted = formatZhihuTextMarks(text, marks, footnotes)
                if (formatted.isNotBlank()) {
                    val quoted = formatted.trim().lines().joinToString("\n") { "> $it" }
                    blocks.add(quoted)
                }
            }
            "list_node" -> {
                val ln = seg.getAsJsonObject("list_node")
                val isOrdered = ln?.safeString("type") == "ordered"
                val items = ln?.getAsJsonArray("items")
                val listLines = mutableListOf<String>()
                if (items != null) {
                    for (i in 0 until items.size()) {
                        val it = items.get(i)
                        if (!it.isJsonObject) continue
                        val itemObj = it.asJsonObject
                        val text = itemObj.safeString("text")
                        val marks = itemObj.getAsJsonArray("marks")
                        val formatted = formatZhihuTextMarks(text, marks, footnotes)
                        val prefix = if (isOrdered) "${i + 1}. " else "- "
                        listLines.add("$prefix${formatted.trim()}")
                    }
                }
                if (listLines.isNotEmpty()) blocks.add(listLines.joinToString("\n"))
            }
            "code_block" -> {
                val cb = seg.getAsJsonObject("code_block")
                val lang = cb?.safeString("language") ?: ""
                val code = cb?.safeString("content") ?: ""
                blocks.add("```$lang\n${code.trimEnd()}\n```")
            }
            "image" -> {
                val img = seg.getAsJsonObject("image")
                val desc = img?.safeString("description") ?: ""
                var url = ""
                val urls = img?.getAsJsonArray("original_urls")
                if (urls != null && urls.size() > 0 && !urls.get(0).isJsonNull) {
                    url = urls.get(0).asString
                }
                if (url.isBlank()) {
                    val origToken = img?.safeString("original_token") ?: ""
                    val token = img?.safeString("token") ?: ""
                    val t = origToken.ifBlank { token }
                    if (t.isNotBlank()) url = "https://pic1.zhimg.com/$t.jpg"
                }
                if (url.isNotBlank()) blocks.add("![$desc]($url)")
            }
            "hr" -> {
                blocks.add("---")
            }
            "table" -> {
                val tb = seg.getAsJsonObject("table")
                val cols = tb?.get("column_count")?.asInt ?: 0
                val rows = tb?.get("row_count")?.asInt ?: 0
                val cells = tb?.getAsJsonArray("cells")
                if (cols > 0 && rows > 0 && cells != null && cells.size() >= cols * rows) {
                    val tableLines = mutableListOf<String>()
                    val headerCells = (0 until cols).map { cells.get(it).asString.replace("|", "\\|") }
                    tableLines.add("| " + headerCells.joinToString(" | ") + " |")
                    tableLines.add("| " + List(cols) { "---" }.joinToString(" | ") + " |")
                    for (r in 1 until rows) {
                        val rowCells = (0 until cols).map { cells.get(r * cols + it).asString.replace("|", "\\|") }
                        tableLines.add("| " + rowCells.joinToString(" | ") + " |")
                    }
                    blocks.add(tableLines.joinToString("\n"))
                }
            }
            "reference_block" -> {
                val rb = seg.getAsJsonObject("reference_block")
                val items = rb?.getAsJsonArray("items")
                if (items != null) {
                    for (i in 0 until items.size()) {
                        val it = items.get(i)
                        if (!it.isJsonObject) continue
                        val itemObj = it.asJsonObject
                        val idx = i + 1
                        val text = itemObj.safeString("text")
                        var linkHref = ""
                        val marks = itemObj.getAsJsonArray("marks")
                        if (marks != null) {
                            for (elem in marks) {
                                if (elem.isJsonObject && elem.asJsonObject.safeString("type") == "link") {
                                    linkHref = elem.asJsonObject.getAsJsonObject("link")?.safeString("href") ?: ""
                                    if (linkHref.contains("link.zhihu.com")) {
                                        linkHref = extractQueryParam(linkHref, "target") ?: linkHref
                                    }
                                }
                            }
                        }
                        footnotes[idx] = Pair(text, linkHref)
                    }
                }
            }
            "card" -> {
                val card = seg.getAsJsonObject("card")
                if (card?.safeString("card_type") == "matrix-image-card") {
                    // extra_info is encoded JSON; a malformed image card must not lose the text.
                    val extra = runCatching {
                        com.google.gson.JsonParser.parseString(card.safeString("extra_info")).asJsonObject
                    }.getOrNull()
                    val images = extra?.getAsJsonObject("image_list")?.getAsJsonArray("images")
                    images?.forEach { element ->
                        if (element.isJsonObject) {
                            val image = element.asJsonObject
                            val url = image.safeString("url").ifBlank { image.safeString("original_url") }
                            if (url.isNotBlank()) blocks.add("![]($url)")
                        }
                    }
                }
            }
        }
    }

    if (footnotes.isNotEmpty()) {
        val fnLines = mutableListOf<String>()
        for (idx in footnotes.keys.sorted()) {
            val (title, href) = footnotes[idx]!!
            if (href.isNotBlank() && !title.contains(href)) {
                fnLines.add("[^$idx]: $title [$href]($href)")
            } else {
                fnLines.add("[^$idx]: $title")
            }
        }
        blocks.add(fnLines.joinToString("\n"))
    }

    return blocks.joinToString("\n\n").trim()
}

private data class ZhihuFormattedMark(
    val type: String,
    val startIndex: Int,
    val endIndex: Int,
    val formulaContent: String = "",
    val linkHref: String = "",
    val refIndex: Int = 0,
    val refTitle: String = "",
    val refHref: String = ""
)

private fun formatZhihuTextMarks(
    text: String,
    marks: JsonArray?,
    footnotes: MutableMap<Int, Pair<String, String>>
): String {
    if (text.isEmpty() || marks == null || marks.size() == 0) return text

    val parsedMarks = mutableListOf<ZhihuFormattedMark>()
    for (elem in marks) {
        if (!elem.isJsonObject) continue
        val m = elem.asJsonObject
        val type = m.safeString("type")
        val s = m.get("start_index")?.asInt ?: continue
        val e = m.get("end_index")?.asInt ?: continue
        val start = s.coerceIn(0, text.length)
        val end = e.coerceIn(start, text.length)
        if (start == end && type != "reference") continue

        when (type) {
            "bold", "italic", "strikethrough", "code" -> {
                parsedMarks.add(ZhihuFormattedMark(type, start, end))
            }
            "link" -> {
                val linkObj = m.getAsJsonObject("link")
                var href = linkObj?.safeString("href") ?: ""
                if (href.contains("link.zhihu.com")) {
                    href = extractQueryParam(href, "target") ?: href
                }
                parsedMarks.add(ZhihuFormattedMark(type, start, end, linkHref = href))
            }
            "formula" -> {
                val fObj = m.getAsJsonObject("formula")
                val fContent = fObj?.safeString("content") ?: ""
                parsedMarks.add(ZhihuFormattedMark(type, start, end, formulaContent = fContent))
            }
            "reference" -> {
                val refObj = m.getAsJsonObject("reference")
                val idx = refObj?.get("index")?.asInt ?: (footnotes.size + 1)
                val title = refObj?.safeString("title") ?: ""
                var href = refObj?.safeString("href") ?: ""
                if (href.contains("link.zhihu.com")) {
                    href = extractQueryParam(href, "target") ?: href
                }
                parsedMarks.add(ZhihuFormattedMark(type, start, end, refIndex = idx, refTitle = title, refHref = href))
            }
        }
    }

    if (parsedMarks.isEmpty()) return text

    parsedMarks.sortWith(compareBy({ it.startIndex }, { it.endIndex }))

    val sb = java.lang.StringBuilder()
    var curr = 0

    for (m in parsedMarks) {
        if (m.startIndex < curr) {
            continue
        }
        if (m.startIndex > curr) {
            sb.append(text.substring(curr, m.startIndex))
        }

        val slice = text.substring(m.startIndex, m.endIndex)
        when (m.type) {
            "bold" -> sb.append("**").append(slice).append("**")
            "italic" -> sb.append("*").append(slice).append("*")
            "strikethrough" -> sb.append("~~").append(slice).append("~~")
            "code" -> sb.append("`").append(slice).append("`")
            "link" -> sb.append("[").append(slice).append("](").append(m.linkHref).append(")")
            "formula" -> {
                val isBlock = text.trim() == "[公式]" || m.formulaContent.startsWith("\\begin") || m.formulaContent.contains("\\\\")
                if (isBlock) {
                    sb.append("$$\n").append(m.formulaContent.trim()).append("\n$$")
                } else {
                    sb.append("$").append(m.formulaContent).append("$")
                }
            }
            "reference" -> {
                sb.append("[^").append(m.refIndex).append("]")
                if (!footnotes.containsKey(m.refIndex)) {
                    footnotes[m.refIndex] = Pair(m.refTitle, m.refHref)
                }
            }
            else -> sb.append(slice)
        }
        curr = maxOf(curr, m.endIndex)
    }

    if (curr < text.length) {
        sb.append(text.substring(curr))
    }

    return sb.toString()
}

private fun JsonObject.safeString(key: String): String {
    return if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asString else ""
}
