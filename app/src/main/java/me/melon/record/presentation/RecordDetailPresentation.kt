package me.melon.record.presentation

import me.melon.record.data.model.UniversalRecord
import me.melon.record.markdown.MarkdownContentFormat
import org.jsoup.Jsoup

internal data class RecordHeaderPresentation(
    val title: String,
    val titleImage: String,
    val author: String,
    val authorHeadline: String,
    val starCount: Double
)

internal fun recordHeaderPresentation(record: UniversalRecord): RecordHeaderPresentation {
    val metadata = RecordMetadata(record.extraMetadata, record.platform)
    return RecordHeaderPresentation(
        title = record.title,
        titleImage = metadata.titleImage,
        author = record.authorName.ifBlank {
            when (record.platform) {
                "douban" -> "豆瓣用户"
                "threads" -> "Threads 用户"
                "reddit" -> "Reddit 用户"
                else -> ""
            }
        },
        authorHeadline = metadata.authorHeadline,
        starCount = metadata.starCount
    )
}

internal data class RecordBodyPresentation(
    val content: String,
    val contentFormat: MarkdownContentFormat,
    val enableMath: Boolean
)

internal fun recordBodyPresentation(record: UniversalRecord): RecordBodyPresentation = RecordBodyPresentation(
    content = record.content,
    contentFormat = if (record.platform == "reddit") MarkdownContentFormat.MARKDOWN else MarkdownContentFormat.AUTO,
    enableMath = record.platform != "reddit"
)

internal fun commentPlainText(record: UniversalRecord): String = Jsoup.parse(record.content).text()

/** A reference, not an invented captured subject; retained for the related-record navigation fallback. */
internal fun doubanSubjectReference(record: UniversalRecord): UniversalRecord? {
    val metadata = RecordMetadata(record.extraMetadata, record.platform)
    if (metadata.subjectId.isBlank() || metadata.subjectTitle.isBlank()) return null
    return UniversalRecord(
        platform = "douban",
        recordType = "subject",
        sourceApp = record.sourceApp,
        platformId = record.parentId,
        title = metadata.subjectTitle,
        content = metadata.subjectTitle,
        extraMetadata = record.extraMetadata
    )
}
