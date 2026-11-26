package com.melonhu.indexrecord.parser.zhihu

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.markdown.zhihuHtmlToMarkdown
import com.melonhu.indexrecord.parser.zhihu.ZhihuItemV2Parser.safeString
import java.util.regex.Pattern

/**
 * 知乎文章解析器。
 * 支持 v1 JSON 文章接口与 v2 卡片模式文章接口。
 */
object ZhihuArticleParser {

    private val PATTERN_JSON_ARTICLE = Pattern.compile("https?://(?:api\\.zhihu\\.com|(?:www\\.)?zhihu\\.com/api/v4)/articles/[^/]+/?")
    private val PATTERN_ZHIHU_ARTICLE_V2 = Pattern.compile("https?://api\\.zhihu\\.com/articles/v2/([^/]+)/?")

    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_ZHIHU_ARTICLE_V2.matcher(cleanUrl).matches() ||
                PATTERN_JSON_ARTICLE.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val jsonParams = JsonParser.parseString(raw.json).asJsonObject

        // 如果是 v2 卡片模式或包含 structured_content
        if (PATTERN_ZHIHU_ARTICLE_V2.matcher(cleanUrl).matches() || jsonParams.has("structured_content")) {
            val record = ZhihuItemV2Parser.parse(jsonParams, raw, defaultType = "article")
            return listOfNotNull(record)
        }

        // v1 JSON 文章
        return listOf(parseJsonArticleV1(jsonParams, raw, cleanUrl))
    }

    private fun parseJsonArticleV1(jsonParams: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord {
        val articleId = cleanUrl.trimEnd('/').substringAfterLast('/')
        val title = jsonParams.safeString("title")
        val rawContent = jsonParams.safeString("content")
        val content = zhihuHtmlToMarkdown(rawContent)

        val authorObj = jsonParams.getAsJsonObject("author")
        val authorName = authorObj?.safeString("name") ?: "Unknown"
        val authorId = authorObj?.safeString("id") ?: ""
        val authorAvatar = authorObj?.safeString("avatar_url") ?: ""
        val authorHeadline = authorObj?.safeString("headline") ?: ""

        val extraJson = JsonObject()
        val fieldsToExtract = listOf("url", "created", "updated", "ip_info", "voteup_count", "comment_count", "favlists_count", "title_image")
        for (field in fieldsToExtract) {
            if (jsonParams.has(field)) extraJson.add(field, jsonParams.get(field))
        }
        if (authorHeadline.isNotBlank()) extraJson.addProperty("author_headline", authorHeadline)

        return UniversalRecord(

            contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
            platform = "zhihu",
            recordType = "article",
            sourceApp = raw.hostApp,
            platformId = "zhihu_article_$articleId",
            title = title,
            content = content,
            authorId = authorId,
            authorName = authorName,
            authorAvatar = authorAvatar,
            sourceUrl = raw.url,
            publishTime = jsonParams.get("created")?.asLong?.times(1000) ?: raw.timestamp,
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extraJson)
        )
    }
}
