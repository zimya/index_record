package me.melon.record.parser.threads

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord

object ThreadsRelations {
    /** Stable post IDs allow reply-before-parent ingestion without placeholder records or relinking. */
    fun mergeRecord(existing: UniversalRecord, incoming: UniversalRecord) {
        if (existing.platform != "threads" || incoming.platform != "threads" || existing.platformId != incoming.platformId) return
        if (incoming.parentId.isBlank()) incoming.parentId = existing.parentId
        if (incoming.parentId.isNotBlank() || existing.recordType == "comment") incoming.recordType = "comment"
        if (incoming.content.isBlank() || existing.content.length > incoming.content.length) {
            incoming.content = existing.content
            incoming.contentFormat = existing.contentFormat
        }
        if (incoming.authorId.isBlank()) incoming.authorId = existing.authorId
        if (incoming.authorName.isBlank()) incoming.authorName = existing.authorName
        if (incoming.authorAvatar.isBlank()) incoming.authorAvatar = existing.authorAvatar
        if (incoming.sourceUrl.isBlank()) incoming.sourceUrl = existing.sourceUrl
        incoming.publishTime = maxOf(existing.publishTime, incoming.publishTime)
        incoming.captureTime = maxOf(existing.captureTime, incoming.captureTime)
        fun metadata(value: String): JsonObject = runCatching { JsonParser.parseString(value).asJsonObject }.getOrElse { JsonObject() }
        val merged = metadata(existing.extraMetadata)
        metadata(incoming.extraMetadata).entrySet().forEach { (key, value) ->
            val empty = value.isJsonNull ||
                (value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isBlank()) ||
                (value.isJsonArray && value.asJsonArray.size() == 0) ||
                (value.isJsonObject && value.asJsonObject.size() == 0)
            if (!empty) merged.add(key, value)
        }
        incoming.extraMetadata = merged.toString()
    }
}
