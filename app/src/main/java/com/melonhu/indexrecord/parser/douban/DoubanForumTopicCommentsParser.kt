package com.melonhu.indexrecord.parser.douban

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.douban.DoubanUtils.parseDoubanComments
import java.util.regex.Pattern

/**
 * 豆瓣书影音论坛话题讨论评论解析器。
 * 例如：https://frodo.douban.com/api/v2/forum_topic/616438845/comments
 */
object DoubanForumTopicCommentsParser {

    private val PATTERN_FORUM_TOPIC_COMMENTS = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/forum_topic/(\\d+)/comments/?$")

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_FORUM_TOPIC_COMMENTS.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = PATTERN_FORUM_TOPIC_COMMENTS.matcher(cleanUrl)
        val topicId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val defaultParentId = if (topicId.isNotBlank()) "douban_forum_topic_$topicId" else ""
        return parseDoubanComments(json, raw, defaultParentId)
    }
}
