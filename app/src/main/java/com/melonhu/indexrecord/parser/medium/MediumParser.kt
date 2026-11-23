package com.melonhu.indexrecord.parser.medium

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.RawDataParser
import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Medium 文章存档。兼容 GraphQL JSON 与阅读页 HTML 两种响应。
 */
object MediumParser : RawDataParser {
    override val platform = "medium"
    const val HOST_APP = "com.medium.reader"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val host = URI(cleanUrl).host.orEmpty().lowercase()
        host == "medium.com" || host.endsWith(".medium.com")
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Unsupported Medium endpoint")
        val record = (if (raw.json.trimStart().startsWith("{")) fromJson(raw, cleanUrl) else fromHtml(raw, cleanUrl))
            ?: return@attempt ParseResult.Success(emptyList())
        ParseResult.Success(listOf(record))
    }

    private fun fromJson(raw: RawData, cleanUrl: String): UniversalRecord? {
        val root = JsonParser.parseString(raw.json).asJsonObject
        val post = findPost(root) ?: return null
        val paragraphs = post.obj("content")?.obj("bodyModel")?.array("paragraphs") ?: JsonArray()
        val body = StringBuilder()
        paragraphs.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val paragraph = el.asJsonObject
            val text = paragraph.str("text")
            if (text.isNotBlank()) {
                body.append(text)
                body.append('\n')
            }
            paragraph.obj("metadata")?.str("src")?.takeIf { it.isNotBlank() }?.let {
                body.append("\n<img src=\"").append(it).append("\">\n")
            }
        }
        val postId = post.str("id").ifBlank { post.str("postId") }.ifBlank { urlId(cleanUrl) }
        if (postId.isEmpty()) return null
        val creator = post.obj("creator")
        return UniversalRecord(
            platform = platform,
            recordType = "article",
            sourceApp = raw.hostApp,
            platformId = "medium_$postId",
            title = post.str("title"),
            content = body.toString().trim(),
            contentFormat = RecordBodyFormat.HTML.storageValue,
            authorId = creator?.str("id").orEmpty().ifBlank { creator?.str("username").orEmpty() },
            authorName = creator?.str("name").orEmpty().ifBlank { creator?.str("username").orEmpty() },
            authorAvatar = creator?.str("imageId").orEmpty(),
            sourceUrl = post.str("mediumUrl").ifBlank { cleanUrl },
            publishTime = epochMillis(post.str("firstPublishedAt")),
            captureTime = raw.timestamp
        )
    }

    private fun fromHtml(raw: RawData, cleanUrl: String): UniversalRecord? {
        val doc = Jsoup.parse(raw.json, cleanUrl)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            .ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
        val container = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        val body = container?.html().orEmpty()
        if (title.isEmpty() && body.isEmpty()) return null
        val postId = urlId(cleanUrl)
        if (postId.isEmpty()) return null
        val author = doc.selectFirst("meta[name=author]")?.attr("content").orEmpty()
            .ifBlank { doc.selectFirst("a[rel=author]")?.text().orEmpty() }
        val published = doc.selectFirst("meta[property=article:published_time]")?.attr("content").orEmpty()
        return UniversalRecord(
            platform = platform,
            recordType = "article",
            sourceApp = raw.hostApp,
            platformId = "medium_$postId",
            title = title,
            content = body,
            contentFormat = RecordBodyFormat.HTML.storageValue,
            authorName = author,
            authorAvatar = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty(),
            sourceUrl = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty().ifBlank { cleanUrl },
            publishTime = parseTime(published),
            captureTime = raw.timestamp
        )
    }

    private fun findPost(root: JsonObject): JsonObject? {
        root.obj("post")?.let { return it }
        root.obj("data")?.let { data ->
            data.obj("post")?.let { return it }
            data.obj("page")?.obj("post")?.let { return it }
        }
        return null
    }

    private fun urlId(url: String): String =
        Regex("""/p/([0-9A-Za-z]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""-([0-9a-f]{8,})$""").find(url.substringBefore('?'))?.groupValues?.get(1)
            ?: ""

    private fun epochMillis(value: String): Long = value.toLongOrNull() ?: parseTime(value)

    private fun parseTime(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
