package me.melon.record.parser.x

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import me.melon.record.data.PlatformCatalog
import me.melon.record.data.model.RawData
import me.melon.record.data.model.RecordBodyFormat
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.x.XUtils.safeInt
import me.melon.record.parser.x.XUtils.safeJsonArray
import me.melon.record.parser.x.XUtils.safeJsonObject
import me.melon.record.parser.x.XUtils.safeLong
import me.melon.record.parser.x.XUtils.safeString

object XTweetParser {

    private val gson = Gson()

    data class ParsedTweetResult(
        val mainRecord: UniversalRecord,
        val relatedRecords: List<UniversalRecord> = emptyList()
    )

    /**
     * 解析推特单个推文对象 (Tweet / TweetWithVisibilityResults)
     */
    fun parseTweet(tweetResultObj: JsonObject, raw: RawData): ParsedTweetResult? {
        if (XAdFilter.isPromoted(tweetResultObj)) return null
        val tweetObj = unwrapTweet(tweetResultObj) ?: return null
        if (XAdFilter.isPromoted(tweetObj)) return null

        val details = tweetObj.safeJsonObject("details")
        val counts = tweetObj.safeJsonObject("counts")
        val legacy = tweetObj.safeJsonObject("legacy")

        val tweetId = tweetObj.safeString("rest_id").ifBlank {
            legacy?.safeString("id_str") ?: ""
        }
        if (tweetId.isBlank()) return null

        // 1. 检查是否是转推 (Retweet)
        val retweetResult = legacy?.safeJsonObject("retweeted_status_result")?.safeJsonObject("result")
            ?: tweetObj.safeJsonObject("retweeted_tweet_results")?.safeJsonObject("result")
            ?: tweetObj.safeJsonObject("retweet_results")?.safeJsonObject("result")
        val relatedList = mutableListOf<UniversalRecord>()
        var originalTweet: UniversalRecord? = null

        if (retweetResult != null) {
            val parsedOriginal = parseTweet(retweetResult, raw)
            if (parsedOriginal != null) {
                originalTweet = parsedOriginal.mainRecord
                relatedList.add(originalTweet)
                relatedList.addAll(parsedOriginal.relatedRecords)
            }
        }

        // 2. 检查引用转推 (Quote Tweet)
        val quoteResult = tweetObj.safeJsonObject("quoted_tweet_results")?.safeJsonObject("result")
            ?: tweetObj.safeJsonObject("quoted_status_result")?.safeJsonObject("result")
            ?: legacy?.safeJsonObject("quoted_status_result")?.safeJsonObject("result")
        var quotedTweet: UniversalRecord? = null
        if (quoteResult != null) {
            val parsedQuote = parseTweet(quoteResult, raw)
            if (parsedQuote != null) {
                quotedTweet = parsedQuote.mainRecord
                relatedList.add(quotedTweet)
                relatedList.addAll(parsedQuote.relatedRecords)
            }
        }

        // 3. 提取作者信息
        val userResult = tweetObj.safeJsonObject("core")
            ?.safeJsonObject("user_results")
            ?.safeJsonObject("result")

        val userCore = userResult?.safeJsonObject("core")
        val userLegacy = userResult?.safeJsonObject("legacy")

        val authorId = userResult?.safeString("rest_id") ?: ""
        val authorName = userCore?.safeString("name")
            ?: userLegacy?.safeString("name")
            ?: "X 用户"
        val screenName = userCore?.safeString("screen_name")
            ?: userLegacy?.safeString("screen_name")
            ?: ""

        var avatarUrl = userResult?.safeJsonObject("avatar")?.safeString("image_url") ?: ""
        if (avatarUrl.isBlank()) {
            avatarUrl = userLegacy?.safeString("profile_image_url_https") ?: ""
        }
        // 将普通小头像换成稍大清晰的头像
        if (avatarUrl.contains("_normal.")) {
            avatarUrl = avatarUrl.replace("_normal.", "_bigger.")
        }

        // 4. 提取正文文本 (Note Tweet 优先，保证长文本不被裁剪；其次现代 Android details.full_text，再次 legacy.full_text)
        val noteTweetText = tweetObj.safeJsonObject("note_tweet")
            ?.safeJsonObject("note_tweet_results")
            ?.safeJsonObject("result")
            ?.safeString("text")

        val detailsText = details?.safeString("full_text") ?: ""
        val legacyText = legacy?.safeString("full_text") ?: ""

        val rawText = when {
            !noteTweetText.isNullOrBlank() -> noteTweetText
            detailsText.isNotBlank() -> detailsText
            legacyText.isNotBlank() -> legacyText
            else -> tweetObj.safeString("text")
        }

        val mediaUrls = mutableListOf<String>()
        var content = XUtils.formatTweetToMarkdown(rawText, tweetObj, legacy, mediaUrls)

        // 过滤空推文：既无正文又无任何多媒体（图片/视频）的推文一律视为无效占位卡片/已被删除，不予入库
        if (content.isBlank() && mediaUrls.isEmpty()) {
            return null
        }

        // 如果包含引用转推，附加上引用推文内容
        if (quotedTweet != null) {
            val qAuthor = quotedTweet.authorName
            val qHeadline = try {
                val qMeta = com.google.gson.JsonParser.parseString(quotedTweet.extraMetadata).asJsonObject
                qMeta.safeString("author_headline")
            } catch (_: Exception) { "" }
            val qLabel = if (qHeadline.isNotBlank()) "$qAuthor ($qHeadline)" else qAuthor

            val quotedBlock = quotedTweet.content.lines().joinToString("\n") { "> $it" }
            content = content.trim() + "\n\n> 💬 **引用 @$qLabel**：\n$quotedBlock"
        }

        // 5. 关系判定：普通贴 vs 回复(评论) vs 转推
        val replyToResults = tweetObj.safeJsonObject("reply_to_results")
        val replyToUserResults = tweetObj.safeJsonObject("reply_to_user_results")?.safeJsonObject("result")
        val modernInReplyToId = replyToResults?.safeString("rest_id") ?: ""
        val modernInReplyToScreenName = replyToUserResults?.safeJsonObject("core")?.safeString("screen_name") ?: ""

        val inReplyToStatusId = if (modernInReplyToId.isNotBlank()) {
            modernInReplyToId
        } else {
            legacy?.safeString("in_reply_to_status_id_str") ?: ""
        }
        val inReplyToScreenName = if (modernInReplyToScreenName.isNotBlank()) {
            modernInReplyToScreenName
        } else {
            legacy?.safeString("in_reply_to_screen_name") ?: ""
        }
        val conversationId = legacy?.safeString("conversation_id_str") ?: ""
        val isReply = inReplyToStatusId.isNotBlank()

        val recordType: String
        val platformId: String
        val parentId: String
        val title: String

        if (retweetResult != null && originalTweet != null) {
            recordType = "tweet"
            platformId = "twitter_tweet_$tweetId"
            parentId = ""
            title = XUtils.generateTweetTitle(content, prefix = "转推了 @$screenName: ")
        } else if (isReply) {
            recordType = "comment"
            platformId = "twitter_comment_$tweetId"
            // 若回复的目标 ID 等于根 conversation_id，则父节点为主推文；否则为上级评论；
            // 现代 schema 中若无 conversationId，默认先挂在主推文下（由 XRelations 动态校正）
            parentId = if (conversationId.isNotBlank() && inReplyToStatusId != conversationId) {
                "twitter_comment_$inReplyToStatusId"
            } else {
                "twitter_tweet_$inReplyToStatusId"
            }
            title = XUtils.generateTweetTitle(rawText, prefix = if (inReplyToScreenName.isNotBlank()) "回复 @$inReplyToScreenName: " else "回复: ")
        } else {
            recordType = "tweet"
            platformId = "twitter_tweet_$tweetId"
            parentId = ""
            title = XUtils.generateTweetTitle(content)
        }

        // 6. 统计数据与元数据（优先读取现代 Android counts 对象，其次 legacy）
        val favCount = counts?.safeLong("favorite_count") ?: legacy?.safeLong("favorite_count") ?: 0L
        val rtCount = counts?.safeLong("retweet_count") ?: legacy?.safeLong("retweet_count") ?: 0L
        val replyCount = counts?.safeLong("reply_count") ?: legacy?.safeLong("reply_count") ?: 0L
        val bookmarkCount = counts?.safeLong("bookmark_count") ?: legacy?.safeLong("bookmark_count") ?: 0L
        val viewsCount = tweetObj.safeJsonObject("views")?.safeLong("count")
            ?: tweetResultObj.safeJsonObject("views")?.safeLong("count")
            ?: 0L

        val createdAtMs = details?.safeLong("created_at_ms") ?: 0L
        val publishTime = if (createdAtMs > 0L) {
            createdAtMs
        } else {
            val createdAt = legacy?.safeString("created_at") ?: ""
            XUtils.parseTwitterDate(createdAt)
        }

        val extra = JsonObject()
        extra.addProperty("tweet_id", tweetId)
        extra.addProperty("screen_name", screenName)
        if (screenName.isNotBlank()) {
            extra.addProperty("author_headline", "@$screenName")
        }
        if (conversationId.isNotBlank()) {
            extra.addProperty("conversation_id", conversationId)
        }
        if (inReplyToStatusId.isNotBlank()) {
            extra.addProperty("in_reply_to_status_id", inReplyToStatusId)
        }
        if (inReplyToScreenName.isNotBlank()) {
            extra.addProperty("in_reply_to_screen_name", inReplyToScreenName)
        }

        // 互动数据字段（与 RecordDetailScreen 的 MetadataBadge 兼容）
        extra.addProperty("voteup_count", favCount)
        extra.addProperty("like_count", favCount)
        extra.addProperty("comment_count", replyCount)
        extra.addProperty("reply_count", replyCount)
        extra.addProperty("favlists_count", rtCount + bookmarkCount)
        extra.addProperty("retweet_count", rtCount)
        extra.addProperty("bookmark_count", bookmarkCount)
        if (viewsCount > 0L) {
            extra.addProperty("views_count", viewsCount)
        }

        // 首张图片设为 title_image，以便在详情页顶部展示卡片封面
        if (mediaUrls.isNotEmpty()) {
            extra.addProperty("title_image", mediaUrls.first())
            val mediaArr = JsonArray()
            mediaUrls.forEach { mediaArr.add(it) }
            extra.add("media_urls", mediaArr)
        }

        if (quotedTweet != null) {
            extra.addProperty("is_quote", true)
            extra.addProperty("quoted_status_id", quotedTweet.platformId)
        }
        if (retweetResult != null) {
            extra.addProperty("is_retweet", true)
            if (originalTweet != null) {
                extra.addProperty("retweeted_status_id", originalTweet.platformId)
            }
        }

        val sourceUrl = if (screenName.isNotBlank()) {
            "https://x.com/$screenName/status/$tweetId"
        } else {
            "https://x.com/i/status/$tweetId"
        }

        val record = UniversalRecord(

            contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
            platform = "twitter",
            recordType = recordType,
            sourceApp = raw.hostApp.ifBlank { PlatformCatalog.defaultPackage("twitter") },
            platformId = platformId,
            title = title,
            content = content,
            authorId = authorId.ifBlank { screenName },
            authorName = authorName,
            authorAvatar = avatarUrl,
            sourceUrl = sourceUrl,
            parentId = parentId,
            publishTime = if (publishTime > 0L) publishTime else raw.timestamp,
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extra)
        )

        return ParsedTweetResult(
            mainRecord = record,
            relatedRecords = relatedList
        )
    }

    fun unwrapTweet(obj: JsonObject?): JsonObject? {
        if (obj == null) return null
        val type = obj.safeString("__typename")
        if (type in listOf("TweetTombstone", "TweetUnavailable")) return null
        return when (type) {
            "Tweet" -> obj
            "TweetWithVisibilityResults" -> obj.safeJsonObject("tweet")
            else -> {
                // 部分接口可能直接嵌套在 tweet 键或没有 __typename
                if (obj.has("tweet")) obj.safeJsonObject("tweet")
                else if (obj.has("legacy") || obj.has("details") || obj.has("counts")) obj
                else null
            }
        }
    }
}
