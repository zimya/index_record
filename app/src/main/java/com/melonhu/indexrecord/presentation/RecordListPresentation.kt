package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RecordContent
import com.melonhu.indexrecord.data.model.bodyPlainText


internal data class RecordListPresentation(
    val badgeText: String,
    val useBlackBadge: Boolean,
    val title: String,
    val context: String,
    val author: String,
    val preview: String,
    val capturedAt: String,
    val statistics: List<RecordStatistic>,

    val snippet: String?
)

internal fun recordListPresentation(record: RecordContent, searchQuery: String): RecordListPresentation {
    val metadata = PlatformMetadataAdapters(record.extraMetadata, record.platform)
    val preview = recordPreviewText(record, metadata)
    val snippetContent = record.bodyPlainText()
    return RecordListPresentation(
        badgeText = PlatformCatalog.recordLabel(record.platform, record.recordType),
        useBlackBadge = record.platform == "twitter",
        title = recordDisplayTitle(record, metadata),
        context = recordContext(record, metadata),
        author = displayAuthor(record.authorName),
        preview = preview,
        capturedAt = displayCaptureTime(record.captureTime),
        statistics = recordStatistics(record, StatisticsSurface.LIST, metadata),

        snippet = if (searchQuery.isNotEmpty() && snippetContent.contains(searchQuery, ignoreCase = true))
            contentSnippet(snippetContent, searchQuery) else null
    )
}
