package me.melon.record.parser.zhihu

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.data.model.RecordBodyFormat
import me.melon.record.data.model.UniversalRecord
import me.melon.record.markdown.zhihuHtmlToMarkdown
import me.melon.record.parser.zhihu.ZhihuItemV2Parser.safeString
import org.jsoup.Jsoup
import java.util.regex.Pattern

/**
 * 知乎回答解析器。
 * 支持 v1 HTML 页面回答与 v2 卡片模式回答。
 */
object ZhihuAnswerParser {

    private val PATTERN_HTML_ANSWER = Pattern.compile("https?://www\\.zhihu\\.com/appview/v2/answer/[^/]+/?")
    private val PATTERN_ZHIHU_ANSWER_V2 = Pattern.compile("https?://api\\.zhihu\\.com/answers/v2/([^/]+)/?")

    private val gson = Gson()

    fun canHandle(cleanUrl: String): Boolean {
        return PATTERN_ZHIHU_ANSWER_V2.matcher(cleanUrl).matches() ||
                PATTERN_HTML_ANSWER.matcher(cleanUrl).matches()
    }

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        if (PATTERN_ZHIHU_ANSWER_V2.matcher(cleanUrl).matches()) {
            val jsonParams = JsonParser.parseString(raw.json).asJsonObject
            val record = ZhihuItemV2Parser.parse(jsonParams, raw, defaultType = "answer")
            return listOfNotNull(record)
        }

        return listOf(parseHtmlAnswerV1(raw, cleanUrl))
    }

    private fun parseHtmlAnswerV1(raw: RawData, cleanUrl: String): UniversalRecord {
        val answerId = cleanUrl.trimEnd('/').substringAfterLast('/')
        val doc = Jsoup.parse(raw.json)
        val scriptTag = doc.select("script#js-initialData[type=\"text/json\"]").first()
            ?: throw Exception("script#js-initialData tag NOT found in HTML!")

        val fullJson = JsonParser.parseString(scriptTag.html()).asJsonObject
        val answerData = fullJson
            .getAsJsonObject("initialState")
            ?.getAsJsonObject("answers")
            ?.getAsJsonObject(answerId)
            ?: throw Exception("Answer data not found for id: $answerId")

        val rawContent = answerData.safeString("content")
        val content = zhihuHtmlToMarkdown(rawContent)

        val initialState = fullJson.getAsJsonObject("initialState")

        var authorName = "知乎用户"
        var authorId = ""
        var authorAvatar = ""
        var authorHeadline = ""

        if (answerData.has("author") && answerData.get("author").isJsonObject) {
            val authorObj = answerData.getAsJsonObject("author")
            authorName = authorObj.safeString("name")
            authorId = authorObj.safeString("id")
            authorAvatar = authorObj.safeString("avatarUrl").ifBlank { authorObj.safeString("avatar_url") }
            authorHeadline = authorObj.safeString("headline")
        } else {
            val authorToken = answerData.safeString("author")
            if (authorToken.isNotBlank()) {
                val userMap = initialState?.getAsJsonObject("user")
                val userObj = userMap?.getAsJsonObject(authorToken)
                if (userObj != null) {
                    authorName = userObj.safeString("name").ifBlank { authorToken }
                    authorId = userObj.safeString("id")
                    authorAvatar = userObj.safeString("avatarUrl").ifBlank { userObj.safeString("avatar_url") }
                    authorHeadline = userObj.safeString("headline")
                } else {
                    authorName = authorToken
                }
            }
        }

        val questionData = answerData.getAsJsonObject("question")
        val questionTitle = questionData?.safeString("title") ?: "知乎回答 $answerId"
        val questionId = questionData?.safeString("id") ?: ""

        val extraJson = JsonObject()
        extraJson.addProperty("parse_mode", "html_script")

        val fieldsToExtract = listOf("voteupCount", "commentCount", "updatedTime", "createdTime", "ipInfo")
        for (field in fieldsToExtract) {
            if (answerData.has(field)) {
                val value = answerData.get(field)
                val targetField = when(field) {
                    "voteupCount" -> "voteup_count"
                    "commentCount" -> "comment_count"
                    "updatedTime" -> "updated"
                    "createdTime" -> "created"
                    "ipInfo" -> "ip_info"
                    else -> field
                }
                extraJson.add(targetField, value)
            }
        }
        if (authorHeadline.isNotBlank()) extraJson.addProperty("author_headline", authorHeadline)
        if (questionId.isNotBlank()) extraJson.addProperty("question_id", questionId)

        val createdTime = if (answerData.has("createdTime")) answerData.get("createdTime").asLong * 1000 else raw.timestamp
        val updatedTime = if (answerData.has("updatedTime")) answerData.get("updatedTime").asLong * 1000 else createdTime
        return UniversalRecord(
            contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
            platform = "zhihu",
            recordType = "answer",
            sourceApp = raw.hostApp,
            platformId = "zhihu_answer_$answerId",
            title = questionTitle,
            content = content,
            authorId = authorId,
            authorName = authorName,
            authorAvatar = authorAvatar,
            sourceUrl = raw.url,
            parentId = "",
            publishTime = updatedTime,
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extraJson)
        )
    }
}
