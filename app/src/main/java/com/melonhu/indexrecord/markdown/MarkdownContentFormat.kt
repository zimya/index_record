package com.melonhu.indexrecord.markdown

enum class MarkdownContentFormat { MARKDOWN, HTML, PLAIN_TEXT }

/** Parse only the declared source format; markup never selects a different parser. */
fun contentToMdAst(
    content: String,
    contentFormat: MarkdownContentFormat,
    enableMath: Boolean = false
): MdDocument = when (contentFormat) {
    MarkdownContentFormat.PLAIN_TEXT -> MdDocument().apply {
        appendChild(MdParagraph().apply {
            content.split('\n').forEachIndexed { index, line ->
                if (index > 0) appendChild(MdHardLineBreak())
                appendChild(MdText(line))
            }
        })
    }
    MarkdownContentFormat.HTML -> htmlToMdAst(content)
    MarkdownContentFormat.MARKDOWN -> markdownToMdAst(content, enableMath = enableMath)
}
