package me.melon.record.presentation

import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.RecordContent
import me.melon.record.markdown.MarkdownContentFormat
import me.melon.record.data.model.bodyFormat
import me.melon.record.data.model.bodyPlainText

internal data class RecordHeaderPresentation(
    val title: String,
    val titleImage: String,
    val author: String,
    val authorHeadline: String,
    val starCount: Double
)

internal fun recordHeaderPresentation(record: RecordContent): RecordHeaderPresentation {
    val metadata = PlatformMetadataAdapters(record.extraMetadata, record.platform)
    return RecordHeaderPresentation(
        title = record.title,
        titleImage = metadata.zhihu?.titleImage ?: metadata.douban?.titleImage.orEmpty(),
        author = record.authorName.ifBlank { "作者未知" },
        authorHeadline = metadata.zhihu?.authorHeadline.orEmpty(),
        starCount = metadata.douban?.starCount ?: 0.0
    )
}

internal data class RecordBodyPresentation(
    val content: String,
    val contentFormat: MarkdownContentFormat,
    val enableMath: Boolean
)

internal fun recordBodyPresentation(record: RecordContent): RecordBodyPresentation {
    val format = record.bodyFormat()
    return RecordBodyPresentation(record.content, format.renderingFormat, format.enableMath)
}

internal fun commentPlainText(record: RecordContent): String = record.bodyPlainText()

/** A reference, not an invented captured subject; retained for the related-record navigation fallback. */
internal fun doubanSubjectReference(record: RecordContent): UniversalRecord? {
    val metadata = PlatformMetadataAdapters(record.extraMetadata, record.platform).douban ?: return null
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
