package com.melonhu.indexrecord.parser.nga

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.hook.NgaHook
import com.melonhu.indexrecord.parser.RawDataParser

/**
 * NGA 玩家社区数据解析器。
 * 支持 NGA 帖子详情及回复列表（app_api.php?__lib=post&__act=list）。
 */
object NgaParser : RawDataParser {

    override val platform: String = "nga"

    private const val DEFAULT_ATTACH_PREFIX = "https://img.nga.cn/attachments/"
    private val TID_PARAM_REGEX = Regex("""[?&]tid=(\d+)""")
    private val PID_TAG_REGEX = Regex("""\[pid=(\d+),\d+,\d+\]""")
    private val IMG_TAG_REGEX = Regex("""\[img\](.*?)\[/img\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val URL_WITH_TEXT_REGEX = Regex("""\[url=([^\]]+)\](.*?)\[/url\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val URL_PLAIN_REGEX = Regex("""\[url\](.*?)\[/url\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TID_LINK_REGEX = Regex("""\[tid=(\d+)\](.*?)\[/tid\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val UID_TAG_REGEX = Regex("""\[uid=[^\]]*\](.*?)\[/uid\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COLLAPSE_TITLE_REGEX = Regex("""\[collapse=([^\]]+)\](.*?)\[/collapse\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COLLAPSE_PLAIN_REGEX = Regex("""\[collapse\](.*?)\[/collapse\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val QUOTE_REGEX = Regex("""\[quote\](.*?)\[/quote\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val REPLY_TO_HEADER_REGEX = Regex("""<b>\s*Reply\s+to\s+(?:\[pid=[^\]]+\][^\[]*\[/pid\]\s*)?Post\s+by\s+(.*?)</b>(?:\s*<br\s*/?>)*""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LEADING_BR_REGEX = Regex("""^(?:\s*<br\s*/?>)+""", RegexOption.IGNORE_CASE)
    private val TRAILING_AFTER_QUOTE_REGEX = Regex("""(</blockquote>)(?!\s*<(?:p|div|blockquote)\b)(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val VIDEO_TAG_REGEX = Regex("""<video\b[^>]*\bsrc="([^"]+)"[^>]*>(?:</video>)?""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SMILEY_TAG_REGEX = Regex("""\[s:[^:\]]+:([^\]]+)\]""")
    private val SIMPLE_BBCODE_STRIP_REGEX = Regex("""\[/?(?:size|color|font|align|flash|audio|code|list|\*|h|l|r)(?:=[^\]]*)?\]""", RegexOption.IGNORE_CASE)

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean {
        val targetUrl = raw.url.ifEmpty { cleanUrl }
        if (raw.hostApp != NgaHook.HOST_APP && !NgaHook.isTargetUrl(targetUrl)) {
            return false
        }
        return targetUrl.contains("__lib=post") && targetUrl.contains("__act=list")
    }

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val root = runCatching {
            JsonParser.parseString(raw.json).asJsonObject
        }.getOrNull() ?: return emptyList()

        val code = root.long("code")
        if (code != null && code != 0L) return emptyList()

        val resultArray = root.array("result") ?: return emptyList()
        val urlTid = TID_PARAM_REGEX.find(raw.url.ifEmpty { cleanUrl })?.groupValues?.getOrNull(1).orEmpty()
        val tsubject = root.text("tsubject").trim()
        val tauthorId = root.text("tauthorid")
        val tauthor = root.text("tauthor")
        val rootFid = root.text("fid")
        val forumName = root.text("forum_name")
        val vrows = root.long("vrows")
        val totalReplies = vrows?.let { (it - 1L).coerceAtLeast(0L) }
        val attachPrefix = root.text("attachPrefix").ifEmpty { DEFAULT_ATTACH_PREFIX }

        val records = ArrayList<UniversalRecord>(resultArray.size())
        for (element in resultArray) {
            val item = element.asObj() ?: continue
            val tid = item.text("tid").ifEmpty { urlTid }
            val pid = item.text("pid")
            val lou = item.long("lou") ?: -1L
            val isMainPost = lou == 0L || pid == "0"
            if (tid.isEmpty() || (!isMainPost && pid.isEmpty())) continue

            val authorObj = item.obj("author")
            val authorId = authorObj?.text("uid").orEmpty().ifEmpty { if (isMainPost) tauthorId else "" }
            val authorName = authorObj?.text("username").orEmpty().ifEmpty { if (isMainPost) tauthor else "" }
            val authorAvatar = parseAvatar(authorObj?.text("avatar").orEmpty())
            val rawTimestamp = item.long("postdatetimestamp") ?: 0L
            val publishTime = if (rawTimestamp > 0L) rawTimestamp * 1000L else raw.timestamp
            val rawContent = item.text("content")
            val htmlContent = formatNgaContent(rawContent, attachPrefix, item.array("attches"))
            val fid = item.text("fid").ifEmpty { rootFid }
            val voteGood = item.long("vote_good")
            val voteBad = item.long("vote_bad")
            val fromClient = item.text("from_client")

            val extra = JsonObject().apply {
                addProperty("tid", tid)
                if (fid.isNotEmpty()) addProperty("fid", fid)
                if (forumName.isNotEmpty()) addProperty("forum_name", forumName)
                if (voteGood != null) addProperty("vote_good", voteGood)
                if (voteBad != null) addProperty("vote_bad", voteBad)
                if (fromClient.isNotEmpty()) addProperty("from_client", fromClient)
            }

            if (isMainPost) {
                if (vrows != null) extra.addProperty("vrows", vrows)
                if (totalReplies != null) extra.addProperty("replies", totalReplies)
                records.add(
                    UniversalRecord(
                        platformId = "nga_post_$tid",
                        platform = platform,
                        sourceApp = raw.hostApp.ifEmpty { NgaHook.HOST_APP },
                        recordType = "post",
                        title = tsubject.ifEmpty { item.text("subject").trim() },
                        content = htmlContent,
                        authorName = authorName,
                        authorId = authorId,
                        authorAvatar = authorAvatar,
                        sourceUrl = "https://ngabbs.com/read.php?tid=$tid",
                        parentId = "",
                        publishTime = publishTime,
                        captureTime = raw.timestamp,
                        extraMetadata = extra.toString(),
                        contentFormat = RecordBodyFormat.HTML.storageValue
                    )
                )
            } else {
                val replyTargetPid = extractReplyTargetPid(item, rawContent, pid)
                val parentId = if (replyTargetPid.isNotEmpty()) "nga_comment_$replyTargetPid" else "nga_post_$tid"
                extra.addProperty("pid", pid)
                extra.addProperty("post_id", "nga_post_$tid")
                if (lou >= 0L) extra.addProperty("lou", lou)

                records.add(
                    UniversalRecord(
                        platformId = "nga_comment_$pid",
                        platform = platform,
                        sourceApp = raw.hostApp.ifEmpty { NgaHook.HOST_APP },
                        recordType = "comment",
                        title = item.text("subject").trim(),
                        content = htmlContent,
                        authorName = authorName,
                        authorId = authorId,
                        authorAvatar = authorAvatar,
                        sourceUrl = "https://ngabbs.com/read.php?tid=$tid&pid=$pid",
                        parentId = parentId,
                        publishTime = publishTime,
                        captureTime = raw.timestamp,
                        extraMetadata = extra.toString(),
                        contentFormat = RecordBodyFormat.HTML.storageValue
                    )
                )
            }
        }
        return records
    }

    private fun extractReplyTargetPid(item: JsonObject, rawContent: String, selfPid: String): String {
        val commentToId = item.text("comment_to_id").trim()
        if (commentToId.isNotEmpty() && commentToId != "0" && commentToId != selfPid) {
            return commentToId
        }
        val tagPid = PID_TAG_REGEX.find(rawContent)?.groupValues?.getOrNull(1).orEmpty()
        return if (tagPid.isNotEmpty() && tagPid != "0" && tagPid != selfPid) tagPid else ""
    }

    internal fun parseAvatar(rawAvatar: String): String {
        if (rawAvatar.isBlank()) return ""
        val first = rawAvatar
            .substringBefore('|')
            .substringBefore("%7c")
            .substringBefore("%7C")
            .trim()
        if (first.startsWith("https://")) return first
        if (first.startsWith("http://")) return "https://${first.removePrefix("http://")}"
        if (first.startsWith("//")) return "https:$first"
        return first
    }

    internal fun formatNgaContent(rawContent: String, attachPrefix: String, attaches: JsonArray?): String {
        var text = rawContent
        if (text.isNotBlank()) {
            text = VIDEO_TAG_REGEX.replace(text) { m ->
                val url = m.groupValues[1].trim()
                if (url.isNotEmpty()) """<p><a href="$url">[视频]</a></p>""" else ""
            }
            text = IMG_TAG_REGEX.replace(text) { m ->
                val resolved = resolveAttachUrl(m.groupValues[1].trim(), attachPrefix)
                if (resolved.isNotEmpty()) """<p><img src="$resolved"/></p>""" else ""
            }
            text = REPLY_TO_HEADER_REGEX.replace(text) { m ->
                "<blockquote>回复 ${m.groupValues[1].trim()}</blockquote>"
            }
            text = PID_TAG_REGEX.replace(text, "")
            text = text.replace("[/pid]", "")
            text = UID_TAG_REGEX.replace(text) { m -> "@${m.groupValues[1]}" }
            text = TID_LINK_REGEX.replace(text) { m ->
                """<a href="https://ngabbs.com/read.php?tid=${m.groupValues[1]}">${m.groupValues[2]}</a>"""
            }
            text = URL_WITH_TEXT_REGEX.replace(text) { m ->
                """<a href="${m.groupValues[1].trim()}">${m.groupValues[2]}</a>"""
            }
            text = URL_PLAIN_REGEX.replace(text) { m ->
                val u = m.groupValues[1].trim()
                """<a href="$u">$u</a>"""
            }
            text = COLLAPSE_TITLE_REGEX.replace(text) { m ->
                "<blockquote><b>${m.groupValues[1]}</b><br/>${m.groupValues[2]}</blockquote>"
            }
            text = COLLAPSE_PLAIN_REGEX.replace(text) { m ->
                "<blockquote>${m.groupValues[1]}</blockquote>"
            }
            text = QUOTE_REGEX.replace(text) { m ->
                val inner = LEADING_BR_REGEX.replace(m.groupValues[1], "").trim()
                "<blockquote>$inner</blockquote>"
            }
            text = TRAILING_AFTER_QUOTE_REGEX.replace(text) { m ->
                val body = LEADING_BR_REGEX.replace(m.groupValues[2], "").trim()
                if (body.isEmpty()) m.groupValues[1] else "${m.groupValues[1]}<p>$body</p>"
            }
            text = text
                .replace("[b]", "<b>", ignoreCase = true).replace("[/b]", "</b>", ignoreCase = true)
                .replace("[i]", "<i>", ignoreCase = true).replace("[/i]", "</i>", ignoreCase = true)
                .replace("[u]", "<u>", ignoreCase = true).replace("[/u]", "</u>", ignoreCase = true)
                .replace("[del]", "<del>", ignoreCase = true).replace("[/del]", "</del>", ignoreCase = true)
            text = SMILEY_TAG_REGEX.replace(text) { m -> "[${m.groupValues[1]}]" }
            text = SIMPLE_BBCODE_STRIP_REGEX.replace(text, "")
        }

        if (attaches != null && attaches.size() > 0) {
            val extraImgs = StringBuilder()
            for (el in attaches) {
                val obj = el.asObj() ?: continue
                if (obj.text("type") != "img") continue
                val attachUrl = obj.text("attachurl").trim()
                if (attachUrl.isEmpty()) continue
                val resolved = resolveAttachUrl(attachUrl, attachPrefix)
                if (resolved.isNotEmpty() && !text.contains(attachUrl)) {
                    extraImgs.append("""<p><img src="$resolved"/></p>""")
                }
            }
            if (extraImgs.isNotEmpty()) {
                text = if (text.isBlank()) extraImgs.toString() else "$text<br/>$extraImgs"
            }
        }
        return text.trim()
    }

    private fun resolveAttachUrl(rawUrl: String, attachPrefix: String): String {
        if (rawUrl.isEmpty()) return ""
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) return rawUrl
        if (rawUrl.startsWith("//")) return "https:$rawUrl"
        val normalizedPrefix = if (attachPrefix.endsWith("/")) attachPrefix else "$attachPrefix/"
        val relative = rawUrl.removePrefix("./").removePrefix("/")
        return normalizedPrefix + relative
    }

    private fun JsonElement.asObj(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.obj(key: String): JsonObject? {
        val el = get(key) ?: return null
        return if (el.isJsonObject) el.asJsonObject else null
    }

    private fun JsonObject.array(key: String): JsonArray? {
        val el = get(key) ?: return null
        return if (el.isJsonArray) el.asJsonArray else null
    }

    private fun JsonObject.text(key: String): String {
        val el = get(key) ?: return ""
        return if (el.isJsonPrimitive) el.asString.orEmpty() else ""
    }

    private fun JsonObject.long(key: String): Long? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive) return null
        return runCatching { el.asString.toLongOrNull() ?: el.asLong }.getOrNull()
    }
}
