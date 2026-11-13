package me.melon.record.presentation

import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.directRelation

/** Layout capabilities describe content structure, not a navigation entry's platform. */
internal data class ContentStructure(
    val hasTitle: Boolean,
    val supportsLocalReplies: Boolean,
    val hasRelatedContent: Boolean
)

internal fun contentStructure(record: UniversalRecord): ContentStructure = ContentStructure(
    hasTitle = record.title.isNotBlank(),
    supportsLocalReplies = record.recordType in setOf(
        "answer", "article", "pin", "comment", "tweet", "status", "thread", "post",
        "topic", "review", "interest", "lou", "floor"
    ),
    hasRelatedContent = record.directRelation() != null
)

internal enum class ReplySectionStatus { UNSUPPORTED, NOT_CAPTURED, PLATFORM_EMPTY, AVAILABLE }

internal data class ReplySectionPresentation(
    val status: ReplySectionStatus,
    val localCount: Int,
    val platformCount: Long?,
    val hasMore: Boolean?
) {
    val message: String get() = when (status) {
        ReplySectionStatus.UNSUPPORTED -> "当前内容类型暂不支持展示本地回复"
        ReplySectionStatus.NOT_CAPTURED -> "本地尚未采集到回复，不代表平台没有回复"
        ReplySectionStatus.PLATFORM_EMPTY -> "平台报告回复数为 0，本地尚无回复记录"
        ReplySectionStatus.AVAILABLE -> "本地记录不代表平台完整回复列表"
    }
}

internal fun replySectionPresentation(record: UniversalRecord, localCount: Int): ReplySectionPresentation {
    val metadata = PlatformMetadataAdapters(record.extraMetadata, record.platform)
    val platformCount = recordStatistics(record, StatisticsSurface.DETAIL, metadata)
        .firstOrNull { it.kind == StatisticKind.REPLY }?.value
    val hasMore = metadata.reddit?.commentsHasNextPage
    val status = when {
        localCount > 0 -> ReplySectionStatus.AVAILABLE
        !contentStructure(record).supportsLocalReplies -> ReplySectionStatus.UNSUPPORTED
        platformCount == 0L && hasMore != true -> ReplySectionStatus.PLATFORM_EMPTY
        else -> ReplySectionStatus.NOT_CAPTURED
    }
    return ReplySectionPresentation(status, localCount, platformCount, hasMore)
}
