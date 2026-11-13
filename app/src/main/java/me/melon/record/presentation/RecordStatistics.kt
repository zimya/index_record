package me.melon.record.presentation

import me.melon.record.data.model.UniversalRecord

internal enum class StatisticKind { LIKE, REPLY, REPOST, VIEW, SHARE, QUOTE, BOOKMARK, LOCATION, FAVORITE }
internal data class RecordStatistic(val kind: StatisticKind, val text: String)
internal enum class StatisticsSurface { LIST, DETAIL }

internal fun redditStatistics(metadata: RedditMetadata): List<RecordStatistic> = buildList {
    metadata.score?.let { add(RecordStatistic(StatisticKind.LIKE, formatCount(it))) }
    metadata.commentCount?.let { add(RecordStatistic(StatisticKind.REPLY, formatCount(it))) }
    metadata.shareCount?.let { add(RecordStatistic(StatisticKind.SHARE, formatCount(it))) }
}

/** Same count semantics on both surfaces; order and detail-only badges intentionally differ. */
internal fun recordStatistics(
    record: UniversalRecord,
    surface: StatisticsSurface,
    metadata: RecordMetadata = RecordMetadata(record.extraMetadata, record.platform)
): List<RecordStatistic> {
    metadata.reddit?.let { return redditStatistics(it) }
    return buildList {
        fun count(kind: StatisticKind, value: Long, prefix: String = "") {
            if (value > 0) add(RecordStatistic(kind, prefix + formatCount(value)))
        }
        if (record.platform in setOf("twitter", "weibo", "threads")) {
            if (surface == StatisticsSurface.LIST) {
                count(StatisticKind.REPLY, metadata.replyCount)
                count(StatisticKind.REPOST, metadata.repostCount)
                count(StatisticKind.LIKE, metadata.likeCount)
            } else {
                count(StatisticKind.LIKE, metadata.likeCount)
                count(StatisticKind.REPOST, metadata.repostCount)
                count(StatisticKind.REPLY, metadata.replyCount)
                if (record.platform == "threads") {
                    count(StatisticKind.QUOTE, metadata.quoteCount, "引用 ")
                    count(StatisticKind.SHARE, metadata.reshareCount, "分享 ")
                }
                count(StatisticKind.BOOKMARK, metadata.bookmarkCount)
            }
            count(StatisticKind.VIEW, metadata.viewCount)
        } else if (surface == StatisticsSurface.DETAIL) {
            metadata.voteupText?.let { add(RecordStatistic(StatisticKind.LIKE, it)) }
            metadata.commentCountText?.let { add(RecordStatistic(StatisticKind.REPLY, it)) }
            metadata.ipInfo?.let { add(RecordStatistic(StatisticKind.LOCATION, it)) }
            metadata.favlistsText?.let { add(RecordStatistic(StatisticKind.FAVORITE, it)) }
        }
    }
}

internal data class CommentStatistics(
    val likeCount: Long?,
    val replyCount: Long?,
    val shareCount: Long?,
    val dislikeCount: Long,
    val viewCount: Long
)

internal fun commentStatistics(
    record: UniversalRecord,
    metadata: RecordMetadata = RecordMetadata(record.extraMetadata, record.platform)
): CommentStatistics {
    val reddit = metadata.reddit
    return CommentStatistics(
        likeCount = if (reddit != null) reddit.score else metadata.commentLikeCount.takeIf { it > 0 },
        replyCount = reddit?.commentCount,
        shareCount = reddit?.shareCount,
        dislikeCount = if (reddit == null) metadata.dislikeCount else 0L,
        viewCount = if (record.platform == "threads") metadata.viewCount else 0L
    )
}
