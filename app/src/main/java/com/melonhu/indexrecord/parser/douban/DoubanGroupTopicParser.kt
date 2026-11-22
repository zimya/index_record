package com.melonhu.indexrecord.parser.douban

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.douban.DoubanUtils.appendMissingPhotos
import com.melonhu.indexrecord.parser.douban.DoubanUtils.doubanHtmlToMarkdown
import com.melonhu.indexrecord.parser.douban.DoubanUtils.extractDoubanPhotos
import com.melonhu.indexrecord.parser.douban.DoubanUtils.extractUser
import com.melonhu.indexrecord.parser.douban.DoubanUtils.parseDoubanDate
import com.melonhu.indexrecord.parser.douban.DoubanUtils.safeString
import java.net.URI
import java.util.regex.Pattern

/**
 * 豆瓣小组帖子与个人动态解析器。
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
        val photos = extractDoubanPhotos(json.getAsJsonArray("photos"))
        val content = appendMissingPhotos(doubanHtmlToMarkdown(rawContent).trim(), photos)

        val author = extractUser(json)
        val createdTime = parseDoubanDate(json.safeString("create_time"), raw.timestamp)
        val coverUrl = json.safeString("cover_url")
        val isPersonal = json.safeString("subtype") == "personal"

        val extra = JsonObject()
        extra.addProperty("topic_id", topicId)

        val groupObj = json.getAsJsonObject("group")
        if (groupObj != null) {
            val groupName = groupObj.safeString("name")
            val groupId = groupObj.safeString("id")
            if (groupName.isNotBlank()) extra.addProperty("group_name", groupName)
            if (groupId.isNotBlank() && groupId != "0") extra.addProperty("group_id", groupId)
        }

        // The Rexxar detail response uses like_count=0 as a placeholder and omits
        // collections_count. It must not erase statistics from the native detail response.
        val isRexxar = runCatching {
            URI(raw.url).query?.split('&')?.contains("s=rexxar_new") == true
        }.getOrDefault(false)
        val likeKeys = if (isRexxar) listOf("likers_count", "reactions_count")
            else listOf("like_count", "likers_count", "reactions_count")
        val likeCount = likeKeys.firstNotNullOfOrNull { json.statCount(it) }
        val commentCount = json.statCount("comments_count")
        val collectCount = json.statCount("collections_count")
        val reshareCount = json.statCount("reshares_count")
        val ipLocation = json.safeString("ip_location")

        if (likeCount != null) {
            extra.addProperty("voteup_count", likeCount)
            extra.addProperty("like_count", likeCount)
        }
        if (commentCount != null) extra.addProperty("comment_count", commentCount)
        if (collectCount != null) extra.addProperty("favlists_count", collectCount)
        if (reshareCount != null) extra.addProperty("reshares_count", reshareCount)
        if (ipLocation.isNotBlank()) extra.addProperty("ip_info", ipLocation)
        if (coverUrl.isNotBlank()) extra.addProperty("cover_url", coverUrl)

        val record = UniversalRecord(
            contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
            platform = "douban",
            recordType = if (isPersonal) "feed" else "topic",
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

    private fun JsonObject.statCount(key: String): Long? {
        val value = get(key)?.takeIf { it.isJsonPrimitive } ?: return null
        return value.asString.toLongOrNull()?.takeIf { it >= 0 }
    }
}
