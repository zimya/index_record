package me.melon.record.presentation

import me.melon.record.data.PlatformCatalog
import me.melon.record.data.model.UniversalRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class RecordListPresentation(
    val badgeText: String,
    val useBlackBadge: Boolean,
    val title: String,
    val context: String,
    val author: String,
    val preview: String,
    val capturedAt: String,
    val statistics: List<RecordStatistic>,
    val wrapStatistics: Boolean,
    val snippet: String?
)

internal fun recordListPresentation(record: UniversalRecord, searchQuery: String): RecordListPresentation {
    val metadata = RecordMetadata(record.extraMetadata, record.platform)
    val preview = recordPreviewText(record, metadata)
    val snippetContent = if (record.platform == "reddit") preview else record.content
    return RecordListPresentation(
        badgeText = if (record.platform == "twitter" && record.recordType == "tweet") "X · tweet"
            else "${PlatformCatalog.displayName(record.platform)} · ${PlatformCatalog.typeLabel(record.platform, record.recordType)}",
        useBlackBadge = record.platform == "twitter" && record.recordType == "tweet",
        title = recordDisplayTitle(record, metadata),
        context = recordContext(record, metadata),
        author = record.authorName,
        preview = preview,
        capturedAt = "浏览于 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(record.captureTime))}",
        statistics = recordStatistics(record, StatisticsSurface.LIST, metadata),
        wrapStatistics = record.platform == "reddit",
        snippet = if (searchQuery.isNotEmpty() && snippetContent.contains(searchQuery, ignoreCase = true))
            contentSnippet(snippetContent, searchQuery) else null
    )
}

