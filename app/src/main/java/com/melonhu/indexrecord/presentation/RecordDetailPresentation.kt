package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.RecordContent
import com.melonhu.indexrecord.markdown.MarkdownContentFormat
import com.melonhu.indexrecord.data.model.bodyFormat
import com.melonhu.indexrecord.data.model.bodyPlainText

internal data class RecordHeaderPresentation(
    val title: String,
    val titleImage: String,
    val author: String,
    val authorHeadline: String,
    val starCount: Double
)

internal fun recordHeaderPresentation(record: RecordContent): RecordHeaderPresentation {
    val metadata = PlatformMetadataAdapters(record.extraMetadata, record.platform)
    val authorHeadline = metadata.zhihu?.authorHeadline
        ?: metadata.coolapk?.let {
            listOfNotNull(
                it.deviceTitle.takeIf { d -> d.isNotBlank() }?.let { d -> "来自 $d" },
                it.ttitle.takeIf { t -> t.isNotBlank() }?.let { t -> "#$t#" }
            ).joinToString(" · ")
        }.orEmpty()
    val starCount = metadata.douban?.starCount
        ?: (metadata.coolapk?.ratingScore?.let { it / 2.0 } ?: 0.0)
    return RecordHeaderPresentation(
        title = record.title,
        titleImage = metadata.zhihu?.titleImage ?: metadata.douban?.titleImage ?: metadata.coolapk?.messageCover.orEmpty(),
        author = record.authorName.ifBlank { "作者未知" },
        authorHeadline = authorHeadline,
        starCount = starCount
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
