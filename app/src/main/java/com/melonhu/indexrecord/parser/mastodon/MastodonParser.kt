package com.melonhu.indexrecord.parser.mastodon

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.RawDataParser
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Mastodon（长毛象）嘟文与回复存档。
 * 正文是实例返回的 HTML；CW（spoiler_text）保留在正文首行引用中。
 */
object MastodonParser : RawDataParser {
    override val platform = "mastodon"
    const val HOST_APP = "org.joinmastodon.android"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val path = URI(cleanUrl).path.orEmpty()
        path.contains("/api/v1/statuses/") || path.contains("/api/v1/timelines/") ||
            path.contains("/api/v1/accounts/") || path.contains("/api/v2/search") ||
            path.contains("/api/v1/search")
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Unsupported Mastodon endpoint")
        val root = JsonParser.parseString(raw.json)
        val records = linkedMapOf<String, UniversalRecord>()

        fun keep(node: JsonObject?) {
            val record = node?.let { statusRecord(it, raw) } ?: return
            records[record.platformId] = record
        }

        when {
            root.isJsonArray -> root.asJsonArray.forEach { if (it.isJsonObject) keep(it.asJsonObject) }
            root.isJsonObject -> {
                val obj = root.asJsonObject
                if (obj.has("id")) keep(obj)
                listOf("ancestors", "descendants").forEach { key ->
                    obj.array(key).forEach { if (it.isJsonObject) keep(it.asJsonObject) }
                }
                obj.array("statuses").forEach { if (it.isJsonObject) keep(it.asJsonObject) }
                obj.obj("reblog")?.let { keep(it) }
            }
        }

        ParseResult.decoded(records.values.toList(), emptyList())
    }

    private fun statusRecord(node: JsonObject, raw: RawData): UniversalRecord? {
        val id = node.str("id")
        if (id.isEmpty()) return null
        val account = node.obj("account")
        val parentId = node.str("in_reply_to_id")
        val media = node.array("media_attachments")
        val blocks = mutableListOf<String>()
        val spoiler = node.str("spoiler_text").trim()
        if (spoiler.isNotEmpty()) blocks.add("> CW: $spoiler")
        val html = node.str("content").trim()
        if (html.isNotEmpty()) blocks.add(html)
        media.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val item = el.asJsonObject
            val url = item.str("url").ifBlank { item.str("remote_url") }
            val description = item.str("description").replace("\n", " ").trim()
            if (url.isNotEmpty()) blocks.add("<img src=\"$url\" alt=\"$description\">")
        }

        val meta = JsonObject().apply {
            node["favourites_count"]?.takeIf { it.isJsonPrimitive }?.let { add("likeCount", it) }
            node["reblogs_count"]?.takeIf { it.isJsonPrimitive }?.let { add("repostCount", it) }
            node["replies_count"]?.takeIf { it.isJsonPrimitive }?.let { add("replyCount", it) }
            node.str("acct").takeIf { it.isNotBlank() }?.let { addProperty("acct", it) }
            node.str("visibility").takeIf { it.isNotBlank() }?.let { addProperty("visibility", it) }
        }

        return UniversalRecord(
            platform = platform,
            recordType = if (parentId.isNotEmpty()) "comment" else "post",
            sourceApp = raw.hostApp,
            platformId = "mastodon_$id",
            parentId = if (parentId.isNotEmpty()) "mastodon_$parentId" else "",
            content = blocks.joinToString("\n\n"),
            contentFormat = RecordBodyFormat.HTML.storageValue,
            authorId = account?.str("id").orEmpty(),
            authorName = account?.str("display_name").orEmpty().ifBlank { account?.str("acct").orEmpty() },
            authorAvatar = account?.str("avatar").orEmpty(),
            sourceUrl = node.str("url").ifBlank { node.str("uri") },
            publishTime = parseTime(node.str("created_at")),
            captureTime = raw.timestamp,
            extraMetadata = meta.toString()
        )
    }

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
