package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.RecordContent
import com.melonhu.indexrecord.data.model.directRelation

/** Layout capabilities describe content structure, not a navigation entry's platform. */
internal data class ContentStructure(
    val hasTitle: Boolean,
    val supportsLocalReplies: Boolean,
    val hasRelatedContent: Boolean
)

internal fun contentStructure(record: RecordContent): ContentStructure = ContentStructure(
    hasTitle = record.title.isNotBlank(),
    supportsLocalReplies = record.recordType in setOf(
        "answer", "article", "pin", "comment", "tweet", "status", "thread", "post",
        "topic", "review", "interest", "note", "lou", "floor",
        "feed", "rating", "feedArticle", "question", "video", "opus"
    ),
    hasRelatedContent = record.directRelation() != null
)


internal data class ReplySectionPresentation(
    val message: String?,
    val localCount: Int,
    val platformCount: Long?,
    val hasMore: Boolean?
)

internal fun replySectionPresentation(record: RecordContent, localCount: Int): ReplySectionPresentation {
    val metadata = PlatformMetadataAdapters(record.extraMetadata, record.platform)
    val platformCount = recordStatistics(record, StatisticsSurface.DETAIL, metadata)
        .firstOrNull { it.kind == StatisticKind.REPLY }?.value
    val hasMore = metadata.reddit?.commentsHasNextPage
    val message = when {
        localCount > 0 -> null
        !contentStructure(record).supportsLocalReplies -> "当前内容类型暂不支持展示本地回复"
        platformCount == 0L && hasMore != true -> "平台报告回复数为 0，本地尚无回复记录"
        else -> "本地尚未采集到回复"
    }
    return ReplySectionPresentation(message, localCount, platformCount, hasMore)
}
