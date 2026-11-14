package com.melonhu.indexrecord.markdown

/**
 * 将 AST 转换为规范的 Markdown 字符串。
 */
fun MdNode.toMarkdown(): String {
    val sb = StringBuilder()
    serializeNodeToMarkdown(this, sb)
    return sb.toString().trim()
}

internal fun serializeNodeToMarkdown(node: MdNode, sb: StringBuilder) {
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
        is MdGif -> sb.append("[GIF](").append(node.url).append(")")
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

internal fun serializeChildren(node: MdNode, sb: StringBuilder) {
    for (child in node.children) {
        serializeNodeToMarkdown(child, sb)
    }
}

internal fun splitInlinesByImages(inlines: List<MdNode>): List<MdNode> {
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
