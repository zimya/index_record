package com.melonhu.indexrecord.parser.douban

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.markdown.escapeMarkdownText
import com.melonhu.indexrecord.markdown.toMarkdown
import com.melonhu.indexrecord.parser.zhihu.markdown.htmlToMdAst
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object DoubanUtils {

    fun JsonObject.safeString(key: String): String {
        if (!has(key)) return ""
        val elem: JsonElement = get(key)
        if (elem.isJsonNull) return ""
        if (elem.isJsonPrimitive) {
            val prim = elem.asJsonPrimitive
            if (prim.isString) return prim.asString
            return prim.toString()
        }
        return elem.toString()
    }

    fun JsonObject.safeLong(key: String, default: Long = 0L): Long {
        if (!has(key)) return default
        val elem: JsonElement = get(key)
        if (elem.isJsonNull) return default
        return try {
            if (elem.isJsonPrimitive) {
                val prim = elem.asJsonPrimitive
                if (prim.isNumber) prim.asLong else prim.asString.toLongOrNull() ?: default
            } else default
        } catch (_: Exception) {
            default
        }
    }

    fun JsonObject.safeDouble(key: String, default: Double = 0.0): Double {
        if (!has(key)) return default
        val elem: JsonElement = get(key)
        if (elem.isJsonNull) return default
        return try {
            if (elem.isJsonPrimitive) {
                val prim = elem.asJsonPrimitive
                if (prim.isNumber) prim.asDouble else prim.asString.toDoubleOrNull() ?: default
            } else default
        } catch (_: Exception) {
            default
        }
    }

    fun JsonObject.safeStringList(key: String): List<String> {
        if (!has(key)) return emptyList()
        val elem: JsonElement = get(key)
        if (elem.isJsonNull) return emptyList()
        if (elem.isJsonArray) {
            val list = mutableListOf<String>()
            for (item in elem.asJsonArray) {
                if (item.isJsonPrimitive) {
                    val s = item.asString.trim()
                    if (s.isNotBlank()) list.add(s)
                } else if (item.isJsonObject) {
                    val obj = item.asJsonObject
                    val name = obj.safeString("name")
                        .ifBlank { obj.safeString("title") }
                        .ifBlank { obj.safeString("cn_name") }
                    if (name.isNotBlank()) list.add(name)
                }
            }
            return list
        }
        if (elem.isJsonPrimitive) {
            val s = elem.asString.trim()
            if (s.isNotBlank()) return listOf(s)
        }
        return emptyList()
    }

    fun parseDoubanDate(dateStr: String?, fallback: Long = System.currentTimeMillis()): Long {
        if (dateStr.isNullOrBlank()) return fallback
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
                timeZone = TimeZone.getTimeZone("GMT+8")
            }
            sdf.parse(dateStr)?.time ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    fun doubanHtmlToMarkdown(html: String): String {
        if (html.isBlank()) return ""
        return try {
            htmlToMdAst(html).toMarkdown()
        } catch (_: Exception) {
            html
        }
    }

    data class UserInfo(val id: String, val name: String, val avatar: String)

    fun extractUser(obj: JsonObject): UserInfo {
        val userObj = when {
            obj.has("author") && obj.get("author").isJsonObject -> obj.getAsJsonObject("author")
            obj.has("user") && obj.get("user").isJsonObject -> obj.getAsJsonObject("user")
            else -> null
        }
        val id = userObj?.safeString("id")?.ifBlank { userObj.safeString("uid") } ?: ""
        val name = userObj?.safeString("name")?.ifBlank { "豆瓣用户" } ?: "豆瓣用户"
        val avatar = userObj?.safeString("avatar")?.ifBlank { userObj.safeString("avatar_url") } ?: ""
        return UserInfo(id = id, name = name, avatar = avatar)
    }

    data class DoubanPhoto(val id: String, val url: String, val title: String = "")

    fun extractPhotoUrl(photoObj: JsonObject): String {
        val imgObj = photoObj.get("image")?.takeIf { it.isJsonObject }?.asJsonObject
        if (imgObj != null) {
            for (sizeKey in listOf("raw", "large", "normal", "small")) {
                val url = imgObj.get(sizeKey)?.takeIf { it.isJsonObject }?.asJsonObject?.safeString("url").orEmpty()
                if (url.isNotBlank()) return url
            }
        }
        val directImage = photoObj.get("image")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
        if (directImage.isNotBlank()) return directImage
        return photoObj.safeString("url")
    }

    fun extractDoubanPhotos(array: JsonArray?): List<DoubanPhoto> {
        if (array == null || array.size() == 0) return emptyList()
        val photos = mutableListOf<DoubanPhoto>()
        val seenUrls = mutableSetOf<String>()
        for (elem in array) {
            if (!elem.isJsonObject) continue
            val obj = elem.asJsonObject
            val url = extractPhotoUrl(obj).trim()
            if (url.isNotBlank() && seenUrls.add(url)) {
                photos.add(DoubanPhoto(id = obj.safeString("id"), url = url, title = obj.safeString("title").trim()))
            }
        }
        return photos
    }

    fun appendMissingPhotos(bodyText: String, photos: List<DoubanPhoto>): String {
        val missing = photos.filter { photo ->
            !bodyText.contains(photo.url) && (photo.id.isBlank() || !bodyText.contains("p${photo.id}"))
        }
        if (missing.isEmpty()) return bodyText
        return buildString {
            if (bodyText.isNotBlank()) append(bodyText)
            for (photo in missing) {
                if (isNotEmpty()) append("\n\n")
                val label = if (photo.title.isNotBlank()) escapeMarkdownText(photo.title) else "图片"
                append("![").append(label).append("](").append(photo.url).append(")")
            }
        }
    }

    private val gson = Gson()

    fun parseDoubanComments(
        json: JsonObject,
        raw: RawData,
        defaultParentId: String,
        arrayKeys: List<String> = listOf("popular_comments", "comments")
    ): List<UniversalRecord> {
        val records = mutableListOf<UniversalRecord>()
        val seenIds = mutableSetOf<String>()

        fun processComment(commentObj: JsonObject, fallbackParentId: String) {
            if (commentObj.has("ad_info") && !commentObj.get("ad_info").isJsonNull) return
            val commentId = commentObj.safeString("id")
            if (commentId.isBlank() || !seenIds.add(commentId)) return

            val author = extractUser(commentObj)
            val text = commentObj.safeString("text")
            val photos = extractDoubanPhotos(commentObj.getAsJsonArray("photos"))
            val content = if (photos.isNotEmpty()) {
                val imagesMd = photos.joinToString("\n\n") { "![图片](${it.url})" }
                if (text.isNotBlank()) "${escapeMarkdownText(text)}\n\n$imagesMd" else imagesMd
            } else {
                text
            }

            val voteCount = commentObj.safeLong("vote_count")
            val ipLocation = commentObj.safeString("ip_location")
            val createdTime = parseDoubanDate(commentObj.safeString("create_time"), raw.timestamp)
            val parentCommentId = commentObj.safeString("parent_comment_id")
            val refObj = commentObj.get("ref_comment")?.takeIf { it.isJsonObject }?.asJsonObject
            val refId = refObj?.safeString("id").orEmpty()

            val parentId = when {
                parentCommentId.isNotBlank() && parentCommentId != "0" -> "douban_comment_$parentCommentId"
                refId.isNotBlank() -> "douban_comment_$refId"
                fallbackParentId.isNotBlank() -> fallbackParentId
                else -> ""
            }

            val extra = JsonObject()
            extra.addProperty("vote_count", voteCount)
            extra.addProperty("like_count", voteCount)
            if (ipLocation.isNotBlank()) extra.addProperty("ip_info", ipLocation)
            if (parentCommentId.isNotBlank() && parentCommentId != "0") {
                extra.addProperty("parent_comment_id", parentCommentId)
            }
            if (refId.isNotBlank()) extra.addProperty("ref_comment_id", refId)

            records.add(
                UniversalRecord(
                    contentFormat = if (photos.isEmpty()) RecordBodyFormat.PLAIN_TEXT.storageValue else RecordBodyFormat.MARKDOWN.storageValue,
                    platform = "douban",
                    recordType = "comment",
                    sourceApp = raw.hostApp.ifBlank { PlatformCatalog.defaultPackage("douban") },
                    platformId = "douban_comment_$commentId",
                    title = "评论: ${author.name}",
                    content = content,
                    authorId = author.id,
                    authorName = author.name,
                    authorAvatar = author.avatar,
                    sourceUrl = raw.url,
                    parentId = parentId,
                    publishTime = createdTime,
                    captureTime = raw.timestamp,
                    extraMetadata = gson.toJson(extra)
                )
            )

            commentObj.get("replies")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { replyElem ->
                if (replyElem.isJsonObject) {
                    processComment(replyElem.asJsonObject, "douban_comment_$commentId")
                }
            }
        }

        for (key in arrayKeys) {
            val arr = json.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (elem in arr) {
                if (elem.isJsonObject) processComment(elem.asJsonObject, defaultParentId)
            }
        }
        return records
    }
}
