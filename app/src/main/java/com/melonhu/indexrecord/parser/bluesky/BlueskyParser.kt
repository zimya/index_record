package com.melonhu.indexrecord.parser.bluesky

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

object BlueskyParser : RawDataParser {
    override val platform = "bluesky"
    const val HOST_APP = "xyz.blueskyweb.app"

    private val SUPPORTED_XRPC = setOf(
        "/xrpc/app.bsky.feed.getTimeline",
        "/xrpc/app.bsky.feed.getFeed",
        "/xrpc/app.bsky.feed.getAuthorFeed",
        "/xrpc/app.bsky.feed.getListFeed",
        "/xrpc/app.bsky.feed.getActorLikes",
        "/xrpc/app.bsky.feed.getPosts",
        "/xrpc/app.bsky.feed.searchPosts",
        "/xrpc/app.bsky.feed.getPostThread",
        "/xrpc/app.bsky.unspecced.getPostThreadV2"
    )

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val uri = URI(cleanUrl)
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        val trusted = raw.hostApp == HOST_APP ||
            host == "bsky.app" || host.endsWith(".bsky.app") ||
            host == "bsky.network" || host.endsWith(".bsky.network") ||
            host == "bsky.social" || host.endsWith(".bsky.social")
        trusted && path in SUPPORTED_XRPC
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Unsupported Bluesky endpoint")
        val root = JsonParser.parseString(raw.json).asJsonObject
        val records = linkedMapOf<String, UniversalRecord>()

        fun emit(post: JsonObject) {
            val uri = post.str("uri")
            val id = postId(uri)
            if (id.isEmpty()) return
            val recordObj = post.obj("record") ?: return
            val author = post.obj("author")
            val handle = author?.str("handle").orEmpty()
            val did = author?.str("did").orEmpty().ifEmpty { extractRepo(uri) }
            val replyObj = recordObj.obj("reply")
            val parentUri = replyObj?.obj("parent")?.str("uri").orEmpty()
            val parentId = postId(parentUri).takeUnless { it == id }.orEmpty()
            val rootUri = replyObj?.obj("root")?.str("uri").orEmpty()
            val rootPostId = postId(rootUri)

            val meta = JsonObject()
            mapOf(
                "likeCount" to "like_count",
                "replyCount" to "reply_count",
                "repostCount" to "repost_count",
                "quoteCount" to "quote_count",
                "bookmarkCount" to "bookmark_count"
            ).forEach { (srcKey, dstKey) ->
                post[srcKey]?.takeIf { it.isJsonPrimitive && it.asString.toLongOrNull() != null }
                    ?.let { meta.add(dstKey, it) }
            }
            if (handle.isNotBlank()) meta.addProperty("handle", handle)
            if (uri.isNotBlank()) meta.addProperty("uri", uri)
            if (rootPostId.isNotEmpty()) meta.addProperty("root_post_id", rootPostId)
            extractQuotedPostId(post.obj("embed"))?.let { meta.addProperty("quoted_post_id", it) }

            val rkey = extractRkey(uri)
            val profileSegment = handle.ifBlank { did }
            val sourceUrl = if (profileSegment.isNotBlank() && rkey.isNotBlank()) {
                "https://bsky.app/profile/$profileSegment/post/$rkey"
            } else ""

            val publishTime = parseIsoTime(recordObj.str("createdAt"))
                .takeIf { it > 0L } ?: parseIsoTime(post.str("indexedAt"))

            val record = UniversalRecord(
                contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
                platform = platform,
                recordType = if (parentId.isNotEmpty()) "comment" else "post",
                sourceApp = raw.hostApp,
                platformId = id,
                parentId = parentId,
                content = buildBody(recordObj, post.obj("embed")),
                authorId = did,
                authorName = author?.str("displayName").orEmpty().ifBlank { handle },
                authorAvatar = author?.str("avatar").orEmpty(),
                sourceUrl = sourceUrl,
                publishTime = publishTime,
                captureTime = raw.timestamp,
                extraMetadata = meta.toString()
            )
            records[id]?.let { BlueskyRelations.mergeRecord(it, record) }
            records[id] = record
        }

        // 1. Feed endpoints: getTimeline, getFeed, getAuthorFeed, getListFeed, getActorLikes
        root.array("feed").forEach { itemEl ->
            if (!itemEl.isJsonObject) return@forEach
            val item = itemEl.asJsonObject
            item.obj("post")?.let(::emit)
            item.obj("reply")?.let { reply ->
                listOf("root", "parent").forEach { key ->
                    reply.obj(key)?.takeIf { it.has("record") }?.let(::emit)
                }
            }
        }

        // 2. Batch/Search endpoints: getPosts, searchPosts
        root.array("posts").forEach { postEl ->
            if (postEl.isJsonObject) emit(postEl.asJsonObject)
        }

        // 3. Thread endpoints: getPostThreadV2 (array) or getPostThread (nested object)
        val threadEl = root["thread"]
        if (threadEl != null) {
            if (threadEl.isJsonArray) {
                threadEl.asJsonArray.forEach { itemEl ->
                    if (!itemEl.isJsonObject) return@forEach
                    val value = itemEl.asJsonObject.obj("value") ?: return@forEach
                    value.obj("post")?.let(::emit)
                }
            } else if (threadEl.isJsonObject) {
                fun walkThreadV1(node: JsonObject, depth: Int = 0) {
                    if (depth > 64) return
                    node.obj("post")?.let(::emit)
                    node.obj("parent")?.let { walkThreadV1(it, depth + 1) }
                    node.array("replies").forEach { replyEl ->
                        if (replyEl.isJsonObject) walkThreadV1(replyEl.asJsonObject, depth + 1)
                    }
                }
                walkThreadV1(threadEl.asJsonObject)
            }
        }

        ParseResult.decoded(records.values.toList(), emptyList())
    }

    internal fun postId(uri: String): String {
        if (!uri.startsWith("at://")) return ""
        val parts = uri.removePrefix("at://").split('/')
        return if (parts.size >= 3 && parts[1] == "app.bsky.feed.post" && parts[2].isNotBlank()) {
            "bluesky_post_${parts[0]}_${parts[2]}"
        } else ""
    }

    private fun extractRepo(uri: String): String =
        if (uri.startsWith("at://")) uri.removePrefix("at://").substringBefore('/') else ""

    private fun extractRkey(uri: String): String =
        if (uri.startsWith("at://")) uri.substringAfterLast('/', "") else ""

    private fun parseIsoTime(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun buildBody(recordObj: JsonObject, embed: JsonObject?): String {
        val blocks = mutableListOf<String>()
        val text = formatRichText(recordObj.str("text"), recordObj.array("facets"))
        if (text.isNotBlank()) blocks.add(text)
        if (embed != null) {
            appendEmbed(blocks, embed)
        }
        return blocks.joinToString("\n\n")
    }

    private fun formatRichText(text: String, facets: JsonArray): String {
        if (text.isEmpty() || facets.size() == 0) return text
        val bytes = text.toByteArray(Charsets.UTF_8)
        data class Span(val start: Int, val end: Int, val replacement: String)
        val spans = mutableListOf<Span>()

        for (el in facets) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val index = obj.obj("index") ?: continue
            val start = index["byteStart"]?.asInt ?: continue
            val end = index["byteEnd"]?.asInt ?: continue
            if (start < 0 || end <= start || end > bytes.size) continue
            val slice = String(bytes, start, end - start, Charsets.UTF_8)
            val features = obj.array("features")
            val feature = features.firstOrNull { it.isJsonObject }?.asJsonObject ?: continue
            val type = feature.str("\$type")
            val replacement = when (type) {
                "app.bsky.richtext.facet#link" -> {
                    val uri = feature.str("uri")
                    if (uri.isNotBlank()) "[$slice]($uri)" else slice
                }
                "app.bsky.richtext.facet#mention" -> {
                    val did = feature.str("did")
                    if (did.isNotBlank()) "[$slice](https://bsky.app/profile/$did)" else slice
                }
                else -> slice
            }
            spans.add(Span(start, end, replacement))
        }

        if (spans.isEmpty()) return text
        spans.sortBy { it.start }
        val sb = StringBuilder()
        var cursor = 0
        for (span in spans) {
            if (span.start < cursor) continue
            if (span.start > cursor) {
                sb.append(String(bytes, cursor, span.start - cursor, Charsets.UTF_8))
            }
            sb.append(span.replacement)
            cursor = span.end
        }
        if (cursor < bytes.size) {
            sb.append(String(bytes, cursor, bytes.size - cursor, Charsets.UTF_8))
        }
        return sb.toString()
    }

    private fun appendEmbed(blocks: MutableList<String>, embed: JsonObject) {
        when (embed.str("\$type")) {
            "app.bsky.embed.images#view" -> {
                embed.array("images").forEach { imgEl ->
                    if (!imgEl.isJsonObject) return@forEach
                    val img = imgEl.asJsonObject
                    val url = img.str("fullsize").ifEmpty { img.str("thumb") }
                    val alt = img.str("alt").replace("\n", " ").trim()
                    if (url.isNotBlank()) blocks.add("![$alt]($url)")
                }
            }
            "app.bsky.embed.video#view" -> {
                val thumb = embed.str("thumbnail")
                val playlist = embed.str("playlist")
                if (thumb.isNotBlank()) blocks.add("![视频封面]($thumb)")
                if (playlist.isNotBlank()) blocks.add("[视频]($playlist)")
            }
            "app.bsky.embed.external#view" -> {
                val ext = embed.obj("external") ?: return
                val uri = ext.str("uri")
                val title = ext.str("title").ifBlank { uri }
                if (uri.isNotBlank()) blocks.add("[$title]($uri)")
                val desc = ext.str("description").trim()
                if (desc.isNotBlank()) blocks.add("> $desc")
            }
            "app.bsky.embed.record#view" -> {
                val rec = embed.obj("record") ?: return
                appendQuotedRecord(blocks, rec)
            }
            "app.bsky.embed.recordWithMedia#view" -> {
                embed.obj("media")?.let { appendEmbed(blocks, it) }
                embed.obj("record")?.obj("record")?.let { appendQuotedRecord(blocks, it) }
            }
        }
    }

    private fun appendQuotedRecord(blocks: MutableList<String>, rec: JsonObject) {
        if (rec.str("\$type") != "app.bsky.embed.record#viewRecord") return
        val author = rec.obj("author")
        val handle = author?.str("handle").orEmpty()
        val display = author?.str("displayName").orEmpty().ifBlank { handle }
        val text = rec.obj("value")?.str("text").orEmpty().trim()
        val header = if (display.isNotBlank()) "**$display**" else "引用"
        val quoted = if (text.isNotBlank()) {
            "$header: $text".lines().joinToString("\n") { "> $it" }
        } else {
            "> $header"
        }
        blocks.add(quoted)
    }

    private fun extractQuotedPostId(embed: JsonObject?): String? {
        if (embed == null) return null
        val rec = when (embed.str("\$type")) {
            "app.bsky.embed.record#view" -> embed.obj("record")
            "app.bsky.embed.recordWithMedia#view" -> embed.obj("record")?.obj("record")
            else -> null
        } ?: return null
        return postId(rec.str("uri")).takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(key: String): JsonArray =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
