package com.melonhu.indexrecord.data.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The comment OID is scoped to its type; it is not necessarily the public opus ID. */
internal data class BilibiliTopic(val type: Long, val oid: Long) {
    val parentId: String get() = if (type == VIDEO) "bilibili_video_$oid" else "bilibili_opus_${type}_$oid"
    val commentPrefix: String get() = "bilibili_comment_${type}_${oid}_"
    fun commentId(id: Long): String = "$commentPrefix$id"

    fun writeTo(values: JsonObject) {
        values.addProperty("topic_type", type)
        values.addProperty("topic_id", oid.toString())
    }

    fun ownsCommentId(id: String): Boolean = id.startsWith(commentPrefix) &&
        id.removePrefix(commentPrefix).toLongOrNull()?.let { it > 0 && commentId(it) == id } == true

    companion object {
        const val VIDEO = 1L

        fun fromMetadata(values: JsonObject): BilibiliTopic? {
            val type = values.bilibiliInteger("topic_type")?.takeIf { it > 0 } ?: return null
            val oid = values.bilibiliInteger("topic_id")?.takeIf { it > 0 } ?: return null
            return BilibiliTopic(type, oid)
        }

        fun fromCommentId(id: String): BilibiliTopic? {
            val parts = id.removePrefix("bilibili_comment_").split('_', limit = 3)
            if (parts.size != 3) return null
            val type = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val oid = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return BilibiliTopic(type, oid).takeIf { it.ownsCommentId(id) }
        }
    }
}

internal fun RecordContent.bilibiliTopic(): BilibiliTopic? {
    if (platform != "bilibili") return null
    val values = runCatching { JsonParser.parseString(extraMetadata).asJsonObject }.getOrNull() ?: return null
    val topic = BilibiliTopic.fromMetadata(values) ?: return null
    return topic.takeIf {
        when (recordType) {
            "video" -> it.type == BilibiliTopic.VIDEO && platformId == it.parentId
            "opus" -> it.type != BilibiliTopic.VIDEO && platformId == it.parentId
            "comment" -> it.ownsCommentId(platformId)
            else -> false
        }
    }
}

internal fun JsonObject.bilibiliInteger(key: String): Long? = get(key)
    ?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }?.asString?.toLongOrNull()
