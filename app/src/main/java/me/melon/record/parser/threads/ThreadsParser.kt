package me.melon.record.parser.threads

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonStreamParser
import me.melon.record.data.model.RawData
import me.melon.record.data.model.RecordBodyFormat
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.RawDataParser
import me.melon.record.parser.ParseResult
import me.melon.record.parser.ParseDiagnostic
import java.net.URI

/** Only explicit displayed media containers are traversed; captions and prefetches are not posts. */
object ThreadsParser : RawDataParser {
    override val platform = "threads"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean = runCatching {
        val uri = URI(cleanUrl)
        val host = uri.host.orEmpty().lowercase()
        val trusted = raw.hostApp == "com.instagram.barcelona" ||
            host == "threads.com" || host.endsWith(".threads.com") ||
            host == "threads.net" || host.endsWith(".threads.net")
        trusted && (uri.path.orEmpty().contains("/feed/") || uri.path.orEmpty().contains("/media/") ||
            uri.path.orEmpty().contains("/text_feed/") || uri.path.orEmpty().contains("/graphql"))
    }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> = parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt("threads") {
        val diagnostics = mutableListOf<ParseDiagnostic>()
        ParseResult.decoded(parseChunks(raw, cleanUrl, diagnostics), diagnostics)
    }

    private fun parseChunks(raw: RawData, cleanUrl: String, diagnostics: MutableList<ParseDiagnostic>): List<UniversalRecord> {
        if (!canHandle(cleanUrl, raw)) return emptyList()

        val records = linkedMapOf<String, UniversalRecord>()
        fun emit(post: JsonObject) {
            if (isAd(post)) return
            val info = post.obj("text_post_app_info") ?: return
            val id = postId(post.str("pk").ifEmpty { post.str("id") })
            if (id.isEmpty()) return
            val user = post.obj("user")
            val parent = postId(info.str("reply_to_post_id")).takeUnless { it == id }.orEmpty()
            val meta = JsonObject()
            listOf("like_count", "reply_count", "comment_count", "repost_count", "reshare_count", "quote_count", "view_count").forEach { key ->
                sequenceOf(post[key], info[key]).filterNotNull()
                    .firstOrNull { it.isJsonPrimitive && it.asString.toLongOrNull() != null }
                    ?.let { meta.add(key, it) }
            }
            if (!meta.has("reply_count") && !meta.has("comment_count")) {
                info["direct_reply_count"]?.takeIf { it.isJsonPrimitive && it.asString.toLongOrNull() != null }
                    ?.let { meta.add("reply_count", it) }
            }
            listOf("reply_level", "root_post_id").forEach { key ->
                info[key]?.takeIf { it.isJsonPrimitive }?.let { meta.add(key, it) }
            }
            if (parent.isNotEmpty()) meta.addProperty("reply_to_post_id", parent.removePrefix("threads_post_"))
            user?.str("username")?.takeIf { it.isNotBlank() }?.let { meta.addProperty("username", it) }
            val share = info.obj("share_info")?.takeUnless(::isAd)
            listOf("quoted_post", "reposted_post").forEach { key ->
                share?.obj(key)?.takeUnless(::isAd)?.let { related ->
                    postId(related.str("pk").ifEmpty { related.str("id") }).takeIf { it.isNotEmpty() }
                        ?.let { meta.addProperty(key + "_id", it) }
                }
            }
            val record = UniversalRecord(
                contentFormat = RecordBodyFormat.MARKDOWN.storageValue,
                platform = platform, recordType = if (parent.isNotEmpty() || info.str("is_reply") == "true") "comment" else "thread",
                sourceApp = raw.hostApp, platformId = id, parentId = parent,
                content = body(post), authorId = user?.str("pk").orEmpty().ifEmpty { user?.str("id").orEmpty() },
                authorName = user?.str("full_name").orEmpty().ifBlank { user?.str("username").orEmpty() },
                authorAvatar = user?.str("profile_pic_url").orEmpty(),
                sourceUrl = safeUrl(post.str("permalink")).ifEmpty {
                    val code = post.str("code")
                    if (code.isNotBlank()) "https://www.threads.com/@${user?.str("username").orEmpty()}/post/$code" else ""
                },
                publishTime = (post.str("taken_at").toLongOrNull() ?: 0L) * 1000L,
                captureTime = raw.timestamp, extraMetadata = meta.toString()
            )
            records[id]?.let { ThreadsRelations.mergeRecord(it, record) }
            records[id] = record
        }
        fun walk(element: JsonElement, depth: Int = 0) {
            if (depth > 24) return
            if (element.isJsonArray) {
                element.asJsonArray.forEach { walk(it, depth + 1) }
                return
            }
            if (!element.isJsonObject) return
            val obj = element.asJsonObject
            if (isAd(obj) || listOf("new_stories", "old_stories", "priority_stories", "users", "user_tstf_groups").any(obj::has)) return
            obj.array("feed_items").forEach { item ->
                if (item.isJsonObject) {
                    val wrapper = item.asJsonObject
                    if (!isAd(wrapper) && !wrapper.has("suggested_users")) {
                        wrapper.obj("text_post_app_thread")?.let { walk(it, depth + 1) }
                    }
                }
            }
            // related_threads is a recommendation container, unlike containing/sibling/reply threads.
            // Do not descend through arbitrary GraphQL edges or recommendation/prefetch fields.
            listOf("post", "media").forEach { key -> obj.obj(key)?.let { if (!isAd(it)) emit(it) } }
            listOf("data", "threads", "thread_items", "medias", "reply_threads", "containing_thread", "sibling_threads").forEach { key ->
                obj[key]?.let { walk(it, depth + 1) }
            }
            // medias may contain post objects directly rather than media wrappers.
            if (obj.has("text_post_app_info")) emit(obj)
        }
        readChunks(raw.json, diagnostics) { chunk -> walk(chunk) }
        return records.values.toList()
    }

    private fun readChunks(json: String, diagnostics: MutableList<ParseDiagnostic>, consume: (JsonElement) -> Unit) {
        fun read(stream: JsonStreamParser): Boolean = try {
            while (stream.hasNext()) {
                val chunk = stream.next()
                try {
                    require(chunk.isJsonObject || chunk.isJsonArray) { "Expected Threads object or array" }
                    consume(chunk)
                } catch (e: Exception) {
                    diagnostics.add(ParseDiagnostic("threads.chunk", e.message.orEmpty(), e))
                }
            }
            true
        } catch (e: Exception) {
            diagnostics.add(ParseDiagnostic("threads.stream", e.message.orEmpty(), e))
            false
        }
        if (json.isBlank()) diagnostics.add(ParseDiagnostic("threads.stream", "Empty response body"))
        if (!read(JsonStreamParser(json))) {
            // NDJSON has a recoverable line boundary after syntax errors. Replay is safe:
            // records are deduplicated/merged by stable ID in their original order.
            json.lineSequence().filter { it.isNotBlank() }.forEach { read(JsonStreamParser(it)) }
        }
    }

    internal fun postId(value: String): String {
        // Gson's tree retains the decimal token; never pass IDs through Double or a generic Map.
        val number = value.substringBefore('_')
        return if (number.isNotEmpty() && number.all { it in '0'..'9' } && number.any { it != '0' }) "threads_post_$number" else ""
    }

    internal fun isAd(obj: JsonObject): Boolean = listOf("ads", "ad", "ad_info", "ad_id", "ad_data", "ad_action", "sponsored_info").any { key ->
        obj[key]?.let { !it.isJsonNull && it.toString() !in setOf("false", "0", "\"\"", "[]", "{}") } == true
    } || obj.obj("injected")?.let(::isAd) == true || listOf("is_ad", "is_sponsored").any { obj.str(it) in setOf("true", "1") } ||
        listOf("type", "__typename", "thread_type").any { obj.str(it).lowercase() in setOf("ad", "ads", "sponsored", "heartbeat", "notification", "user_recommendation") }

    private fun body(post: JsonObject, depth: Int = 0): String {
        if (depth > 4 || isAd(post)) return ""
        val text = post.obj("caption")?.str("text").orEmpty()
            .ifEmpty { post.obj("text_with_entities")?.str("text").orEmpty() }
            .ifEmpty { post.obj("text_post_app_info")?.obj("text_fragments")?.array("fragments")
                ?.joinToString("") { if (it.isJsonObject) it.asJsonObject.str("plaintext") else "" }.orEmpty() }
        val blocks = mutableListOf<String>()
        if (text.isNotBlank()) blocks.add(escapeText(text))
        fun media(item: JsonObject) {
            if (isAd(item)) return
            val image = bestUrl(item.obj("image_versions2")?.array("candidates") ?: JsonArray())
            val video = bestUrl(item.array("video_versions"))
            when {
                video.isNotEmpty() -> blocks.add(if (image.isNotEmpty()) "[![视频封面]($image)]($video)\n\n[▶ 点击播放视频]($video)" else "[▶ 点击播放视频]($video)")
                image.isNotEmpty() -> blocks.add("![图片]($image)")
            }
        }
        val carousel = post.array("carousel_media")
        if (carousel.size() > 0) carousel.forEach { if (it.isJsonObject) media(it.asJsonObject) } else media(post)
        val share = post.obj("text_post_app_info")?.obj("share_info")
        if (share != null && !isAd(share)) {
            listOf("quoted_post", "reposted_post").forEach { key ->
                share.obj(key)?.takeUnless(::isAd)?.let { related ->
                    val quoted = body(related, depth + 1)
                    if (quoted.isNotBlank()) {
                        val author = related.obj("user")?.str("username").orEmpty()
                        blocks.add(("@$author\n\n$quoted").lineSequence().joinToString("\n") { "> $it" })
                    }
                }
            }
        }
        return blocks.joinToString("\n\n")
    }

    private fun bestUrl(array: JsonArray): String = array.filter { it.isJsonObject }
        .map { it.asJsonObject }.filter { safeUrl(it.str("url")).isNotEmpty() }
        .maxByOrNull { (it.str("width").toLongOrNull() ?: 0) * (it.str("height").toLongOrNull() ?: 0) }
        ?.str("url").orEmpty().replace("(", "%28").replace(")", "%29")

    private fun escapeText(text: String): String = buildString {
        text.forEach { c ->
            if (c in "\\`*_{}[]<>#!|~") append('\\')
            append(c)
        }
    }
    private fun safeUrl(value: String): String = value.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
    private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
}
