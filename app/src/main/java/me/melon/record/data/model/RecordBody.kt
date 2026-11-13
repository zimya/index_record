package me.melon.record.data.model

import me.melon.record.markdown.MarkdownContentFormat
import me.melon.record.markdown.contentToMdAst
import me.melon.record.markdown.toPlainText

/** Stable storage values; math is part of the body dialect, never a UI platform decision. */
enum class RecordBodyFormat(val storageValue: String, val renderingFormat: MarkdownContentFormat, val enableMath: Boolean = false) {
    PLAIN_TEXT("plain_text", MarkdownContentFormat.PLAIN_TEXT),
    HTML("html", MarkdownContentFormat.HTML),
    MARKDOWN("markdown", MarkdownContentFormat.MARKDOWN),
    MARKDOWN_MATH("markdown_math", MarkdownContentFormat.MARKDOWN, true);

    companion object {
        fun fromStorage(value: String): RecordBodyFormat = requireNotNull(entries.firstOrNull { it.storageValue == value }) {
                    "Unsupported record body format: '$value'"
                }
    }
}

/** The declared storage format is authoritative; invalid values are rejected, never inferred. */
fun UniversalRecord.bodyFormat(): RecordBodyFormat = RecordBodyFormat.fromStorage(contentFormat)

/** Visible body text for summaries, comment text and search; excludes hidden link/media URLs. */
fun UniversalRecord.bodyPlainText(): String {
    val format = bodyFormat()
    return contentToMdAst(content, format.renderingFormat, format.enableMath).toPlainText()
}
