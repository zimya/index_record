package com.melonhu.indexrecord.parser.zhihu

import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.RawDataParser
import com.melonhu.indexrecord.parser.ParseResult

/**
 * 知乎平台总解析器。
 * 负责分发和调度知乎各种类型（文章、回答、问题详情、回答列表、推荐信息流、评论）的解析工作。
 */
object ZhihuParser : RawDataParser {

    override val platform: String = "zhihu"

    private fun isPinV2Url(url: String): Boolean =
        Regex("https?://api\\.zhihu\\.com/pins/v2/[0-9]+/?").matches(url)

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean {
        if (!cleanUrl.contains("zhihu.com") && raw.hostApp != "com.zhihu.android") {
            return false
        }
        return isPinV2Url(cleanUrl) || ZhihuV4Parser.canHandle(cleanUrl) ||
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
                com.google.gson.JsonParser.parseString(raw.json).asJsonObject, raw, defaultType = "pin"
            ))
            ZhihuFeedParser.canHandle(cleanUrl) -> ZhihuFeedParser.parse(raw, cleanUrl)
            ZhihuV4Parser.canHandle(cleanUrl) -> ZhihuV4Parser.parse(raw, cleanUrl)
            ZhihuArticleParser.canHandle(cleanUrl) -> ZhihuArticleParser.parse(raw, cleanUrl)
            ZhihuAnswerParser.canHandle(cleanUrl) -> ZhihuAnswerParser.parse(raw, cleanUrl)
            ZhihuQuestionParser.canHandle(cleanUrl) -> ZhihuQuestionParser.parse(raw, cleanUrl)
            ZhihuCommentParser.canHandle(cleanUrl) -> ZhihuCommentParser.parse(raw, cleanUrl)
            else -> emptyList()
        }
    }
}
