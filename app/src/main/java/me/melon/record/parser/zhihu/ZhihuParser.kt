package me.melon.record.parser.zhihu

import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.RawDataParser

/**
 * 知乎平台总解析器。
 * 负责分发和调度知乎各种类型（文章、回答、问题详情、回答列表、推荐信息流、评论）的解析工作。
 */
object ZhihuParser : RawDataParser {

    override val platform: String = "zhihu"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean {
        if (!cleanUrl.contains("zhihu.com") && raw.hostApp != "com.zhihu.android") {
            return false
        }
        return ZhihuArticleParser.canHandle(cleanUrl) ||
                ZhihuAnswerParser.canHandle(cleanUrl) ||
                ZhihuQuestionParser.canHandle(cleanUrl) ||
                ZhihuFeedParser.canHandle(cleanUrl) ||
                ZhihuCommentParser.canHandle(cleanUrl)
    }

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        return when {
            ZhihuFeedParser.canHandle(cleanUrl) -> ZhihuFeedParser.parse(raw, cleanUrl)
            ZhihuArticleParser.canHandle(cleanUrl) -> ZhihuArticleParser.parse(raw, cleanUrl)
            ZhihuAnswerParser.canHandle(cleanUrl) -> ZhihuAnswerParser.parse(raw, cleanUrl)
            ZhihuQuestionParser.canHandle(cleanUrl) -> ZhihuQuestionParser.parse(raw, cleanUrl)
            ZhihuCommentParser.canHandle(cleanUrl) -> ZhihuCommentParser.parse(raw, cleanUrl)
            else -> emptyList()
        }
    }
}
