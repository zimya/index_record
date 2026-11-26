package com.melonhu.indexrecord.parser.bilibili

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.BilibiliTopic
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.bilibiliInteger
import com.melonhu.indexrecord.parser.ParseDiagnostic
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.RawDataParser
import java.net.URI

/** Converts the official hook's and other adapters' normalized data to Bilibili records. */
object BilibiliParser : RawDataParser {
    override val platform = "bilibili"
    private const val BASE = "https://grpc.biliapi.net/"
    private val kindsByUrl = mapOf(
        BASE + "bilibili.app.viewunite.v1.View/View" to "video",
        BASE + "bilibili.app.dynamic.v2.Opus/OpusDetail" to "opus",
        BASE + "bilibili.main.community.reply.v1.Reply/MainList" to "comments",
        BASE + "bilibili.main.community.reply.v1.Reply/DetailList" to "comments"
    )
    private val bvidPattern = Regex("BV[A-Za-z0-9]{10}")
    private val commonCounts = listOf("like", "reply")
    private val videoCounts = commonCounts + listOf("view", "danmaku", "coin", "favorite", "share", "duration")

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean =
        (raw.hostApp == PlatformCatalog.BILIBILI_PACKAGE || raw.hostApp == PlatformCatalog.BILIBILI_INTERNATIONAL_PACKAGE) &&
            raw.url.ifEmpty { cleanUrl } in kindsByUrl

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> = parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt(platform) {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Not a Bilibili capture route")
        val url = raw.url.ifEmpty { cleanUrl }
        val envelope = JsonParser.parseString(raw.json).asJsonObject
        parseNormalized(raw, url, kindsByUrl.getValue(url), envelope)
    }

    internal fun parseNormalized(raw: RawData, url: String, kind: String, envelope: JsonObject): ParseResult {
        require(envelope.text("kind") == kind) { "Expected normalized $kind envelope" }
        return when (kind) {
            "video" -> ParseResult.Success(listOf(parseVideo(raw, envelope)))
            "opus" -> ParseResult.Success(listOf(parseOpus(raw, envelope)))
            "comments" -> parseComments(raw, url, envelope)
            else -> error("Unsupported normalized Bilibili kind")
        }
    }

    private fun parseVideo(raw: RawData, data: JsonObject): UniversalRecord {
        val aid = data.requiredInteger("aid", minimum = 1)
        val topic = BilibiliTopic(BilibiliTopic.VIDEO, aid)
        val bvid = data.text("bvid")
        val metadata = data.metadata(topic, videoCounts).apply {
            addProperty("aid", aid.toString())
            if (bvid.isNotBlank()) addProperty("bvid", bvid)
            imageUrl(data.text("cover"))?.let { addProperty("cover", it) }
        }
        val publicId = bvid.takeIf { bvidPattern.matches(it) } ?: "av$aid"
        return record(raw, data, "video", topic.parentId, data.text("description"), metadata,
            sourceUrl = "https://www.bilibili.com/video/$publicId")
    }

    private fun parseOpus(raw: RawData, data: JsonObject): UniversalRecord {
        val topic = data.requiredTopic()
        require(topic.type != BilibiliTopic.VIDEO) { "Opus cannot own a video topic" }
        val opusId = data.bilibiliInteger("opus_id")?.takeIf { it > 0 }
        val metadata = data.metadata(topic, commonCounts).apply {
            opusId?.let { addProperty("opus_id", it.toString()) }
            copyImages(data)
        }
        return record(raw, data, "opus", topic.parentId, data.text("text"), metadata,
            sourceUrl = opusId?.let { "https://www.bilibili.com/opus/$it" }.orEmpty())
    }

    private fun parseComments(raw: RawData, url: String, data: JsonObject): ParseResult {
        val topic = data.requiredTopic()
        val rows = data.getAsJsonArray("replies") ?: error("Missing replies array")
        val records = mutableListOf<UniversalRecord>()
        val diagnostics = mutableListOf<ParseDiagnostic>()
        rows.forEachIndexed { index, row ->
            try {
                require(row.isJsonObject) { "Expected reply object" }
                records.add(parseComment(raw, url, topic, row.asJsonObject))
            } catch (e: IllegalArgumentException) {
                diagnostics.add(ParseDiagnostic("bilibili.replies[$index]", e.message ?: "Invalid reply"))
            }
        }
        return ParseResult.decoded(records, diagnostics)
    }

    private fun parseComment(raw: RawData, url: String, topic: BilibiliTopic, row: JsonObject): UniversalRecord {
        val id = row.requiredInteger("id", minimum = 1)
        val parent = row.requiredInteger("parent", minimum = 0)
        val root = row.requiredInteger("root", minimum = 0)
        require((parent == 0L) == (root == 0L) && parent != id && root != id) { "Invalid reply ancestry" }
        val metadata = row.metadata(topic, commonCounts).apply {
            addProperty("parent", parent.toString())
            addProperty("root", root.toString())
            copyImages(row)
        }
        val parentId = if (parent == 0L) topic.parentId else topic.commentId(parent)
        return record(raw, row, "comment", topic.commentId(id), row.text("text"), metadata, url, parentId)
    }

    private fun record(
        raw: RawData, data: JsonObject, type: String, id: String, body: String, metadata: JsonObject,
        sourceUrl: String, parentId: String = ""
    ): UniversalRecord = UniversalRecord(
        platform = platform,
        recordType = type,
        sourceApp = raw.hostApp,
        platformId = id,
        title = if (type == "comment") "" else data.text("title"),
        content = body,
        contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue,
        authorName = data.text("author"),
        authorAvatar = imageUrl(data.text("author_avatar")).orEmpty(),
        sourceUrl = sourceUrl,
        parentId = parentId,
        publishTime = data.bilibiliInteger("published_at")?.takeIf { it in 1..(Long.MAX_VALUE / 1000) }
            ?.times(1000) ?: 0,
        captureTime = raw.timestamp,
        extraMetadata = metadata.toString()
    )

    private fun JsonObject.requiredTopic(): BilibiliTopic =
        requireNotNull(BilibiliTopic.fromMetadata(this)) { "Expected positive topic_type and topic_id" }

    private fun JsonObject.requiredInteger(key: String, minimum: Long): Long =
        requireNotNull(bilibiliInteger(key)?.takeIf { it >= minimum }) { "Invalid $key" }

    private fun JsonObject.text(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

    private fun JsonObject.metadata(topic: BilibiliTopic, counts: List<String>): JsonObject = JsonObject().apply {
        topic.writeTo(this)
        for (key in counts) this@metadata.bilibiliInteger(key)?.takeIf { it >= 0 }?.let { addProperty(key, it) }
    }

    private fun JsonObject.copyImages(data: JsonObject) {
        val images = data.get("images")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        val urls = images.mapNotNull {
            it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let(::imageUrl)
        }.distinct()
        if (urls.isNotEmpty()) add("images", JsonArray().apply { urls.forEach { add(it) } })
    }

    private fun imageUrl(value: String): String? {
        val url = if (value.startsWith("//")) "https:$value" else value
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf { uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null }
    }
}
