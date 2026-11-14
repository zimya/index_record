package com.melonhu.indexrecord.parser.tieba

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.melonhu.indexrecord.data.model.UniversalRecord

/** Pure record merging; no database access or cross-thread global cache. */
object TiebaRelations {
    data class MergedContent(val content: String, val contentFormat: String)

    /**
     * Selects a body without mutating either record. Call before merging extraMetadata:
     * known full content outranks unknown completeness, which outranks a known preview.
     * Blank bodies never replace nonblank ones; equal quality prefers the longer body,
     * retaining the existing body on a tie.
     */
    fun mergeRecordContent(existing: UniversalRecord, incoming: UniversalRecord): MergedContent {
        val selected = when {
            incoming.content.isBlank() -> existing
            existing.content.isBlank() -> incoming
            else -> {
                val oldQuality = parse(existing.extraMetadata)?.let(::quality) ?: 1
                val newQuality = parse(incoming.extraMetadata)?.let(::quality) ?: 1
                if (newQuality > oldQuality ||
                    newQuality == oldQuality && incoming.content.length > existing.content.length) incoming
                else existing
            }
        }
        return MergedContent(selected.content, selected.contentFormat)
    }

    /**
     * Pagination unions media and keyed objects; counts are high-water marks. Empty/default
     * fields never erase known data. A floor's full response outranks its PbPage preview.
     * Conflicting equally complete scalar values keep the existing value.
     */
    fun mergeExtra(existing: String, incoming: String): String {
        val old = parse(existing)
        val new = parse(incoming)
        if (old == null) return new?.toString() ?: "{}"
        if (new == null) return old.toString()
        return merge(old, new, quality(new) > quality(old)).toString()
    }

    private fun parse(value: String): JsonObject? = try {
        if (value.length > TiebaWireReader.MAX_BYTES) null else boundedTiebaJson(value)
    } catch (_: Exception) { null }

    private fun quality(value: JsonObject): Int = when (value.text("is_preview")) {
        "false" -> 2
        "true" -> 0
        else -> 1
    }

    private fun meaningful(value: JsonElement?): Boolean = when {
        value == null || value.isJsonNull -> false
        value.isJsonPrimitive -> if (value.asJsonPrimitive.isString) value.asString.isNotBlank() && value.asString != "0" else true
        value.isJsonArray -> value.asJsonArray.size() > 0
        value.isJsonObject -> value.asJsonObject.size() > 0
        else -> false
    }

    private fun merge(old: JsonObject, new: JsonObject, preferNew: Boolean): JsonObject {
        val result = old.deepCopy()
        for ((key, value) in new.entrySet()) {
            val previous = result.get(key)
            when {
                !meaningful(value) -> Unit
                !meaningful(previous) -> result.add(key, value.deepCopy())
                key == "is_preview" -> {
                    if (previous.isJsonPrimitive && value.isJsonPrimitive)
                        result.addProperty(key, previous.asString == "true" && value.asString == "true")
                }
                key.endsWith("_count") || key == "page" -> {
                    val a = previous.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
                    val b = value.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
                    if (a != null && b != null) result.addProperty(key, maxOf(a, b))
                }
                previous.isJsonObject && value.isJsonObject -> result.add(key, merge(previous.asJsonObject, value.asJsonObject, preferNew))
                previous.isJsonArray && value.isJsonArray -> {
                    if (key == "content_segments") {
                        // This is an ordered body, not a set: never concatenate preview and full text.
                        if (bodySize(value.asJsonArray) > bodySize(previous.asJsonArray) && quality(new) >= quality(old)) {
                            result.add(key, value.deepCopy())
                        }
                    } else result.add(key, union(previous.asJsonArray, value.asJsonArray, preferNew))
                }
                previous.isJsonPrimitive && value.isJsonPrimitive -> {
                    val oldNumber = previous.asString.toLongOrNull()
                    val newNumber = value.asString.toLongOrNull()
                    if ((oldNumber == 0L && newNumber != null && newNumber > 0) ||
                        (preferNew && key !in identityKeys && newNumber != 0L)) result.add(key, value.deepCopy())
                }
            }
        }
        return result
    }

    private val identityKeys = setOf("thread_id", "post_id", "floor_post_id", "forum_id", "reply_to_id", "floor")

    private fun bodySize(parts: JsonArray): Int = parts.sumOf { part ->
        if (part.isJsonObject) part.asJsonObject.entrySet().sumOf { (key, value) ->
            if (key in setOf("text", "src", "origin_src", "originSrc", "link", "voice_md5") && value.isJsonPrimitive)
                value.asString.length else 0
        } else 0
    }

    private fun union(old: JsonArray, new: JsonArray, preferNew: Boolean): JsonArray {
        val values = linkedMapOf<String, JsonElement>()
        for (value in old + new) {
            val identity = if (value.isJsonObject) {
                val id = value.asJsonObject.text("platformId", "platform_id", "post_id", "id")
                id.takeIf { it.isNotBlank() }?.let { "id:$it" } ?: value.toString()
            } else value.toString()
            val previous = values[identity]
            values[identity] = if (previous?.isJsonObject == true && value.isJsonObject)
                merge(previous.asJsonObject, value.asJsonObject, preferNew) else value.deepCopy()
        }
        return JsonArray().apply { values.values.forEach { add(it) } }
    }
}
