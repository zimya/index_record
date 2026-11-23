package com.melonhu.indexrecord.parser.dcard

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.RawDataParser
import java.net.URI
import java.time.Instant

/**
 * Dcard 文章与留言存档。
 */
object DCardParser : RawDataParser {
    override val platform = "dcard"
    const val HOST_APP = "com.dcard.app"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val host = URI(cleanUrl).host.orEmpty().lowercase()
        (host == "dcard.tw" || host.endsWith(".dcard.tw")) &&
            (URI(cleanUrl).path.orEmpty().contains("/api/"))
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Unsupported Dcard endpoint")
        val root = JsonParser.parseString(raw.json)
        val comments = cleanUrl.contains("/comments")
        val nodes = when {
            root.isJsonArray -> root.asJsonArray.map { it }
            root.isJsonObject && root.asJsonObject.has("comments") -> root.asJsonObject.array("comments").map { it }
            root.isJsonObject -> listOf(root)
            else -> emptyList()
        }
        val records = linkedMapOf<String, UniversalRecord>()
        nodes.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val obj = el.asJsonObject
            val record = if (comments) commentRecord(obj, raw, cleanUrl) else postRecord(obj, raw, cleanUrl)
            if (record != null) records[record.platformId] = record
        }
        ParseResult.decoded(records.values.toList(), emptyList())
    }

    private fun postRecord(node: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val id = node.str("id").ifBlank { urlId(cleanUrl) }
        if (id.isEmpty()) return null
        val topics = node.array("topics").mapNotNull {
            if (it.isJsonObject) it.asJsonObject.str("name").takeIf { name -> name.isNotBlank() } else null
        }
        val meta = JsonObject().apply {
            node["likeCount"]?.takeIf { it.isJsonPrimitive }?.let { add("likeCount", it) }
            node["commentCount"]?.takeIf { it.isJsonPrimitive }?.let { add("replyCount", it) }
            node.str("school")?.takeIf { it.isNotBlank() }?.let { addProperty("school", it) }
            node.str("department")?.takeIf { it.isNotBlank() }?.let { addProperty("department", it) }
            if (topics.isNotEmpty()) add("topics", JsonArray().apply { topics.forEach { name -> add(name) } })
        }
        val media = node.array("media").mapNotNull {
            if (it.isJsonObject) it.asJsonObject.str("url").takeIf { url -> url.isNotBlank() } else null
        }
        val body = buildString {
            val content = node.str("content")
            if (content.isNotBlank()) append(content)
            media.forEach { append("\n\n<img src=\"").append(it).append("\">") }
        }
        return UniversalRecord(
            platform = platform,
            recordType = "post",
            sourceApp = raw.hostApp,
            platformId = "dcard_post_$id",
            title = node.str("title"),
            content = body.trim(),
            contentFormat = RecordBodyFormat.HTML.storageValue,
            authorName = node.str("anonymousSchool").ifBlank { node.str("school") },
            sourceUrl = "https://www.dcard.tw/f/all/p/$id",
            publishTime = parseTime(node.str("createdAt").ifBlank { node.str("created_at") }),
            captureTime = raw.timestamp,
            extraMetadata = meta.toString()
        )
    }

    private fun commentRecord(node: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val id = node.str("id")
        if (id.isEmpty()) return null
        val postId = urlId(cleanUrl)
        val meta = JsonObject().apply {
            node["likeCount"]?.takeIf { it.isJsonPrimitive }?.let { add("likeCount", it) }
            node["floor"]?.takeIf { it.isJsonPrimitive }?.let { add("floor", it) }
        }
        return UniversalRecord(
            platform = platform,
            recordType = "comment",
            sourceApp = raw.hostApp,
            platformId = "dcard_comment_$id",
            parentId = if (postId.isNotEmpty()) "dcard_post_$postId" else "",
            content = node.str("content"),
            contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
            authorName = node.str("anonymousSchool").ifBlank { node.str("school") },
            publishTime = parseTime(node.str("createdAt").ifBlank { node.str("created_at") }),
            captureTime = raw.timestamp,
            extraMetadata = meta.toString()
        )
    }

    private fun urlId(url: String): String =
        Regex("""/posts/([0-9A-Za-z_-]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/p/([0-9A-Za-z_-]+)""").find(url)?.groupValues?.get(1)
            ?: ""

    private fun parseTime(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
