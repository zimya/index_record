package com.melonhu.indexrecord.parser.douban

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.markdown.escapeMarkdownText
import com.melonhu.indexrecord.parser.douban.DoubanUtils.appendMissingPhotos
import com.melonhu.indexrecord.parser.douban.DoubanUtils.doubanHtmlToMarkdown
import com.melonhu.indexrecord.parser.douban.DoubanUtils.extractDoubanPhotos
import com.melonhu.indexrecord.parser.douban.DoubanUtils.extractUser
import com.melonhu.indexrecord.parser.douban.DoubanUtils.parseDoubanDate
import com.melonhu.indexrecord.parser.douban.DoubanUtils.safeString
import com.melonhu.indexrecord.parser.douban.DoubanUtils.safeStringList
import com.melonhu.indexrecord.parser.douban.DoubanUtils.statCount
import java.util.regex.Pattern

/**
 * 豆瓣日记详情解析器。
 * 例如：https://frodo.douban.com/api/v2/note/878617681
 */
object DoubanNoteParser {

    private val PATTERN_NOTE = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/note/(\\d+)/?$")
    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean = PATTERN_NOTE.matcher(cleanUrl).matches()

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = PATTERN_NOTE.matcher(cleanUrl)
        val urlNoteId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val noteId = json.safeString("id").ifBlank { urlNoteId }
        if (noteId.isBlank()) return emptyList()

        val title = json.safeString("title").trim()
        val photos = extractDoubanPhotos(json.getAsJsonArray("photos"))
        val rawContent = json.safeString("content")
        val body = if (rawContent.isNotBlank()) {
            doubanHtmlToMarkdown(rawContent, photos).trim()
        } else {
            escapeMarkdownText(json.safeString("abstract").trim())
        }
        val content = appendMissingPhotos(body, photos)

        val author = extractUser(json)
        val createdTime = parseDoubanDate(json.safeString("create_time"), raw.timestamp)
        val coverUrl = json.safeString("cover_url")

        val extra = JsonObject()
        extra.addProperty("note_id", noteId)
        val likeCount = json.statCount("like_count", "likers_count", "reactions_count")
        val commentCount = json.statCount("comments_count")
        val collectCount = json.statCount("collections_count")
        val reshareCount = json.statCount("reshares_count")
        val ipLocation = json.safeString("ip_location").ifBlank { json.safeString("updated_ip_location") }

        if (likeCount != null) {
            extra.addProperty("voteup_count", likeCount)
            extra.addProperty("like_count", likeCount)
        }
        if (commentCount != null) extra.addProperty("comment_count", commentCount)
        if (collectCount != null) extra.addProperty("favlists_count", collectCount)
        if (reshareCount != null) extra.addProperty("reshares_count", reshareCount)
        if (ipLocation.isNotBlank()) extra.addProperty("ip_info", ipLocation)
        if (coverUrl.isNotBlank()) extra.addProperty("cover_url", coverUrl)

        val tags = json.safeStringList("tags")
        if (tags.isNotEmpty()) extra.add("tags", gson.toJsonTree(tags))
        val topicName = json.get("topic")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.let { it.safeString("name").ifBlank { it.safeString("title") } }.orEmpty().trim()
        if (topicName.isNotBlank()) extra.add("topics", gson.toJsonTree(listOf(topicName)))

        return listOf(
            UniversalRecord(
                contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
                platform = "douban",
                recordType = "note",
                sourceApp = raw.hostApp.ifBlank { PlatformCatalog.defaultPackage("douban") },
                platformId = "douban_note_$noteId",
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
        )
    }
}
