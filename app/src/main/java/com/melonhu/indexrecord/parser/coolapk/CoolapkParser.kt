package com.melonhu.indexrecord.parser.coolapk

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.RawDataParser
import java.util.Locale

/**
 * 酷安数据解析器。
 * 支持动态 (feed)、点评 (rating)、图文 (feedArticle)、问答 (question / answer) 以及评论回复 (feed_reply)。
 */
object CoolapkParser : RawDataParser {

    override val platform: String = "coolapk"

    override fun canHandle(cleanUrl: String, raw: RawData): Boolean {
        if (raw.hostApp == "com.coolapk.market") return true
        if (!cleanUrl.contains("coolapk.com")) return false
        return cleanUrl.contains("/v6/feed/detail") ||
            cleanUrl.contains("/v6/feed/replyList") ||
            cleanUrl.contains("/v6/feed/replyDetail")
    }

    override fun parse(raw: RawData, cleanUrl: String): List<UniversalRecord> {
        val root = runCatching {
            JsonParser.parseString(raw.json).asJsonObject
        }.getOrNull() ?: return emptyList()

        val dataElem = root.get("data") ?: return emptyList()
        val records = mutableListOf<UniversalRecord>()
        val seenCommentIds = mutableSetOf<String>()

        if (dataElem.isJsonObject) {
            val data = dataElem.asJsonObject
            val id = data.get("id")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()

            val feedType = data.get("feedType")?.asString?.trim().orEmpty()
            val type = data.get("type")?.asInt ?: 0

            val (recordType, platformId, parentId) = determineTypeAndIds(id, feedType, type, data)

            val username = data.get("username")?.asString.orEmpty()
            val uid = data.get("uid")?.asString.orEmpty()
            val userAvatar = fixCoolapkUrl(data.get("userAvatar")?.asString.orEmpty())
            val dateline = (data.get("dateline")?.asLong ?: 0L) * 1000L

            val rawTitle = data.get("message_title")?.asString.orEmpty().trim()
            val ttitle = data.get("ttitle")?.asString.orEmpty().trim()
            val title = when {
                rawTitle.isNotEmpty() -> rawTitle
                recordType == "rating" && ttitle.isNotEmpty() -> "$ttitle 的点评"
                else -> ""
            }

            val rawMessage = data.get("message")?.asString.orEmpty()
            val pictures = extractPictures(data)
            val content = buildContent(rawMessage, pictures, recordType, data)

            val extra = JsonObject().apply {
                data.get("device_title")?.asString?.takeIf { it.isNotBlank() }?.let { addProperty("device_title", it) }
                if (ttitle.isNotBlank()) addProperty("ttitle", ttitle)
                if (feedType.isNotBlank()) addProperty("feed_type", feedType)
                data.get("message_cover")?.asString?.takeIf { it.isNotBlank() }?.let { addProperty("message_cover", fixCoolapkUrl(it)) }
                data.get("likenum")?.let { add("likenum", it) }
                data.get("replynum")?.let { add("replynum", it) }
                data.get("favnum")?.let { add("favnum", it) }
                data.get("share_num")?.let { add("share_num", it) }
                data.get("rating_score")?.let { add("rating_score", it) }
                data.get("rank_score")?.let { add("rank_score", it) }
            }

            records.add(
                UniversalRecord(
                    platform = platform,
                    recordType = recordType,
                    sourceApp = raw.hostApp.ifEmpty { "com.coolapk.market" },
                    platformId = platformId,
                    title = title,
                    content = content,
                    authorId = uid,
                    authorName = username,
                    authorAvatar = userAvatar,
                    sourceUrl = "https://www.coolapk.com/feed/$id",
                    parentId = parentId,
                    publishTime = dateline,
                    captureTime = raw.timestamp,
                    extraMetadata = extra.toString(),
                    contentFormat = RecordBodyFormat.HTML.storageValue
                )
            )

            // 附带解析详情内已有的热门/置顶回复
            data.getAsJsonArray("topReplyRows")?.let { arr ->
                for (elem in arr) {
                    if (elem.isJsonObject) {
                        parseReplyObject(elem.asJsonObject, id, raw.timestamp, raw.hostApp, records, seenCommentIds)
                    }
                }
            }
            data.getAsJsonArray("replyRows")?.let { arr ->
                for (elem in arr) {
                    if (elem.isJsonObject) {
                        parseReplyObject(elem.asJsonObject, id, raw.timestamp, raw.hostApp, records, seenCommentIds)
                    }
                }
            }
        } else if (dataElem.isJsonArray) {
            val urlFeedId = extractIdFromUrl(raw.url)
            for (elem in dataElem.asJsonArray) {
                if (elem.isJsonObject) {
                    parseReplyObject(elem.asJsonObject, urlFeedId, raw.timestamp, raw.hostApp, records, seenCommentIds)
                }
            }
        }

        return records
    }

    private fun parseReplyObject(
        replyObj: JsonObject,
        defaultFeedId: String,
        rawTimestamp: Long,
        hostApp: String,
        outList: MutableList<UniversalRecord>,
        seenIds: MutableSet<String>
    ) {
        val id = replyObj.get("id")?.asString?.trim().orEmpty()
        if (id.isEmpty() || !seenIds.add(id)) return

        val fid = replyObj.get("fid")?.asString?.trim()?.takeIf { it.isNotEmpty() && it != "0" } ?: defaultFeedId
        val rid = replyObj.get("rid")?.asString?.trim().orEmpty()
        val rrid = replyObj.get("rrid")?.asString?.trim().orEmpty()

        val parentId = when {
            rid.isNotEmpty() && rid != "0" -> "coolapk_comment_$rid"
            rrid.isNotEmpty() && rrid != "0" -> "coolapk_comment_$rrid"
            fid.isNotEmpty() -> "coolapk_feed_$fid"
            else -> ""
        }

        val username = replyObj.get("username")?.asString.orEmpty()
        val uid = replyObj.get("uid")?.asString.orEmpty()
        val userAvatar = fixCoolapkUrl(replyObj.get("userAvatar")?.asString.orEmpty())
        val rusername = replyObj.get("rusername")?.asString.orEmpty().trim()
        val rawMessage = replyObj.get("message")?.asString.orEmpty()
        val pic = replyObj.get("pic")?.asString?.trim().orEmpty()
        val picArr = replyObj.getAsJsonArray("picArr")
        val pics = mutableListOf<String>()
        if (picArr != null) {
            for (p in picArr) {
                if (p.isJsonPrimitive) {
                    val u = fixCoolapkUrl(p.asString.trim())
                    if (u.isNotEmpty() && !pics.contains(u)) pics.add(u)
                }
            }
        }
        if (pic.isNotEmpty()) {
            val u = fixCoolapkUrl(pic)
            if (!pics.contains(u)) pics.add(u)
        }

        val content = buildCommentContent(rawMessage, rusername, pics)
        val dateline = (replyObj.get("dateline")?.asLong ?: 0L) * 1000L

        val extra = JsonObject().apply {
            replyObj.get("likenum")?.let { add("likenum", it); add("like_count", it) }
            replyObj.get("replynum")?.let { add("replynum", it); add("comment_count", it) }
            if (rusername.isNotEmpty()) addProperty("rusername", rusername)
            replyObj.get("ruid")?.asString?.takeIf { it.isNotBlank() }?.let { addProperty("ruid", it) }
            if (rid.isNotEmpty() && rid != "0") addProperty("rid", rid)
            if (rrid.isNotEmpty() && rrid != "0") addProperty("rrid", rrid)
        }

        val commentRecord = UniversalRecord(
            platform = platform,
            recordType = "comment",
            sourceApp = hostApp.ifEmpty { "com.coolapk.market" },
            platformId = "coolapk_comment_$id",
            title = "评论: $username",
            content = content,
            authorId = uid,
            authorName = username,
            authorAvatar = userAvatar,
            sourceUrl = if (fid.isNotEmpty()) "https://www.coolapk.com/feed/$fid" else "",
            parentId = parentId,
            publishTime = dateline,
            captureTime = rawTimestamp,
            extraMetadata = extra.toString(),
            contentFormat = RecordBodyFormat.HTML.storageValue
        )
        outList.add(commentRecord)

        replyObj.getAsJsonArray("replyRows")?.let { nestedArray ->
            for (item in nestedArray) {
                if (item.isJsonObject) {
                    parseReplyObject(item.asJsonObject, fid, rawTimestamp, hostApp, outList, seenIds)
                }
            }
        }
    }

    private fun buildCommentContent(rawMessage: String, rusername: String, pictures: List<String>): String {
        val sb = StringBuilder()
        if (rusername.isNotEmpty()) {
            sb.append("回复 @").append(rusername).append(": ")
        }
        val message = fixCoolapkUrl(rawMessage)
        val formattedMessage = if (message.contains("<br") || message.contains("<p")) {
            message
        } else {
            message.replace("\n", "<br/>")
        }
        sb.append(formattedMessage)

        if (pictures.isNotEmpty()) {
            for (pic in pictures) {
                val httpsPic = fixCoolapkUrl(pic)
                if (!message.contains(pic) && !message.contains(httpsPic)) {
                    sb.append("<p><img src=\"").append(httpsPic).append("\" /></p>")
                }
            }
        }
        return sb.toString()
    }

    private fun extractIdFromUrl(url: String): String {
        val regex = Regex("""[?&]id=(\d+)""")
        return regex.find(url)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun fixCoolapkUrl(url: String): String {
        return url.replace("http://image.coolapk.com", "https://image.coolapk.com")
            .replace("http://avatar.coolapk.com", "https://avatar.coolapk.com")
    }

    private data class TypeAndIds(val recordType: String, val platformId: String, val parentId: String)

    private fun determineTypeAndIds(id: String, feedType: String, type: Int, data: JsonObject): TypeAndIds {
        return when {
            feedType == "rating" || type == 5 -> TypeAndIds("rating", "coolapk_rating_$id", "")
            feedType == "feedArticle" || type == 12 -> TypeAndIds("feedArticle", "coolapk_article_$id", "")
            feedType == "question" || type == 10 -> TypeAndIds("question", "coolapk_question_$id", "")
            feedType == "answer" || type == 11 -> {
                val qid = extractQuestionId(data)
                val parent = if (qid.isNotEmpty()) "coolapk_question_$qid" else ""
                TypeAndIds("answer", "coolapk_answer_$id", parent)
            }
            else -> TypeAndIds("feed", "coolapk_feed_$id", "")
        }
    }

    private fun extractQuestionId(data: JsonObject): String {
        val qid = data.get("question_id")?.asString?.trim()
        if (!qid.isNullOrEmpty() && qid != "0") return qid
        val questionUrl = data.get("questionUrl")?.asString.orEmpty()
        val regex = Regex("""/feed/(\d+)""")
        val match = regex.find(questionUrl)
        return match?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractPictures(data: JsonObject): List<String> {
        val list = mutableListOf<String>()
        val picArr = data.getAsJsonArray("picArr")
        if (picArr != null && picArr.size() > 0) {
            for (elem in picArr) {
                if (elem.isJsonPrimitive) {
                    val url = fixCoolapkUrl(elem.asString.trim())
                    if (url.isNotEmpty() && !list.contains(url)) {
                        list.add(url)
                    }
                }
            }
        }
        val singlePic = fixCoolapkUrl(data.get("pic")?.asString?.trim().orEmpty())
        if (singlePic.isNotEmpty() && !list.contains(singlePic)) {
            list.add(singlePic)
        }
        val cover = fixCoolapkUrl(data.get("message_cover")?.asString?.trim().orEmpty())
        if (cover.isNotEmpty() && !list.contains(cover)) {
            list.add(cover)
        }
        return list
    }

    private fun buildContent(rawMessage: String, pictures: List<String>, recordType: String, data: JsonObject): String {
        val sb = StringBuilder()

        if (recordType == "rating") {
            val score = data.get("rating_score")?.takeIf { it.isJsonPrimitive }?.asDouble
            val items = data.getAsJsonArray("rating_item_info")
            if (score != null && score > 0) {
                sb.append("<p><b>评分：").append(String.format(Locale.US, "%.1f", score / 2.0)).append(" ★ (")
                    .append(score.toInt()).append("分)</b></p>")
            }
            if (items != null && items.size() > 0) {
                val subScores = mutableListOf<String>()
                for (item in items) {
                    if (item.isJsonObject) {
                        val name = item.asJsonObject.get("name")?.asString.orEmpty()
                        val v4Score = item.asJsonObject.get("v4_score")?.asInt ?: 0
                        if (name.isNotEmpty() && v4Score > 0) {
                            subScores.add("$name $v4Score")
                        }
                    }
                }
                if (subScores.isNotEmpty()) {
                    sb.append("<p>").append(subScores.joinToString(" · ")).append("</p>")
                }
            }
        }

        val message = fixCoolapkUrl(rawMessage)
        val formattedMessage = if (message.contains("<br") || message.contains("<p")) {
            message
        } else {
            message.replace("\n", "<br/>")
        }
        sb.append(formattedMessage)

        val uncontainedPics = pictures.filter { !message.contains(it) }
        if (uncontainedPics.isNotEmpty()) {
            sb.append("<br/><br/>")
            for (pic in uncontainedPics) {
                sb.append("<p><img src=\"").append(pic).append("\" /></p>")
            }
        }

        return sb.toString()
    }
}
