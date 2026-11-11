package me.melon.record.parser.tieba

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Tags from TiebaLite protos, cross-checked with research/tieba Post/SubPostList. */
internal object TiebaProto {
    private enum class Kind { NUMBER, ID, UNSIGNED_ID, TEXT, MESSAGE }
    private data class Field(
        val name: String,
        val kind: Kind,
        val schema: Map<Int, Field> = emptyMap(),
        val repeated: Boolean = false
    )
    private fun n(name: String) = Field(name, Kind.NUMBER)
    private fun id(name: String, unsigned: Boolean = false) = Field(name, if (unsigned) Kind.UNSIGNED_ID else Kind.ID)
    private fun s(name: String, repeated: Boolean = false) = Field(name, Kind.TEXT, repeated = repeated)
    private fun m(name: String, schema: Map<Int, Field>, repeated: Boolean = false) =
        Field(name, Kind.MESSAGE, schema, repeated)

    // Observed in the LSPosed PbFloor binary; matches decoded-capture JSON names.
    private val userShowInfo = mapOf(1 to m("feed_head",
        mapOf(1 to m("image_data", mapOf(1 to s("img_url"))))))
    private val user = mapOf(
        2 to id("id"), 3 to s("name"), 4 to s("name_show"), 5 to s("portrait"),
        23 to n("level_id"), 27 to s("portraith"), 127 to s("ip_address"),
        155 to m("user_show_info", userShowInfo)
    )
    private val forum = mapOf(1 to id("id"), 2 to s("name"), 4 to s("avatar"))
    private val content = mapOf(
        1 to n("type"), 2 to s("text"), 3 to s("link"), 4 to s("src"),
        5 to s("bsize"), 6 to s("big_src"), 7 to s("big_size"),
        8 to s("cdn_src"), 9 to s("big_cdn_src"), 10 to s("imgtype"),
        11 to s("c"), 12 to s("voice_md5"), 13 to n("during_time"),
        15 to id("uid"), 16 to s("dynamic"), 17 to s("_static"),
        18 to n("width"), 19 to n("height"), 25 to s("origin_src"),
        27 to n("origin_size"), 31 to s("media_subtitle"), 32 to n("url_type"),
        36 to s("cdn_src_active")
    )
    private val video = mapOf(
        2 to s("video_url"), 3 to n("video_duration"), 6 to s("thumbnail_url"),
        11 to s("media_subtitle")
    )
    private val agree = mapOf(1 to n("agree_num"))
    private val sub = mapOf(
        1 to id("id", unsigned = true), 2 to m("content", content, true), 3 to n("time"),
        4 to id("author_id"), 5 to s("title"), 6 to n("floor"),
        7 to m("author", user), 9 to m("agree", agree)
    )
    private val subList = mapOf(1 to id("pid", unsigned = true), 2 to m("sub_post_list", sub, true))
    private val post = mapOf(
        1 to id("id", unsigned = true), 2 to s("title"), 3 to n("floor"), 4 to n("time"),
        5 to m("content", content, true), 6 to s("arr_video", true),
        13 to n("sub_post_number"), 15 to m("sub_post_list", subList),
        19 to id("author_id"), 23 to m("author", user),
        29 to m("video_info", video), 37 to m("agree", agree), 46 to id("tid")
    )
    private val thread = mapOf(
        1 to id("id"), 2 to id("thread_id"), 3 to s("title"), 4 to n("reply_num"),
        5 to n("view_num"), 18 to m("author", user), 20 to n("comment_num"),
        27 to id("forum_id"), 28 to s("forum_name"), 40 to id("first_post_id"),
        45 to n("create_time"), 56 to id("author_id"), 79 to m("video_info", video),
        142 to m("first_post_content", content, true), 155 to m("forum_info", forum),
                190 to n("collect_num")
    )
    private val page = mapOf(
        1 to n("page_size"), 3 to n("current_page"), 4 to n("total_count"),
        5 to n("total_page"), 6 to n("has_more"), 7 to n("has_prev")
    )
    private val pageData = mapOf(
        2 to m("forum", forum), 3 to m("page", page), 6 to m("post_list", post, true),
        8 to m("thread", thread), 13 to m("user_list", user, true),
        38 to m("first_floor_post", post), 97 to m("first_floor", post)
    )
    private val floorData = mapOf(
        1 to m("page", page), 3 to m("post", post), 4 to m("subpost_list", sub, true),
        5 to m("thread", thread), 6 to m("forum", forum), 13 to n("subpost_num")
    )
    // Modern clients load the post list separately; these tags differ from PbPage.
    private val listData = mapOf(
        1 to m("thread", thread), 2 to m("forum", forum), 5 to m("user_list", user, true),
        6 to m("first_floor", post), 7 to m("post_list", post, true), 8 to m("page", page)
    )
    private val error = mapOf(1 to n("errorno"), 2 to s("errmsg"), 3 to s("usermsg"))

    fun decode(bytes: ByteArray, endpoint: String): JsonObject = decode(
        TiebaWireReader(bytes), mapOf(1 to m("error", error), 2 to m("data", when {
            endpoint.endsWith("/floor") -> floorData
            endpoint.endsWith("/list") -> listData
            else -> pageData
        }))
    )

    private fun decode(reader: TiebaWireReader, schema: Map<Int, Field>): JsonObject {
        val result = JsonObject()
        while (!reader.exhausted) {
            val tag = reader.tag()
            val field = schema[tag ushr 3]
            val expectedWire = when (field?.kind) {
                Kind.NUMBER, Kind.ID, Kind.UNSIGNED_ID -> 0
                else -> 2
            }
            if (field == null || tag and 7 != expectedWire) {
                reader.skip(tag)
                continue
            }
            val value = when (field.kind) {
                Kind.NUMBER -> com.google.gson.JsonPrimitive(reader.varint())
                Kind.ID -> com.google.gson.JsonPrimitive(reader.varint().toString())
                Kind.UNSIGNED_ID -> com.google.gson.JsonPrimitive(java.lang.Long.toUnsignedString(reader.varint()))
                Kind.TEXT -> com.google.gson.JsonPrimitive(reader.string())
                Kind.MESSAGE -> decode(reader.message(), field.schema)
            }
            if (field.repeated) {
                val array = result.getAsJsonArray(field.name) ?: JsonArray().also { result.add(field.name, it) }
                array.add(value)
            } else if (value.isJsonObject && result.get(field.name)?.isJsonObject == true) {
                // Singular protobuf message occurrences merge, rather than replace one another.
                mergeMessage(result.getAsJsonObject(field.name), value.asJsonObject)
            } else {
                result.add(field.name, value)
            }
        }
        return result
    }

    private fun mergeMessage(target: JsonObject, incoming: JsonObject) {
        for ((key, value) in incoming.entrySet()) {
            val old = target.get(key)
            when {
                old?.isJsonObject == true && value.isJsonObject -> mergeMessage(old.asJsonObject, value.asJsonObject)
                old?.isJsonArray == true && value.isJsonArray -> old.asJsonArray.addAll(value.asJsonArray)
                else -> target.add(key, value)
            }
        }
    }
}
