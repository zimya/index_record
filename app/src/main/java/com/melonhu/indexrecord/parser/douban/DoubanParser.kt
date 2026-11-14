package com.melonhu.indexrecord.parser.douban

import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.RawDataParser

/**
 * 豆瓣平台总解析器。
 * 负责分发和调度豆瓣各种类型（小组帖子、小组评论、书影音游热评/短评、长评、长评评论、论坛话题贴、话题讨论）的解析工作。
 */
object DoubanParser : RawDataParser {

    override val platform: String = "douban"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean {
        if (!cleanUrl.contains("douban.com") && !cleanUrl.contains("doubanio.com") && raw.hostApp != "com.douban.frodo") {
            return false
        }
        return DoubanGroupTopicCommentsParser.canHandle(cleanUrl) ||
                DoubanGroupTopicParser.canHandle(cleanUrl) ||
                DoubanHotInterestsParser.canHandle(cleanUrl) ||
                DoubanReviewCommentsParser.canHandle(cleanUrl) ||
                DoubanReviewParser.canHandle(cleanUrl) ||
                DoubanForumTopicCommentsParser.canHandle(cleanUrl) ||
                DoubanForumTopicParser.canHandle(cleanUrl) ||
                DoubanSubjectParser.canHandle(cleanUrl)
    }

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        return when {
            DoubanGroupTopicCommentsParser.canHandle(cleanUrl) -> DoubanGroupTopicCommentsParser.parse(raw, cleanUrl)
            DoubanGroupTopicParser.canHandle(cleanUrl) -> DoubanGroupTopicParser.parse(raw, cleanUrl)
            DoubanHotInterestsParser.canHandle(cleanUrl) -> DoubanHotInterestsParser.parse(raw, cleanUrl)
            DoubanReviewCommentsParser.canHandle(cleanUrl) -> DoubanReviewCommentsParser.parse(raw, cleanUrl)
            DoubanReviewParser.canHandle(cleanUrl) -> DoubanReviewParser.parse(raw, cleanUrl)
            DoubanForumTopicCommentsParser.canHandle(cleanUrl) -> DoubanForumTopicCommentsParser.parse(raw, cleanUrl)
            DoubanForumTopicParser.canHandle(cleanUrl) -> DoubanForumTopicParser.parse(raw, cleanUrl)
            DoubanSubjectParser.canHandle(cleanUrl) -> DoubanSubjectParser.parse(raw, cleanUrl)
            else -> emptyList()
        }
    }
}
