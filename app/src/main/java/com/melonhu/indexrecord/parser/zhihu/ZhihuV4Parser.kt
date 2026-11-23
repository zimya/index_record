package com.melonhu.indexrecord.parser.zhihu

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.markdown.zhihuHtmlToMarkdown

/**
 * 知乎网页端 v4 API 解析器（www.zhihu.com/api/v4/...）。
 *
 * 用于知乎++ 等第三方客户端：回答、文章、问题、想法的 JSON 结构与官方 Android
 * 客户端的卡片结构不同，正文是 HTML，需要单独转换。
 */
object ZhihuV4Parser {

    private val ANSWER = Regex("""https?://(?:www\.)?zhihu\.com/api/v4/answers/(\d+)""")
    private val ARTICLE = Regex("""https?://(?:www\.)?zhihu\.com/api/v4/articles/(\d+)""")
    private val QUESTION = Regex("""https?://(?:www\.)?zhihu\.com/api/v4/questions/(\d+)(?:\?|$)""")
    private val QUESTION_FEEDS = Regex("""https?://(?:www\.)?zhihu\.com/api/v4/questions/(\d+)/feeds""")
    private val PIN = Regex("""https?://(?:www\.)?zhihu\.com/api/v4/pins/(\d+)""")

    fun canHandle(url: String): Boolean =
        ANSWER.containsMatchIn(url) || ARTICLE.containsMatchIn(url) ||
            QUESTION.containsMatchIn(url) || PIN.containsMatchIn(url) ||
            QUESTION_FEEDS.containsMatchIn(url)

    fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val root = JsonParser.parseString(raw.json)
        val obj = when {
            root.isJsonObject -> root.asJsonObject
            root.isJsonArray -> root.asJsonArray.firstOrNull { it.isJsonObject }?.asJsonObject
            else -> null
        } ?: return emptyList()
        if (QUESTION_FEEDS.containsMatchIn(cleanUrl)) {
            return answerFeeds(obj, raw, cleanUrl)
        }
        val record = when {
            ANSWER.containsMatchIn(cleanUrl) -> answer(obj, raw, cleanUrl)
            ARTICLE.containsMatchIn(cleanUrl) -> article(obj, raw, cleanUrl)
            QUESTION.containsMatchIn(cleanUrl) -> question(obj, raw, cleanUrl)
            PIN.containsMatchIn(cleanUrl) -> pin(obj, raw, cleanUrl)
            else -> null
        }
        return listOfNotNull(record)
    }

    private fun answer(obj: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val id = idOf(obj, ANSWER, cleanUrl) ?: return null
        val author = obj.obj("author")
        val question = obj.obj("question")
        val questionId = question?.str("id").orEmpty()
        val content = zhihuHtmlToMarkdown(obj.str("content")).ifBlank { obj.str("excerpt") }
        return UniversalRecord(
            contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
            platform = "zhihu",
            recordType = "answer",
            sourceApp = raw.hostApp,
            platformId = "zhihu_answer_$id",
            parentId = if (questionId.isNotEmpty()) "zhihu_question_$questionId" else "",
            title = question?.str("title").orEmpty(),
            content = content,
            authorId = author?.str("id").orEmpty(),
            authorName = author?.str("name").orEmpty().ifBlank { "知乎用户" },
            authorAvatar = author?.str("avatar_url").orEmpty(),
            sourceUrl = if (questionId.isNotEmpty()) {
                "https://www.zhihu.com/question/$questionId/answer/$id"
            } else cleanUrl,
            publishTime = seconds(obj.str("created_time")),
            captureTime = raw.timestamp,
            extraMetadata = meta(obj, "answer", author, cleanUrl)
        )
    }

    /** 问题页的回答流：data[].target 才是回答本体。 */
    private fun answerFeeds(root: JsonObject, raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val records = linkedMapOf<String, UniversalRecord>()
        root.array("data").forEach { element ->
            if (!element.isJsonObject) return@forEach
            val item = element.asJsonObject
            val type = item.str("type")
            if (type.isNotBlank() && type != "answer") return@forEach
            val target = item.obj("target") ?: item
            answer(target, raw, cleanUrl)?.let { records[it.platformId] = it }
        }
        return records.values.toList()
    }

    private fun article(obj: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val id = idOf(obj, ARTICLE, cleanUrl) ?: return null
        val author = obj.obj("author")
        return UniversalRecord(
            contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
            platform = "zhihu",
            recordType = "article",
            sourceApp = raw.hostApp,
            platformId = "zhihu_article_$id",
            title = obj.str("title").ifBlank { "知乎文章 $id" },
            content = zhihuHtmlToMarkdown(obj.str("content")),
            authorId = author?.str("id").orEmpty(),
            authorName = author?.str("name").orEmpty().ifBlank { "知乎用户" },
            authorAvatar = author?.str("avatar_url").orEmpty(),
            sourceUrl = "https://zhuanlan.zhihu.com/p/$id",
            publishTime = seconds(obj.str("created")),
            captureTime = raw.timestamp,
            extraMetadata = meta(obj, "article", author, cleanUrl)
        )
    }

    private fun question(obj: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val id = idOf(obj, QUESTION, cleanUrl) ?: return null
        return UniversalRecord(
            contentFormat = RecordBodyFormat.MARKDOWN_MATH.storageValue,
            platform = "zhihu",
            recordType = "question",
            sourceApp = raw.hostApp,
            platformId = "zhihu_question_$id",
            title = obj.str("title"),
            content = zhihuHtmlToMarkdown(obj.str("detail")),
            sourceUrl = "https://www.zhihu.com/question/$id",
            publishTime = seconds(obj.str("created")),
            captureTime = raw.timestamp,
            extraMetadata = meta(obj, "question", null, cleanUrl)
        )
    }

    private fun pin(obj: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val id = idOf(obj, PIN, cleanUrl) ?: return null
        val author = obj.obj("author")
        return UniversalRecord(
            contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
            platform = "zhihu",
            recordType = "pin",
            sourceApp = raw.hostApp,
            platformId = "zhihu_pin_$id",
            content = pinBody(obj.array("content")),
            authorId = author?.str("id").orEmpty(),
            authorName = author?.str("name").orEmpty().ifBlank { "知乎用户" },
            authorAvatar = author?.str("avatar_url").orEmpty(),
            sourceUrl = "https://www.zhihu.com/pin/$id",
            publishTime = seconds(obj.str("created")),
            captureTime = raw.timestamp,
            extraMetadata = meta(obj, "pin", author, cleanUrl)
        )
    }

    private fun pinBody(content: JsonArray): String {
        val blocks = mutableListOf<String>()
        content.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val block = element.asJsonObject
            when (block.str("type")) {
                "text" -> block.str("content").trim().takeIf { it.isNotEmpty() }?.let(blocks::add)
                "image" -> block.obj("urls")?.str("original")?.takeIf { it.isNotEmpty() }
                    ?.let { blocks.add("![]($it)") }
                "link" -> {
                    val url = block.str("url")
                    val title = block.str("title").ifBlank { url }
                    if (url.isNotEmpty()) blocks.add("[$title]($url)")
                }
                "video" -> block.str("url").takeIf { it.isNotEmpty() }?.let { blocks.add("[视频]($it)") }
            }
        }
        return blocks.joinToString("\n\n")
    }

    private fun meta(
        obj: JsonObject,
        type: String,
        author: JsonObject?,
        cleanUrl: String
    ): String = JsonObject().apply {
        addProperty("parse_mode", "web_v4")
        addProperty("record_type", type)
        listOf("voteup_count", "comment_count", "follower_count", "answer_count", "like_count", "repin_count")
            .forEach { key ->
                obj[key]?.takeIf { it.isJsonPrimitive }?.let { add(key, it) }
            }
        author?.str("headline")?.takeIf { it.isNotBlank() }?.let { addProperty("author_headline", it) }
        if (cleanUrl.contains("include=")) addProperty("request_url", cleanUrl)
    }.toString()

    private fun idOf(obj: JsonObject, pattern: Regex, cleanUrl: String): String? =
        obj.str("id").ifBlank { pattern.find(cleanUrl)?.groupValues?.get(1).orEmpty() }
            .takeIf { it.isNotEmpty() }

    private fun seconds(value: String): Long {
        val number = value.toLongOrNull() ?: return 0L
        return if (number > 10_000_000_000L) number else number * 1000L
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
