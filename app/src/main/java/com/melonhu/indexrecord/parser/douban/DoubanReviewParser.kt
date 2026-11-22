package com.melonhu.indexrecord.parser.douban

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.douban.DoubanUtils.doubanHtmlToMarkdown
import com.melonhu.indexrecord.parser.douban.DoubanUtils.extractUser
import com.melonhu.indexrecord.parser.douban.DoubanUtils.parseDoubanDate
import com.melonhu.indexrecord.parser.douban.DoubanUtils.safeLong
import com.melonhu.indexrecord.parser.douban.DoubanUtils.safeString
import java.util.regex.Pattern

/**
 * 豆瓣书影音长评解析器。
 * 例如：https://frodo.douban.com/api/v2/review/7379728
 */
object DoubanReviewParser {

    private val PATTERN_REVIEW = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/review/(\\d+)/?$")
    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_REVIEW.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = PATTERN_REVIEW.matcher(cleanUrl)
        val urlReviewId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val reviewId = json.safeString("id").ifBlank { urlReviewId }
        if (reviewId.isBlank()) return emptyList()

        val subjectObj = if (json.has("subject") && json.get("subject").isJsonObject) json.getAsJsonObject("subject") else null
        val subjectTitle = subjectObj?.safeString("title") ?: ""
        val subjectId = subjectObj?.safeString("id") ?: ""
        val subjectType = subjectObj?.safeString("type") ?: ""

        val rawTitle = json.safeString("title")
        val title = if (rawTitle.isNotBlank()) rawTitle else if (subjectTitle.isNotBlank()) "$subjectTitle 影评/书评" else "豆瓣长评"

        val rawContent = json.safeString("content")
        val content = doubanHtmlToMarkdown(rawContent)

        val author = extractUser(json)
        val createdTime = parseDoubanDate(json.safeString("create_time"), raw.timestamp)
        val coverUrl = json.safeString("cover_url")

        val usefulCount = json.safeLong("useful_count")
        val commentCount = json.safeLong("comments_count")
        val ipLocation = json.safeString("ip_location")

        val ratingObj = if (json.has("rating") && json.get("rating").isJsonObject) json.getAsJsonObject("rating") else null
        val ratingValue = ratingObj?.safeLong("value") ?: 0L
        val starCount = ratingObj?.safeLong("star_count") ?: 0L

        val extra = JsonObject()
        extra.addProperty("review_id", reviewId)
        if (subjectId.isNotBlank()) extra.addProperty("subject_id", subjectId)
        if (subjectTitle.isNotBlank()) extra.addProperty("subject_title", subjectTitle)
        if (subjectType.isNotBlank()) extra.addProperty("subject_type", subjectType)

        extra.addProperty("voteup_count", usefulCount)
        extra.addProperty("like_count", usefulCount)
        extra.addProperty("useful_count", usefulCount)
        extra.addProperty("comment_count", commentCount)
        if (ipLocation.isNotBlank()) extra.addProperty("ip_info", ipLocation)
        if (ratingValue > 0) extra.addProperty("rating_value", ratingValue)
        if (starCount > 0) extra.addProperty("star_count", starCount)
        if (coverUrl.isNotBlank()) {
            extra.addProperty("cover_url", coverUrl)
        }

        val parentId = if (subjectId.isNotBlank() && subjectType.isNotBlank()) {
            "douban_${subjectType}_${subjectId}"
        } else ""

        val record = UniversalRecord(

            contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
            platform = "douban",
            recordType = "review",
            sourceApp = raw.hostApp.ifBlank { PlatformCatalog.defaultPackage("douban") },
            platformId = "douban_review_$reviewId",
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
