package com.melonhu.indexrecord.parser.weibo

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.RawDataParser
import org.jsoup.Jsoup
import com.melonhu.indexrecord.markdown.escapeMarkdownText
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

/** Traverse content containers only, never arbitrary objects containing an ID. */
object WeiboParser : RawDataParser {
    override val platform = "weibo"
    private val hosts = setOf("api.weibo.cn", "api.weibo.com", "m.weibo.cn")
    override fun canHandle(cleanUrl: String, raw: RawData): Boolean =
        runCatching { URI(cleanUrl).host.lowercase() in hosts }.getOrDefault(false)

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        if (!canHandle(cleanUrl, raw)) return emptyList()
        val root = runCatching { JsonParser.parseString(raw.json).asJsonObject }.getOrNull() ?: return emptyList()
        val path = runCatching { URI(cleanUrl).path }.getOrDefault("")
        val query = runCatching {
            URI(raw.url).rawQuery.orEmpty().split('&').mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2) pair[0] to URLDecoder.decode(pair[1], "UTF-8") else null
            }.toMap()
        }.getOrDefault(emptyMap())
        val statusId = root.obj("status")?.id().orEmpty()
            .ifEmpty { query["id"].orEmpty() }.ifEmpty { query["mid"].orEmpty() }
            .ifEmpty { query["source_mid"].orEmpty().removePrefix("mid:").takeIf { it.all(Char::isDigit) }.orEmpty() }
        val records = linkedMapOf<String, UniversalRecord>()
        fun key(type: String, id: String) = if (id.isEmpty() || id == "0") "" else "weibo_${type}_$id"
        fun emit(o: JsonObject, type: String, parent: String, commentStatusId: String = statusId): String {
            val id = o.id()
            val pid = key(type, id)
            val user = o.obj("user") ?: return ""
            val longText = o.obj("longText")
            // Sparse patches cannot safely be inserted by the current generic persistence path.
            if (pid.isEmpty() || user.id().isBlank() || user.str("screen_name").ifEmpty { user.str("name") }.isBlank() || ad(o)) return ""
            val longTextContent = longText?.str("longTextContent").orEmpty().ifEmpty { longText?.str("content").orEmpty() }
            val batchContent = if (type == "status" && path.endsWith("/longtext_show_batch")) o.str("content") else ""
            val richText = longTextContent.ifEmpty { o.str("longTextContent") }.ifEmpty { batchContent }
            if (richText.isBlank() && listOf("text", "text_raw").none { o.str(it).isNotBlank() }) return ""
            val text = if (richText.isNotEmpty()) html(richText)
                else o.str("text_raw").ifEmpty { html(o.str("text")) }
            val images = images(o)
            val content = if (images.isEmpty()) text
                else escapeMarkdownText(text) + images.joinToString("") { "\n\n![]($it)" }
            val meta = JsonObject()
            listOf("like_count" to listOf("attitudes_count", "like_counts", "like_count"),
                "retweet_count" to listOf("reposts_count"), "comment_count" to listOf("comments_count", "total_number"),
                "views_count" to listOf("reads_count", "read_count", "views_count")).forEach { (target, sources) ->
                sources.firstOrNull { o[it]?.isJsonPrimitive == true }?.let { meta.add(target, o[it]) }
            }
            meta.addProperty("author_headline", user.str("description"))
            meta.addProperty("source", html(o.str("source")))
            meta.addProperty("region_name", o.str("region_name"))
            if (o.has("isLongText")) meta.add("is_long_text", o["isLongText"])
            if (richText.isNotBlank()) meta.addProperty("full_text", true)
            if (images.isNotEmpty()) meta.add("images", JsonArray().apply { images.forEach { add(it) } })
            val record = UniversalRecord(
                            contentFormat = if (images.isEmpty()) RecordBodyFormat.PLAIN_TEXT.storageValue else RecordBodyFormat.MARKDOWN.storageValue,
                            platform = platform, recordType = type, sourceApp = raw.hostApp,
                platformId = pid, content = content.trim(), authorId = user.id(),
                authorName = user.str("screen_name").ifEmpty { user.str("name") },
                authorAvatar = user.str("avatar_hd").ifEmpty { user.str("avatar_large") }.ifEmpty { user.str("profile_image_url") },
                sourceUrl = if (type == "status") "https://m.weibo.cn/detail/$id" else if (commentStatusId.isNotEmpty()) "https://m.weibo.cn/detail/$commentStatusId?cid=$id" else "https://m.weibo.cn/comment/$id",
                parentId = parent, publishTime = time(o.str("created_at")), captureTime = raw.timestamp,
                extraMetadata = meta.toString())
            val previous = records[pid]
            if (previous == null || record.content.length > previous.content.length) records[pid] = record
            return pid
        }
        fun status(o: JsonObject?, depth: Int = 0): String {
            if (o == null || depth > 16 || ad(o)) return ""
            val parent = status(o.obj("retweeted_status"), depth + 1)
            return emit(o, "status", parent)
        }

        fun comment(o: JsonObject?, fallback: String, depth: Int = 0, statusFallback: String = statusId) {
            if (o == null || depth > 16 || ad(o)) return
            val id = o.id()
            val sid = statusFallback.ifEmpty { o.analysisMid() }
            val reply = o.obj("reply_comment")
            val rootId = o.str("rootidstr").ifEmpty { o.str("rootid") }
            val parent = reply?.id()?.takeIf { it != id && it != "0" }?.let { key("comment", it) }
                ?: rootId.takeIf { it.isNotEmpty() && it != "0" && it != id }?.let { key("comment", it) }
                ?: fallback.takeUnless { it == key("comment", id) }.orEmpty().ifEmpty { key("status", sid) }
            if (reply != null && reply.id() != id) comment(reply, fallback, depth + 1, sid)
            val own = emit(o, "comment", parent, sid)
            if (own.isNotEmpty()) o.array("comments").forEach { if (it.isJsonObject) comment(it.asJsonObject, own, depth + 1, sid) }
        }
        fun items(array: JsonArray, depth: Int = 0) {
            if (depth > 16) return
            array.forEach { item ->
                if (!item.isJsonObject) return@forEach
                val o = item.asJsonObject
                if (ad(o)) return@forEach
                when {
                    o.str("category") == "feed" -> status(o.obj("data"))
                    o.str("type") == "comment" -> comment(o.obj("data"), key("status", statusId))
                    o.str("category") == "group" -> items(o.array("items"), depth + 1)
                    o.str("card_type") in setOf("9", "89") -> status(o.obj("mblog"))
                    o.str("card_type") == "11" -> items(o.array("card_group"), depth + 1)
                }
            }
        }
        when {
            path.endsWith("/build_comments") -> {
                status(root.obj("status"))
                val parent = key("status", statusId)
                comment(root.obj("rootComment"), parent)
                val fallback = key("comment", root.obj("rootComment")?.id().orEmpty()).ifEmpty { parent }
                root.array("datas").forEach { if (it.isJsonObject && it.asJsonObject.str("type") in setOf("0", "42")) comment(it.asJsonObject.obj("data"), fallback) }
                root.array("data").forEach { if (it.isJsonObject) comment(it.asJsonObject, fallback) }
                root.array("comments").forEach { if (it.isJsonObject) comment(it.asJsonObject, fallback) }
            }
            path.endsWith("/container_detail") -> status(root.obj("detailInfo")?.obj("status"))
            path.endsWith("/container_detail_comment") || path.endsWith("/container_positive") || path.contains("timeline") -> {
                items(root.array("items"))
                root.array("statuses").forEach { if (it.isJsonObject) status(it.asJsonObject) }
            }
            path.endsWith("/longtext_show_batch") -> root.obj("longtexts")?.array("data")?.forEach {
                if (it.isJsonObject) status(it.asJsonObject)
            }
            path.endsWith("/container/getIndex") || path.endsWith("/cardlist") -> items(root.obj("data")?.array("cards") ?: root.array("cards"))
            path.endsWith("/statuses/show") -> status(root.obj("data") ?: root)
            path.endsWith("/statuses/longtext") -> status(root.obj("data") ?: root)
        }
        return records.values.toList()
    }

    private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.id() = str("idstr").ifEmpty { str("id") }.ifEmpty { str("mid") }
    private fun JsonObject.analysisMid(): String = str("analysis_extra").split('|')
        .firstNotNullOfOrNull { it.takeIf { p -> p.startsWith("mid:") }?.removePrefix("mid:")?.takeIf { v -> v.isNotEmpty() && v.all(Char::isDigit) } }
        .orEmpty()
    private fun ad(o: JsonObject): Boolean = listOf("is_ad", "ad_marked", "is_promoted").any { o.str(it) in setOf("1", "true") } ||
        o.str("type") in setOf("ad", "heartbeat") || o.str("category") in setOf("ad", "heartbeat") || o.obj("ad") != null
    private fun html(text: String): String {
        val doc = Jsoup.parseBodyFragment(text)
        doc.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
        doc.select("p").forEach { it.after("\n") }
        doc.select("img").forEach { it.replaceWith(org.jsoup.nodes.TextNode(it.attr("alt"))) }
        return doc.wholeText().trim()
    }
    private fun images(o: JsonObject): List<String> {
        val urls = linkedSetOf<String>()
        fun image(pic: JsonObject?) {
            if (pic == null) return
            val url = listOf("original", "largest", "large", "bmiddle", "thumbnail").firstNotNullOfOrNull { pic.obj(it)?.str("url")?.takeIf(String::isNotEmpty) }
                ?: pic.str("url").ifEmpty { pic.str("original_pic") }
            if (url.startsWith("https://") || url.startsWith("http://")) urls.add(url)
        }
        val infos = o.obj("pic_infos")
        val ids = o.array("pic_ids")
        if (ids.size() > 0) ids.forEach { if (it.isJsonPrimitive) image(infos?.obj(it.asString)) }
        else infos?.entrySet()?.forEach { if (it.value.isJsonObject) image(it.value.asJsonObject) }
        o.array("pics").forEach { if (it.isJsonObject) image(it.asJsonObject) }
        image(o.obj("pic"))
        o.obj("mix_media_info")?.array("items")?.forEach {
            if (it.isJsonObject && it.asJsonObject.str("type") == "pic") image(it.asJsonObject.obj("data"))
        }
        if (urls.isEmpty()) o.str("original_pic").takeIf { it.startsWith("http") }?.let(urls::add)
        return urls.toList()
    }
    private fun time(value: String): Long = value.toLongOrNull()?.let { if (it < 100000000000L) it * 1000 else it }
        ?: runCatching { SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH).parse(value)?.time ?: 0 }.getOrDefault(0)
}
