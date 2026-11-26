package com.melonhu.indexrecord.parser.piliplus

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.bilibiliInteger
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.RawDataParser
import com.melonhu.indexrecord.parser.bilibili.BilibiliParser

/** Routes stock PiliPlus response bodies, separately from the official hook's normalized captures. */
internal object PiliPlusParser : RawDataParser {
    override val platform = BilibiliParser.platform
    private const val VIDEO = "https://api.bilibili.com/x/web-interface/wbi/view"
    private const val MAIN_LIST = "https://grpc.biliapi.net/bilibili.main.community.reply.v1.Reply/MainList"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean =
        raw.hostApp == PlatformCatalog.PILIPLUS_PACKAGE &&
            when (raw.url.ifEmpty { cleanUrl }) {
                VIDEO, MAIN_LIST -> true
                else -> false
            }

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> = parseResult(raw, cleanUrl).records

    override fun parseResult(raw: RawData, cleanUrl: String): ParseResult = ParseResult.attempt("piliplus") {
        if (!canHandle(cleanUrl, raw)) return@attempt ParseResult.Ignored("Not a PiliPlus capture route")
        val url = raw.url.ifEmpty { cleanUrl }
        val body = JsonParser.parseString(raw.json)
        require(body.isJsonObject) { "Expected PiliPlus JSON object" }
        if (url == VIDEO) {
            BilibiliParser.parseNormalized(raw, url, "video", normalizeVideo(body.asJsonObject))
        } else {
            val grpc = body.asJsonObject.get("grpc")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            requireNotNull(grpc) { "Expected base64 gRPC frame in grpc field" }
            PiliPlusMainList.parse(raw, url, decodeGrpcFrame(grpc.asString))
        }
    }

    private fun normalizeVideo(body: JsonObject): JsonObject {
        require(body.bilibiliInteger("code") == 0L) { "PiliPlus video response did not report success" }
        val data = requireNotNull(body.getAsJsonObject("data")) { "Missing video response data" }
        return JsonObject().apply {
            addProperty("kind", "video")
            copyFields(data, "aid" to "aid", "bvid" to "bvid", "title" to "title", "desc" to "description",
                "pic" to "cover", "pubdate" to "published_at", "duration" to "duration")
            data.objectOrNull("owner")?.let { copyFields(it, "name" to "author", "face" to "author_avatar") }
            data.objectOrNull("stat")?.let {
                copyFields(it, "view" to "view", "danmaku" to "danmaku", "reply" to "reply", "favorite" to "favorite",
                    "coin" to "coin", "share" to "share", "like" to "like")
            }
        }
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.copyFields(source: JsonObject, vararg fields: Pair<String, String>) {
        for ((from, to) in fields) source.get(from)?.let { add(to, it) }
    }
}
