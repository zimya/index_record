package com.melonhu.indexrecord.parser.netease

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.NeteaseThread
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.hook.NeteaseCloudMusicHook
import com.melonhu.indexrecord.parser.ParseDiagnostic
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.RawDataParser
import java.net.URI

object NeteaseParser : RawDataParser {
    override val platform = "netease"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean =
        raw.hostApp == PlatformCatalog.NETEASE_PACKAGE &&
            NeteaseCloudMusicHook.isTargetUrl(raw.url.ifEmpty { cleanUrl })

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> =
        parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Not a NetEase Cloud Music capture route")
        val targetUrl = raw.url.ifEmpty { cleanUrl }
        val path = runCatching { URI.create(targetUrl).path }.getOrNull().orEmpty()
        val root = JsonParser.parseString(raw.json).asJsonObject
        val rootCode = root.optionalLong("code")
        if (rootCode != null && rootCode != 200L) {
            return@attempt ParseResult.Ignored("Non-200 NetEase response: $rootCode")
        }

        when (path) {
            "/eapi/batch", "/api/batch" -> parseBatchComments(raw, root)
            "/eapi/v2/resource/comments", "/api/v2/resource/comments" -> parseCommentsPayload(raw, root)
            "/eapi/v2/resource/comment/floor/get", "/api/v2/resource/comment/floor/get" -> parseFloorComments(raw, root)
            "/eapi/resource/comments/reply/preload", "/api/resource/comments/reply/preload" -> parsePreloadComments(raw, root)
            "/eapi/v3/song/detail", "/api/v3/song/detail" -> parseSongs(raw, root)
            "/eapi/v6/playlist/detail", "/api/v6/playlist/detail" -> parsePlaylist(raw, root)
            "/eapi/album/v3/detail", "/api/album/v3/detail" -> parseAlbum(raw, root)
            "/eapi/dj/program/detail", "/api/dj/program/detail" -> parseProgram(raw, root)
            else -> ParseResult.Ignored("Unsupported NetEase Cloud Music path: $path")
        }
    }

    private fun parseBatchComments(raw: RawData, root: JsonObject): ParseResult {
        val commentsEnvelope = root.obj("/api/v2/resource/comments")
            ?: return ParseResult.Ignored("Batch response contains no comments")
        val code = commentsEnvelope.optionalLong("code")
        if (code != null && code != 200L) {
            return ParseResult.Ignored("Non-200 batch comments response: $code")
        }
        return parseCommentsPayload(raw, commentsEnvelope)
    }

    private fun parseCommentsPayload(raw: RawData, envelope: JsonObject): ParseResult {
        val data = envelope.obj("data") ?: error("Missing comments data object")
        val records = mutableListOf<UniversalRecord>()
        val diagnostics = mutableListOf<ParseDiagnostic>()
        val seenIds = mutableSetOf<Long>()

        data.obj("currentComment")?.let {
            addComment(raw, it, records, diagnostics, seenIds, "netease.currentComment")
        }
        val comments = data.arr("comments") ?: error("Missing comments array")
        collectCommentArray(raw, comments, records, diagnostics, seenIds, "netease.comments")
        return ParseResult.decoded(records, diagnostics)
    }

    private fun parseFloorComments(raw: RawData, envelope: JsonObject): ParseResult {
        val data = envelope.obj("data") ?: error("Missing floor data object")
        val records = mutableListOf<UniversalRecord>()
        val diagnostics = mutableListOf<ParseDiagnostic>()
        val seenIds = mutableSetOf<Long>()

        data.obj("ownerComment")?.let {
            addComment(raw, it, records, diagnostics, seenIds, "netease.ownerComment")
        }
        data.arr("bestComments")?.let {
            collectCommentArray(raw, it, records, diagnostics, seenIds, "netease.bestComments")
        }
        val comments = data.arr("comments") ?: error("Missing floor comments array")
        collectCommentArray(raw, comments, records, diagnostics, seenIds, "netease.floorComments")
        return ParseResult.decoded(records, diagnostics)
    }

    private fun parsePreloadComments(raw: RawData, envelope: JsonObject): ParseResult {
        val data = envelope.obj("data") ?: error("Missing preload data object")
        val map = data.obj("preloadCommentMap") ?: error("Missing preloadCommentMap")
        val records = mutableListOf<UniversalRecord>()
        val diagnostics = mutableListOf<ParseDiagnostic>()
        val seenIds = mutableSetOf<Long>()

        for ((key, value) in map.entrySet()) {
            val arr = value.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            collectCommentArray(raw, arr, records, diagnostics, seenIds, "netease.preload[$key]")
        }
        return ParseResult.decoded(records, diagnostics)
    }

    private fun collectCommentArray(
        raw: RawData,
        array: JsonArray,
        records: MutableList<UniversalRecord>,
        diagnostics: MutableList<ParseDiagnostic>,
        seenIds: MutableSet<Long>,
        stagePrefix: String
    ) {
        array.forEachIndexed { index, element ->
            if (!element.isJsonObject) {
                diagnostics.add(ParseDiagnostic("$stagePrefix[$index]", "Expected comment object"))
                return@forEachIndexed
            }
            val obj = element.asJsonObject
            addComment(raw, obj, records, diagnostics, seenIds, "$stagePrefix[$index]")
            obj.arr("hideSerialComments")?.forEachIndexed { subIdx, subEl ->
                if (subEl.isJsonObject) {
                    addComment(raw, subEl.asJsonObject, records, diagnostics, seenIds, "$stagePrefix[$index].hideSerialComments[$subIdx]")
                }
            }
        }
    }

    private fun addComment(
        raw: RawData,
        comment: JsonObject,
        records: MutableList<UniversalRecord>,
        diagnostics: MutableList<ParseDiagnostic>,
        seenIds: MutableSet<Long>,
        stage: String
    ) {
        try {
            val id = requireNotNull(comment.optionalLong("commentId")?.takeIf { it > 0 }) { "Invalid commentId" }
            if (!seenIds.add(id)) return
            val thread = requireNotNull(NeteaseThread.parse(comment.text("threadId"))) { "Invalid threadId" }
            val user = comment.obj("user")
            val authorName = user?.text("nickname").orEmpty()
            val authorAvatar = validUrl(user?.text("avatarUrl").orEmpty()).orEmpty()

            val rawContent = comment.text("content").ifBlank { comment.text("richContent") }
            val voiceExt = comment.obj("voiceExt")
            val voiceText = voiceExt?.text("asrText").orEmpty()
            val voiceDuration = comment.optionalLong("voiceDurationMillSecond")?.takeIf { it > 0 }?.div(1000)
            val body = rawContent.ifBlank {
                if (voiceText.isNotBlank()) "[语音] $voiceText" else ""
            }

            val rootId = comment.optionalLong("parentCommentId")?.takeIf { it > 0 && it != id } ?: 0L
            val replyTargetId = comment.arr("beReplied")
                ?.firstOrNull { it.isJsonObject }?.asJsonObject
                ?.optionalLong("beRepliedCommentId")
                ?.takeIf { it > 0 && it != id }
                ?: rootId
            val parentId = if (replyTargetId > 0) thread.commentId(replyTargetId) else thread.parentId

            val replyCount = comment.optionalLong("replyCount")?.takeIf { it >= 0 }
                ?: comment.obj("showFloorComment")?.optionalLong("replyCount")?.takeIf { it >= 0 }
            val ipLocation = comment.obj("ipLocation")?.text("location").orEmpty()
            val images = listOfNotNull(
                validUrl(comment.text("contentPicUrl")),
                validUrl(comment.text("expressionUrl"))
            ).distinct()

            val metadata = JsonObject().apply {
                thread.writeTo(this)
                addProperty("parent", replyTargetId.toString())
                addProperty("root", rootId.toString())
                comment.optionalLong("likedCount")?.takeIf { it >= 0 }?.let { addProperty("like", it) }
                replyCount?.let { addProperty("reply", it) }
                if (ipLocation.isNotBlank()) addProperty("ip_location", ipLocation)
                if (voiceDuration != null) addProperty("duration", voiceDuration)
                if (voiceText.isNotBlank()) addProperty("voice_text", voiceText)
                if (images.isNotEmpty()) {
                    add("images", JsonArray().apply { images.forEach(::add) })
                }
            }

            records.add(
                UniversalRecord(
                    platform = platform,
                    recordType = "comment",
                    sourceApp = raw.hostApp,
                    platformId = thread.commentId(id),
                    title = "",
                    content = body,
                    contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
                    authorName = authorName,
                    authorAvatar = authorAvatar,
                    sourceUrl = thread.sourceUrl,
                    parentId = parentId,
                    publishTime = comment.optionalLong("time")?.takeIf { it > 0 } ?: 0L,
                    captureTime = raw.timestamp,
                    extraMetadata = metadata.toString()
                )
            )
        } catch (e: IllegalArgumentException) {
            diagnostics.add(ParseDiagnostic(stage, e.message ?: "Invalid NetEase comment"))
        }
    }

    private fun parseSongs(raw: RawData, root: JsonObject): ParseResult {
        val songs = root.arr("songs") ?: error("Missing songs array")
        val records = mutableListOf<UniversalRecord>()
        val diagnostics = mutableListOf<ParseDiagnostic>()
        songs.forEachIndexed { index, element ->
            if (!element.isJsonObject) {
                diagnostics.add(ParseDiagnostic("netease.songs[$index]", "Expected song object"))
                return@forEachIndexed
            }
            try {
                records.add(parseSong(raw, element.asJsonObject))
            } catch (e: IllegalArgumentException) {
                diagnostics.add(ParseDiagnostic("netease.songs[$index]", e.message ?: "Invalid song"))
            }
        }
        return ParseResult.decoded(records, diagnostics)
    }

    private fun parseSong(raw: RawData, song: JsonObject): UniversalRecord {
        val id = requireNotNull(song.optionalLong("id")?.takeIf { it > 0 }) { "Invalid song id" }
        val thread = NeteaseThread.forSong(id)
        val name = song.text("name")
        val aliases = song.stringList("alia")
        val title = if (aliases.isNotEmpty() && name.isNotBlank()) {
            "$name (${aliases.joinToString(" / ")})"
        } else name

        val artists = song.arr("ar")?.mapNotNull {
            it.takeIf { el -> el.isJsonObject }?.asJsonObject?.text("name")?.takeIf(String::isNotBlank)
        }.orEmpty().joinToString(" / ")

        val album = song.obj("al")
        val albumName = album?.text("name").orEmpty()
        val cover = validUrl(album?.text("picUrl").orEmpty()).orEmpty()
        val durationSeconds = song.optionalLong("dt")?.takeIf { it > 0 }?.div(1000)

        val metadata = JsonObject().apply {
            thread.writeTo(this)
            if (cover.isNotBlank()) addProperty("cover", cover)
            if (albumName.isNotBlank()) addProperty("album_name", albumName)
            durationSeconds?.let { addProperty("duration", it) }
        }

        val content = if (albumName.isNotBlank()) "专辑：$albumName" else ""
        return UniversalRecord(
            platform = platform,
            recordType = "song",
            sourceApp = raw.hostApp,
            platformId = thread.parentId,
            title = title,
            content = content,
            contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
            authorName = artists,
            authorAvatar = "",
            sourceUrl = thread.sourceUrl,
            parentId = "",
            publishTime = song.optionalLong("publishTime")?.takeIf { it > 0 } ?: 0L,
            captureTime = raw.timestamp,
            extraMetadata = metadata.toString()
        )
    }

    private fun parsePlaylist(raw: RawData, root: JsonObject): ParseResult {
        val playlist = root.obj("playlist") ?: error("Missing playlist object")
        val id = requireNotNull(playlist.optionalLong("id")?.takeIf { it > 0 }) { "Invalid playlist id" }
        val thread = NeteaseThread.parse(playlist.text("commentThreadId")) ?: NeteaseThread.forPlaylist(id)
        require(thread.resourceType == "playlist" && thread.parentId == "netease_playlist_$id") {
            "Inconsistent playlist commentThreadId"
        }

        val creator = playlist.obj("creator")
        val cover = validUrl(playlist.text("coverImgUrl")).orEmpty()
        val metadata = JsonObject().apply {
            thread.writeTo(this)
            if (cover.isNotBlank()) addProperty("cover", cover)
            playlist.optionalLong("commentCount")?.takeIf { it >= 0 }?.let { addProperty("reply", it) }
            playlist.optionalLong("shareCount")?.takeIf { it >= 0 }?.let { addProperty("share", it) }
            playlist.optionalLong("subscribedCount")?.takeIf { it >= 0 }?.let { addProperty("favorite", it) }
            playlist.optionalLong("playCount")?.takeIf { it >= 0 }?.let { addProperty("view", it) }
        }

        val record = UniversalRecord(
            platform = platform,
            recordType = "playlist",
            sourceApp = raw.hostApp,
            platformId = thread.parentId,
            title = playlist.text("name"),
            content = playlist.text("description"),
            contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
            authorName = creator?.text("nickname").orEmpty(),
            authorAvatar = validUrl(creator?.text("avatarUrl").orEmpty()).orEmpty(),
            sourceUrl = thread.sourceUrl,
            parentId = "",
            publishTime = playlist.optionalLong("createTime")?.takeIf { it > 0 } ?: 0L,
            captureTime = raw.timestamp,
            extraMetadata = metadata.toString()
        )
        return ParseResult.Success(listOf(record))
    }

    private fun parseAlbum(raw: RawData, root: JsonObject): ParseResult {
        val album = root.obj("album") ?: error("Missing album object")
        val id = requireNotNull(album.optionalLong("id")?.takeIf { it > 0 }) { "Invalid album id" }
        val thread = NeteaseThread.parse(album.text("commentThreadId")) ?: NeteaseThread.forAlbum(id)
        require(thread.resourceType == "album" && thread.parentId == "netease_album_$id") {
            "Inconsistent album commentThreadId"
        }

        val artist = album.obj("artist")
        val artists = album.arr("artists")?.mapNotNull {
            it.takeIf { el -> el.isJsonObject }?.asJsonObject?.text("name")?.takeIf(String::isNotBlank)
        }.orEmpty().joinToString(" / ").ifBlank { artist?.text("name").orEmpty() }
        val info = root.obj("info") ?: album.obj("info")
        val cover = validUrl(album.text("picUrl")).orEmpty()

        val metadata = JsonObject().apply {
            thread.writeTo(this)
            if (cover.isNotBlank()) addProperty("cover", cover)
            info?.optionalLong("commentCount")?.takeIf { it >= 0 }?.let { addProperty("reply", it) }
            info?.optionalLong("shareCount")?.takeIf { it >= 0 }?.let { addProperty("share", it) }
            info?.optionalLong("likedCount")?.takeIf { it >= 0 }?.let { addProperty("like", it) }
        }

        val record = UniversalRecord(
            platform = platform,
            recordType = "album",
            sourceApp = raw.hostApp,
            platformId = thread.parentId,
            title = album.text("name"),
            content = album.text("description"),
            contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
            authorName = artists,
            authorAvatar = validUrl(artist?.text("picUrl").orEmpty()).orEmpty(),
            sourceUrl = thread.sourceUrl,
            parentId = "",
            publishTime = album.optionalLong("publishTime")?.takeIf { it > 0 } ?: 0L,
            captureTime = raw.timestamp,
            extraMetadata = metadata.toString()
        )
        return ParseResult.Success(listOf(record))
    }

    private fun parseProgram(raw: RawData, root: JsonObject): ParseResult {
        val program = root.obj("program") ?: error("Missing program object")
        val id = requireNotNull(program.optionalLong("id")?.takeIf { it > 0 }) { "Invalid program id" }
        val thread = NeteaseThread.parse(program.text("commentThreadId")) ?: NeteaseThread.forProgram(id)
        require(thread.resourceType == "program" && thread.parentId == "netease_program_$id") {
            "Inconsistent program commentThreadId"
        }

        val dj = program.obj("dj")
        val radio = program.obj("radio")
        val radioName = radio?.text("name").orEmpty()
        val cover = validUrl(program.text("coverUrl")).orEmpty()
        val durationSeconds = program.optionalLong("duration")?.takeIf { it > 0 }?.div(1000)

        val metadata = JsonObject().apply {
            thread.writeTo(this)
            if (cover.isNotBlank()) addProperty("cover", cover)
            if (radioName.isNotBlank()) addProperty("radio_name", radioName)
            durationSeconds?.let { addProperty("duration", it) }
            program.optionalLong("likedCount")?.takeIf { it >= 0 }?.let { addProperty("like", it) }
            program.optionalLong("commentCount")?.takeIf { it >= 0 }?.let { addProperty("reply", it) }
            program.optionalLong("shareCount")?.takeIf { it >= 0 }?.let { addProperty("share", it) }
            program.optionalLong("listenerCount")?.takeIf { it >= 0 }?.let { addProperty("view", it) }
        }

        val record = UniversalRecord(
            platform = platform,
            recordType = "program",
            sourceApp = raw.hostApp,
            platformId = thread.parentId,
            title = program.text("name"),
            content = program.text("description"),
            contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
            authorName = dj?.text("nickname").orEmpty().ifBlank { radioName },
            authorAvatar = validUrl(dj?.text("avatarUrl").orEmpty()).orEmpty(),
            sourceUrl = thread.sourceUrl,
            parentId = "",
            publishTime = program.optionalLong("createTime")?.takeIf { it > 0 } ?: 0L,
            captureTime = raw.timestamp,
            extraMetadata = metadata.toString()
        )
        return ParseResult.Success(listOf(record))
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.text(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

    private fun JsonObject.optionalLong(key: String): Long? {
        val p = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (!p.isNumber && !p.isString) return null
        val s = p.asString
        if ('.' in s) return null
        return s.toLongOrNull()
    }

    private fun JsonObject.stringList(key: String): List<String> =
        arr(key)?.mapNotNull {
            it.takeIf { el -> el.isJsonPrimitive && el.asJsonPrimitive.isString }?.asString?.takeIf(String::isNotBlank)
        }.orEmpty()

    private fun validUrl(value: String): String? {
        if (value.isBlank()) return null
        val url = if (value.startsWith("//")) "https:$value" else value
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf { uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null }
    }
}
