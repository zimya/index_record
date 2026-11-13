package me.melon.record.parser.douban

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.PlatformCatalog
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.douban.DoubanUtils.extractUser
import me.melon.record.parser.douban.DoubanUtils.parseDoubanDate
import me.melon.record.parser.douban.DoubanUtils.safeLong
import me.melon.record.parser.douban.DoubanUtils.safeString
import java.util.regex.Pattern

/**
 * 豆瓣书影音论坛话题讨论评论解析器。
 * 例如：https://frodo.douban.com/api/v2/forum_topic/616438845/comments
 */
object DoubanForumTopicCommentsParser {

    private val PATTERN_FORUM_TOPIC_COMMENTS = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/forum_topic/(\\d+)/comments/?$")
    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_FORUM_TOPIC_COMMENTS.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = PATTERN_FORUM_TOPIC_COMMENTS.matcher(cleanUrl)
        val topicId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val records = mutableListOf<UniversalRecord>()
        val seenIds = mutableSetOf<String>()

        fun processComment(commentObj: JsonObject) {
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

            val refObj = if (commentObj.has("ref_comment") && commentObj.get("ref_comment").isJsonObject) {
                commentObj.getAsJsonObject("ref_comment")
            } else null
            val refId = refObj?.safeString("id") ?: ""

            val parentId = if (refId.isNotBlank()) {
                "douban_comment_$refId"
            } else if (topicId.isNotBlank()) {
                "douban_forum_topic_$topicId"
            } else {
                ""
            }

            val extra = JsonObject()
            extra.addProperty("vote_count", voteCount)
            extra.addProperty("like_count", voteCount)
            if (ipLocation.isNotBlank()) extra.addProperty("ip_info", ipLocation)
            if (refId.isNotBlank()) extra.addProperty("ref_comment_id", refId)

            records.add(
                UniversalRecord(
                    platform = "douban",
                    recordType = "comment",
                    sourceApp = raw.hostApp.ifBlank { PlatformCatalog.defaultPackage("douban") },
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
        }

        fun processArray(arr: JsonArray?) {
            if (arr == null) return
            for (elem in arr) {
                if (elem.isJsonObject) {
                    processComment(elem.asJsonObject)
                }
            }
        }

        processArray(json.getAsJsonArray("popular_comments"))
        processArray(json.getAsJsonArray("comments"))

        return records
    }
}
