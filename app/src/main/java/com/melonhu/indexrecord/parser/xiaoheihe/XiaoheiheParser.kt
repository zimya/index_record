package com.melonhu.indexrecord.parser.xiaoheihe

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.hook.XiaoheiheHook
import com.melonhu.indexrecord.parser.RawDataParser

/**
 * 小黑盒数据解析器。
 * 支持小黑盒帖子详情（link/tree）以及楼中楼评论/回复列表（comment/sub/comments）。
 */
object XiaoheiheParser : RawDataParser {

    override val platform: String = "xiaoheihe"

    private val LINK_ID_REGEX = Regex("""[?&]link_id=(\w+)""")
    private val ROOT_COMMENT_ID_REGEX = Regex("""[?&]root_comment_id=(\w+)""")

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean {
        if (!cleanUrl.contains("xiaoheihe.cn") &&
            !cleanUrl.contains("maxjia.com") &&
            !cleanUrl.contains("heybox") &&
            raw.hostApp != XiaoheiheHook.HOST_APP
        ) {
            return false
        }
        return isLinkTreeUrl(cleanUrl) || isSubCommentsUrl(cleanUrl)
    }

    fun isLinkTreeUrl(cleanUrl: String): Boolean = cleanUrl.contains("/bbs/app/link/tree")

    fun isSubCommentsUrl(cleanUrl: String): Boolean = cleanUrl.contains("/bbs/app/comment/sub/comments")

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val root = runCatching {
            JsonParser.parseString(raw.json).asJsonObject
        }.getOrNull() ?: return emptyList()

        val status = root.text("status")
        if (status.isNotEmpty() && status != "ok") return emptyList()

        return when {
            isLinkTreeUrl(cleanUrl) -> parseLinkTree(root, raw, cleanUrl)
            isSubCommentsUrl(cleanUrl) -> parseSubComments(root, raw, cleanUrl)
            else -> emptyList()
        }
    }

    private fun parseLinkTree(root: JsonObject, raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val result = root.obj("result") ?: return emptyList()
        val records = mutableListOf<UniversalRecord>()

        val linkObj = result.obj("link")
        var linkId = ""
        if (linkObj != null) {
            val body = linkObj.obj("body")
            if (body != null) {
                linkId = body.text("link_id")
                    .ifEmpty { extractParam(cleanUrl, LINK_ID_REGEX) }
                    .ifEmpty { extractParam(raw.url, LINK_ID_REGEX) }

                val title = body.text("title").trim()
                val rawText = body.text("text")
                val content = formatPostBody(rawText)

                val userObj = body.obj("user")
                val authorName = userObj?.text("username").orEmpty()
                val authorId = userObj?.text("userid").orEmpty()
                val authorAvatar = userObj?.text("avatar").orEmpty()
                    .ifEmpty { userObj?.text("avartar").orEmpty() }

                val rawCreateTime = body.long("create_time") ?: 0L
                val publishTime = if (rawCreateTime > 0L) rawCreateTime * 1000L else raw.timestamp
                val ipLocation = body.text("ip_location")

                val stats = linkObj.obj("stats")
                val favourCount = stats?.long("favour_count")
                val commentNum = stats?.long("comment_num")

                val shareUrl = linkObj.obj("share_info")?.text("share_url").orEmpty()
                val sourceUrl = shareUrl.ifEmpty {
                    if (linkId.isNotEmpty()) "https://api.xiaoheihe.cn/bbs/app/link/web/view?link_id=$linkId" else ""
                }

                val extra = JsonObject().apply {
                    if (ipLocation.isNotEmpty()) addProperty("ip_location", ipLocation)
                    favourCount?.let {
                        addProperty("favour_count", it)
                        addProperty("like_count", it)
                    }
                    commentNum?.let {
                        addProperty("comment_num", it)
                        addProperty("reply_count", it)
                    }
                    if (linkId.isNotEmpty()) addProperty("link_id", linkId)
                }

                if (linkId.isNotEmpty()) {
                    records.add(
                        UniversalRecord(
                            platform = platform,
                            recordType = "post",
                            sourceApp = raw.hostApp.ifEmpty { XiaoheiheHook.HOST_APP },
                            platformId = "xiaoheihe_post_$linkId",
                            title = title.ifEmpty { "小黑盒帖子" },
                            content = content,
                            authorId = authorId,
                            authorName = authorName,
                            authorAvatar = authorAvatar,
                            sourceUrl = sourceUrl,
                            parentId = "",
                            publishTime = publishTime,
                            captureTime = raw.timestamp,
                            extraMetadata = extra.toString(),
                            contentFormat = RecordBodyFormat.MARKDOWN.storageValue
                        )
                    )
                }
            }
        }

        val commentObj = result.obj("comment")
        val commentsArray = commentObj?.get("comments")?.takeIf { it.isJsonArray }?.asJsonArray
        if (commentsArray != null) {
            for (groupElem in commentsArray) {
                if (!groupElem.isJsonObject) continue
                val group = groupElem.asJsonObject
                val commentList = group.get("comment")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                if (commentList.size() == 0) continue

                var rootCommentId = ""
                for (i in 0 until commentList.size()) {
                    val cElem = commentList[i]
                    if (!cElem.isJsonObject) continue
                    val c = cElem.asJsonObject
                    val commentId = c.text("commentid")
                    if (commentId.isEmpty()) continue

                    if (i == 0) {
                        rootCommentId = commentId
                    }

                    val isRoot = i == 0
                    val replyId = c.text("replyid")
                    val parentId = when {
                        isRoot -> if (linkId.isNotEmpty()) "xiaoheihe_post_$linkId" else ""
                        replyId.isNotEmpty() && replyId != "0" -> "xiaoheihe_comment_$replyId"
                        rootCommentId.isNotEmpty() -> "xiaoheihe_comment_$rootCommentId"
                        linkId.isNotEmpty() -> "xiaoheihe_post_$linkId"
                        else -> ""
                    }

                    records.add(buildCommentRecord(c, raw, linkId, rootCommentId, replyId, parentId))
                }
            }
        }

        return records
    }

    private fun parseSubComments(root: JsonObject, raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val result = root.obj("result") ?: return emptyList()
        val commentsArray = result.get("comments")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()

        val rootCommentId = extractParam(cleanUrl, ROOT_COMMENT_ID_REGEX)
            .ifEmpty { extractParam(raw.url, ROOT_COMMENT_ID_REGEX) }
        val linkId = extractParam(cleanUrl, LINK_ID_REGEX)
            .ifEmpty { extractParam(raw.url, LINK_ID_REGEX) }

        val records = mutableListOf<UniversalRecord>()
        for (elem in commentsArray) {
            if (!elem.isJsonObject) continue
            val c = elem.asJsonObject
            val commentId = c.text("commentid")
            if (commentId.isEmpty()) continue

            val replyId = c.text("replyid")
            val parentId = when {
                replyId.isNotEmpty() && replyId != "0" -> "xiaoheihe_comment_$replyId"
                rootCommentId.isNotEmpty() -> "xiaoheihe_comment_$rootCommentId"
                linkId.isNotEmpty() -> "xiaoheihe_post_$linkId"
                else -> ""
            }

            records.add(buildCommentRecord(c, raw, linkId, rootCommentId, replyId, parentId))
        }
        return records
    }

    private fun buildCommentRecord(
        c: JsonObject,
        raw: RawData,
        linkId: String,
        rootCommentId: String,
        replyId: String,
        parentId: String
    ): UniversalRecord {
        val commentId = c.text("commentid")
        val text = c.text("text")

        val userObj = c.obj("user")
        val authorName = userObj?.text("username").orEmpty()
        val authorId = userObj?.text("userid").orEmpty()
        val authorAvatar = userObj?.text("avatar").orEmpty()
            .ifEmpty { userObj?.text("avartar").orEmpty() }

        val replyUserObj = c.obj("replyuser")
        val replyUserName = replyUserObj?.text("username").orEmpty()

        val rawCreateAt = c.long("create_at") ?: 0L
        val publishTime = if (rawCreateAt > 0L) rawCreateAt * 1000L else raw.timestamp

        val up = c.long("up")
        val floorNum = c.long("floor_num")
        val childNum = c.long("child_num")
        val ipLocation = c.text("ip_location")

        val title = when {
            replyUserName.isNotBlank() -> "回复 $replyUserName"
            authorName.isNotBlank() -> "回复: $authorName"
            else -> "回复"
        }

        val extra = JsonObject().apply {
            if (linkId.isNotEmpty()) addProperty("link_id", linkId)
            if (rootCommentId.isNotEmpty()) addProperty("root_comment_id", rootCommentId)
            if (replyId.isNotEmpty() && replyId != "0") addProperty("reply_id", replyId)
            if (ipLocation.isNotEmpty()) addProperty("ip_location", ipLocation)
            up?.let {
                addProperty("up", it)
                addProperty("like_count", it)
            }
            floorNum?.let { addProperty("floor", it.toString()) }
            childNum?.let {
                addProperty("child_num", it)
                addProperty("reply_count", it)
            }
        }

        return UniversalRecord(
            platform = platform,
            recordType = "comment",
            sourceApp = raw.hostApp.ifEmpty { XiaoheiheHook.HOST_APP },
            platformId = "xiaoheihe_comment_$commentId",
            title = title,
            content = text,
            authorId = authorId,
            authorName = authorName,
            authorAvatar = authorAvatar,
            sourceUrl = if (linkId.isNotEmpty()) "https://api.xiaoheihe.cn/bbs/app/link/web/view?link_id=$linkId#comment_$commentId" else "",
            parentId = parentId,
            publishTime = publishTime,
            captureTime = raw.timestamp,
            extraMetadata = extra.toString(),
            contentFormat = RecordBodyFormat.PLAIN_TEXT.storageValue
        )
    }

    private fun formatPostBody(rawText: String): String {
        if (rawText.isBlank()) return ""
        val array = runCatching {
            JsonParser.parseString(rawText).takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull() ?: return rawText

        val sb = StringBuilder()
        for (elem in array) {
            if (!elem.isJsonObject) continue
            val item = elem.asJsonObject
            val type = item.text("type")
            when (type) {
                "text" -> {
                    val t = item.text("text")
                    if (t.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(t)
                    }
                }
                "img" -> {
                    val url = item.text("url").ifEmpty {
                        val t = item.text("text")
                        if (t.startsWith("http://") || t.startsWith("https://")) t else ""
                    }
                    if (url.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append("![image]($url)")
                    }
                }
                "simple_card" -> {
                    val card = item.obj("simple_card")
                    val title = card?.text("title").orEmpty()
                    val desc = card?.text("desc").orEmpty()
                    if (title.isNotEmpty() || desc.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        val cardText = if (title.isNotEmpty() && desc.isNotEmpty()) "> **$title**: $desc" else "> ${title.ifEmpty { desc }}"
                        sb.append(cardText)
                    }
                }
                else -> {
                    val t = item.text("text")
                    if (t.isNotEmpty() && !t.startsWith("/storage/")) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(t)
                    }
                }
            }
        }
        return sb.toString().ifEmpty { rawText }
    }

    private fun extractParam(url: String, regex: Regex): String =
        regex.find(url)?.groupValues?.getOrNull(1).orEmpty()

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
