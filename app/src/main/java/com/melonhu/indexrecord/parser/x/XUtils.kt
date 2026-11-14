package com.melonhu.indexrecord.parser.x

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object XUtils {

    fun JsonObject.safeString(key: String, default: String = ""): String {
        val elem = get(key) ?: return default
        if (elem.isJsonNull) return default
        return try {
            elem.asString
        } catch (_: Exception) {
            default
        }
    }

    fun JsonObject.safeLong(key: String, default: Long = 0L): Long {
        val elem = get(key) ?: return default
        if (elem.isJsonNull) return default
        return try {
            if (elem.isJsonPrimitive) {
                val prim = elem.asJsonPrimitive
                if (prim.isNumber) prim.asLong
                else prim.asString.toLongOrNull() ?: default
            } else default
        } catch (_: Exception) {
            default
        }
    }

    fun JsonObject.safeInt(key: String, default: Int = 0): Int {
        val elem = get(key) ?: return default
        if (elem.isJsonNull) return default
        return try {
            if (elem.isJsonPrimitive) {
                val prim = elem.asJsonPrimitive
                if (prim.isNumber) prim.asInt
                else prim.asString.toIntOrNull() ?: default
            } else default
        } catch (_: Exception) {
            default
        }
    }

    fun JsonObject.safeJsonObject(key: String): JsonObject? {
        val elem = get(key) ?: return null
        return if (elem.isJsonObject) elem.asJsonObject else null
    }

    fun JsonObject.safeJsonArray(key: String): JsonArray? {
        val elem = get(key) ?: return null
        return if (elem.isJsonArray) elem.asJsonArray else null
    }

    /**
     * 解析推特日期，例如 "Tue Nov 10 07:22:57 +0000 2026"
     */
    fun parseTwitterDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            val sdf = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            try {
                // 备用 ISO 格式解析
                java.time.Instant.parse(dateStr).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    /**
     * 将推文文本与 entities 链接、多媒体（图片、视频）转换为 Markdown
     * 同时支持现代 Android 端 (tweetObj.media_entities / url_entities) 与 Web/旧版 (legacy.extended_entities)
     */
    fun formatTweetToMarkdown(
        rawText: String,
        legacyObj: JsonObject?,
        mediaUrls: MutableList<String>
    ): String {
        return formatTweetToMarkdown(rawText, null, legacyObj, mediaUrls)
    }

    fun formatTweetToMarkdown(
        rawText: String,
        tweetObj: JsonObject?,
        legacyObj: JsonObject?,
        mediaUrls: MutableList<String>
    ): String {
        var text = rawText

        // 1. 替换短链接 t.co 为实际可读链接
        // 现代 Android schema: tweetObj.url_entities
        // Web/旧版 schema: legacy.entities.urls
        val urlsArr = tweetObj?.safeJsonArray("url_entities")
            ?: legacyObj?.safeJsonObject("entities")?.safeJsonArray("urls")

        if (urlsArr != null) {
            for (uElem in urlsArr) {
                if (!uElem.isJsonObject) continue
                val uObj = uElem.asJsonObject
                val shortUrl = uObj.safeString("url")
                val expandedUrl = uObj.safeString("expanded_url")
                val displayUrl = uObj.safeString("display_url")
                if (shortUrl.isNotBlank() && expandedUrl.isNotBlank()) {
                    val label = displayUrl.ifBlank { expandedUrl }
                    text = text.replace(shortUrl, "[$label]($expandedUrl)")
                }
            }
        }

        val mediaMarkdownBlocks = mutableListOf<String>()

        // 2. 收集与处理多媒体
        // 2.1 现代 Android schema: tweetObj.media_entities
        val modernMedia = tweetObj?.safeJsonArray("media_entities")
        if (modernMedia != null && modernMedia.size() > 0) {
            for (mElem in modernMedia) {
                if (!mElem.isJsonObject) continue
                val mObj = mElem.asJsonObject
                val tCoUrl = mObj.safeString("url")
                if (tCoUrl.isNotBlank()) {
                    text = text.replace(tCoUrl, "").trim()
                }

                val mediaResults = mObj.safeJsonObject("media_results")?.safeJsonObject("result")
                val mediaInfo = mediaResults?.safeJsonObject("media_info")
                val mediaType = mediaInfo?.safeString("__typename") ?: ""

                when (mediaType) {
                    "ApiImage" -> {
                        val imgUrl = mediaInfo?.safeString("original_img_url") ?: ""
                        if (imgUrl.isNotBlank()) {
                            mediaUrls.add(imgUrl)
                            mediaMarkdownBlocks.add("![图片]($imgUrl)")
                        }
                    }
                    "ApiVideo", "ApiGif" -> {
                        val previewUrl = mediaInfo?.safeJsonObject("preview_image")?.safeString("original_img_url")
                            ?: mediaInfo?.safeString("original_img_url")
                            ?: ""
                        val bestVideoUrl = selectMp4Url(mediaInfo?.safeJsonArray("variants"))

                        if (previewUrl.isNotBlank()) {
                            mediaUrls.add(previewUrl)
                        }

                        if (bestVideoUrl.isNotBlank()) {
                            if (mediaType == "ApiGif") {
                                mediaMarkdownBlocks.add("[GIF]($bestVideoUrl)")
                            } else if (previewUrl.isNotBlank()) {
                                mediaMarkdownBlocks.add("[![视频封面]($previewUrl)]($bestVideoUrl)\n\n[▶ 点击播放视频]($bestVideoUrl)")
                            } else {
                                mediaMarkdownBlocks.add("[▶ 点击播放视频]($bestVideoUrl)")
                            }
                        } else if (previewUrl.isNotBlank()) {
                            mediaMarkdownBlocks.add("![视频封面]($previewUrl)")
                        }
                    }
                    else -> {
                        val imgUrl = mediaInfo?.safeString("original_img_url") ?: ""
                        if (imgUrl.isNotBlank()) {
                            mediaUrls.add(imgUrl)
                            mediaMarkdownBlocks.add("![图片]($imgUrl)")
                        }
                    }
                }
            }
        } else if (legacyObj != null) {
            // 2.2 Web/旧版 schema: extended_entities / entities
            val extEntities = legacyObj.safeJsonObject("extended_entities") ?: legacyObj.safeJsonObject("entities")
            val mediaArr = extEntities?.safeJsonArray("media")

            if (mediaArr != null) {
                for (mElem in mediaArr) {
                    if (!mElem.isJsonObject) continue
                    val mObj = mElem.asJsonObject
                    val tCoUrl = mObj.safeString("url")
                    val type = mObj.safeString("type")
                    val imgUrl = mObj.safeString("media_url_https").ifBlank { mObj.safeString("media_url") }

                    if (imgUrl.isNotBlank()) {
                        mediaUrls.add(imgUrl)
                    }

                    // 文本中如果包含该附件的 t.co 短链，直接剔除（因为后续会用 Markdown 图片展示）
                    if (tCoUrl.isNotBlank()) {
                        text = text.replace(tCoUrl, "").trim()
                    }

                    when (type) {
                        "video", "animated_gif" -> {
                            val videoInfo = mObj.safeJsonObject("video_info")
                            val bestVideoUrl = selectMp4Url(videoInfo?.safeJsonArray("variants"))

                            if (bestVideoUrl.isNotBlank()) {
                                if (type == "animated_gif") {
                                    mediaMarkdownBlocks.add("[GIF]($bestVideoUrl)")
                                } else if (imgUrl.isNotBlank()) {
                                    mediaMarkdownBlocks.add("[![视频封面]($imgUrl)]($bestVideoUrl)\n\n[▶ 点击播放视频]($bestVideoUrl)")
                                } else {
                                    mediaMarkdownBlocks.add("[▶ 点击播放视频]($bestVideoUrl)")
                                }
                            } else if (imgUrl.isNotBlank()) {
                                mediaMarkdownBlocks.add("![媒体图片]($imgUrl)")
                            }
                        }
                        else -> {
                            if (imgUrl.isNotBlank()) {
                                mediaMarkdownBlocks.add("![图片]($imgUrl)")
                            }
                        }
                    }
                }
            }
        }

        if (mediaMarkdownBlocks.isNotEmpty()) {
            text = (text.trim() + "\n\n" + mediaMarkdownBlocks.joinToString("\n\n")).trim()
        }

        return text.trim()
    }

    private fun selectMp4Url(variants: JsonArray?): String {
        var bestUrl = ""
        var maxBitrate = -1L
        for (element in variants ?: return "") {
            if (!element.isJsonObject) continue
            val variant = element.asJsonObject
            if (variant.safeString("content_type") != "video/mp4") continue
            val url = variant.safeString("url")
            val bitrate = variant.safeLong("bitrate", variant.safeLong("bit_rate", -1L))
            // 兼容未提供码率的 GIF，仍应选中第一个有效 MP4。
            if (url.isNotBlank() && (bestUrl.isBlank() || bitrate > maxBitrate)) {
                bestUrl = url
                maxBitrate = bitrate
            }
        }
        return bestUrl
    }

    /**
     * 生成推文标题（截取正文前 60 字或第一行，消除多余 Markdown 标记）
     */
    fun generateTweetTitle(content: String, prefix: String = ""): String {
        val clean = content
            .replace(Regex("!\\[.*?\\]\\(.*?\\)"), "") // 移除图片
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1") // 保留链接文本
            .replace(Regex("[>#*`_~]"), "") // 移除 Markdown 标记
            .lines()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() } ?: "推文"

        val titleText = if (clean.length > 60) clean.substring(0, 60).trim() + "..." else clean
        return if (prefix.isNotBlank()) "$prefix$titleText" else titleText
    }
}
