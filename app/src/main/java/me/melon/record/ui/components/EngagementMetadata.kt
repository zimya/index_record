package me.melon.record.ui.components

import com.google.gson.JsonObject

/** Accept legacy metadata keys and their generic aliases without failing on null/malformed counts. */
internal fun JsonObject.engagementCount(vararg keys: String): Long {
    for (key in keys) {
        val count = runCatching { get(key)?.takeUnless { it.isJsonNull }?.asLong }.getOrNull()
        if (count != null) return count.coerceAtLeast(0L)
    }
    return 0L
}

internal fun JsonObject.viewEngagementCount(platform: String): Long =
    if (platform == "threads") {
        engagementCount("view_count", "views_count", "viewCount")
    } else {
        engagementCount("views_count", "viewCount")
    }
