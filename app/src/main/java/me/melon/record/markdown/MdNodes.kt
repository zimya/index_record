package me.melon.record.markdown

/**
 * Clean, lightweight AST nodes for Markdown content representation.
 * No dependencies on vendored or external UI frameworks.
 */
sealed class MdNode {
    val children = mutableListOf<MdNode>()

    fun appendChild(child: MdNode) {
        children.add(child)
    }

    fun appendChildren(nodes: Collection<MdNode>) {
        children.addAll(nodes)
    }
}

class MdDocument : MdNode() {
    val footnoteDefinitions: MutableMap<String, MdFootnoteDefinition> = mutableMapOf()
}

class MdHeading(val level: Int) : MdNode()

class MdParagraph : MdNode()

class MdText(val text: String) : MdNode()

class MdEmphasis : MdNode()

class MdStrongEmphasis : MdNode()

class MdStrikethrough : MdNode()

class MdLink(val destination: String, val title: String = "") : MdNode()

class MdImage(
    val url: String,
    val altText: String = "",
    val width: Int? = null,
    val height: Int? = null,
) : MdNode()

class MdFencedCodeBlock(
    val language: String,
    val code: String,
) : MdNode()

class MdInlineCode(val literal: String) : MdNode()

class MdMathBlock(val formula: String) : MdNode()

class MdInlineMath(val formula: String) : MdNode()

class MdBlockQuote : MdNode()

class MdList(
    val isOrdered: Boolean = false,
    val startNumber: Int = 1,
) : MdNode()

class MdListItem : MdNode()

class MdTable : MdNode()

class MdTableRow(val isHeader: Boolean = false) : MdNode()

class MdTableCell(
    val isHeader: Boolean = false,
    val alignment: MdTableAlignment = MdTableAlignment.NONE,
) : MdNode()

enum class MdTableAlignment {
    NONE, LEFT, CENTER, RIGHT
}

class MdThematicBreak : MdNode()

class MdHardLineBreak : MdNode()

class MdFootnoteReference(val label: String, val index: Int = 0) : MdNode()

class MdFootnoteDefinition(
    val label: String,
    val index: Int = 0,
    val content: String = "",
    val url: String = "",
) : MdNode()

/**
 * Returns all footnote definitions in index order.
 */
fun MdDocument.allFootnotes(): List<MdFootnoteDefinition> {
    val map = mutableMapOf<String, MdFootnoteDefinition>()
    map.putAll(footnoteDefinitions)
    fun walk(node: MdNode) {
        if (node is MdFootnoteDefinition) {
            map[node.label] = node
        }
        for (c in node.children) walk(c)
    }
    walk(this)
    return map.values.sortedBy { if (it.index > 0) it.index else Int.MAX_VALUE }
}

/**
 * Recursively collect all image URLs for full-screen preview gallery.
 */
fun MdNode.collectImageUrls(): List<String> {
    val list = mutableListOf<String>()
    fun walk(node: MdNode) {
        if (node is MdImage && node.url.isNotBlank()) {
            list.add(node.url)
        }
        for (child in node.children) {
            walk(child)
        }
    }
    walk(this)
    return list
}
