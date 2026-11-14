package com.melonhu.indexrecord.parser.tieba

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.RawDataParser
import com.melonhu.indexrecord.parser.ParseResult
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** PbPage/PbFloor/PbList response parser. */
object TiebaParser : RawDataParser {
    override val platform = "tieba"
    private val paths = setOf("/c/f/pb/page", "/c/f/pb/floor", "/c/f/pb/list")

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = endpoint(cleanUrl) != null

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> = parseResult(raw, cleanUrl).records

    private fun parseDecoded(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val path = endpoint(cleanUrl) ?: return emptyList()
        val floorResponse = path.endsWith("/floor")
        val root = decode(raw.json, path)
        val response = root.obj("response").takeIf { it.size() > 0 } ?: root
        val error = response.obj("error")
        require(error.number("errorno", "error_code", "errorCode") == 0L &&
            response.number("error_code", "errorCode") == 0L) { "Tieba API returned an error" }
        val data = response.obj("data")
        if (data.size() == 0) return emptyList()
        return records(raw, cleanUrl, data, floorResponse)
    }

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt("tieba.decode") {
        ParseResult.Success(parseDecoded(raw, cleanUrl))
    }

    private fun endpoint(url: String): String? = try {
        val uri = URI(url)
        if (uri.scheme !in setOf("https", "http") ||
            uri.host?.lowercase() !in setOf("tiebac.baidu.com", "tieba.baidu.com")) null
        else uri.path.takeIf { it in paths }
    } catch (_: Exception) { null }

    private fun decode(json: String, endpoint: String): JsonObject {
        val root = boundedTiebaJson(json)
        if (!root.has("encoding")) return root
        require(root.text("encoding").equals("base64", ignoreCase = true)) { "Unsupported encoding" }
        val body = root.text("body")
        require(body.length <= (TiebaWireReader.MAX_BYTES + 2) / 3 * 4)
        return TiebaProto.decode(Base64.getDecoder().decode(body), endpoint)
    }

    private fun records(raw: RawData, cleanUrl: String, data: JsonObject, floorResponse: Boolean): List<UniversalRecord> {
        val thread = data.obj("thread")
        val forum = data.obj("forum")
        val posts = if (floorResponse) listOf(data.obj("post")) else buildList {
            val first = data.obj("first_floor_post", "firstFloorPost")
            if (first.size() > 0) add(first)
            val modernFirst = data.obj("first_floor", "firstFloor")
            if (modernFirst.size() > 0) add(modernFirst)
            addAll(data.objects("post_list", "postList"))
        }
        val query = query(raw.url) + query(cleanUrl)
        val tid = thread.identifier("id").ifBlank { thread.identifier("thread_id", "threadId") }
            .ifBlank { posts.firstNotNullOfOrNull { it.identifier("tid").ifBlank { null } }.orEmpty() }
            .ifBlank { query["tid"].validId().ifBlank { query["kz"].validId() } }
        if (tid.isBlank()) return emptyList()
        val threadId = "tieba_thread_$tid"
        val title = thread.text("title").ifBlank { posts.firstOrNull { it.number("floor") == 1L }?.text("title").orEmpty() }
        val forumName = forum.text("name").ifBlank { thread.text("forum_name", "forumName") }
            .ifBlank { thread.obj("forum_info", "forumInfo").text("name") }
        val users = data.objects("user_list", "userList").associateBy { it.identifier("id") }
        val output = linkedMapOf<String, UniversalRecord>()
        val firstPid = thread.identifier("first_post_id", "firstPostId")
        val explicitFirst = setOf(
            data.obj("first_floor_post", "firstFloorPost").identifier("id"),
            data.obj("first_floor", "firstFloor").identifier("id")
        ).filter { it.isNotBlank() }

        fun emit(post: JsonObject, parent: UniversalRecord? = null, preview: Boolean = false): UniversalRecord? {
            val pid = post.identifier("id").ifBlank {
                if (floorResponse && parent == null) query["pid"].validId() else ""
            }
            if (pid.isBlank()) return null
            val isSub = parent != null
            val floor = if (isSub) metadata(parent!!).number("floor") else post.number("floor").let {
                if (it == 0L && (pid == firstPid || pid in explicitFirst)) 1L else it
            }
            val isThread = !isSub && floor == 1L
            val authorId = post.obj("author").identifier("id").ifBlank { post.identifier("author_id", "authorId") }
                .ifBlank { if (isThread) thread.obj("author").identifier("id").ifBlank { thread.identifier("author_id", "authorId") } else "" }
            val author = post.obj("author").deepCopy()
            val fallbacks = listOfNotNull(users[authorId], thread.obj("author").takeIf { isThread })
            for (fallback in fallbacks) {
                for ((key, value) in fallback.entrySet()) if (author.text(key).isBlank()) author.add(key, value)
            }
            val segments = post.objects("content").ifEmpty {
                if (isThread) thread.objects("first_post_content", "firstPostContent") else emptyList()
            }
            val rendered = render(segments, post)
            val extra = JsonObject().apply {
                addProperty("thread_id", tid)
                addProperty("post_id", pid)
                addProperty("capture_url", raw.url)
                addProperty("floor", floor)
                addProperty("thread_title", title)
                addProperty("forum_name", forumName)
                addProperty("is_preview", preview)
                val replyCount = if (isThread) thread.number("reply_num", "replyNum")
                    else if (isSub) 0L else post.number("sub_post_number", "subPostNumber")
                addProperty("reply_count", replyCount)
                addProperty("thread_reply_count", thread.number("reply_num", "replyNum"))
                                if (isThread) {
                                    // PbFloor can omit this field; absence must not overwrite a captured count with zero.
                                    thread.text("collect_num", "collectNum").toLongOrNull()?.takeIf { it >= 0 }
                                        ?.let { addProperty("favlists_count", it) }
                                }
                forum.identifier("id").ifBlank { thread.identifier("forum_id", "forumId") }
                    .ifBlank { thread.obj("forum_info", "forumInfo").identifier("id") }
                    .takeIf { it.isNotBlank() }?.let { addProperty("forum_id", it) }
                if (isSub) {
                    addProperty("floor_post_id", metadata(parent!!).text("post_id"))
                    post.number("floor").takeIf { it > 0 }?.let { addProperty("sub_floor", it) }
                }
                if (segments.isNotEmpty()) add("content_segments", JsonArray().apply { segments.forEach { add(it) } })
                rendered.images.takeIf { it.isNotEmpty() }?.let { add("image_urls", strings(it)) }
                rendered.videos.takeIf { it.isNotEmpty() }?.let { add("video_urls", strings(it)) }
                author.text("portrait").takeIf { it.isNotBlank() }?.let { addProperty("portrait", it) }
                author.text("ip_address", "ipAddress").takeIf { it.isNotBlank() }?.let { addProperty("ip_address", it) }
                author.number("level_id", "levelId").takeIf { it > 0 }?.let { addProperty("author_level", it) }
                post.obj("agree").number("agree_num", "agreeNum").takeIf { it > 0 }?.let { addProperty("agree_count", it) }
                val page = data.obj("page")
                page.number("current_page", "currentPage").takeIf { it > 0 }?.let { addProperty("page", it) }
                if (isSub) replyTarget(segments)?.let { (name, id) ->
                    addProperty("reply_to_name", name)
                    if (id.isNotBlank()) addProperty("reply_to_id", id)
                }
            }
            val time = post.number("time").takeIf { it > 0 }
                ?: if (isThread) thread.number("create_time", "createTime") else 0L
            val record = UniversalRecord(
                contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
                platform = platform, recordType = if (isSub) "comment" else if (isThread) "lou" else "floor",
                sourceApp = raw.hostApp.ifBlank { PlatformCatalog.defaultPackage("tieba") },
                platformId = if (isThread) threadId else "tieba_post_$pid",
                title = if (isThread) title.ifBlank { post.text("title") } else "",
                content = rendered.markdown, authorId = authorId,
                authorName = author.text("name_show", "nameShow").ifBlank { author.text("name") },
                authorAvatar = sequenceOf(author.text("avatar_url", "avatarUrl"), author.text("portraith"), author.text("portrait"),
                    author.obj("user_show_info").obj("feed_head").obj("image_data").text("img_url"))
                    .firstOrNull { webUrl(it) } ?: "",
                sourceUrl = "https://tieba.baidu.com/p/$tid" + if (isThread) "" else "?pid=$pid#$pid",
                parentId = parent?.platformId ?: if (isThread) "" else threadId,
                publishTime = if (time > 0 && time <= Long.MAX_VALUE / 1000) time * 1000 else 0,
                captureTime = raw.timestamp, extraMetadata = extra.toString()
            )
            val existing = output[record.platformId]
            if (existing == null) output[record.platformId] = record
            else {
                val body = TiebaRelations.mergeRecordContent(existing, record)
                existing.content = body.content
                existing.contentFormat = body.contentFormat
                existing.extraMetadata = TiebaRelations.mergeExtra(existing.extraMetadata, record.extraMetadata)
            }
            return output[record.platformId]
        }

        for (post in posts) {
            if (post.size() == 0) continue
            val parent = emit(post) ?: continue
            val previewList = post.obj("sub_post_list", "subPostList")
            for (sub in previewList.objects("sub_post_list", "subPostList")) emit(sub, parent, preview = true)
            if (floorResponse) {
                val total = data.number("subpost_num", "subpostNum").coerceAtLeast(data.obj("page").number("total_count", "totalCount"))
                if (total > 0 && parent.recordType == "floor") {
                    val extra = metadata(parent)
                    extra.addProperty("reply_count", maxOf(extra.number("reply_count"), total))
                    parent.extraMetadata = extra.toString()
                }
                for (sub in data.objects("subpost_list", "subpostList")) emit(sub, parent)
            }
        }
        return output.values.toList()
    }

    private data class Rendered(val markdown: String, val images: List<String>, val videos: List<String>)

    private fun render(segments: List<JsonObject>, post: JsonObject): Rendered {
        val images = linkedSetOf<String>()
        val videos = linkedSetOf<String>()
        val markdown = buildString {
            for (part in segments) {
                val text = part.text("text")
                val link = part.text("link")
                val src = part.text("origin_src", "originSrc", "big_cdn_src", "bigCdnSrc", "big_src", "bigSrc",
                    "dynamic", "cdn_src", "cdnSrc", "cdn_src_active", "cdnSrcActive", "src", "_static")
                when (part.number("type")) {
                    0L, 4L -> append(text)
                    1L -> append(if (webUrl(link)) "[${label(text.ifBlank { link })}](<${destination(link)}>)" else text)
                    2L -> {
                        val alt = part.text("c").ifBlank { text }.ifBlank { "表情" }
                        val image = part.text("src").ifBlank { text.takeIf { webUrl(it) }.orEmpty() }
                        if (webUrl(image)) append("![${label(alt)}](<${destination(image)}>)")
                        else append(if (alt.startsWith("[")) alt else "[$alt]")
                    }
                    3L, 20L -> if (webUrl(src)) {
                        images.add(src)
                        append("\n\n![${label(text)}](<${destination(src)}>)\n\n")
                    } else append(text)
                    5L -> {
                        if (webUrl(src)) { images.add(src); append("\n\n![视频封面](<${destination(src)}>)\n\n") }
                        val url = link.takeIf { webUrl(it) } ?: text.takeIf { webUrl(it) }
                        if (url != null) { videos.add(url); append("[视频](<${destination(url)}>)\n\n") }
                        else append(text)
                    }
                    10L -> append("[语音${part.number("during_time", "duringTime").takeIf { it > 0 }?.let { " ${it}秒" }.orEmpty()}]")
                    else -> {
                        append(text)
                        if (webUrl(link)) append(" [链接](<${destination(link)}>)")
                        if (webUrl(src)) { images.add(src); append("\n\n![图片](<${destination(src)}>)\n\n") }
                    }
                }
            }
            val info = post.obj("video_info", "videoInfo")
            val videoUrls = post.array("arr_video", "arrVideo").mapNotNull { it.takeIf { v -> v.isJsonPrimitive }?.asString } +
                listOf(info.text("video_url", "videoUrl"))
            for (url in videoUrls.filter { webUrl(it) }) if (videos.add(url)) append("\n\n[视频](<${destination(url)}>)")
        }
        return Rendered(markdown.trim(), images.toList(), videos.toList())
    }

    private fun replyTarget(parts: List<JsonObject>): Pair<String, String>? {
        val prefix = parts.take(3).joinToString("") { it.text("text").take(512) }.trimStart().take(512)
        if (!prefix.startsWith("回复")) return null
        val match = Regex("^回复\\s*(.+?)\\s*[:：]").find(prefix) ?: return null
        val name = match.groupValues[1].trim().removePrefix("@")
        if (name.isBlank()) return null
        val mention = parts.take(3).firstOrNull {
            it.number("type") == 4L && it.text("text").trim().removePrefix("@") == name
        }
        return name to mention?.identifier("uid").orEmpty()
    }

    private fun query(url: String): Map<String, String> = try {
        URI(url).rawQuery.orEmpty().split('&').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
        }.toMap()
    } catch (_: Exception) { emptyMap() }

    private fun metadata(record: UniversalRecord) = JsonParser.parseString(record.extraMetadata).asJsonObject
    private fun strings(values: Collection<String>) = JsonArray().apply { values.forEach { add(it) } }
    private fun webUrl(value: String) = try { URI(value).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() } } catch (_: Exception) { false }
    private fun label(value: String) = value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
    private fun destination(value: String) = value.replace("<", "%3C").replace(">", "%3E").replace("\n", "%0A").replace("\r", "%0D")
}

/** Keep both capture parsing and public metadata merging bounded before Gson recursion. */
internal fun boundedTiebaJson(json: String): JsonObject {
    require(json.length <= TiebaWireReader.MAX_BYTES * 2) { "JSON too large" }
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in json) {
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 64) { "JSON nesting too deep" } }
            '}', ']' -> { depth--; require(depth >= 0) }
        }
    }
    require(depth == 0 && !quoted)
    return JsonParser.parseString(json).asJsonObject
}

internal fun JsonObject.text(vararg names: String): String = names.asSequence()
    .mapNotNull { get(it)?.takeIf { value -> value.isJsonPrimitive }?.asString }.firstOrNull { it.isNotBlank() }.orEmpty()
internal fun JsonObject.number(vararg names: String): Long = text(*names).toLongOrNull() ?: 0L
internal fun String?.validId(): String = this?.takeIf { it.length <= 20 && it.all { char -> char in '0'..'9' } }
    ?.trimStart('0')?.takeIf { it.isNotEmpty() && (it.length < 20 || it <= "18446744073709551615") }.orEmpty()
internal fun JsonObject.identifier(vararg names: String): String = names.asSequence().map { text(it).validId() }
    .firstOrNull { it.isNotBlank() }.orEmpty()
internal fun JsonObject.obj(vararg names: String): JsonObject = names.asSequence().mapNotNull { get(it)?.takeIf { v -> v.isJsonObject }?.asJsonObject }
    .firstOrNull() ?: JsonObject()
internal fun JsonObject.array(vararg names: String): JsonArray = names.asSequence().mapNotNull { get(it)?.takeIf { v -> v.isJsonArray }?.asJsonArray }
    .firstOrNull() ?: JsonArray()
internal fun JsonObject.objects(vararg names: String): List<JsonObject> = array(*names).filter { it.isJsonObject }.map(JsonElement::getAsJsonObject)
