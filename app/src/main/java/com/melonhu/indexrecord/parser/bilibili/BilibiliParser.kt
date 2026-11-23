package com.melonhu.indexrecord.parser.bilibili

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.RawDataParser
import java.net.URI

/**
 * 哔哩哔哩图文（专栏 / 动态 opus）与评论存档。
 */
object BilibiliParser : RawDataParser {
    override val platform = "bilibili"
    const val HOST_APP = "tv.danmaku.bili"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val host = URI(cleanUrl).host.orEmpty().lowercase()
        val path = URI(cleanUrl).path.orEmpty()
        (host == "api.bilibili.com" || host.endsWith(".bilibili.com")) && (
            path.contains("/x/article/view") ||
                path.contains("/x/polymer/web-dynamic/v1/opus") ||
                path.contains("/x/v2/reply")
            )
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Unsupported Bilibili endpoint")
        val root = JsonParser.parseString(raw.json).asJsonObject
        val records = linkedMapOf<String, UniversalRecord>()

        root["data"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { data ->
            if (cleanUrl.contains("/x/v2/reply")) {
                parseReplies(data, raw, cleanUrl, records)
            } else if (cleanUrl.contains("/opus")) {
                opusRecord(data, raw)?.let { records[it.platformId] = it }
            } else {
                articleRecord(data, raw, cleanUrl)?.let { records[it.platformId] = it }
            }
        }

        ParseResult.decoded(records.values.toList(), emptyList())
    }

    private fun articleRecord(data: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val id = data.str("id").ifBlank { queryParam(cleanUrl, "id") }
        if (id.isEmpty()) return null
        val stats = data.obj("stats")
        val meta = JsonObject().apply {
            stats?.str("view")?.takeIf { it.isNotBlank() }?.let { addProperty("viewCount", it) }
            stats?.str("like")?.takeIf { it.isNotBlank() }?.let { addProperty("likeCount", it) }
            stats?.str("reply")?.takeIf { it.isNotBlank() }?.let { addProperty("replyCount", it) }
            stats?.str("coin")?.takeIf { it.isNotBlank() }?.let { addProperty("coinCount", it) }
            stats?.str("favorite")?.takeIf { it.isNotBlank() }?.let { addProperty("favoriteCount", it) }
            stats?.str("share")?.takeIf { it.isNotBlank() }?.let { addProperty("shareCount", it) }
        }
        return UniversalRecord(
            platform = platform,
            recordType = "article",
            sourceApp = raw.hostApp,
            platformId = "bili_article_$id",
            title = data.str("title"),
            content = data.str("content"),
            contentFormat = RecordBodyFormat.HTML.storageValue,
            authorId = data.str("mid").ifBlank { data.str("author_id") },
            authorName = data.str("author_name").ifBlank { data.str("author") },
            authorAvatar = data.str("author_face").ifBlank { data.str("face") },
            sourceUrl = "https://www.bilibili.com/read/cv$id",
            publishTime = epochSeconds(data.str("publish_time").ifBlank { data.str("ctime") }),
            captureTime = raw.timestamp,
            extraMetadata = meta.toString()
        )
    }

    private fun opusRecord(data: JsonObject, raw: RawData): UniversalRecord? {
        val item = data.obj("item") ?: return null
        val id = item.str("id")
        if (id.isEmpty()) return null
        val modules = item.obj("modules")
        val author = modules?.obj("module_author")
        val content = modules?.obj("module_content")
        val stat = modules?.obj("module_stat")
        val body = StringBuilder()
        content?.array("paragraphs")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val paragraph = el.asJsonObject
            paragraph.obj("text")?.array("nodes")?.forEach { nodeEl ->
                if (!nodeEl.isJsonObject) return@forEach
                val node = nodeEl.asJsonObject
                node.obj("word")?.str("words")?.let { body.append(it) }
                node.obj("rich")?.obj("text")?.str("words")?.let { body.append(it) }
            }
            paragraph.obj("pic")?.array("pics")?.forEach { picEl ->
                if (!picEl.isJsonObject) return@forEach
                val url = picEl.asJsonObject.str("url")
                if (url.isNotEmpty()) body.append("\n\n<img src=\"").append(url).append("\">")
            }
            body.append('\n')
        }
        val meta = JsonObject().apply {
            stat?.obj("like")?.str("count")?.takeIf { it.isNotBlank() }?.let { addProperty("likeCount", it) }
            stat?.obj("comment")?.str("count")?.takeIf { it.isNotBlank() }?.let { addProperty("replyCount", it) }
            stat?.obj("forward")?.str("count")?.takeIf { it.isNotBlank() }?.let { addProperty("shareCount", it) }
        }
        return UniversalRecord(
            platform = platform,
            recordType = "opus",
            sourceApp = raw.hostApp,
            platformId = "bili_opus_$id",
            content = body.toString().trim(),
            contentFormat = RecordBodyFormat.HTML.storageValue,
            authorId = author?.str("mid").orEmpty(),
            authorName = author?.str("name").orEmpty(),
            authorAvatar = author?.str("face").orEmpty(),
            sourceUrl = "https://www.bilibili.com/opus/$id",
            publishTime = epochSeconds(author?.str("pub_ts").orEmpty()),
            captureTime = raw.timestamp,
            extraMetadata = meta.toString()
        )
    }

    private fun parseReplies(
        data: JsonObject,
        raw: RawData,
        cleanUrl: String,
        records: MutableMap<String, UniversalRecord>
    ) {
        val oid = queryParam(cleanUrl, "oid")
        val replyType = queryParam(cleanUrl, "type")
        val rootId = if (replyType == "12") "bili_article_$oid" else "bili_opus_$oid"
        data.array("replies").forEach { el ->
            if (!el.isJsonObject) return@forEach
            val reply = el.asJsonObject
            val rpid = reply.str("rpid")
            if (rpid.isEmpty()) return@forEach
            val parent = reply.str("parent").ifBlank { reply.str("root") }
            val parentId = if (parent.isNotBlank() && parent != "0") "bili_reply_$parent" else rootId
            val member = reply.obj("member")
            val meta = JsonObject().apply {
                reply["like"]?.takeIf { it.isJsonPrimitive }?.let { add("likeCount", it) }
                reply["rcount"]?.takeIf { it.isJsonPrimitive }?.let { add("replyCount", it) }
            }
            val record = UniversalRecord(
                platform = platform,
                recordType = "comment",
                sourceApp = raw.hostApp,
                platformId = "bili_reply_$rpid",
                parentId = parentId,
                content = reply.obj("content")?.str("message").orEmpty(),
                contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
                authorId = member?.str("mid").orEmpty(),
                authorName = member?.str("uname").orEmpty(),
                authorAvatar = member?.str("avatar").orEmpty(),
                publishTime = epochSeconds(reply.str("ctime")),
                captureTime = raw.timestamp,
                extraMetadata = meta.toString()
            )
            records[record.platformId] = record
        }
    }

    private fun queryParam(url: String, key: String): String =
        runCatching {
            URI(url).query.orEmpty().split('&').firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=').orEmpty()
        }.getOrDefault("")

    private fun epochSeconds(value: String): Long {
        val seconds = value.toLongOrNull() ?: return 0L
        return if (seconds > 10_000_000_000L) seconds else seconds * 1000L
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
