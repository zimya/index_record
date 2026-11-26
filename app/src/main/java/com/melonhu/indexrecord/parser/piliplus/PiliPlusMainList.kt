package com.melonhu.indexrecord.parser.piliplus

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.melonhu.indexrecord.data.model.BilibiliTopic
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.parser.ParseDiagnostic
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.bilibili.BilibiliParser

/** Field numbers come from PiliPlus's grpc/bilibili/main/community/reply/v1.pb.dart. */
internal object PiliPlusMainList {
    private const val MAX_REPLIES = 5_000
    private const val MAX_DEPTH = 16

    fun parse(raw: RawData, url: String, protobuf: ByteArray): ParseResult {
        // Match the official Bilibili projector: replies, topReplies, upTop, adminTop, voteTop.
        val groups = listOf(2, 14, 4, 5, 6).associateWith { mutableListOf<ProtoReader>() }
        var topLevelCount = 0
        ProtoReader(protobuf).fields { number, wire ->
            val group = groups[number]
            if (group == null) skip(wire) else {
                require(++topLevelCount <= MAX_REPLIES) { "MainList exceeds reply limit" }
                group.add(message(wire))
            }
        }
        val page = Page()
        for (group in groups.values) {
            for (reply in group) page.append(reply, 0, null)
        }
        val topic = page.topic ?: return ParseResult.decoded(emptyList(), page.diagnostics)
        val envelope = JsonObject().apply {
            addProperty("kind", "comments")
            topic.writeTo(this)
            add("replies", page.rows)
        }
        val normalized = BilibiliParser.parseNormalized(raw, url, "comments", envelope)
        return ParseResult.decoded(normalized.records.distinctBy { it.platformId }, page.diagnostics + normalized.diagnostics)
    }

    private class Page {
        var topic: BilibiliTopic? = null
            private set
        val rows = JsonArray()
        val diagnostics = mutableListOf<ParseDiagnostic>()
        private var visited = 0

        fun append(reader: ProtoReader, depth: Int, enclosingRoot: Long?) {
            val index = visited++
            require(visited <= MAX_REPLIES) { "MainList exceeds reply limit" }
            val reply = try {
                require(depth < MAX_DEPTH) { "MainList reply nesting exceeds limit" }
                readReply(reader)
            } catch (e: Exception) {
                diagnostics.add(ParseDiagnostic("piliplus.replies[$index]", e.message ?: "Invalid reply"))
                return
            }
            val problem = when {
                reply.id <= 0 || reply.topic.oid <= 0 || reply.topic.type <= 0 -> "Missing reply identity or topic"
                topic != null && reply.topic != topic -> "Reply belongs to a different topic"
                enclosingRoot != null && (reply.root != enclosingRoot || reply.parent <= 0) -> "Reply belongs to a different thread"
                else -> null
            }
            if (problem != null) {
                diagnostics.add(ParseDiagnostic("piliplus.replies[$index]", problem))
                return
            }
            if (topic == null) topic = reply.topic
            rows.add(reply.row)
            val root = reply.root.takeIf { it > 0 } ?: reply.id
            for (child in reply.children) append(child, depth + 1, root)
        }
    }

    private class Reply {
        var id = 0L
        var oid = 0L
        var type = 0L
        var root = 0L
        var parent = 0L
        val topic get() = BilibiliTopic(type, oid)
        val row = JsonObject()
        val children = mutableListOf<ProtoReader>()
    }

    private fun readReply(reader: ProtoReader): Reply = Reply().apply {
        val member = JsonObject()
        val basic = JsonObject()
        reader.fields { number, wire ->
            when (number) {
                1 -> {
                    require(children.size < MAX_REPLIES) { "MainList exceeds reply limit" }
                    children.add(message(wire))
                }
                2 -> id = int64(wire)
                3 -> oid = int64(wire)
                4 -> type = int64(wire)
                6 -> root = int64(wire)
                7 -> parent = int64(wire)
                9 -> row.addProperty("like", int64(wire))
                10 -> row.addProperty("published_at", int64(wire))
                11 -> row.addProperty("reply", int64(wire))
                12 -> readContent(message(wire), row)
                13 -> readMember(message(wire), member)
                15 -> message(wire).fields { field, kind ->
                    if (field == 1) readMember(message(kind), basic) else skip(kind)
                }
                else -> skip(wire)
            }
        }
        row.addProperty("id", id)
        // Omitted protobuf scalar ancestry fields have the default value zero.
        row.addProperty("root", root)
        row.addProperty("parent", parent)
        for (key in listOf("author", "author_avatar")) {
            (member.get(key)?.takeIf { it.asString.isNotBlank() } ?: basic.get(key))?.let { row.add(key, it) }
        }
    }

    private fun readContent(reader: ProtoReader, row: JsonObject) {
        reader.fields { number, wire ->
            when (number) {
                1 -> row.addProperty("text", string(wire))
                9 -> {
                    var url: String? = null
                    message(wire).fields { field, kind ->
                        if (field == 1) url = string(kind) else skip(kind)
                    }
                    url?.let {
                        val images = row.getAsJsonArray("images") ?: JsonArray().also { row.add("images", it) }
                        images.add(it)
                    }
                }
                else -> skip(wire)
            }
        }
    }

    // Member and MemberV2.Basic use the same name/face tags.
    private fun readMember(reader: ProtoReader, row: JsonObject) {
        reader.fields { number, wire ->
            when (number) {
                2 -> row.addProperty("author", string(wire))
                4 -> row.addProperty("author_avatar", string(wire))
                else -> skip(wire)
            }
        }
    }
}
