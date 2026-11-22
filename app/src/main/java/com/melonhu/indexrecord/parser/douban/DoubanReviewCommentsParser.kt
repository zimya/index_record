package com.melonhu.indexrecord.parser.douban

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.douban.DoubanUtils.parseDoubanComments
import java.util.regex.Pattern

/**
 * 豆瓣书影音长评评论解析器。
 * 支持嵌套楼中楼回复（nested/replies）。
 * 例如：https://frodo.douban.com/api/v2/review/1967305/comments
 */
object DoubanReviewCommentsParser {

    private val PATTERN_REVIEW_COMMENTS = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/review/(\\d+)/comments/?$")

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_REVIEW_COMMENTS.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val matcher = PATTERN_REVIEW_COMMENTS.matcher(cleanUrl)
        val reviewId = if (matcher.find()) matcher.group(1) ?: "" else ""

        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val defaultParentId = if (reviewId.isNotBlank()) "douban_review_$reviewId" else ""
        return parseDoubanComments(json, raw, defaultParentId, arrayKeys = listOf("comments"))
    }
}
