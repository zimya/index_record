package me.melon.record.parser.x

import com.google.gson.JsonObject
import me.melon.record.parser.x.XUtils.safeJsonObject
import me.melon.record.parser.x.XUtils.safeString

internal object XAdFilter {
    fun isPromoted(obj: JsonObject): Boolean {
        if (listOf("promotedMetadata", "promoted_metadata").any { key ->
                obj.get(key)?.let { !it.isJsonNull } == true
            }) return true

        val entryId = obj.safeString("entryId").ifBlank { obj.safeString("entry_id") }
        if (entryId.startsWith("promoted-")) return true

        val eventInfo = obj.safeJsonObject("clientEventInfo") ?: return false
        val component = eventInfo.safeString("component")
        val injectionType = eventInfo.safeJsonObject("details")
            ?.safeJsonObject("timelinesDetails")?.safeString("injectionType").orEmpty()
        return component.contains("promoted", ignoreCase = true) ||
            injectionType.contains("promoted", ignoreCase = true)
    }
}
