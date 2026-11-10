package me.melon.record.parser.douban

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.douban.DoubanUtils.doubanHtmlToMarkdown
import me.melon.record.parser.douban.DoubanUtils.extractUser
import me.melon.record.parser.douban.DoubanUtils.parseDoubanDate
import me.melon.record.parser.douban.DoubanUtils.safeLong
import me.melon.record.parser.douban.DoubanUtils.safeString
import java.util.regex.Pattern

/**
 * 豆瓣书影音论坛话题贴解析器。
 * 例如：https://frodo.douban.com/api/v2/forum_topic/22546527
 */
object DoubanForumTopicParser {

    private val PATTERN_FORUM_TOPIC = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/forum_topic/(\\d+)/?$")
    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_FORUM_TOPIC.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = PATTERN_FORUM_TOPIC.matcher(cleanUrl)
        val urlTopicId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val topicId = json.safeString("id").ifBlank { urlTopicId }
        if (topicId.isBlank()) return emptyList()

        val subjectObj = if (json.has("subject") && json.get("subject").isJsonObject) json.getAsJsonObject("subject") else null
        val subjectTitle = subjectObj?.safeString("title") ?: ""
        val subjectId = subjectObj?.safeString("id") ?: ""
        val subjectType = subjectObj?.safeString("type") ?: ""

        val rawTitle = json.safeString("title")
        val title = if (rawTitle.isNotBlank()) rawTitle else if (subjectTitle.isNotBlank()) "$subjectTitle 讨论" else "豆瓣话题贴"

        val rawContent = json.safeString("content")
        val content = doubanHtmlToMarkdown(rawContent)

        val author = extractUser(json)
        val createdTime = parseDoubanDate(json.safeString("create_time"), raw.timestamp)
        val coverUrl = json.safeString("cover_url")

        val likeCount = when {
            json.has("likers_count") -> json.safeLong("likers_count")
            json.has("like_count") -> json.safeLong("like_count")
            json.has("reactions_count") -> json.safeLong("reactions_count")
            else -> 0L
        }
        val commentCount = json.safeLong("comments_count")
        val collectCount = json.safeLong("collections_count")
        val ipLocation = json.safeString("ip_location")

        val extra = JsonObject()
        extra.addProperty("topic_id", topicId)
        if (subjectId.isNotBlank()) extra.addProperty("subject_id", subjectId)
        if (subjectTitle.isNotBlank()) extra.addProperty("subject_title", subjectTitle)
        if (subjectType.isNotBlank()) extra.addProperty("subject_type", subjectType)

        extra.addProperty("voteup_count", likeCount)
        extra.addProperty("like_count", likeCount)
        extra.addProperty("comment_count", commentCount)
        extra.addProperty("favlists_count", collectCount)
        if (ipLocation.isNotBlank()) extra.addProperty("ip_info", ipLocation)
        if (coverUrl.isNotBlank()) {
            extra.addProperty("cover_url", coverUrl)
            extra.addProperty("title_image", coverUrl)
        }

        val parentId = if (subjectId.isNotBlank() && subjectType.isNotBlank()) {
            "douban_${subjectType}_${subjectId}"
        } else ""

        val record = UniversalRecord(
            platform = "douban",
            recordType = "topic",
            sourceApp = raw.hostApp.ifBlank { "com.douban.frodo" },
            platformId = "douban_forum_topic_$topicId",
            title = title,
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

        val result = mutableListOf(record)
        if (subjectObj != null) {
            val subjectRec = DoubanSubjectParser.parseSubjectFromObject(subjectObj, raw, cleanUrl)
            if (subjectRec != null) {
                result.add(subjectRec)
            }
        }
        return result
    }
}
