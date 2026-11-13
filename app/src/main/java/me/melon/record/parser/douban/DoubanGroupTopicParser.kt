package me.melon.record.parser.douban

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.PlatformCatalog
import me.melon.record.data.model.RawData
import me.melon.record.data.model.RecordBodyFormat
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.douban.DoubanUtils.doubanHtmlToMarkdown
import me.melon.record.parser.douban.DoubanUtils.extractUser
import me.melon.record.parser.douban.DoubanUtils.parseDoubanDate
import me.melon.record.parser.douban.DoubanUtils.safeLong
import me.melon.record.parser.douban.DoubanUtils.safeString
import java.util.regex.Pattern

/**
 * 豆瓣小组帖子解析器。
 * 例如：https://frodo.douban.com/api/v2/group/topic/505200892
 */
object DoubanGroupTopicParser {

    private val PATTERN_GROUP_TOPIC = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/group/topic/(\\d+)/?$")
    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_GROUP_TOPIC.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = PATTERN_GROUP_TOPIC.matcher(cleanUrl)
        val urlTopicId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val topicId = json.safeString("id").ifBlank { urlTopicId }
        if (topicId.isBlank()) return emptyList()

        val title = json.safeString("title")
        val rawContent = json.safeString("content")
        val content = doubanHtmlToMarkdown(rawContent)

        val author = extractUser(json)
        val createdTime = parseDoubanDate(json.safeString("create_time"), raw.timestamp)
        val coverUrl = json.safeString("cover_url")

        val extra = JsonObject()
        extra.addProperty("topic_id", topicId)

        val groupObj = json.getAsJsonObject("group")
        if (groupObj != null) {
            extra.addProperty("group_name", groupObj.safeString("name"))
            extra.addProperty("group_id", groupObj.safeString("id"))
        }

        val likeCount = when {
            json.has("like_count") -> json.safeLong("like_count")
            json.has("likers_count") -> json.safeLong("likers_count")
            json.has("reactions_count") -> json.safeLong("reactions_count")
            else -> 0L
        }
        val commentCount = json.safeLong("comments_count")
        val collectCount = json.safeLong("collections_count")
        val ipLocation = json.safeString("ip_location")

        extra.addProperty("voteup_count", likeCount)
        extra.addProperty("like_count", likeCount)
        extra.addProperty("comment_count", commentCount)
        extra.addProperty("favlists_count", collectCount)
        if (ipLocation.isNotBlank()) extra.addProperty("ip_info", ipLocation)
        if (coverUrl.isNotBlank()) {
            extra.addProperty("cover_url", coverUrl)
            extra.addProperty("title_image", coverUrl)
        }

        val record = UniversalRecord(

            contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
            platform = "douban",
            recordType = "topic",
            sourceApp = raw.hostApp.ifBlank { PlatformCatalog.defaultPackage("douban") },
            platformId = "douban_group_topic_$topicId",
            title = title,
            content = content,
            authorId = author.id,
            authorName = author.name,
            authorAvatar = author.avatar,
            sourceUrl = raw.url,
            parentId = "",
            publishTime = createdTime,
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extra)
        )

        return listOf(record)
    }
}
