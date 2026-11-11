package me.melon.record.parser.douban

import com.google.gson.Gson
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
 * 豆瓣书影音游热评和普通短评解析器。
 * 例如：
 * https://frodo.douban.com/api/v2/movie/1292722/hot_interests
 * https://frodo.douban.com/api/v2/game/26817171/hot_interests
 * https://frodo.douban.com/api/v2/book/1007305/hot_interests
 * https://frodo.douban.com/api/v2/music/35282427/hot_interests
 * https://frodo.douban.com/api/v2/tv/2373195/interests
 */
object DoubanHotInterestsParser {

    private val PATTERN_HOT_INTERESTS = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/([^/]+)/(\\d+)/hot_interests/?$")
    private val PATTERN_INTERESTS = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/([^/]+)/(\\d+)/interests/?$")
    private val gson = Gson()

    private fun matchUrl(cleanUrl: String) = sequenceOf(PATTERN_HOT_INTERESTS, PATTERN_INTERESTS)
        .map { it.matcher(cleanUrl) }
        .firstOrNull { it.matches() }

    fun canHandle(cleanUrl: String): Boolean {
        return matchUrl(cleanUrl) != null
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = matchUrl(cleanUrl) ?: return emptyList()

        val subjectType = matcher.group(1) ?: "item"
        val subjectId = matcher.group(2) ?: ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val interestsArray = json.getAsJsonArray("interests") ?: return emptyList()
        val records = mutableListOf<UniversalRecord>()

        val typeLabel = when (subjectType) {
            "movie" -> "电影短评"
            "book" -> "图书短评"
            "music" -> "音乐短评"
            "game" -> "游戏短评"
            "tv" -> "剧集短评"
            "drama" -> "舞台剧短评"
            else -> "豆瓣短评"
        }

        val subjectTitle = DoubanRelations.getSubjectTitle(subjectType, subjectId)
        val displayTitle = if (subjectTitle.isNotBlank()) {
            "$typeLabel：$subjectTitle"
        } else {
            "$typeLabel：$subjectId"
        }

        for (elem in interestsArray) {
            if (!elem.isJsonObject) continue
            val item = elem.asJsonObject
            val id = item.safeString("id")
            if (id.isBlank()) continue

            val author = extractUser(item)
            val comment = item.safeString("comment")
            val voteCount = item.safeLong("vote_count")
            val ipLocation = item.safeString("ip_location")
            val status = item.safeString("status")
            val createdTime = parseDoubanDate(item.safeString("create_time"), raw.timestamp)

            val ratingObj = if (item.has("rating") && item.get("rating").isJsonObject) item.getAsJsonObject("rating") else null
            val ratingValue = ratingObj?.safeLong("value") ?: 0L
            val starCount = ratingObj?.safeLong("star_count") ?: 0L

            val extra = JsonObject()
            extra.addProperty("subject_type", subjectType)
            extra.addProperty("subject_id", subjectId)
            if (subjectTitle.isNotBlank()) extra.addProperty("subject_title", subjectTitle)
            extra.addProperty("voteup_count", voteCount)
            extra.addProperty("like_count", voteCount)
            extra.addProperty("vote_count", voteCount)
            if (status.isNotBlank()) extra.addProperty("status", status)
            if (ipLocation.isNotBlank()) extra.addProperty("ip_info", ipLocation)
            if (ratingValue > 0) extra.addProperty("rating_value", ratingValue)
            if (starCount > 0) extra.addProperty("star_count", starCount)

            records.add(
                UniversalRecord(
                    platform = "douban",
                    recordType = "interest",
                    sourceApp = raw.hostApp.ifBlank { "com.douban.frodo" },
                    platformId = "douban_interest_$id",
                    title = displayTitle,
                    content = comment,
                    authorId = author.id,
                    authorName = author.name,
                    authorAvatar = author.avatar,
                    sourceUrl = raw.url,
                    parentId = if (subjectId.isNotBlank()) "douban_${subjectType}_${subjectId}" else "",
                    publishTime = createdTime,
                    captureTime = raw.timestamp,
                    extraMetadata = gson.toJson(extra)
                )
            )
        }

        return records
    }
}
