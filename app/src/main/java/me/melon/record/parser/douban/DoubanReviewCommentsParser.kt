package me.melon.record.parser.douban

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.douban.DoubanUtils.extractUser
import me.melon.record.parser.douban.DoubanUtils.parseDoubanDate
import me.melon.record.parser.douban.DoubanUtils.safeLong
import me.melon.record.parser.douban.DoubanUtils.safeString
import java.util.regex.Pattern

/**
 * 豆瓣书影音长评评论解析器。
 * 支持嵌套楼中楼回复（nested/replies）。
 * 例如：https://frodo.douban.com/api/v2/review/1967305/comments
 */
object DoubanReviewCommentsParser {

    private val PATTERN_REVIEW_COMMENTS = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/review/(\\d+)/comments/?$")
    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_REVIEW_COMMENTS.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = PATTERN_REVIEW_COMMENTS.matcher(cleanUrl)
        val reviewId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val commentsArray = json.getAsJsonArray("comments") ?: return emptyList()
        val records = mutableListOf<UniversalRecord>()
        val seenIds = mutableSetOf<String>()

        fun parseCommentNode(commentObj: JsonObject, fallbackParentId: String) {
            if (commentObj.has("ad_info")) return
            val commentId = commentObj.safeString("id")
            if (commentId.isBlank() || !seenIds.add(commentId)) return

            val author = extractUser(commentObj)
            val text = commentObj.safeString("text")

            val photoUrls = mutableListOf<String>()
            val photosArray = commentObj.getAsJsonArray("photos")
            if (photosArray != null) {
                for (p in photosArray) {
                    if (!p.isJsonObject) continue
                    val pObj = p.asJsonObject
                    val imgObj = pObj.getAsJsonObject("image")
                    val url = imgObj?.getAsJsonObject("large")?.safeString("url")
                        ?: imgObj?.getAsJsonObject("normal")?.safeString("url")
                        ?: pObj.safeString("image")
                    if (!url.isNullOrBlank()) photoUrls.add(url)
                }
            }
            val content = if (photoUrls.isNotEmpty()) {
                val imagesMd = photoUrls.joinToString("\n\n") { "![图片]($it)" }
                if (text.isNotBlank()) "$text\n\n$imagesMd" else imagesMd
            } else {
                text
            }

            val voteCount = commentObj.safeLong("vote_count")
            val ipLocation = commentObj.safeString("ip_location")
            val createdTime = parseDoubanDate(commentObj.safeString("create_time"), raw.timestamp)

            val parentCommentId = commentObj.safeString("parent_comment_id")
            val refObj = if (commentObj.has("ref_comment") && commentObj.get("ref_comment").isJsonObject) {
                commentObj.getAsJsonObject("ref_comment")
            } else null
            val refId = refObj?.safeString("id") ?: ""

            val parentId = when {
                parentCommentId.isNotBlank() && parentCommentId != "0" -> "douban_comment_$parentCommentId"
                refId.isNotBlank() -> "douban_comment_$refId"
                fallbackParentId.isNotBlank() -> fallbackParentId
                reviewId.isNotBlank() -> "douban_review_$reviewId"
                else -> ""
            }

            val extra = JsonObject()
            extra.addProperty("vote_count", voteCount)
            extra.addProperty("like_count", voteCount)
            if (ipLocation.isNotBlank()) extra.addProperty("ip_info", ipLocation)
            if (parentCommentId.isNotBlank() && parentCommentId != "0") {
                extra.addProperty("parent_comment_id", parentCommentId)
            }
            if (refId.isNotBlank()) extra.addProperty("ref_comment_id", refId)

            records.add(
                UniversalRecord(
                    platform = "douban",
                    recordType = "comment",
                    sourceApp = raw.hostApp.ifBlank { "com.douban.frodo" },
                    platformId = "douban_comment_$commentId",
                    title = "评论: ${author.name}",
                    content = content,
                    authorId = author.id,
                    authorName = author.name,
                    authorAvatar = author.avatar,
                    sourceUrl = raw.url,
                    parentId = parentId,
                    publishTime = createdTime,
                    captureTime = raw.timestamp,
                    extraMetadata = gson.toJson(extra)
                )
            )

            // 处理嵌套回复 replies
            if (commentObj.has("replies") && commentObj.get("replies").isJsonArray) {
                val repliesArray = commentObj.getAsJsonArray("replies")
                for (rElem in repliesArray) {
                    if (rElem.isJsonObject) {
                        parseCommentNode(rElem.asJsonObject, "douban_comment_$commentId")
                    }
                }
            }
        }

        for (elem in commentsArray) {
            if (elem.isJsonObject) {
                parseCommentNode(elem.asJsonObject, if (reviewId.isNotBlank()) "douban_review_$reviewId" else "")
            }
        }

        return records
    }
}
