package me.melon.record.parser.douban

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.douban.DoubanUtils.safeDouble
import me.melon.record.parser.douban.DoubanUtils.safeLong
import me.melon.record.parser.douban.DoubanUtils.safeString
import me.melon.record.parser.douban.DoubanUtils.safeStringList
import java.util.regex.Pattern

/**
 * 豆瓣书影音游剧条目（Subject）解析器。
 * 支持从电影(movie)、图书(book)、音乐(music)、游戏(game)、电视剧(tv)、舞台剧(drama)
 * 以及 /subject/{id}/forum_topic/forum 等接口中全量提取条目信息（名称、原名、评分、海报、简介、演职员/作者、标签等），
 * 并建立条目主体记录，用于聚合短评、长评与讨论。
 */
object DoubanSubjectParser {

    private val PATTERN_SUBJECT_FORUM = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/subject/(\\d+)/forum_topic/forum/?.*$")
    // 放宽匹配规则：支持后接参数、子路径（如 /modules、/credits、/rating、/interests 等）
    private val PATTERN_SUBJECT_DETAIL = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/(movie|book|music|game|tv|drama|subject)/(\\d+)(?:/.*)?$")
    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_SUBJECT_FORUM.matcher(cleanUrl).matches() ||
                PATTERN_SUBJECT_DETAIL.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val subjectObj = if (json.has("subject") && json.get("subject").isJsonObject) {
            json.getAsJsonObject("subject")
        } else if (json.has("title") && json.has("id")) {
            json
        } else {
            null
        } ?: return emptyList()

        val record = parseSubjectFromObject(subjectObj, raw, cleanUrl) ?: return emptyList()
        return listOf(record)
    }

    /**
     * 从任意包含 subject 数据的 JsonObject 中构建 UniversalRecord 条目记录。
     * 可供长评（Review）、论坛帖（Topic）等接口在携带完整条目信息时复用沉淀。
     */
    fun parseSubjectFromObject(
        subjectObj: JsonObject,
        raw: RawData,
        cleanUrl: String = ""
    ): UniversalRecord? {
        val subjectId = subjectObj.safeString("id")
        val subjectTitle = subjectObj.safeString("title")
        if (subjectId.isBlank() || subjectTitle.isBlank()) return null

        // 提取 subtype
        val urlSubtype = if (cleanUrl.isNotBlank()) {
            val matcher = PATTERN_SUBJECT_DETAIL.matcher(cleanUrl)
            if (matcher.find()) matcher.group(1) else ""
        } else ""

        val subtype = subjectObj.safeString("subtype")
            .ifBlank { subjectObj.safeString("type") }
            .ifBlank { urlSubtype }
            .ifBlank { "movie" }

        DoubanRelations.registerSubjectTitle(subjectId, subjectTitle)

        val originalTitle = subjectObj.safeString("original_title")
        val year = subjectObj.safeString("year")
        val cardSubtitle = subjectObj.safeString("card_subtitle")
        val intro = subjectObj.safeString("intro").ifBlank { subjectObj.safeString("summary") }

        // 海报图片
        val picObj = if (subjectObj.has("pic") && subjectObj.get("pic").isJsonObject) subjectObj.getAsJsonObject("pic") else null
        val coverObj = if (subjectObj.has("cover") && subjectObj.get("cover").isJsonObject) subjectObj.getAsJsonObject("cover") else null
        val coverUrl = picObj?.safeString("large")
            ?: picObj?.safeString("normal")
            ?: coverObj?.safeString("url")
            ?: subjectObj.safeString("cover_url")

        // 评分系统
        val ratingObj = if (subjectObj.has("rating") && subjectObj.get("rating").isJsonObject) subjectObj.getAsJsonObject("rating") else null
        val ratingValue = ratingObj?.safeDouble("value") ?: 0.0
        val starCount = ratingObj?.safeDouble("star_count") ?: 0.0
        val ratingCount = ratingObj?.safeLong("count") ?: 0L
        val maxRating = ratingObj?.safeLong("max", 10L) ?: 10L

        // 各种通用及分类元数据
        val genres = subjectObj.safeStringList("genres")
        val countries = subjectObj.safeStringList("countries")
        val languages = subjectObj.safeStringList("languages")
        val durations = subjectObj.safeStringList("durations")
        val pubdate = subjectObj.safeStringList("pubdate")
        val episodesCount = subjectObj.safeLong("episodes_count")

        // 影剧演职员
        val directors = subjectObj.safeStringList("directors")
        val actors = subjectObj.safeStringList("actors").ifEmpty { subjectObj.safeStringList("casts") }
        val writers = subjectObj.safeStringList("writers")

        // 书籍特有
        val author = subjectObj.safeStringList("author")
        val translator = subjectObj.safeStringList("translator")
        val press = subjectObj.safeStringList("press").ifEmpty { subjectObj.safeStringList("publisher") }
        val pages = subjectObj.safeStringList("pages")
        val price = subjectObj.safeStringList("price")
        val binding = subjectObj.safeString("binding")
        val isbn13 = subjectObj.safeString("isbn13")
        val catalog = subjectObj.safeString("catalog")
        val authorIntro = subjectObj.safeString("author_intro")

        // 游戏特有
        val developers = subjectObj.safeStringList("developers")
        val publishers = subjectObj.safeStringList("publishers")
        val platforms = subjectObj.safeStringList("platforms")
        val releaseDate = subjectObj.safeString("release_date")

        // 音乐特有
        val singer = subjectObj.safeStringList("singer")
        val discs = subjectObj.safeStringList("discs")

        // 荣誉榜单 (如 Top250, 高分榜)
        val honorInfos = mutableListOf<String>()
        if (subjectObj.has("honor_infos") && subjectObj.get("honor_infos").isJsonArray) {
            for (elem in subjectObj.getAsJsonArray("honor_infos")) {
                if (elem.isJsonObject) {
                    val obj = elem.asJsonObject
                    val rank = obj.safeLong("rank", -1)
                    val title = obj.safeString("title").ifBlank { obj.safeString("name") }
                    if (title.isNotBlank()) {
                        if (rank > 0) {
                            honorInfos.add("No.$rank $title")
                        } else {
                            honorInfos.add(title)
                        }
                    }
                } else if (elem.isJsonPrimitive) {
                    val s = elem.asString.trim()
                    if (s.isNotBlank()) honorInfos.add(s)
                }
            }
        }
        if (honorInfos.isEmpty()) {
            honorInfos.addAll(subjectObj.safeStringList("honor_infos"))
        }
        // 标签
        val tags = subjectObj.safeStringList("tags")
        val headerBgColor = subjectObj.safeString("header_bg_color")

        val extra = JsonObject()
        extra.addProperty("subject_id", subjectId)
        extra.addProperty("subject_title", subjectTitle)
        if (originalTitle.isNotBlank()) extra.addProperty("original_title", originalTitle)
        extra.addProperty("subject_type", subtype)
        if (year.isNotBlank()) extra.addProperty("year", year)
        if (headerBgColor.isNotBlank()) extra.addProperty("header_bg_color", headerBgColor)
        if (coverUrl.isNotBlank()) {
            extra.addProperty("cover_url", coverUrl)
            extra.addProperty("title_image", coverUrl)
        }
        if (cardSubtitle.isNotBlank()) extra.addProperty("card_subtitle", cardSubtitle)
        if (intro.isNotBlank()) extra.addProperty("intro", intro)

        // 评分
        extra.addProperty("rating_value", ratingValue)
        extra.addProperty("rating_stars", starCount)
        extra.addProperty("rating_count", ratingCount)
        extra.addProperty("rating_max", maxRating)

        fun addListIfNotEmpty(key: String, list: List<String>) {
            if (list.isNotEmpty()) {
                val arr = JsonArray(list.size)
                for (s in list) arr.add(s)
                extra.add(key, arr)
            }
        }

        addListIfNotEmpty("genres", genres)
        addListIfNotEmpty("countries", countries)
        addListIfNotEmpty("languages", languages)
        addListIfNotEmpty("durations", durations)
        addListIfNotEmpty("pubdate", pubdate)
        if (episodesCount > 0) extra.addProperty("episodes_count", episodesCount)

        addListIfNotEmpty("directors", directors)
        addListIfNotEmpty("actors", actors)
        addListIfNotEmpty("writers", writers)

        addListIfNotEmpty("author", author)
        addListIfNotEmpty("translator", translator)
        addListIfNotEmpty("press", press)
        addListIfNotEmpty("pages", pages)
        addListIfNotEmpty("price", price)
        if (binding.isNotBlank()) extra.addProperty("binding", binding)
        if (isbn13.isNotBlank()) extra.addProperty("isbn13", isbn13)
        if (catalog.isNotBlank()) extra.addProperty("catalog", catalog)
        if (authorIntro.isNotBlank()) extra.addProperty("author_intro", authorIntro)

        addListIfNotEmpty("developers", developers)
        addListIfNotEmpty("publishers", publishers)
        addListIfNotEmpty("platforms", platforms)
        if (releaseDate.isNotBlank()) extra.addProperty("release_date", releaseDate)

        addListIfNotEmpty("singer", singer)
        addListIfNotEmpty("discs", discs)
        addListIfNotEmpty("honor_infos", honorInfos)
        addListIfNotEmpty("tags", tags)

        return UniversalRecord(
            platform = "douban",
            recordType = "subject",
            sourceApp = raw.hostApp.ifBlank { "com.douban.frodo" },
            platformId = "douban_${subtype}_${subjectId}",
            title = subjectTitle,
            content = intro.ifBlank { cardSubtitle }.ifBlank { subjectTitle },
            authorId = "",
            authorName = "豆瓣",
            authorAvatar = "",
            sourceUrl = raw.url,
            parentId = "",
            publishTime = raw.timestamp,
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extra)
        )
    }
}
