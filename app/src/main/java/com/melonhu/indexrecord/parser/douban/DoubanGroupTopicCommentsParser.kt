package com.melonhu.indexrecord.parser.douban

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.douban.DoubanUtils.parseDoubanComments
import java.util.regex.Pattern

/**
 * 豆瓣小组帖子与个人动态评论解析器。
 * 例如：https://frodo.douban.com/api/v2/group/topic/505200892/comments
 */
object DoubanGroupTopicCommentsParser {

    private val PATTERN_GROUP_TOPIC_COMMENTS = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/group/topic/(\\d+)/comments/?$")

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_GROUP_TOPIC_COMMENTS.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = PATTERN_GROUP_TOPIC_COMMENTS.matcher(cleanUrl)
        val topicId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val defaultParentId = if (topicId.isNotBlank()) "douban_group_topic_$topicId" else ""
        return parseDoubanComments(json, raw, defaultParentId)
    }
}
