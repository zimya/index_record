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
 * 知乎问题解析器。
 * 支持问题详情接口及问题下的回答列表（Feeds）接口。
 */
object ZhihuQuestionParser {

    private val PATTERN_ZHIHU_QUESTION_DETAIL = Pattern.compile(
        "(?:https?://(?:www\\.)?zhihu\\.com/api/v4/questions/(\\d+)/?)|(?:https?://api\\.zhihu\\.com/(?:api/v4/)?questions/(\\d+)/?)"
    )
    private val PATTERN_ZHIHU_QUESTION_FEEDS = Pattern.compile("https?://api\\.zhihu\\.com/questions/(\\d+)/feeds/?")

    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_ZHIHU_QUESTION_DETAIL.matcher(cleanUrl).matches() ||
                PATTERN_ZHIHU_QUESTION_FEEDS.matcher(cleanUrl).matches()
    }

    fun isQuestionFeeds(cleanUrl: String): Boolean {
        return PATTERN_ZHIHU_QUESTION_FEEDS.matcher(cleanUrl).matches()
    }

    fun isAuxiliaryQuestion(record: UniversalRecord): Boolean {
        if (record.platform != "zhihu" || record.recordType != "question") return false
        val cleanUrl = record.sourceUrl.substringBefore("?")
        if (isQuestionFeeds(cleanUrl)) return true
        val meta = runCatching { JsonParser.parseString(record.extraMetadata).asJsonObject }.getOrNull()
        if (meta?.has("is_auxiliary") == true || meta?.has("is_feed_response") == true) return true
        return record.content.isBlank() && record.authorId.isBlank() && record.authorName.isBlank() && meta?.has("answer_refs") == true
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        if (PATTERN_ZHIHU_QUESTION_DETAIL.matcher(cleanUrl).matches()) {
            return listOf(parseZhihuQuestionDetail(raw, cleanUrl))
        }
        if (PATTERN_ZHIHU_QUESTION_FEEDS.matcher(cleanUrl).matches()) {
            return listOf(parseZhihuQuestionFeeds(raw, cleanUrl))
        }
        return emptyList()
    }

    private fun parseZhihuQuestionDetail(raw: RawData, cleanUrl: String): UniversalRecord {
        val jsonParams = JsonParser.parseString(raw.json).asJsonObject
        val questionId = jsonParams.safeString("id").ifBlank {
            val matcher = PATTERN_ZHIHU_QUESTION_DETAIL.matcher(cleanUrl)
            if (matcher.find()) (matcher.group(1) ?: "").ifBlank { matcher.group(2) ?: "" } else ""
        }
        if (questionId.isBlank()) throw Exception("Question id not found")

        val title = jsonParams.safeString("title")
        val rawDetail = jsonParams.safeString("detail").ifBlank { jsonParams.safeString("editable_detail") }
        val detail = if (rawDetail.isNotBlank()) zhihuHtmlToMarkdown(rawDetail) else ""

        val authorObj = jsonParams.getAsJsonObject("author")
        val authorName = authorObj?.safeString("name") ?: "知乎用户"
        val authorId = authorObj?.safeString("id") ?: ""
        val authorAvatar = authorObj?.safeString("avatar_url") ?: ""
        val authorHeadline = authorObj?.safeString("headline") ?: ""

        val extraJson = JsonObject()
        extraJson.addProperty("question_id", questionId)
        val fieldsToExtract = listOf(
            "url",
            "created",
            "updated_time",
            "answer_count",
            "comment_count",
            "follower_count",
            "collapsed_answer_count",
            "excerpt"
        )
        for (field in fieldsToExtract) {
            if (jsonParams.has(field)) extraJson.add(field, jsonParams.get(field))
        }
        if (jsonParams.has("topics")) extraJson.add("topics", jsonParams.get("topics"))
        if (authorHeadline.isNotBlank()) extraJson.addProperty("author_headline", authorHeadline)

        val createdMs = jsonParams.get("created")?.asLong?.times(1000) ?: raw.timestamp
        val updatedMs = jsonParams.get("updated_time")?.asLong?.times(1000) ?: createdMs

        return UniversalRecord(

            contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
            platform = "zhihu",
            recordType = "question",
            sourceApp = raw.hostApp,
            platformId = "zhihu_question_$questionId",
            title = title,
            content = detail,
            authorId = authorId,
            authorName = authorName,
            authorAvatar = authorAvatar,
            sourceUrl = raw.url,
            publishTime = updatedMs,
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extraJson)
        )
    }

    private fun parseZhihuQuestionFeeds(raw: RawData, cleanUrl: String): UniversalRecord {
        val matcher = PATTERN_ZHIHU_QUESTION_FEEDS.matcher(cleanUrl)
        if (!matcher.find()) throw Exception("Question feed id not found")
        val questionId = matcher.group(1) ?: throw Exception("Question feed id not found")

        val jsonParams = JsonParser.parseString(raw.json).asJsonObject
        val dataArray = jsonParams.getAsJsonArray("data")

        val answerRefs = com.google.gson.JsonArray()
        var title = ""
        if (dataArray != null) {
            for (elem in dataArray) {
                if (!elem.isJsonObject) continue
                val target = elem.asJsonObject.getAsJsonObject("target") ?: continue
                if (target.safeString("type") != "answer") continue
                val answerId = target.safeString("id")
                if (answerId.isBlank()) continue

                val questionObj = target.getAsJsonObject("question")
                if (title.isBlank()) title = questionObj?.safeString("title") ?: ""

                val ref = JsonObject()
                ref.addProperty("id", answerId)
                val excerpt = target.safeString("big_card_summary").ifBlank { target.safeString("excerpt") }
                ref.addProperty("excerpt", excerpt)
                if (target.has("created_time")) ref.add("created_time", target.get("created_time"))
                if (target.has("updated_time")) ref.add("updated_time", target.get("updated_time"))
                if (target.has("voteup_count")) ref.add("voteup_count", target.get("voteup_count"))
                if (target.has("comment_count")) ref.add("comment_count", target.get("comment_count"))

                val author = target.getAsJsonObject("author")
                if (author != null) {
                    val authorRef = JsonObject()
                    authorRef.addProperty("id", author.safeString("id"))
                    authorRef.addProperty("name", author.safeString("name"))
                    authorRef.addProperty("avatar_url", author.safeString("avatar_url"))
                    authorRef.addProperty("headline", author.safeString("headline"))
                    ref.add("author", authorRef)
                }

                answerRefs.add(ref)
            }
        }

        val extraJson = JsonObject()
        extraJson.addProperty("question_id", questionId)
        extraJson.add("answer_refs", answerRefs)
        extraJson.addProperty("is_auxiliary", true)
        extraJson.addProperty("is_feed_response", true)

        return UniversalRecord(

            contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
            platform = "zhihu",
            recordType = "question",
            sourceApp = raw.hostApp,
            platformId = "zhihu_question_$questionId",
            title = title,
            content = "",
            authorId = "",
            authorName = "",
            authorAvatar = "",
            sourceUrl = raw.url,
            publishTime = raw.timestamp,
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extraJson)
        )
    }
}
