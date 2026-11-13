package me.melon.record.markdown

/** Extracts the same nodes rendered by RenderMarkdownDocument, not the source markup. */
fun MdDocument.toPlainText(): String {
    val text = StringBuilder()
    fun append(node: MdNode) {
        when (node) {
            is MdText -> text.append(node.text)
            is MdInlineCode -> text.append(node.literal)
            is MdFencedCodeBlock -> text.append(node.code).append(' ')
            is MdMathBlock -> text.append(node.formula).append(' ')
            is MdInlineMath -> text.append(node.formula)
            is MdImage -> text.append("[图片]")
            is MdGif -> text.append("[GIF]")
            is MdHardLineBreak -> text.append(' ')
            is MdFootnoteReference -> text.append('[').append(node.label).append(']')
            is MdFootnoteDefinition -> text.append(node.content).append(' ')
            else -> {
                node.children.forEach {
                    append(it)
                    if (node is MdDocument || node is MdBlockQuote || node is MdListItem) text.append(' ')
                }
                if (node is MdParagraph || node is MdHeading || node is MdListItem ||
                    node is MdTableCell || node is MdBlockQuote || node is MdDocument) text.append(' ')
            }
        }
    }
    append(this)
    allFootnotes().forEach { text.append(it.content).append(' ') }
    return text.toString().replace(Regex("\\s+"), " ").trim()
}
