package com.melonhu.indexrecord.parser.douban

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.douban.DoubanUtils.parseDoubanComments
import java.util.regex.Pattern

/**
 * 豆瓣广播/动态评论与楼中楼回复解析器。
 * 例如：
 * https://frodo.douban.com/api/v2/status/10054035821/comments
 * https://frodo.douban.com/api/v2/status/comment/468264521/replies
 */
object DoubanStatusCommentsParser {

    private val PATTERN_STATUS_COMMENTS = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/status/(\\d+)/comments/?$")
    private val PATTERN_STATUS_COMMENT_REPLIES = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/status/comment/(\\d+)/replies/?$")

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_STATUS_COMMENTS.matcher(cleanUrl).matches() ||
            PATTERN_STATUS_COMMENT_REPLIES.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val commentsMatcher = PATTERN_STATUS_COMMENTS.matcher(cleanUrl)
        if (commentsMatcher.matches()) {
            val statusId = commentsMatcher.group(1) ?: ""
            val defaultParentId = if (statusId.isNotBlank()) "douban_status_$statusId" else ""
            return parseDoubanComments(json, raw, defaultParentId)
        }

        val repliesMatcher = PATTERN_STATUS_COMMENT_REPLIES.matcher(cleanUrl)
        if (repliesMatcher.matches()) {
            val commentId = repliesMatcher.group(1) ?: ""
            val defaultParentId = if (commentId.isNotBlank()) "douban_comment_$commentId" else ""
            return parseDoubanComments(json, raw, defaultParentId, arrayKeys = listOf("replies"))
        }

        return emptyList()
    }
}
