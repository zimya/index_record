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
 * 知乎评论解析器。
 * 支持回答/文章/想法的根评论（root_comment）与楼中楼子评论（child_comment）。
 */
object ZhihuCommentParser {

    private val PATTERN_ZHIHU_ROOT_COMMENT = Pattern.compile("https?://api\\.zhihu\\.com/comment_v5/(answers|articles|pins)/(\\d+)/root_comment.*")
    private val PATTERN_ZHIHU_CHILD_COMMENT = Pattern.compile("https?://api\\.zhihu\\.com/comment_v5/comment/(\\d+)/child_comment.*")

    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_ZHIHU_ROOT_COMMENT.matcher(cleanUrl).matches() ||
                PATTERN_ZHIHU_CHILD_COMMENT.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val isRoot = PATTERN_ZHIHU_ROOT_COMMENT.matcher(cleanUrl).matches()
        val records = mutableListOf<UniversalRecord>()
        val jsonParams = JsonParser.parseString(raw.json).asJsonObject

        val dataArray = jsonParams.getAsJsonArray("data") ?: return records

        var fallbackParentId = ""
        if (isRoot) {
            val matcher = PATTERN_ZHIHU_ROOT_COMMENT.matcher(cleanUrl)
            if (matcher.find()) {
                val resourceType = matcher.group(1)
                val resourceId = matcher.group(2)
                fallbackParentId = when (resourceType) {
                    "answers" -> "zhihu_answer_$resourceId"
                    "pins" -> "zhihu_pin_$resourceId"
                    else -> "zhihu_article_$resourceId"
                }
            }
        } else {
            val matcher = PATTERN_ZHIHU_CHILD_COMMENT.matcher(cleanUrl)
            if (matcher.find()) {
                val commentId = matcher.group(1)
                fallbackParentId = "zhihu_comment_$commentId"
            }
        }

        fun parseComment(commentObj: JsonObject, parentFallback: String) {
            val commentId = commentObj.safeString("id")
            if (commentId.isEmpty()) return

            val rawContent = commentObj.safeString("content")
            val isHtml = rawContent.contains("<") && rawContent.contains(">")
            val content = if (isHtml) {
                zhihuHtmlToMarkdown(rawContent)
            } else {
                rawContent
            }

            val authorObj = commentObj.getAsJsonObject("author")
            val authorName = authorObj?.safeString("name") ?: "Unknown"
            val authorId = authorObj?.safeString("id") ?: ""
            val authorAvatar = authorObj?.safeString("avatar_url") ?: ""

            val createdTime = if (commentObj.has("created_time")) commentObj.get("created_time").asLong * 1000 else raw.timestamp

            val replyCommentId = commentObj.safeString("reply_comment_id")
            val parentId = if (replyCommentId.isNotEmpty() && replyCommentId != "0") {
                "zhihu_comment_$replyCommentId"
            } else {
                parentFallback
            }

            val extraJson = JsonObject()
            val fieldsToExtract = listOf("like_count", "dislike_count", "child_comment_count", "score", "reply_comment_id", "reply_root_comment_id")
            for (field in fieldsToExtract) {
                if (commentObj.has(field)) extraJson.add(field, commentObj.get(field))
            }

            records.add(
                UniversalRecord(
                    contentFormat = if (isHtml) RecordBodyFormat.MARKDOWN_MATH.storageValue else RecordBodyFormat.PLAIN_TEXT.storageValue,
                    platform = "zhihu",
                    recordType = "comment",
                    sourceApp = raw.hostApp,
                    platformId = "zhihu_comment_$commentId",
                    title = "评论: $authorName",
                    content = content,
                    authorId = authorId,
                    authorName = authorName,
                    authorAvatar = authorAvatar,
                    sourceUrl = raw.url,
                    parentId = parentId,
                    publishTime = createdTime,
                    captureTime = raw.timestamp,
                    extraMetadata = gson.toJson(extraJson)
                )
            )

            if (commentObj.has("child_comments") && commentObj.get("child_comments").isJsonArray) {
                val childArray = commentObj.getAsJsonArray("child_comments")
                for (childElem in childArray) {
                    if (!childElem.isJsonObject) continue
                    parseComment(childElem.asJsonObject, "zhihu_comment_$commentId")
                }
            }
        }

        for (elem in dataArray) {
            if (!elem.isJsonObject) continue
            parseComment(elem.asJsonObject, fallbackParentId)
        }
        return records
    }
}
