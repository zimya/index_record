package me.melon.record.markdown

enum class MarkdownContentFormat { AUTO, MARKDOWN }

/** AUTO preserves legacy HTML detection; MARKDOWN never treats code examples as an HTML document. */
fun contentToMdAst(
    content: String,
    contentFormat: MarkdownContentFormat = MarkdownContentFormat.AUTO,
    enableMath: Boolean = true
): MdDocument {
    if (contentFormat == MarkdownContentFormat.AUTO &&
        (content.contains("<p>") || content.contains("</div>") || content.contains("<figure"))
    ) {
        return htmlToMdAst(content)
    }
    return markdownToMdAst(
        markdown = content,
        enableMath = enableMath,
        preserveHtml = contentFormat == MarkdownContentFormat.MARKDOWN,
        normalizeFootnotes = contentFormat == MarkdownContentFormat.AUTO
    )
}
