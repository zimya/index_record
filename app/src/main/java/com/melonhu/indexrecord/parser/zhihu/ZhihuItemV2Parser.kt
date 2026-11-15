package com.melonhu.indexrecord.parser.zhihu

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.markdown.zhihuStructuredContentToMarkdown
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 知乎卡片模式（v2）通用条目解析器。
 * 负责解析回答与文章卡片数据，提取统一的作者、统计、发布时间与正文 Markdown。
 */
object ZhihuItemV2Parser {

    private val gson = Gson()

    fun parse(
        itemObj: JsonObject,
        raw: RawData,
        defaultType: String? = null
    ): UniversalRecord? {
        val rawType = itemObj.safeString("type")
        val effectiveType = rawType.ifBlank { defaultType ?: (if (itemObj.has("question")) "answer" else "article") }
        val id = itemObj.safeString("id")
        if (id.isBlank()) return null

        val structuredContent = itemObj.safeObject("structured_content")
        var content = zhihuStructuredContentToMarkdown(structuredContent)
            .ifBlank { itemObj.safeString("excerpt") }

        val imageListObj = itemObj.safeObject("image_list")
        val images = imageListObj?.safeArray("images")
        if (images != null && images.size() > 0) {
            val imageMarkdown = images.mapNotNull { imgElem ->
                val imgObj = if (imgElem.isJsonObject) imgElem.asJsonObject else null
                val imgUrl = imgObj?.safeString("original_url")?.ifBlank { imgObj.safeString("url") }
                if (!imgUrl.isNullOrBlank()) "![]($imgUrl)" else null
            }.joinToString("\n\n")

            if (imageMarkdown.isNotBlank()) {
                content = if (content.isBlank() || content == "[图片]") {
                    imageMarkdown
                } else {
                    "$content\n\n$imageMarkdown"
                }
            }
        }

        val headerObj = itemObj.safeObject("header")
        val authorObj = itemObj.safeObject("author")
        val authorName = authorObj?.safeString("fullname")?.ifBlank { authorObj.safeString("name") } ?: "知乎用户"
        val authorId = authorObj?.safeString("id") ?: ""
        val authorAvatar = authorObj?.safeString("avatar_url")
            ?.ifBlank { authorObj.safeString("avatarUrl") }
            ?.ifBlank { authorObj.safeObject("avatar")?.safeObject("avatar_image")?.safeString("day") }
            ?: ""
        val authorHeadline = authorObj?.safeString("headline")
            ?.ifBlank { authorObj.safeString("description") }
            ?: ""

        val contentEndInfo = itemObj.safeObject("content_end_info")
        val createTimeText = contentEndInfo?.safeString("create_time_text") ?: ""
        val updateTimeText = contentEndInfo?.safeString("update_time_text") ?: ""
        val ipInfo = contentEndInfo?.safeString("ip_info") ?: ""
        val parsedCreated = parseZhihuDate(createTimeText)
        val parsedUpdated = parseZhihuDate(updateTimeText)
        val publishTime = parsedUpdated ?: parsedCreated ?: raw.timestamp

        val extraJson = JsonObject()
        extraJson.addProperty("parse_mode", "card_mode_v2")
        if (ipInfo.isNotBlank()) extraJson.addProperty("ip_info", ipInfo)
        if (createTimeText.isNotBlank()) extraJson.addProperty("create_time_text", createTimeText)
        if (updateTimeText.isNotBlank()) extraJson.addProperty("update_time_text", updateTimeText)
        if (authorHeadline.isNotBlank()) extraJson.addProperty("author_headline", authorHeadline)
        if (itemObj.has("topics") && !itemObj.get("topics").isJsonNull) extraJson.add("topics", itemObj.get("topics"))

        val reactionObj = itemObj.safeObject("reaction")
        val statsObj = reactionObj?.safeObject("statistics")
        if (statsObj != null) {
            if (statsObj.has("up_vote_count") && !statsObj.get("up_vote_count").isJsonNull) extraJson.add("voteup_count", statsObj.get("up_vote_count"))
            if (statsObj.has("comment_count") && !statsObj.get("comment_count").isJsonNull) extraJson.add("comment_count", statsObj.get("comment_count"))
            if (statsObj.has("favorites") && !statsObj.get("favorites").isJsonNull) extraJson.add("favlists_count", statsObj.get("favorites"))
        }

        if (effectiveType == "pin") {
            val title = headerObj?.safeString("text").orEmpty()
                .ifBlank { itemObj.safeString("title") }
            return UniversalRecord(
                contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
                platform = "zhihu",
                recordType = "pin",
                sourceApp = raw.hostApp,
                platformId = "zhihu_pin_$id",
                title = title,
                content = content,
                authorId = authorId,
                authorName = authorName,
                authorAvatar = authorAvatar,
                sourceUrl = "https://www.zhihu.com/pin/$id",
                publishTime = publishTime,
                captureTime = raw.timestamp,
                extraMetadata = gson.toJson(extraJson)
            )
        }

        if (effectiveType == "article") {
            val title = headerObj?.safeString("text")?.ifBlank { itemObj.safeString("title") } ?: "知乎文章 $id"
            val sourceUrl = if (raw.url.contains("next-render")) "https://zhuanlan.zhihu.com/p/$id" else raw.url

            return UniversalRecord(

                contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
                platform = "zhihu",
                recordType = "article",
                sourceApp = raw.hostApp,
                platformId = "zhihu_article_$id",
                title = title,
                content = content,
                authorId = authorId,
                authorName = authorName,
                authorAvatar = authorAvatar,
                sourceUrl = sourceUrl,
                parentId = "",
                publishTime = publishTime,
                captureTime = raw.timestamp,
                extraMetadata = gson.toJson(extraJson)
            )
        } else {
            val questionObj = itemObj.safeObject("question")
            val questionId = questionObj?.safeString("id") ?: ""
            val questionTitle = questionObj?.safeString("title")
                ?.ifBlank { headerObj?.safeString("text") }
                ?: (headerObj?.safeString("text") ?: "知乎回答 $id")

            if (questionId.isNotBlank()) extraJson.addProperty("question_id", questionId)

            val sourceUrl = if (raw.url.contains("next-render") && questionId.isNotBlank()) {
                "https://www.zhihu.com/question/$questionId/answer/$id"
            } else {
                raw.url
            }

            return UniversalRecord(

                contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
                platform = "zhihu",
                recordType = "answer",
                sourceApp = raw.hostApp,
                platformId = "zhihu_answer_$id",
                title = questionTitle,
                content = content,
                authorId = authorId,
                authorName = authorName,
                authorAvatar = authorAvatar,
                sourceUrl = sourceUrl,
                parentId = "",
                publishTime = publishTime,
                captureTime = raw.timestamp,
                extraMetadata = gson.toJson(extraJson)
            )
        }
    }

    fun parseZhihuDate(dateStr: String): Long? {
        if (dateStr.isBlank()) return null
        val cleaned = dateStr.replace("编辑于", "").trim()
        if (cleaned.isBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            sdf.timeZone = TimeZone.getTimeZone("GMT+8")
            sdf.parse(cleaned)?.time
        } catch (_: Exception) {
            null
        }
    }

    internal fun JsonObject.safeString(key: String): String {
        return if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asString else ""
    }

    internal fun JsonObject.safeObject(key: String): JsonObject? {
        val element = this.get(key)
        return if (element != null && element.isJsonObject) element.asJsonObject else null
    }

    internal fun JsonObject.safeArray(key: String): JsonArray? {
        val element = this.get(key)
        return if (element != null && element.isJsonArray) element.asJsonArray else null
    }
}
