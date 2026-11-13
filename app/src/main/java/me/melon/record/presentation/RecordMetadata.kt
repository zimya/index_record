package me.melon.record.presentation

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Named, read-only fields for captured metadata. JSON keys never escape into rendering code. */
internal class RecordMetadata(extraMetadata: String, platform: String) {
    private val values = runCatching { JsonParser.parseString(extraMetadata).asJsonObject }
        .getOrElse { JsonObject() }

    val hasValues: Boolean get() = values.size() > 0
    val reddit: RedditMetadata? = if (platform == "reddit") RedditMetadata(extraMetadata) else null
    val likeCount: Long get() = values.engagementCount("like_count", "likeCount", "voteup_count")
    // Comments historically do not fall back to voteup_count, unlike post/list statistics.
    val commentLikeCount: Long get() = values.engagementCount("like_count", "likeCount")
    val repostCount: Long get() = values.engagementCount("retweet_count", "repost_count", "repostCount")
    val replyCount: Long get() = values.engagementCount("reply_count", "comment_count", "commentCount")
    val bookmarkCount: Long get() = values.engagementCount("bookmark_count", "bookmarkCount")
    val viewCount: Long = values.viewEngagementCount(platform)
    val quoteCount: Long get() = values.engagementCount("quote_count")
    val reshareCount: Long get() = values.engagementCount("reshare_count")
    val dislikeCount: Long get() = values.engagementCount("dislike_count")

    val threadTitle: String get() = text("thread_title")
    val forumName: String get() = text("forum_name")
    val floor: Long? get() = text("floor").toLongOrNull()?.takeIf { it >= 0 }
    val excerpt: String get() = text("excerpt")
    val intro: String get() = text("intro")
    val cardSubtitle: String get() = text("card_subtitle")
    val titleImage: String get() = text("title_image")
    val authorHeadline: String get() = text("author_headline")
    val starCount: Double get() = decimal("star_count").let { if (it > 0) it else decimal("rating_value") }
    val subjectId: String get() = text("subject_id")
    val subjectTitle: String get() = text("subject_title")

    // These legacy detail badges deliberately retain their captured, unformatted text (including zero/negative).
    val voteupText: String? get() = optionalText("voteup_count")
    val commentCountText: String? get() = optionalText("comment_count")
    val ipInfo: String? get() = optionalText("ip_info")
    val favlistsText: String? get() = optionalText("favlists_count")

    private fun optionalText(key: String): String? = runCatching {
        values.get(key)?.takeUnless { it.isJsonNull }?.asString
    }.getOrNull()
    private fun text(key: String): String = optionalText(key).orEmpty()
    private fun decimal(key: String): Double = runCatching {
        values.get(key)?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0
    }.getOrDefault(0.0)
}
