package com.melonhu.indexrecord.data.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Scoped NetEase Cloud Music comment thread identity, e.g. R_SO_4_1973665667 or A_PL_0_2936255937. */
internal data class NeteaseThread(val threadId: String) {
    val resourceType: String
    val resourceId: String
    val parentId: String
    val sourceUrl: String
    val commentPrefix: String get() = "netease_comment_${threadId}_"

    init {
        val (type, id) = parseResource(threadId)
        resourceType = type
        resourceId = id
        parentId = when (type) {
            "song" -> "netease_song_$id"
            "playlist" -> "netease_playlist_$id"
            "album" -> "netease_album_$id"
            "program" -> "netease_program_$id"
            else -> "netease_thread_$threadId"
        }
        sourceUrl = when (type) {
            "song" -> "https://music.163.com/#/song?id=$id"
            "playlist" -> "https://music.163.com/#/playlist?id=$id"
            "album" -> "https://music.163.com/#/album?id=$id"
            "program" -> "https://music.163.com/#/program?id=$id"
            else -> ""
        }
    }

    fun commentId(id: Long): String = "$commentPrefix$id"

    fun ownsCommentId(id: String): Boolean = id.startsWith(commentPrefix) &&
        id.removePrefix(commentPrefix).toLongOrNull()?.let { it > 0 && commentId(it) == id } == true

    fun writeTo(values: JsonObject) {
        values.addProperty("thread_id", threadId)
    }

    companion object {
        private val THREAD_ID_REGEX = Regex("^[A-Za-z0-9_-]+$")
        private val PREFIX_MAP = listOf(
            "R_SO_4_" to "song",
            "A_PL_0_" to "playlist",
            "R_AL_3_" to "album",
            "A_DJ_1_" to "program"
        )

        fun forSong(id: Long): NeteaseThread = NeteaseThread("R_SO_4_$id")
        fun forPlaylist(id: Long): NeteaseThread = NeteaseThread("A_PL_0_$id")
        fun forAlbum(id: Long): NeteaseThread = NeteaseThread("R_AL_3_$id")
        fun forProgram(id: Long): NeteaseThread = NeteaseThread("A_DJ_1_$id")

        fun parse(threadId: String): NeteaseThread? {
            val trimmed = threadId.trim()
            if (trimmed.isEmpty() || !THREAD_ID_REGEX.matches(trimmed)) return null
            return NeteaseThread(trimmed)
        }

        fun fromMetadata(values: JsonObject): NeteaseThread? {
            val raw = values.get("thread_id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString ?: return null
            return parse(raw)
        }

        fun fromCommentId(id: String): NeteaseThread? {
            if (!id.startsWith("netease_comment_")) return null
            val rest = id.removePrefix("netease_comment_")
            val sep = rest.lastIndexOf('_')
            if (sep <= 0) return null
            val threadId = rest.substring(0, sep)
            val commentId = rest.substring(sep + 1).toLongOrNull()?.takeIf { it > 0 } ?: return null
            return parse(threadId)?.takeIf { it.ownsCommentId(id) && it.commentId(commentId) == id }
        }

        private fun parseResource(threadId: String): Pair<String, String> {
            for ((prefix, type) in PREFIX_MAP) {
                if (threadId.startsWith(prefix)) {
                    val id = threadId.removePrefix(prefix)
                    if (id.toLongOrNull()?.let { it > 0 } == true) {
                        return type to id
                    }
                }
            }
            return "thread" to threadId
        }
    }
}

internal fun RecordContent.neteaseThread(): NeteaseThread? {
    if (platform != "netease") return null
    val values = runCatching { JsonParser.parseString(extraMetadata).asJsonObject }.getOrNull() ?: return null
    val thread = NeteaseThread.fromMetadata(values) ?: return null
    return thread.takeIf {
        when (recordType) {
            "song", "playlist", "album", "program" -> recordType == it.resourceType && platformId == it.parentId
            "comment" -> it.ownsCommentId(platformId)
            else -> false
        }
    }
}
