package me.melon.record.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node as HtmlNode
import org.jsoup.nodes.TextNode
import java.net.URLDecoder

private const val ZHIHU_EQUATION_URL_PREFIX = "https://www.zhihu.com/equation?tex="

private fun extractQueryParam(url: String, key: String): String? {
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return null
    for (param in query.split('&')) {
        val parts = param.split('=', limit = 2)
        if (parts.size == 2 && parts[0] == key) {
            return runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull()
        }
    }
    return null
}

fun extractImageUrl(attribute: (String) -> String): String? =
    attribute("data-original-token")
        .takeIf { it.startsWith("v2-") }
        ?.let { "https://pic1.zhimg.com/$it" }
        ?: attribute("data-original").takeIf { it.isNotBlank() }
        ?: attribute("data-default-watermark-src").takeIf { it.isNotBlank() }
        ?: attribute("data-actualsrc").takeIf { it.isNotBlank() }
        ?: attribute("data-thumbnail").takeIf { it.isNotBlank() }
        ?: attribute("src").takeIf { it.isNotBlank() }

private fun extractEquationTex(imgElement: Element): String? = extractImageUrl(imgElement::attr)
    ?.takeIf { it.startsWith(ZHIHU_EQUATION_URL_PREFIX) }
    ?.let { extractQueryParam(it, "tex") }
    ?.takeIf { it.isNotBlank() }

private fun Element.extractEquationNode(): MdNode? = extractEquationTex(this)?.let { formula ->
    if (attr("eeimg") == "2" || formula.startsWith("\\begin") || formula.contains("\\\\")) {
        MdMathBlock(formula)
    } else {
        MdInlineMath(formula)
    }
}

/**
 * Parse Zhihu HTML content into clean MdDocument AST.
 */
fun htmlToMdAst(html: String): MdDocument {
    val doc = Jsoup.parseBodyFragment(html)
    val body = doc.body()
    val mdDoc = MdDocument()
    val blocks = convertHtmlChildrenToBlocks(body.childNodes(), mdDoc)
    mdDoc.appendChildren(blocks)
    return mdDoc
}

private fun convertHtmlChildrenToBlocks(nodes: List<HtmlNode>, mdDoc: MdDocument): List<MdNode> {
    val blocks = mutableListOf<MdNode>()
    var currentParagraph: MdParagraph? = null

    fun paragraph(): MdParagraph = currentParagraph ?: MdParagraph().also {
        blocks.add(it)
        currentParagraph = it
    }

    for (node in nodes) {
        when (node) {
            is TextNode -> {
                val text = node.text()
                if (text.isNotBlank()) {
                    paragraph().appendChild(MdText(text))
                }
            }
            is Element -> {
                if (node.tagName().equals("img", ignoreCase = true)) {
                    val eq = node.extractEquationNode()
                    if (eq != null) {
                        if (eq is MdMathBlock) {
                            blocks.add(eq)
                            currentParagraph = null
                        } else {
                            paragraph().appendChild(eq)
                        }
                        continue
                    }
                }

                val convertedBlocks = convertElementToBlock(node, mdDoc)
                if (convertedBlocks.isNotEmpty()) {
                    blocks.addAll(convertedBlocks)
                    currentParagraph = null
                } else {
                    val inlines = extractInlineNodes(node)
                    for (inline in inlines) {
                        if (inline is MdFootnoteDefinition) {
                            mdDoc.footnoteDefinitions[inline.label] = inline
                        } else if (inline is MdMathBlock) {
                            blocks.add(inline)
                            currentParagraph = null
                        } else if (inline is MdImage) {
                            blocks.add(inline)
                            currentParagraph = null
                        } else {
                            paragraph().appendChild(inline)
                        }
                    }
                }
            }
        }
    }
    return blocks
}

private fun convertElementToBlock(element: Element, mdDoc: MdDocument): List<MdNode> {
    val tag = element.tagName().lowercase()
    return when {
        tag in listOf("h1", "h2", "h3", "h4", "h5", "h6") -> {
            val level = tag[1].digitToInt()
            val heading = MdHeading(level)
            heading.appendChildren(extractInlineChildren(element))
            listOf(heading)
        }
        tag == "p" -> {
            val inlines = extractInlineChildren(element)
            if (inlines.isEmpty()) return emptyList()
            for (inline in inlines) {
                if (inline is MdFootnoteDefinition) {
                    mdDoc.footnoteDefinitions[inline.label] = inline
                }
            }
            val contentInlines = inlines.filter { it !is MdFootnoteDefinition }
            if (contentInlines.size == 1 && contentInlines.first() is MdMathBlock) {
                listOf(contentInlines.first())
            } else {
                splitInlinesByImages(contentInlines)
            }
        }
        tag == "pre" -> {
            val codeElement = element.selectFirst("> code") ?: element
            val codeText = codeElement.wholeText()
            val language = codeElement.classNames()
                .firstOrNull { it.startsWith("language-") }
                ?.removePrefix("language-")
                ?: ""
            listOf(MdFencedCodeBlock(language, codeText))
        }
        tag == "blockquote" -> {
            val childBlocks = convertHtmlChildrenToBlocks(element.childNodes(), mdDoc)
            listOf(MdBlockQuote().apply { appendChildren(childBlocks) })
        }
        tag == "ul" || tag == "ol" -> {
            val isOrdered = tag == "ol"
            val listBlock = MdList(isOrdered)
            element.children().filter { it.tagName().equals("li", ignoreCase = true) }.forEach { li ->
                val item = MdListItem()
                val itemBlocks = convertHtmlChildrenToBlocks(li.childNodes(), mdDoc)
                if (itemBlocks.isEmpty()) {
                    val inlines = extractInlineChildren(li)
                    if (inlines.isNotEmpty()) {
                        item.appendChild(MdParagraph().apply { appendChildren(inlines) })
                    }
                } else {
                    item.appendChildren(itemBlocks)
                }
                listBlock.appendChild(item)
            }
            listOf(listBlock)
        }
        tag == "table" -> {
            val table = MdTable()
            element.select("tr").forEachIndexed { idx, tr ->
                val isHeader = idx == 0 || tr.parents().any { it.tagName().equals("thead", ignoreCase = true) }
                val row = MdTableRow(isHeader)
                tr.children().filter { it.tagName().lowercase() in listOf("th", "td") }.forEach { cell ->
                    val align = when (cell.attr("align").lowercase()) {
                        "left" -> MdTableAlignment.LEFT
                        "center" -> MdTableAlignment.CENTER
                        "right" -> MdTableAlignment.RIGHT
                        else -> MdTableAlignment.NONE
                    }
                    val tableCell = MdTableCell(isHeader = cell.tagName().equals("th", ignoreCase = true), alignment = align)
                    tableCell.appendChildren(extractInlineChildren(cell))
                    row.appendChild(tableCell)
                }
                table.appendChild(row)
            }
            listOf(table)
        }
        tag == "hr" -> listOf(MdThematicBreak())
        tag == "figure" -> {
            val img = element.selectFirst("img")
            if (img != null) {
                val url = extractImageUrl(img::attr)
                val caption = element.selectFirst("figcaption")?.text() ?: img.attr("data-caption").ifBlank { img.attr("alt") }
                if (!url.isNullOrBlank()) {
                    val w = img.attr("data-rawwidth").toIntOrNull()
                    val h = img.attr("data-rawheight").toIntOrNull()
                    return listOf(MdImage(url, caption, w, h))
                }
            }
            emptyList()
        }
        tag == "div" -> convertHtmlChildrenToBlocks(element.childNodes(), mdDoc)
        else -> emptyList()
    }
}

private fun extractInlineChildren(element: Element): List<MdNode> {
    val results = mutableListOf<MdNode>()
    for (node in element.childNodes()) {
        when (node) {
            is TextNode -> {
                val t = node.text()
                if (t.isNotEmpty()) results.add(MdText(t))
            }
            is Element -> results.addAll(extractInlineNodes(node))
        }
    }
    return results
}

private fun extractInlineNodes(element: Element): List<MdNode> {
    val tag = element.tagName().lowercase()
    return when (tag) {
        "br" -> listOf(MdHardLineBreak())
        "img" -> {
            val eq = element.extractEquationNode()
            if (eq != null) return listOf(eq)
            val url = extractImageUrl(element::attr) ?: return emptyList()
            val alt = element.attr("data-caption").ifBlank { element.attr("alt") }
            val w = element.attr("data-rawwidth").toIntOrNull()
            val h = element.attr("data-rawheight").toIntOrNull()
            listOf(MdImage(url, alt, w, h))
        }
        "b", "strong" -> listOf(MdStrongEmphasis().apply { appendChildren(extractInlineChildren(element)) })
        "i", "em" -> listOf(MdEmphasis().apply { appendChildren(extractInlineChildren(element)) })
        "del", "s" -> listOf(MdStrikethrough().apply { appendChildren(extractInlineChildren(element)) })
        "code" -> listOf(MdInlineCode(element.wholeText()))
        "a" -> {
            var href = element.attr("href")
            if (href.contains("link.zhihu.com")) {
                href = extractQueryParam(href, "target") ?: href
            }
            listOf(MdLink(href).apply { appendChildren(extractInlineChildren(element)) })
        }
        "sup" -> {
            if (element.attr("data-draft-type") == "reference") {
                val numero = element.attr("data-numero").ifBlank { "1" }
                val index = numero.toIntOrNull() ?: 1
                val text = element.attr("data-text")
                val url = element.attr("data-url")
                listOf(
                    MdFootnoteReference(numero, index),
                    MdFootnoteDefinition(numero, index, text, url)
                )
            } else {
                extractInlineChildren(element)
            }
        }
        else -> extractInlineChildren(element)
    }
}

/**
 * Convert AST to clean Markdown text.
 */
fun MdNode.toMarkdown(): String {
    val sb = StringBuilder()
    serializeNodeToMarkdown(this, sb)
    return sb.toString().trim()
}

private fun serializeNodeToMarkdown(node: MdNode, sb: StringBuilder) {
    when (node) {
        is MdDocument -> {
            for ((index, child) in node.children.withIndex()) {
                if (index > 0) sb.append("\n\n")
                serializeNodeToMarkdown(child, sb)
            }
            if (node.footnoteDefinitions.isNotEmpty()) {
                sb.append("\n\n")
                for (def in node.footnoteDefinitions.values.sortedBy { it.index }) {
                    serializeNodeToMarkdown(def, sb)
                    sb.append("\n")
                }
            }
        }
        is MdHeading -> {
            sb.append("#".repeat(node.level.coerceIn(1, 6))).append(" ")
            serializeChildren(node, sb)
        }
        is MdParagraph -> serializeChildren(node, sb)
        is MdText -> sb.append(node.text)
        is MdEmphasis -> {
            sb.append("*")
            serializeChildren(node, sb)
            sb.append("*")
        }
        is MdStrongEmphasis -> {
            sb.append("**")
            serializeChildren(node, sb)
            sb.append("**")
        }
        is MdStrikethrough -> {
            sb.append("~~")
            serializeChildren(node, sb)
            sb.append("~~")
        }
        is MdInlineCode -> sb.append("`").append(node.literal).append("`")
        is MdFencedCodeBlock -> {
            sb.append("```").append(node.language).append("\n")
            sb.append(node.code.trimEnd()).append("\n")
            sb.append("```")
        }
        is MdInlineMath -> sb.append("$").append(node.formula).append("$")
        is MdMathBlock -> sb.append("$$\n").append(node.formula.trim()).append("\n$$")
        is MdImage -> sb.append("![").append(node.altText).append("](").append(node.url).append(")")
        is MdLink -> {
            sb.append("[")
            serializeChildren(node, sb)
            sb.append("](").append(node.destination).append(")")
        }
        is MdBlockQuote -> {
            val content = StringBuilder()
            for ((index, child) in node.children.withIndex()) {
                if (index > 0) content.append("\n\n")
                serializeNodeToMarkdown(child, content)
            }
            val quoted = content.lines().joinToString("\n") { "> $it" }
            sb.append(quoted)
        }
        is MdList -> {
            node.children.forEachIndexed { index, child ->
                if (index > 0) sb.append("\n")
                val prefix = if (node.isOrdered) "${node.startNumber + index}. " else "- "
                sb.append(prefix)
                serializeNodeToMarkdown(child, sb)
            }
        }
        is MdListItem -> serializeChildren(node, sb)
        is MdTable -> {
            val rows = node.children.filterIsInstance<MdTableRow>()
            if (rows.isNotEmpty()) {
                val header = rows.first()
                val headerCells = header.children.filterIsInstance<MdTableCell>()
                sb.append("| ").append(headerCells.joinToString(" | ") { it.toMarkdown() }).append(" |\n")
                sb.append("| ").append(headerCells.joinToString(" | ") { cell ->
                    when (cell.alignment) {
                        MdTableAlignment.LEFT -> ":---"
                        MdTableAlignment.CENTER -> ":---:"
                        MdTableAlignment.RIGHT -> "---:"
                        MdTableAlignment.NONE -> "---"
                    }
                }).append(" |\n")
                for (row in rows.drop(1)) {
                    val cells = row.children.filterIsInstance<MdTableCell>()
                    sb.append("| ").append(cells.joinToString(" | ") { it.toMarkdown() }).append(" |\n")
                }
            }
        }
        is MdTableCell -> serializeChildren(node, sb)
        is MdThematicBreak -> sb.append("---")
        is MdHardLineBreak -> sb.append("\n")
        is MdFootnoteReference -> sb.append("[^").append(node.label).append("]")
        is MdFootnoteDefinition -> {
            sb.append("[^").append(node.label).append("]: ").append(node.content)
            if (node.url.isNotBlank() && !node.content.contains(node.url)) {
                sb.append(" [").append(node.url).append("](").append(node.url).append(")")
            }
        }
        else -> serializeChildren(node, sb)
    }
}

private fun serializeChildren(node: MdNode, sb: StringBuilder) {
    for (child in node.children) {
        serializeNodeToMarkdown(child, sb)
    }
}

private fun splitInlinesByImages(inlines: List<MdNode>): List<MdNode> {
    val result = mutableListOf<MdNode>()
    var currentParagraph = MdParagraph()

    for (inline in inlines) {
        if (inline is MdImage) {
            if (currentParagraph.children.isNotEmpty()) {
                result.add(currentParagraph)
                currentParagraph = MdParagraph()
            }
            result.add(inline)
        } else {
            currentParagraph.appendChild(inline)
        }
    }
    if (currentParagraph.children.isNotEmpty()) {
        result.add(currentParagraph)
    }
    return result
}

/**
 * Convenience function: Directly convert Zhihu HTML to clean Markdown.
 */
fun zhihuHtmlToMarkdown(html: String): String = htmlToMdAst(html).toMarkdown()

/**
 * CommonMark parser with GFM and Footnote extensions.
 */
private val commonMarkParser = Parser.builder()
    .extensions(listOf(
        TablesExtension.create(),
        AutolinkExtension.create(),
        StrikethroughExtension.create(),
        FootnotesExtension.create()
    ))
    .build()

/**
 * Parse Markdown string into clean MdDocument AST purely using CommonMark.
 */
fun markdownToMdAst(markdown: String): MdDocument {
    val doc = MdDocument()

    // Ensure footnote definitions in single-line flattened documents start on a new block line
    val normalizedMarkdown = if (markdown.contains("[^")) {
        markdown.replace(Regex("""(?<!\n)\s*(\[\^[^\]]+\]:)"""), "\n\n$1")
    } else {
        markdown
    }

    val root = commonMarkParser.parse(normalizedMarkdown)
    var child = root.firstChild
    while (child != null) {
        val blocks = convertCommonMarkBlock(child, doc)
        doc.appendChildren(blocks)
        child = child.next
    }
    return doc
}

private fun convertCommonMarkBlock(node: org.commonmark.node.Node, doc: MdDocument): List<MdNode> {
    return when (node) {
        is FootnoteDefinition -> {
            val inlines = mutableListOf<MdNode>()
            var c = node.firstChild
            while (c != null) {
                if (c is Paragraph) {
                    inlines.addAll(convertCommonMarkInlines(c.firstChild))
                } else {
                    inlines.addAll(convertCommonMarkBlock(c, doc))
                }
                c = c.next
            }
            var url = inlines.filterIsInstance<MdLink>().firstOrNull()?.destination.orEmpty()
            val textParts = mutableListOf<String>()
            for (inline in inlines) {
                when (inline) {
                    is MdText -> textParts.add(inline.text)
                    is MdLink -> {
                        if (url.isEmpty()) url = inline.destination
                        val linkText = inline.children.filterIsInstance<MdText>().joinToString("") { it.text }
                        if (linkText.isNotBlank() && linkText != inline.destination) {
                            textParts.add(linkText)
                        }
                    }
                    else -> {}
                }
            }
            val content = textParts.joinToString("").trim()
            val index = node.label.toIntOrNull() ?: (doc.footnoteDefinitions.size + 1)
            doc.footnoteDefinitions[node.label] = MdFootnoteDefinition(
                label = node.label,
                index = index,
                content = content.ifBlank { url },
                url = url
            )
            emptyList()
        }
        is Heading -> listOf(MdHeading(node.level).apply { appendChildren(convertCommonMarkInlines(node.firstChild)) })
        is Paragraph -> {
            val textOnly = node.extractRawText().trim()
            if (textOnly.startsWith("$$") && textOnly.endsWith("$$") && textOnly.length >= 4) {
                listOf(MdMathBlock(textOnly.removePrefix("$$").removeSuffix("$$").trim()))
            } else {
                val inlines = convertCommonMarkInlines(node.firstChild)
                splitInlinesByImages(inlines)
            }
        }
        is FencedCodeBlock -> listOf(MdFencedCodeBlock(node.info.orEmpty(), node.literal.orEmpty()))
        is IndentedCodeBlock -> listOf(MdFencedCodeBlock("", node.literal.orEmpty()))
        is BlockQuote -> listOf(MdBlockQuote().apply {
            var c = node.firstChild
            while (c != null) {
                appendChildren(convertCommonMarkBlock(c, doc))
                c = c.next
            }
        })
        is BulletList -> listOf(MdList(isOrdered = false).apply {
            var c = node.firstChild
            while (c != null) {
                if (c is ListItem) {
                    val item = MdListItem()
                    var ic = c.firstChild
                    while (ic != null) {
                        item.appendChildren(convertCommonMarkBlock(ic, doc))
                        ic = ic.next
                    }
                    appendChild(item)
                }
                c = c.next
            }
        })
        is OrderedList -> listOf(MdList(isOrdered = true, startNumber = node.startNumber).apply {
            var c = node.firstChild
            while (c != null) {
                if (c is ListItem) {
                    val item = MdListItem()
                    var ic = c.firstChild
                    while (ic != null) {
                        item.appendChildren(convertCommonMarkBlock(ic, doc))
                        ic = ic.next
                    }
                    appendChild(item)
                }
                c = c.next
            }
        })
        is TableBlock -> {
            val table = MdTable()
            var c = node.firstChild
            while (c != null) {
                when (c) {
                    is TableHead -> {
                        var rc = c.firstChild
                        while (rc != null) {
                            if (rc is TableRow) table.appendChild(convertCommonMarkTableRow(rc, isHeader = true))
                            rc = rc.next
                        }
                    }
                    is TableBody -> {
                        var rc = c.firstChild
                        while (rc != null) {
                            if (rc is TableRow) table.appendChild(convertCommonMarkTableRow(rc, isHeader = false))
                            rc = rc.next
                        }
                    }
                }
                c = c.next
            }
            listOf(table)
        }
        is ThematicBreak -> listOf(MdThematicBreak())
        else -> emptyList()
    }
}

private fun convertCommonMarkTableRow(row: TableRow, isHeader: Boolean): MdTableRow {
    val mdRow = MdTableRow(isHeader)
    var c = row.firstChild
    while (c != null) {
        if (c is TableCell) {
            val align = when (c.alignment) {
                TableCell.Alignment.LEFT -> MdTableAlignment.LEFT
                TableCell.Alignment.CENTER -> MdTableAlignment.CENTER
                TableCell.Alignment.RIGHT -> MdTableAlignment.RIGHT
                else -> MdTableAlignment.NONE
            }
            val cell = MdTableCell(isHeader = c.isHeader, alignment = align)
            cell.appendChildren(convertCommonMarkInlines(c.firstChild))
            mdRow.appendChild(cell)
        }
        c = c.next
    }
    return mdRow
}

private fun convertCommonMarkInlines(first: org.commonmark.node.Node?): List<MdNode> {
    val list = mutableListOf<MdNode>()
    var cur = first
    while (cur != null) {
        when (cur) {
            is Text -> list.addAll(parseInlineTokensInText(cur.literal))
            is Emphasis -> list.add(MdEmphasis().apply { appendChildren(convertCommonMarkInlines(cur.firstChild)) })
            is StrongEmphasis -> list.add(MdStrongEmphasis().apply { appendChildren(convertCommonMarkInlines(cur.firstChild)) })
            is Strikethrough -> list.add(MdStrikethrough().apply { appendChildren(convertCommonMarkInlines(cur.firstChild)) })
            is Code -> list.add(MdInlineCode(cur.literal))
            is Link -> list.add(MdLink(cur.destination, cur.title.orEmpty()).apply { appendChildren(convertCommonMarkInlines(cur.firstChild)) })
            is Image -> list.add(MdImage(cur.destination, cur.title.orEmpty()))
            is FootnoteReference -> {
                val index = cur.label.toIntOrNull() ?: 0
                list.add(MdFootnoteReference(label = cur.label, index = index))
            }
            is HardLineBreak -> list.add(MdHardLineBreak())
            is SoftLineBreak -> list.add(MdText(" "))
        }
        cur = cur.next
    }
    return list
}

private val INLINE_MATH_REGEX = Regex("""\$([^$\n]+)\$""")

private fun parseInlineTokensInText(text: String): List<MdNode> {
    if (!text.contains("$")) return listOf(MdText(text))
    val results = mutableListOf<MdNode>()
    var lastIndex = 0

    for (match in INLINE_MATH_REGEX.findAll(text)) {
        if (match.range.first > lastIndex) {
            results.add(MdText(text.substring(lastIndex, match.range.first)))
        }
        val formula = match.groupValues[1]
        results.add(MdInlineMath(formula))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < text.length) {
        results.add(MdText(text.substring(lastIndex)))
    }
    return results
}

private fun org.commonmark.node.Node.extractRawText(): String {
    val sb = StringBuilder()
    var c = firstChild
    while (c != null) {
        if (c is Text) sb.append(c.literal)
        else sb.append(c.extractRawText())
        c = c.next
    }
    return sb.toString()
}
