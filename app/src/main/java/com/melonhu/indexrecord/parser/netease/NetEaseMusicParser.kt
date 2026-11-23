package com.melonhu.indexrecord.parser.netease

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
 * 网易云音乐歌曲信息与评论区存档。
 */
object NetEaseMusicParser : RawDataParser {
    override val platform = "netease"
    const val HOST_APP = "com.netease.cloudmusic"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val host = URI(cleanUrl).host.orEmpty().lowercase()
        (host == "music.163.com" || host.endsWith(".music.163.com")) &&
            URI(cleanUrl).path.orEmpty().contains("/api/")
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Unsupported NetEase endpoint")
        val root = JsonParser.parseString(raw.json).asJsonObject
        val body = root.obj("data") ?: root
        val records = linkedMapOf<String, UniversalRecord>()

        val songs = body.array("songs").ifEmpty(root.array("songs"))
        songs.forEach { el ->
            if (!el.isJsonObject) return@forEach
            songRecord(el.asJsonObject, raw)?.let { records[it.platformId] = it }
        }
        val comments = body.array("comments").ifEmpty(root.array("comments"))
        comments.forEach { el ->
            if (!el.isJsonObject) return@forEach
            commentRecord(el.asJsonObject, raw, cleanUrl)?.let { records[it.platformId] = it }
        }
        ParseResult.decoded(records.values.toList(), emptyList())
    }

    private fun songRecord(node: JsonObject, raw: RawData): UniversalRecord? {
        val id = node.str("id")
        if (id.isEmpty()) return null
        val artists = node.array("ar").ifEmpty(node.array("artists"))
            .mapNotNull { if (it.isJsonObject) it.asJsonObject.str("name") else null }
        val album = node.obj("al") ?: node.obj("album")
        return UniversalRecord(
            platform = platform,
            recordType = "song",
            sourceApp = raw.hostApp,
            platformId = "netease_song_$id",
            title = node.str("name"),
            content = album?.str("name").orEmpty(),
            contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
            authorId = artists.firstOrNull().orEmpty(),
            authorName = artists.joinToString(" / "),
            authorAvatar = album?.str("picUrl").orEmpty(),
            sourceUrl = "https://music.163.com/song?id=$id",
            publishTime = epochMillis(node.str("publishTime")),
            captureTime = raw.timestamp
        )
    }

    private fun commentRecord(node: JsonObject, raw: RawData, cleanUrl: String): UniversalRecord? {
        val id = node.str("commentId").ifBlank { node.str("id") }
        if (id.isEmpty()) return null
        val user = node.obj("user")
        val threadId = queryParam(cleanUrl, "id").ifBlank { queryParam(cleanUrl, "threadId") }
        val parentId = if (threadId.isNotEmpty()) "netease_song_$threadId" else ""
        val beReplied = node.array("beReplied").firstOrNull { it.isJsonObject }?.asJsonObject
        val quote = beReplied?.let { reply ->
            val name = reply.obj("user")?.str("nickname").orEmpty()
            val text = reply.obj("content")?.str("content").orEmpty()
            if (name.isNotBlank()) "回复 @$name：$text" else text.takeIf { it.isNotBlank() }
        }
        val content = listOfNotNull(quote, node.str("content").takeIf { it.isNotBlank() })
            .joinToString("\n\n")
        return UniversalRecord(
            platform = platform,
            recordType = "comment",
            sourceApp = raw.hostApp,
            platformId = "netease_comment_$id",
            parentId = parentId,
            content = content,
            contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
            authorId = user?.str("userId").orEmpty(),
            authorName = user?.str("nickname").orEmpty(),
            authorAvatar = user?.str("avatarUrl").orEmpty(),
            publishTime = epochMillis(node.str("time")),
            captureTime = raw.timestamp,
            extraMetadata = JsonObject().apply {
                node["likedCount"]?.takeIf { it.isJsonPrimitive }?.let { add("likeCount", it) }
                node["replyCount"]?.takeIf { it.isJsonPrimitive }?.let { add("replyCount", it) }
            }.toString()
        )
    }

    private fun queryParam(url: String, key: String): String =
        runCatching {
            URI(url).query.orEmpty().split('&').firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=').orEmpty()
        }.getOrDefault("")

    private fun epochMillis(value: String): Long {
        val number = value.toLongOrNull() ?: return 0L
        return if (number > 10_000_000_000L) number else number * 1000L
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonArray.ifEmpty(fallback: JsonArray): JsonArray = if (size() > 0) this else fallback
}
