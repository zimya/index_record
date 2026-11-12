package me.melon.record.ui.components

import com.google.gson.JsonObject

/** Missing/invalid values stay unknown; signed scores must not use the clamped like-count helper. */
internal fun JsonObject.signedScore(): Long? = optionalLong("score")

internal fun JsonObject.optionalLong(key: String): Long? {
    val value = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!value.isNumber && !value.isString) return null
    return value.asString.toLongOrNull()
}

internal fun JsonObject.optionalEngagementCount(key: String): Long? =
    optionalLong(key)?.takeIf { it >= 0L }

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
