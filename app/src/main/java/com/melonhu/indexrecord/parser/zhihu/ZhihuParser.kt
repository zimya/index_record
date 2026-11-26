package com.melonhu.indexrecord.parser.zhihu

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.markdown.zhihuHtmlToMarkdown
import com.melonhu.indexrecord.parser.RawDataParser
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.zhihu.ZhihuItemV2Parser.safeArray
import com.melonhu.indexrecord.parser.zhihu.ZhihuItemV2Parser.safeObject
import com.melonhu.indexrecord.parser.zhihu.ZhihuItemV2Parser.safeString

/**
 * 知乎平台总解析器。
 * 负责分发和调度知乎各种类型（文章、回答、问题详情、回答列表、推荐信息流、评论）的解析工作。
 */
object ZhihuParser : RawDataParser {

    override val platform: String = "zhihu"

    private fun isPinV2Url(url: String): Boolean =
        Regex("https?://api\\.zhihu\\.com/pins/v2/[0-9]+/?").matches(url)

    private fun isPinV4Url(url: String): Boolean =
        Regex("https?://(?:www\\.)?zhihu\\.com/api/v4/pins/[0-9]+/?").matches(url)

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean {
        if (!cleanUrl.contains("zhihu.com") && PlatformCatalog.platformForPackage(raw.hostApp) != "zhihu") {
            return false
        }
        return isPinV2Url(cleanUrl) || isPinV4Url(cleanUrl) ||
                ZhihuArticleParser.canHandle(cleanUrl) ||
                ZhihuAnswerParser.canHandle(cleanUrl) ||
                ZhihuQuestionParser.canHandle(cleanUrl) ||
                ZhihuFeedParser.canHandle(cleanUrl) ||
                ZhihuCommentParser.canHandle(cleanUrl)
    }

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult =
        if (ZhihuAnswerParser.isHtmlAnswerUrl(cleanUrl)) {
            // Only appview answers are HTML. The HTML parser validates the embedded state;
            // JSON endpoints must still reject HTML error/login pages and malformed JSON.
            ParseResult.attempt("zhihu.htmlAnswer") { ParseResult.Success(parse(raw, cleanUrl)) }
        } else {
            super<RawDataParser>.parseResult(raw, cleanUrl)
        }

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        return when {
            isPinV2Url(cleanUrl) -> listOfNotNull(ZhihuItemV2Parser.parse(
                JsonParser.parseString(raw.json).asJsonObject, raw, defaultType = "pin"
            ))
            isPinV4Url(cleanUrl) -> listOfNotNull(parsePinV4(raw, cleanUrl))
            ZhihuFeedParser.canHandle(cleanUrl) -> ZhihuFeedParser.parse(raw, cleanUrl)
            ZhihuArticleParser.canHandle(cleanUrl) -> ZhihuArticleParser.parse(raw, cleanUrl)
            ZhihuAnswerParser.canHandle(cleanUrl) -> ZhihuAnswerParser.parse(raw, cleanUrl)
            ZhihuQuestionParser.canHandle(cleanUrl) -> ZhihuQuestionParser.parse(raw, cleanUrl)
            ZhihuCommentParser.canHandle(cleanUrl) -> ZhihuCommentParser.parse(raw, cleanUrl)
            else -> emptyList()
        }
    }

    private fun parsePinV4(raw: RawData, cleanUrl: String): UniversalRecord? {
        val obj = JsonParser.parseString(raw.json).asJsonObject
        val pinId = obj.safeString("id").ifBlank { cleanUrl.trimEnd('/').substringAfterLast('/') }
        if (pinId.isBlank()) return null

        val parts = mutableListOf<String>()
        val contentArr = obj.safeArray("content")
        if (contentArr != null) {
            for (elem in contentArr) {
                if (!elem.isJsonObject) continue
                val item = elem.asJsonObject
                when (item.safeString("type")) {
                    "text" -> {
                        val html = item.safeString("content")
                        if (html.isNotBlank()) parts.add(zhihuHtmlToMarkdown(html))
                    }
                    "image" -> {
                        val imgUrl = item.safeString("original_url").ifBlank { item.safeString("url") }
                        if (imgUrl.isNotBlank()) parts.add("![]($imgUrl)")
                    }
                    "link-card" -> {
                        val title = item.safeString("title").ifBlank { item.safeString("url") }
                        val url = item.safeString("url")
                        if (url.isNotBlank()) parts.add("[$title]($url)")
                    }
                    "quote" -> {
                        val text = item.safeString("content")
                        if (text.isNotBlank()) parts.add("> ${zhihuHtmlToMarkdown(text)}")
                    }
                }
            }
        }
        val content = parts.joinToString("\n\n").ifBlank { obj.safeString("excerpt_title") }

        val authorObj = obj.safeObject("author")
        val authorName = authorObj?.safeString("name")?.ifBlank { "知乎用户" } ?: "知乎用户"
        val authorId = authorObj?.safeString("id") ?: ""
        val authorAvatar = authorObj?.safeString("avatar_url") ?: ""
        val authorHeadline = authorObj?.safeString("headline") ?: ""

        val extraJson = JsonObject().apply {
            if (obj.has("like_count")) add("voteup_count", obj.get("like_count"))
            else if (obj.has("reaction_count")) add("voteup_count", obj.get("reaction_count"))
            if (obj.has("comment_count")) add("comment_count", obj.get("comment_count"))
            if (obj.has("repin_count")) add("repin_count", obj.get("repin_count"))
            if (authorHeadline.isNotBlank()) addProperty("author_headline", authorHeadline)
            if (obj.has("topics")) add("topics", obj.get("topics"))
        }

        val createdMs = obj.get("created")?.takeIf { !it.isJsonNull }?.asLong?.times(1000) ?: raw.timestamp
        val updatedMs = obj.get("updated")?.takeIf { !it.isJsonNull }?.asLong?.times(1000) ?: createdMs

        return UniversalRecord(
            contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
            platform = "zhihu",
            recordType = "pin",
            sourceApp = raw.hostApp,
            platformId = "zhihu_pin_$pinId",
            title = "",
            content = content,
            authorId = authorId,
            authorName = authorName,
            authorAvatar = authorAvatar,
            sourceUrl = raw.url,
            publishTime = updatedMs,
            captureTime = raw.timestamp,
            extraMetadata = extraJson.toString()
        )
    }
}
