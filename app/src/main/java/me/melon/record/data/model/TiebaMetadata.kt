package me.melon.record.data.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser


/** Canonical Tieba metadata shared by queries and rendering, independent of capture/parser code. */
internal class TiebaMetadata(private val json: JsonObject) {
    constructor(extraMetadata: String) : this(
        kotlin.runCatching { JsonParser.parseString(extraMetadata).asJsonObject }.getOrDefault(JsonObject())
    )

    val threadId: String = text("thread_id")
    val threadTitle: String = text("thread_title")
    val forumName: String = text("forum_name")
    val floorNumber: Long? = number("floor")
    val subFloorNumber: Long? = number("sub_floor")
    val replyToName: String = text("reply_to_name")
    val replyToId: String = text("reply_to_id")
    val ipAddress: String = text("ip_address")
    val agreeCount: Long? = number("agree_count")
    val replyCount: Long? = number("reply_count")
    val favoriteCount: Long? = number("favlists_count") ?: number("bookmark_count")

    private fun text(key: String): String = runCatching {
        json.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    }.getOrDefault("")

    private fun number(key: String): Long? = text(key).toLongOrNull()?.takeIf { it >= 0 }
}
