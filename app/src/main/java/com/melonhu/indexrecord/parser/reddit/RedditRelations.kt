package com.melonhu.indexrecord.parser.reddit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.UniversalRecord

/** Partial GraphQL details update observed metadata without erasing full Markdown or direct parents. */
object RedditRelations {
    fun mergeRecord(existing: UniversalRecord, incoming: UniversalRecord) {
        if (existing.platform != "reddit" || incoming.platform != "reddit" || existing.platformId != incoming.platformId) return
        val old = metadata(existing.extraMetadata)
        val new = metadata(incoming.extraMetadata)
        val olderCapture = incoming.captureTime < existing.captureTime
        val oldRank = bodyRank(old, existing.content)
        val newRank = bodyRank(new, incoming.content)
        val keepText = newRank == 0 || oldRank > newRank || (oldRank == newRank && olderCapture) ||
            (oldRank == newRank && oldRank < 3 && text(existing, old).length > text(incoming, new).length)
        val selectedText = if (keepText) text(existing, old) else text(incoming, new)
        val bodyMeta = (if (keepText) old else new).deepCopy()
        val oldMedia = media(old)
        val newMedia = media(new)
        val keepMedia = newMedia.isEmpty() || (oldMedia.isNotEmpty() &&
            ((old["media_complete"]?.toString() == "true" && new["media_complete"]?.toString() != "true") || olderCapture))
        val selectedMedia = if (keepMedia) oldMedia else newMedia
        val mediaMeta = (if (keepMedia) old else new).deepCopy()

        for ((key, value) in new.entrySet()) {
            val empty = value.isJsonNull ||
                (value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isBlank()) ||
                (value.isJsonArray && value.asJsonArray.size() == 0) ||
                (value.isJsonObject && value.asJsonObject.size() == 0)
            // Zero, negative score, and false flags are real updates. Missing/null fields are not.
            if (!empty && (!olderCapture || !old.has(key))) old.add(key, value)
        }
        listOf("body_source", "body_complete", "body_length").forEach { key ->
            old.remove(key)
            bodyMeta[key]?.let { old.add(key, it) }
        }
        if (oldRank > 0 || newRank > 0) old.addProperty("body_length", selectedText.length)
        listOf("media_markdown", "media_complete").forEach { key ->
            old.remove(key)
            mediaMeta[key]?.let { old.add(key, it) }
        }
        incoming.content = combineBody(selectedText, selectedMedia)
        incoming.contentFormat = if (keepText) existing.contentFormat else incoming.contentFormat
        incoming.extraMetadata = old.toString()
        if (incoming.parentId.isBlank() || (olderCapture && existing.parentId.isNotBlank())) incoming.parentId = existing.parentId
        if (incoming.title.isBlank() || (olderCapture && existing.title.isNotBlank())) incoming.title = existing.title
        if (incoming.authorId.isBlank() || (olderCapture && existing.authorId.isNotBlank())) incoming.authorId = existing.authorId
        if (incoming.authorName.isBlank() || (olderCapture && existing.authorName.isNotBlank())) incoming.authorName = existing.authorName
        if (incoming.authorAvatar.isBlank() || (olderCapture && existing.authorAvatar.isNotBlank())) incoming.authorAvatar = existing.authorAvatar
        if (incoming.sourceUrl.isBlank() || (existing.sourceUrl.contains("/r/") && !incoming.sourceUrl.contains("/r/"))) incoming.sourceUrl = existing.sourceUrl
        if (incoming.publishTime == 0L) incoming.publishTime = existing.publishTime
        incoming.captureTime = maxOf(existing.captureTime, incoming.captureTime)
    }

    internal fun combineBody(text: String, media: List<String>): String = when {
        media.isEmpty() -> text
        text.isEmpty() -> media.joinToString("\n\n")
        else -> text + "\n\n" + media.joinToString("\n\n")
    }

    private fun metadata(value: String): JsonObject = runCatching { JsonParser.parseString(value).asJsonObject }.getOrElse { JsonObject() }
    private fun bodyRank(meta: JsonObject, content: String): Int = when (meta["body_source"]?.takeIf { it.isJsonPrimitive }?.asString) {
        "markdown" -> 3
        "html" -> 2
        "preview" -> 1
        else -> if (content.isNotBlank() && !meta.has("media_markdown")) 1 else 0
    }
    private fun text(record: UniversalRecord, meta: JsonObject): String {
        val length = meta["body_length"]?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
        return when {
            length != null && length in 0..record.content.length -> record.content.take(length)
            meta.has("media_markdown") && !meta.has("body_source") -> ""
            else -> record.content
        }
    }
    private fun media(meta: JsonObject): List<String> = meta["media_markdown"]?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString }
        ?.filter { it.isNotBlank() }.orEmpty()
}
