package com.melonhu.indexrecord.parser.douban

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.douban.DoubanUtils.parseDoubanComments
import java.util.regex.Pattern

/**
 * 豆瓣日记评论与楼中楼回复解析器。
 * 例如：
 * https://frodo.douban.com/api/v2/note/878617681/comments
 * https://frodo.douban.com/api/v2/note/comment/76509791/replies
 */
object DoubanNoteCommentsParser {

    private val PATTERN_NOTE_COMMENTS = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/note/(\\d+)/comments/?$")
    private val PATTERN_NOTE_COMMENT_REPLIES = Pattern.compile("^https?://[^/]*douban\\.com/api/v2/note/comment/(\\d+)/replies/?$")

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_NOTE_COMMENTS.matcher(cleanUrl).matches() ||
            PATTERN_NOTE_COMMENT_REPLIES.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val json = try {
            JsonParser.parseString(raw.json).asJsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val commentsMatcher = PATTERN_NOTE_COMMENTS.matcher(cleanUrl)
        if (commentsMatcher.matches()) {
            val noteId = commentsMatcher.group(1) ?: ""
            val defaultParentId = if (noteId.isNotBlank()) "douban_note_$noteId" else ""
            return parseDoubanComments(json, raw, defaultParentId)
        }

        val repliesMatcher = PATTERN_NOTE_COMMENT_REPLIES.matcher(cleanUrl)
        if (repliesMatcher.matches()) {
            val commentId = repliesMatcher.group(1) ?: ""
            val defaultParentId = if (commentId.isNotBlank()) "douban_comment_$commentId" else ""
            return parseDoubanComments(json, raw, defaultParentId, arrayKeys = listOf("replies"))
        }

        return emptyList()
    }
}
