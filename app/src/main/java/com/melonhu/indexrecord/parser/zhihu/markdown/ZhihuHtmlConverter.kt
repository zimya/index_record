package com.melonhu.indexrecord.parser.zhihu.markdown

import com.melonhu.indexrecord.markdown.MdBlockQuote
import com.melonhu.indexrecord.markdown.MdDocument
import com.melonhu.indexrecord.markdown.MdEmphasis
import com.melonhu.indexrecord.markdown.MdFencedCodeBlock
import com.melonhu.indexrecord.markdown.MdFootnoteDefinition
import com.melonhu.indexrecord.markdown.MdFootnoteReference
import com.melonhu.indexrecord.markdown.MdHardLineBreak
import com.melonhu.indexrecord.markdown.MdHeading
import com.melonhu.indexrecord.markdown.MdImage
import com.melonhu.indexrecord.markdown.MdInlineCode
import com.melonhu.indexrecord.markdown.MdInlineMath
import com.melonhu.indexrecord.markdown.MdLink
import com.melonhu.indexrecord.markdown.MdList
import com.melonhu.indexrecord.markdown.MdListItem
import com.melonhu.indexrecord.markdown.MdMathBlock
import com.melonhu.indexrecord.markdown.MdNode
import com.melonhu.indexrecord.markdown.MdParagraph
import com.melonhu.indexrecord.markdown.MdStrikethrough
import com.melonhu.indexrecord.markdown.MdStrongEmphasis
import com.melonhu.indexrecord.markdown.MdTable
import com.melonhu.indexrecord.markdown.MdTableAlignment
import com.melonhu.indexrecord.markdown.MdTableCell
import com.melonhu.indexrecord.markdown.MdTableRow
import com.melonhu.indexrecord.markdown.MdText
import com.melonhu.indexrecord.markdown.MdThematicBreak
import com.melonhu.indexrecord.markdown.splitInlinesByImages
import com.melonhu.indexrecord.markdown.toMarkdown
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node as HtmlNode
import org.jsoup.nodes.TextNode
import java.net.URLDecoder

private const val ZHIHU_EQUATION_URL_PREFIX = "https://www.zhihu.com/equation?tex="

internal fun extractQueryParam(url: String, key: String): String? {
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
 * 将知乎 HTML 正文解析为 MdDocument AST。
 */
fun htmlToMdAst(html: String): MdDocument {
    val doc = Jsoup.parseBodyFragment(html)
    val body = doc.body()
    val mdDoc = MdDocument()
    val blocks = convertHtmlChildrenToBlocks(body.childNodes(), mdDoc)
    mdDoc.appendChildren(blocks)
    return mdDoc
}

/**
 * 直接将知乎 HTML 转换为规范 Markdown 文本。
 */
fun zhihuHtmlToMarkdown(html: String): String = htmlToMdAst(html).toMarkdown()

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
