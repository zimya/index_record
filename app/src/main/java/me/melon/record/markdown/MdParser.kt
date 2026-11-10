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
 * 将标准 Markdown 文本解析为 MdDocument AST。
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
