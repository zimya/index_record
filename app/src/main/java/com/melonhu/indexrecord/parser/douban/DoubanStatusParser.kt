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
import com.melonhu.indexrecord.parser.douban.DoubanUtils.extractDoubanPhotos
import com.melonhu.indexrecord.parser.douban.DoubanUtils.extractUser
import com.melonhu.indexrecord.parser.douban.DoubanUtils.parseDoubanDate
import com.melonhu.indexrecord.parser.douban.DoubanUtils.safeLong
import com.melonhu.indexrecord.parser.douban.DoubanUtils.safeString
import java.util.regex.Pattern

/**
 * 豆瓣广播/动态详情解析器。
 * 例如：https://frodo.douban.com/api/v2/status/10033798803
 */
object DoubanStatusParser {

    private val PATTERN_STATUS = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/status/(\\d+)/?$")
    private val PATTERN_SUBJECT_URI = Pattern.compile("^douban://douban\\.com/(movie|book|music|game|tv|drama)/(\\d+)")
    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean = PATTERN_STATUS.matcher(cleanUrl).matches()

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = PATTERN_STATUS.matcher(cleanUrl)
        val urlStatusId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val statusId = json.safeString("id").ifBlank { urlStatusId }
        if (statusId.isBlank()) return emptyList()

        val author = extractUser(json)
        val createdTime = parseDoubanDate(json.safeString("create_time"), raw.timestamp)
        val title = json.safeString("title").trim()
        val activity = json.safeString("activity").trim()
        val text = json.safeString("text").trim()
        val images = extractDoubanPhotos(json.getAsJsonArray("images"))

        val card = json.get("card")?.takeIf { it.isJsonObject }?.asJsonObject
        val reshared = json.get("reshared_status")?.takeIf { it.isJsonObject }?.asJsonObject

        val sections = mutableListOf<String>()
        if (text.isNotBlank()) {
            sections.add(formatPlainText(text))
        }

        val collectedPhotos = images.toMutableList()
        if (card != null) {
            formatCardQuote(activity, card)?.let { sections.add(it) }
            val cardImages = card.get("images_block")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.getAsJsonArray("images")
            collectedPhotos.addAll(extractDoubanPhotos(cardImages))
        } else if (activity.isNotBlank() && text.isBlank()) {
            sections.add(escapeMarkdownText(activity))
        }

        if (reshared != null) {
            formatResharedQuote(reshared)?.let { sections.add(it) }
            collectedPhotos.addAll(extractDoubanPhotos(reshared.getAsJsonArray("images")))
        }

        val content = appendMissingPhotos(sections.joinToString("\n\n"), collectedPhotos)

        val extra = JsonObject()
        extra.addProperty("status_id", statusId)
        val likeCount = json.statCount("like_count", "reactions_count", "likers_count")
        val commentCount = json.statCount("comments_count")
        val reshareCount = json.statCount("reshares_count")
        val ipLocation = json.safeString("ip_location")
        if (likeCount != null) {
            extra.addProperty("voteup_count", likeCount)
            extra.addProperty("like_count", likeCount)
        }
        if (commentCount != null) extra.addProperty("comment_count", commentCount)
        if (reshareCount != null) extra.addProperty("reshares_count", reshareCount)
        if (ipLocation.isNotBlank()) extra.addProperty("ip_info", ipLocation)

        val ratingObj = json.get("rating")?.takeIf { it.isJsonObject }?.asJsonObject
        val ratingValue = ratingObj?.safeLong("value") ?: 0L
        val starCount = ratingObj?.safeLong("star_count") ?: 0L
        if (ratingValue > 0) extra.addProperty("rating_value", ratingValue)
        if (starCount > 0) extra.addProperty("star_count", starCount)

        val (subjectType, subjectId, subjectTitle) = extractSubjectRef(card)
        val parentId = if (subjectType.isNotBlank() && subjectId.isNotBlank()) {
            extra.addProperty("subject_id", subjectId)
            extra.addProperty("subject_type", subjectType)
            if (subjectTitle.isNotBlank()) extra.addProperty("subject_title", subjectTitle)
            "douban_${subjectType}_$subjectId"
        } else ""

        return listOf(
            UniversalRecord(
                contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
                platform = "douban",
                recordType = "feed",
                sourceApp = raw.hostApp.ifBlank { PlatformCatalog.defaultPackage("douban") },
                platformId = "douban_status_$statusId",
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
        )
    }

    private fun formatPlainText(text: String): String =
        text.split(Regex("\r?\n")).joinToString("\n\n") { escapeMarkdownText(it) }

    private fun formatCardQuote(activity: String, card: JsonObject): String? {
        val cardTitle = card.safeString("title").trim()
        val cardUrl = card.safeString("url").trim()
        val cardOwner = card.safeString("owner_name").trim()
        val cardSubtitle = card.safeString("subtitle").trim()

        val lines = mutableListOf<String>()
        val titleLink = when {
            cardTitle.isNotBlank() && cardUrl.isNotBlank() -> "[${escapeMarkdownText(cardTitle)}]($cardUrl)"
            cardTitle.isNotBlank() -> escapeMarkdownText(cardTitle)
            else -> ""
        }
        when {
            activity.isNotBlank() && titleLink.isNotBlank() -> lines.add("> **${escapeMarkdownText(activity)}**：$titleLink")
            titleLink.isNotBlank() -> lines.add("> **$titleLink**")
            activity.isNotBlank() -> lines.add("> **${escapeMarkdownText(activity)}**")
        }
        if (cardOwner.isNotBlank()) {
            lines.add("> 作者：${escapeMarkdownText(cardOwner)}")
        }
        if (cardSubtitle.isNotBlank()) {
            cardSubtitle.split(Regex("\r?\n")).forEach { line ->
                if (line.isNotBlank()) lines.add("> ${escapeMarkdownText(line)}")
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n> \n")
    }

    private fun formatResharedQuote(reshared: JsonObject): String? {
        val authorName = extractUser(reshared).name
        val text = reshared.safeString("text").trim()
        val lines = mutableListOf("> **转发 ${escapeMarkdownText(authorName)}**")
        if (text.isNotBlank()) {
            text.split(Regex("\r?\n")).forEach { line ->
                if (line.isNotBlank()) lines.add("> ${escapeMarkdownText(line)}")
            }
        }
        return lines.joinToString("\n> \n")
    }

    private fun extractSubjectRef(card: JsonObject?): Triple<String, String, String> {
        if (card == null) return Triple("", "", "")
        val cardTitle = card.safeString("title").trim()
        val interestInfo = card.get("interest_info")?.takeIf { it.isJsonObject }?.asJsonObject
        val infoId = interestInfo?.safeString("subject_id").orEmpty()
        val infoType = interestInfo?.safeString("subject_type").orEmpty()
        if (infoId.isNotBlank() && infoType.isNotBlank()) {
            return Triple(infoType, infoId, cardTitle)
        }
        val uriMatcher = PATTERN_SUBJECT_URI.matcher(card.safeString("uri"))
        if (uriMatcher.find()) {
            return Triple(uriMatcher.group(1) ?: "", uriMatcher.group(2) ?: "", cardTitle)
        }
        return Triple("", "", "")
    }

    private fun JsonObject.statCount(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()?.takeIf { it >= 0 }
    }
}
