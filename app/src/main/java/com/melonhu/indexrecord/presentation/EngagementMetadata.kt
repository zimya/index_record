package com.melonhu.indexrecord.presentation

import com.google.gson.JsonObject

/** Missing, fractional, overflowing and malformed values are not captured counts. */
internal fun JsonObject.optionalLong(key: String): Long? {
    val value = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!value.isNumber && !value.isString) return null
    return value.asString.toLongOrNull()
}

internal fun JsonObject.signedScore(): Long? = optionalLong("score")

internal fun JsonObject.optionalEngagementCount(key: String): Long? =
    optionalLong(key)?.takeIf { it >= 0L }

internal data class EngagementMetadata(
    val likeCount: Long? = null,
    val commentLikeCount: Long? = likeCount,
    val replyCount: Long? = null,
    val commentReplyCount: Long? = replyCount,
    val repostCount: Long? = null,
    val bookmarkCount: Long? = null,
    val viewCount: Long? = null,
    val quoteCount: Long? = null,
    val shareCount: Long? = null,
    val dislikeCount: Long? = null,
    val favoriteCount: Long? = null
)

/** Each adapter only accepts keys with a known meaning on that platform. */
internal fun platformEngagement(platform: String, values: JsonObject): EngagementMetadata {
    fun count(vararg keys: String): Long? = keys.firstNotNullOfOrNull { values.optionalEngagementCount(it) }
    return when (platform) {
        "zhihu" -> EngagementMetadata(
            likeCount = count("voteup_count"), commentLikeCount = count("like_count"),
            replyCount = count("comment_count"), commentReplyCount = count("child_comment_count"),
            dislikeCount = count("dislike_count"), favoriteCount = count("favlists_count")
        )
        "douban" -> EngagementMetadata(
            likeCount = count("like_count", "voteup_count", "vote_count", "useful_count"),
            replyCount = count("comment_count"), favoriteCount = count("favlists_count")
        )
        "tieba" -> EngagementMetadata(
            likeCount = count("agree_count"), replyCount = count("reply_count"),
            favoriteCount = count("favlists_count")
        )
        "twitter" -> EngagementMetadata(
            likeCount = count("like_count", "likeCount"), replyCount = count("reply_count", "comment_count", "commentCount"),
            repostCount = count("retweet_count", "repost_count", "repostCount"),
            bookmarkCount = count("bookmark_count", "bookmarkCount"), viewCount = count("views_count", "viewCount")
        )
        "weibo" -> EngagementMetadata(
            likeCount = count("like_count", "likeCount"), replyCount = count("comment_count", "commentCount"),
            repostCount = count("retweet_count", "repost_count", "repostCount"), viewCount = count("views_count", "viewCount")
        )
        "threads" -> EngagementMetadata(
            likeCount = count("like_count", "likeCount"), replyCount = count("reply_count", "comment_count", "commentCount"),
            repostCount = count("repost_count", "repostCount"), quoteCount = count("quote_count"),
            shareCount = count("reshare_count"), viewCount = count("view_count", "views_count", "viewCount")
        )
        "coolapk" -> EngagementMetadata(
            likeCount = count("likenum", "like_count"),
            replyCount = count("replynum", "comment_count"),
            favoriteCount = count("favnum", "favlists_count"),
            shareCount = count("share_num", "share_count")
        )
        // Reddit's signed/hidden score is interpreted by RedditMetadata, never as a like count.
        else -> EngagementMetadata()
    }
}
