package com.melonhu.indexrecord.parser.hupu

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.hook.HupuHook
import com.melonhu.indexrecord.parser.RawDataParser

/**
 * 虎扑数据解析器。
 * 支持虎扑帖子详情（传统与重构接口）及帖子评论/亮回复列表。
 */
object HupuParser : RawDataParser {

    override val platform: String = "hupu"

    private val THREAD_DETAIL_REGEX = Regex(""".*/threads/(?:refactor/)?(\d+)(?:/.*)?$""")
    private val TID_PARAM_REGEX = Regex("""[?&]tid=(\d+)""")
    private val PID_PARAM_REGEX = Regex("""[?&]pid=(\d+)""")

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean {
        if (!cleanUrl.contains("hupu.com") && raw.hostApp != HupuHook.HOST_APP) {
            return false
        }
        return isThreadDetailUrl(cleanUrl) || isReplyListUrl(cleanUrl) || isCheckReplyUrl(cleanUrl)
    }

    fun isThreadDetailUrl(cleanUrl: String): Boolean {
        if (isReplyListUrl(cleanUrl) || isCheckReplyUrl(cleanUrl)) {
            return false
        }
        return THREAD_DETAIL_REGEX.containsMatchIn(cleanUrl)
    }

    fun isReplyListUrl(cleanUrl: String): Boolean {
        return cleanUrl.contains("getsThreadPostList") || cleanUrl.contains("getsThreadLightReplyList")
    }

    fun isCheckReplyUrl(cleanUrl: String): Boolean {
        return cleanUrl.endsWith("/getCheckReply")
    }

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val root = runCatching {
            JsonParser.parseString(raw.json).asJsonObject
        }.getOrNull() ?: return emptyList()

        return when {
            isThreadDetailUrl(cleanUrl) -> parseThreadDetail(root, raw, cleanUrl)
            isReplyListUrl(cleanUrl) || isCheckReplyUrl(cleanUrl) -> parseReplyList(root, raw, cleanUrl)
            else -> emptyList()
        }
    }

    private fun parseThreadDetail(root: JsonObject, raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val urlTid = THREAD_DETAIL_REGEX.find(cleanUrl)?.groupValues?.getOrNull(1).orEmpty()

        // 1. Refactor 接口: /threads/refactor/{tid}
        val dataObj = root.obj("data")
        val basicInfo = dataObj?.obj("basicInfo")
        val moduleConfig = dataObj?.obj("moduleConfigList")

        val tid: String
        val title: String
        val rawContent: String
        val authorName: String
        val authorId: String
        val authorAvatar: String
        val publishTime: Long
        val fid: String
        val forumName: String
        val replies: Long?
        val lights: Long?

        if (basicInfo != null && moduleConfig != null) {
            tid = basicInfo.text("tid").ifEmpty { urlTid }
            val titleObj = moduleConfig.obj("title")?.obj("moduleContent")
            title = titleObj?.text("title").orEmpty().trim()

            val contentObj = moduleConfig.obj("content")?.obj("moduleContent")
            rawContent = contentObj?.text("content").orEmpty()

            val userObj = moduleConfig.obj("user")?.obj("moduleContent")
            authorName = userObj?.text("name").orEmpty()
            authorId = userObj?.text("puid").orEmpty()
            authorAvatar = userObj?.text("header").orEmpty()

            val rawCreateTime = basicInfo.long("create_time") ?: 0L
            publishTime = if (rawCreateTime > 0L) rawCreateTime * 1000L else raw.timestamp

            fid = basicInfo.text("fid")
            forumName = basicInfo.text("topicName").ifEmpty { basicInfo.text("topicCategoryName") }
            replies = dataObj.long("replies")
            lights = dataObj.long("lights")
        } else {
            // 2. 传统详情接口: /threads/{tid}
            val offlineData = root.obj("offline_data")?.obj("data")
            tid = offlineData?.text("tid").orEmpty().ifEmpty { root.text("tid") }.ifEmpty { urlTid }
            title = offlineData?.text("title").orEmpty().ifEmpty { root.text("title") }.trim()
            rawContent = offlineData?.text("content").orEmpty()

            authorName = offlineData?.text("username").orEmpty().ifEmpty { root.text("userName") }
            authorId = offlineData?.text("puid").orEmpty()
                .ifEmpty { offlineData?.text("author_puid").orEmpty() }
                .ifEmpty { root.text("puid") }
            authorAvatar = offlineData?.text("userImg").orEmpty()
                .ifEmpty { root.obj("author")?.text("header").orEmpty() }

            val rawCreateTime = offlineData?.long("create_time") ?: 0L
            publishTime = if (rawCreateTime > 0L) rawCreateTime * 1000L else raw.timestamp

            val forumObj = offlineData?.obj("forum")
            fid = forumObj?.text("fid").orEmpty().ifEmpty { root.text("fid") }
            forumName = forumObj?.text("name").orEmpty().ifEmpty { root.text("forum_name") }
            replies = offlineData?.long("replies") ?: root.long("replies")
            lights = offlineData?.long("lights") ?: root.long("lights")
        }

        if (tid.isEmpty()) return emptyList()

        val extra = JsonObject().apply {
            if (fid.isNotEmpty()) addProperty("fid", fid)
            if (forumName.isNotEmpty()) addProperty("forum_name", forumName)
            replies?.let { addProperty("replies", it) }
            lights?.let { addProperty("lights", it) }
        }

        val content = normalizeHtml(rawContent)

        return listOf(
            UniversalRecord(
                platform = platform,
                recordType = "post",
                sourceApp = raw.hostApp.ifEmpty { HupuHook.HOST_APP },
                platformId = "hupu_post_$tid",
                title = title.ifEmpty { "虎扑帖子" },
                content = content,
                authorId = authorId,
                authorName = authorName,
                authorAvatar = authorAvatar,
                sourceUrl = "https://bbs.hupu.com/$tid.html",
                parentId = "",
                publishTime = publishTime,
                captureTime = raw.timestamp,
                extraMetadata = extra.toString(),
                contentFormat = RecordBodyFormat.HTML.storageValue
            )
        )
    }

    private fun parseReplyList(root: JsonObject, raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val tid = TID_PARAM_REGEX.find(raw.url)?.groupValues?.getOrNull(1)
            ?: TID_PARAM_REGEX.find(cleanUrl)?.groupValues?.getOrNull(1).orEmpty()

        val dataObj = root.obj("data")
        val resultObj = dataObj?.obj("result") ?: root.obj("result")
        val array = resultObj?.get("list")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: dataObj?.get("list")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: root.get("list")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()

        val checkTargetPid = if (isCheckReplyUrl(cleanUrl)) {
            PID_PARAM_REGEX.find(raw.url)?.groupValues?.getOrNull(1)
                ?: PID_PARAM_REGEX.find(cleanUrl)?.groupValues?.getOrNull(1)
                ?: resultObj?.obj("post_info")?.text("pid").orEmpty()
        } else {
            ""
        }
        val parentId = when {
            checkTargetPid.isNotEmpty() -> "hupu_comment_$checkTargetPid"
            tid.isNotEmpty() -> "hupu_post_$tid"
            else -> ""
        }

        val records = mutableListOf<UniversalRecord>()
        for (elem in array) {
            if (!elem.isJsonObject) continue
            val r = elem.asJsonObject
            val pid = r.text("pid").trim().takeIf { it.isNotEmpty() } ?: continue

            val authorName = r.text("userName")
            val authorId = r.text("puid")
            val authorAvatar = r.text("userImg")
            val rawCreateTime = r.long("create_time") ?: 0L
            val publishTime = if (rawCreateTime > 0L) rawCreateTime * 1000L else raw.timestamp

            val floor = r.text("floor")
            val lightCount = r.long("light_count") ?: r.long("allLightCount") ?: 0L
            val location = r.text("location")

            val rawContent = r.text("content")
            val quotes = r.get("quote")?.takeIf { it.isJsonArray }?.asJsonArray

            val quoteHtml = StringBuilder()
            var quotePid: String? = null
            if (quotes != null && quotes.size() > 0) {
                for (q in quotes) {
                    if (!q.isJsonObject) continue
                    val qObj = q.asJsonObject
                    if (quotePid == null) {
                        quotePid = qObj.text("pid").takeIf { it.isNotEmpty() }
                    }
                    val headerArr = qObj.get("header")?.takeIf { it.isJsonArray }?.asJsonArray
                    val rawHeader = headerArr?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    val cleanHeader = rawHeader.replace(Regex("<a[^>]+>([^<]+)</a>"), "$1")
                    val qContent = qObj.text("content")

                    quoteHtml.append("<blockquote>")
                    if (cleanHeader.isNotEmpty()) {
                        quoteHtml.append(cleanHeader).append("<br/>")
                    }
                    quoteHtml.append(qContent).append("</blockquote>")
                }
            }

            val fullContent = if (quoteHtml.isNotEmpty()) {
                val bodyHtml = if (rawContent.startsWith("<p") || rawContent.startsWith("<div")) {
                    rawContent
                } else {
                    "<p>$rawContent</p>"
                }
                "$quoteHtml$bodyHtml"
            } else {
                rawContent
            }

            val effectiveQuotePid = quotePid?.takeIf { it.isNotEmpty() } ?: checkTargetPid.takeIf { it.isNotEmpty() }
            val extra = JsonObject().apply {
                if (tid.isNotEmpty()) addProperty("tid", tid)
                if (floor.isNotEmpty()) addProperty("floor", floor)
                if (location.isNotEmpty()) addProperty("location", location)
                if (lightCount > 0L) addProperty("light_count", lightCount)
                effectiveQuotePid?.let { addProperty("quote_pid", it) }
            }

            records.add(
                UniversalRecord(
                    platform = platform,
                    recordType = "comment",
                    sourceApp = raw.hostApp.ifEmpty { HupuHook.HOST_APP },
                    platformId = "hupu_comment_$pid",
                    title = if (authorName.isNotBlank()) "回复: $authorName" else "回复",
                    content = normalizeHtml(fullContent),
                    authorId = authorId,
                    authorName = authorName,
                    authorAvatar = authorAvatar,
                    sourceUrl = if (tid.isNotEmpty()) "https://bbs.hupu.com/$tid.html" else "",
                    parentId = parentId,
                    publishTime = publishTime,
                    captureTime = raw.timestamp,
                    extraMetadata = extra.toString(),
                    contentFormat = RecordBodyFormat.HTML.storageValue
                )
            )
        }
        return records
    }

    private fun normalizeHtml(html: String): String {
        if (html.isEmpty()) return ""
        // 1. 将 <center class="hupu-img" ...></center> 规范化为 <p><img src="..." /></p>
        var res = html.replace(Regex("""<center[^>]*class=["'][^"']*hupu-img[^"']*["'][^>]*>.*?</center>""", RegexOption.DOT_MATCHES_ALL)) { match ->
            val tag = match.value
            val url = Regex("""data_url=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                ?: Regex("""data-origin=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                ?: Regex("""src=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
            if (!url.isNullOrEmpty()) {
                """<p><img src="$url" /></p>"""
            } else ""
        }

        // 2. 修正带有占位图（如 day.png）的 <img> 标签真实 src 地址
        res = res.replace(Regex("""<img[^>]+>""")) { match ->
            val tag = match.value
            if (tag.contains("day.png") || tag.contains("default")) {
                val realUrl = Regex("""data-origin=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                    ?: Regex("""data_url=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                    ?: Regex("""data-src=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                if (!realUrl.isNullOrEmpty()) {
                    tag.replace(Regex("""src=["'][^"']+["']"""), """src="$realUrl"""")
                } else tag
            } else tag
        }
        return res
    }

    private fun JsonObject.text(key: String): String {
        val elem = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return ""
        return elem.asString
    }

    private fun JsonObject.long(key: String): Long? {
        val elem = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return runCatching { elem.asLong }.getOrNull()
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
