package com.melonhu.indexrecord.parser.lofter

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

/**
 * LOFTER 图文与评论存档。兼容网页 HTML 与接口 JSON。
 */
object LofterParser : RawDataParser {
    override val platform = "lofter"
    const val HOST_APP = "com.lofter.android"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val host = URI(cleanUrl).host.orEmpty().lowercase()
        host == "lofter.com" || host.endsWith(".lofter.com")
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Unsupported LOFTER endpoint")
        val trimmed = raw.json.trimStart()
        val records = if (trimmed.startsWith("{") || trimmed.startsWith("[")) fromJson(raw, cleanUrl)
            else fromHtml(raw, cleanUrl)
        ParseResult.Success(records)
    }

    private fun fromJson(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val root = JsonParser.parseString(raw.json)
        val post = when {
            root.isJsonObject -> findPost(root.asJsonObject)
            else -> null
        } ?: return emptyList()
        val record = postRecord(post, raw, cleanUrl) ?: return emptyList()
        return listOf(record)
    }

    private fun findPost(root: JsonObject): JsonObject? {
        root.obj("post")?.let { return it }
        root.obj("data")?.let { data ->
            data.obj("post")?.let { return it }
            data.obj("postView")?.let { return it }
        }
        return null
    }

    private fun postRecord(post: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val id = post.str("id").ifBlank { post.str("postId") }.ifBlank { urlId(cleanUrl) }
        if (id.isEmpty()) return null
        val body = post.str("content").ifBlank { post.str("body") }
        val blog = post.obj("blogInfo") ?: post.obj("blog")
        return UniversalRecord(
            platform = platform,
            recordType = "post",
            sourceApp = raw.hostApp,
            platformId = "lofter_post_$id",
            title = post.str("title"),
            content = body,
            contentFormat = RecordBodyFormat.HTML.storageValue,
            authorId = blog?.str("blogId").orEmpty().ifBlank { post.str("blogId") },
            authorName = blog?.str("blogNickName").orEmpty().ifBlank { post.str("blogNickName") },
            authorAvatar = blog?.str("bigAvaImg").orEmpty().ifBlank { blog?.str("avatar").orEmpty() },
            sourceUrl = post.str("permalink").ifBlank { "https://www.lofter.com/post/$id" },
            publishTime = epochMillis(post.str("publishTime").ifBlank { post.str("time") }),
            captureTime = raw.timestamp,
            extraMetadata = JsonObject().apply {
                post["likeCount"]?.takeIf { it.isJsonPrimitive }?.let { add("likeCount", it) }
                post["commentCount"]?.takeIf { it.isJsonPrimitive }?.let { add("replyCount", it) }
                post["favoriteCount"]?.takeIf { it.isJsonPrimitive }?.let { add("favoriteCount", it) }
            }.toString()
        )
    }

    private fun fromHtml(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val doc = Jsoup.parse(raw.json, cleanUrl)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            .ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
        val container = doc.selectFirst("div.post") ?: doc.selectFirst("article") ?: doc.body()
        val body = container?.html().orEmpty()
        if (title.isEmpty() && body.isEmpty()) return emptyList()
        val id = urlId(cleanUrl)
        if (id.isEmpty()) return emptyList()
        return listOf(
            UniversalRecord(
                platform = platform,
                recordType = "post",
                sourceApp = raw.hostApp,
                platformId = "lofter_post_$id",
                title = title,
                content = body,
                contentFormat = RecordBodyFormat.HTML.storageValue,
                authorName = doc.selectFirst("meta[property=og:site_name]")?.attr("content").orEmpty()
                    .ifBlank { doc.selectFirst("a.blog-name")?.text().orEmpty() },
                authorAvatar = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty(),
                sourceUrl = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty().ifBlank { cleanUrl },
                publishTime = parseTime(
                    doc.selectFirst("meta[property=article:published_time]")?.attr("content").orEmpty()
                ),
                captureTime = raw.timestamp
            )
        )
    }

    private fun urlId(url: String): String =
        Regex("""/post/([0-9A-Za-z_]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/lof/.*?/(\d+)""").find(url)?.groupValues?.get(1)
            ?: ""

    private fun epochMillis(value: String): Long {
        val number = value.toLongOrNull() ?: return parseTime(value)
        return if (number > 10_000_000_000L) number else number * 1000L
    }

    private fun parseTime(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
