package me.melon.record.presentation

import me.melon.record.data.model.UniversalRecord

internal enum class StatisticKind(val label: String) {
    LIKE("点赞"), DISLIKE("点踩"), REPLY("回复"), REPOST("转发"), VIEW("浏览"), SHARE("分享"),
    QUOTE("引用"), BOOKMARK("书签"), LOCATION("发布地"), FAVORITE("收藏")
}

internal data class RecordStatistic(
    val kind: StatisticKind,
    val text: String,
    val label: String = kind.label,
    val value: Long? = null
) {
    // Read the full count, not its rounded visual abbreviation, and suppress duplicate child text.
    val accessibilityText: String get() {
        val quantityLabel = when {
            kind == StatisticKind.LOCATION || label == "得分" -> label
            kind == StatisticKind.REPLY -> "平台${label}数"
            else -> "${label}数"
        }
        return "$quantityLabel ${value?.toString() ?: text}"
    }
}
internal enum class StatisticsSurface { LIST, DETAIL }

internal fun redditStatistics(metadata: RedditMetadata): List<RecordStatistic> = buildList {
    metadata.score?.let { add(RecordStatistic(StatisticKind.LIKE, formatCount(it), "得分", it)) }
    metadata.commentCount?.let { add(RecordStatistic(StatisticKind.REPLY, formatCount(it), value = it)) }
    metadata.shareCount?.let { add(RecordStatistic(StatisticKind.SHARE, formatCount(it), value = it)) }
}

/** Every surface shares one order; detail adds secondary metrics without reordering shared ones. */
internal fun recordStatistics(
    record: UniversalRecord,
    surface: StatisticsSurface,
    metadata: PlatformMetadataAdapters = PlatformMetadataAdapters(record.extraMetadata, record.platform)
): List<RecordStatistic> {
    metadata.reddit?.let { return redditStatistics(it) }
    val counts = metadata.engagement
    val comment = record.recordType == "comment"
    val likeLabel = when (record.platform) {
        "zhihu" -> if (comment) "点赞" else "赞同"
        "douban" -> if (record.recordType == "review") "有用" else "点赞"
        "tieba" -> "赞同"
        else -> "点赞"
    }
    val replyLabel = if (record.platform in setOf("zhihu", "douban", "weibo") && !comment) "评论" else "回复"
    return buildList {
        fun count(kind: StatisticKind, value: Long?, label: String = kind.label) {
            value?.let { add(RecordStatistic(kind, formatCount(it), label, it)) }
        }
        fun votes() {
            count(StatisticKind.LIKE, if (comment) counts.commentLikeCount else counts.likeCount, likeLabel)
            count(StatisticKind.DISLIKE, counts.dislikeCount)
        }
        fun reply() = count(StatisticKind.REPLY, if (comment) counts.commentReplyCount else counts.replyCount, replyLabel)
        votes()
        reply()
        count(StatisticKind.REPOST, counts.repostCount)
        if (surface == StatisticsSurface.DETAIL) {
            count(StatisticKind.QUOTE, counts.quoteCount)
            count(StatisticKind.SHARE, counts.shareCount)
            count(StatisticKind.BOOKMARK, counts.bookmarkCount)
            count(StatisticKind.FAVORITE, counts.favoriteCount)
        }
        count(StatisticKind.VIEW, counts.viewCount)
        if (surface == StatisticsSurface.DETAIL) {
            val location = metadata.zhihu?.ipInfo ?: metadata.douban?.ipInfo
                ?: metadata.tieba?.ipAddress?.takeIf { it.isNotBlank() }
            location?.let { add(RecordStatistic(StatisticKind.LOCATION, it)) }
        }
    }
}

internal data class CommentStatistics(
    val likeCount: Long?,
    val replyCount: Long?,
    val shareCount: Long?,
    val dislikeCount: Long?,
    val viewCount: Long?
)

internal fun commentStatistics(
    record: UniversalRecord,
    metadata: PlatformMetadataAdapters = PlatformMetadataAdapters(record.extraMetadata, record.platform)
): CommentStatistics {
    val reddit = metadata.reddit
    val counts = metadata.engagement
    return CommentStatistics(
        likeCount = if (reddit != null) reddit.score else counts.commentLikeCount,
        replyCount = if (reddit != null) reddit.commentCount else counts.commentReplyCount,
        shareCount = reddit?.shareCount ?: counts.shareCount,
        dislikeCount = counts.dislikeCount,
        viewCount = counts.viewCount
    )
}
