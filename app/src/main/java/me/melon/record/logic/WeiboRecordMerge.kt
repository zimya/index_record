package me.melon.record.logic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord

/** Mutates only the incoming Weibo payload; generic identity/author fallbacks remain below. */
internal fun mergeWeiboRecord(existing: UniversalRecord, incoming: UniversalRecord) {
    if (existing.platform != "weibo" || incoming.platform != "weibo") return

    fun metadata(value: String): JsonObject = runCatching {
        JsonParser.parseString(value).asJsonObject
    }.getOrElse { JsonObject() }
    val oldMeta = metadata(existing.extraMetadata)
    val newMeta = metadata(incoming.extraMetadata)
    fun fullText(meta: JsonObject): Boolean = meta.get("full_text")?.let {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
    } == true

    // is_long_text can mean a truncated preview, not a fetched full-text body.
    val oldFull = fullText(oldMeta)
    val newFull = fullText(newMeta)
    val keepOld = existing.content.isNotBlank() && (
        incoming.content.isBlank() ||
            (oldFull && !newFull) ||
            (oldFull == newFull && existing.content.length > incoming.content.length)
        )
    if (keepOld) {
        incoming.content = existing.content
        incoming.contentFormat = existing.contentFormat
    }

    for ((key, value) in newMeta.entrySet()) {
        val empty = value.isJsonNull ||
            (value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isBlank()) ||
            (value.isJsonArray && value.asJsonArray.size() == 0) ||
            (value.isJsonObject && value.asJsonObject.size() == 0)
        if (!empty && key != "full_text") oldMeta.add(key, value)
    }
    // Completeness belongs to the selected body, not to the latest response.
    oldMeta.remove("full_text")
    if (if (keepOld) oldFull else newFull) oldMeta.addProperty("full_text", true)
    incoming.extraMetadata = oldMeta.toString()
}
