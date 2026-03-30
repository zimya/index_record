package me.melon.record.logic

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.ObjectBox
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import org.jsoup.Jsoup
import java.util.regex.Pattern

object DataProcessor {

    private const val TAG = "DataProcessor"

    // 1. JSON 类型的文章接口
    private val PATTERN_JSON_ARTICLE = Pattern.compile("https?://api\\.zhihu\\.com/articles/[^/]+/?")
    // 2. HTML 类型的回答接口
    private val PATTERN_HTML_ANSWER = Pattern.compile("https?://www\\.zhihu\\.com/appview/v2/answer/[^/]+/?")

    private val gson = Gson()

    @Synchronized
    fun processPendingData() {
        val rawBox = ObjectBox.store.boxFor(RawData::class.java)
        val recordBox = ObjectBox.store.boxFor(UniversalRecord::class.java)

        val allRawData = rawBox.all
        if (allRawData.isEmpty()) {
            return
        }

        Log.i(TAG, "Found ${allRawData.size} raw records. Starting process...")

        val processedRawIds = mutableListOf<Long>()

        ObjectBox.store.runInTx {
            for (raw in allRawData) {
                try {
                    val cleanUrl = if (raw.url.contains("?")) raw.url.substringBefore("?") else raw.url

                    // 调试输出：打印收到的所有原始数据，不分类型
                    val debugJson = if (raw.json.length > 500) raw.json.take(500) + "..." else raw.json
                    Log.d(TAG, ">>> Processing RawData from [${raw.hostApp}]: $cleanUrl")
                    Log.d(TAG, ">>> Content Snippet: ${debugJson.replace("\n", " ")}")

                    var record: UniversalRecord? = null

                    if (PATTERN_JSON_ARTICLE.matcher(cleanUrl).matches()) {
                        record = parseJsonArticle(raw, cleanUrl)
                    } else if (PATTERN_HTML_ANSWER.matcher(cleanUrl).matches()) {
                        record = parseHtmlAnswer(raw, cleanUrl)
                    } else {
                        // 仅输出日志，不再存入数据库
                        Log.i(TAG, "Unmatched URL (Log Only): $cleanUrl")
                    }

                    if (record != null) {
                        // 【去重逻辑】检查是否已经存过该 platformId
                        val existing = recordBox.query()
                            .equal(UniversalRecord_.platformId, record.platformId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                            .build()
                            .findFirst()

                        if (existing != null) {
                            record.id = existing.id // 复用现有主键，触发 Update 而不是 Insert
                        }
                        recordBox.put(record)
                        Log.d(TAG, "Parse SUCCESS & Saved: ${record.title} [${record.platformId}]")
                    }
                    // 无论是否匹配/成功，都标记为已处理以防卡死队列（视需求也可保留失败的）
                    processedRawIds.add(raw.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process ID: ${raw.id}", e)
                    // 解析失败的记录也删掉，防止无限重试崩溃。如果想调试可以注释下行。
                    processedRawIds.add(raw.id)
                }
            }
            rawBox.remove(*processedRawIds.toLongArray())
        }
        Log.i(TAG, "Cleaned up ${processedRawIds.size} RawData.")
    }

    private fun parseJsonArticle(raw: RawData, cleanUrl: String): UniversalRecord {
        val jsonParams = JsonParser.parseString(raw.json).asJsonObject

        val articleId = cleanUrl.trimEnd('/').substringAfterLast('/')
        val title = jsonParams.safeString("title")
        val content = jsonParams.safeString("content")

        val authorObj = jsonParams.getAsJsonObject("author")
        val authorName = authorObj?.safeString("name") ?: "Unknown"
        val authorId = authorObj?.safeString("id") ?: ""

        val extraJson = JsonObject()
        val fieldsToExtract = listOf("url", "created", "updated", "ip_info", "voteup_count", "comment_count", "favlists_count")
        for (field in fieldsToExtract) {
            if (jsonParams.has(field)) extraJson.add(field, jsonParams.get(field))
        }

        return UniversalRecord(
            platform = "zhihu",
            recordType = "article",
            sourceApp = raw.hostApp,
            platformId = "zhihu_article_$articleId",
            title = title,
            content = content,
            authorId = authorId,
            authorName = authorName,
            sourceUrl = raw.url,
            publishTime = jsonParams.get("created")?.asLong?.times(1000) ?: raw.timestamp,
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extraJson)
        )
    }

    private fun parseHtmlAnswer(raw: RawData, cleanUrl: String): UniversalRecord {
        val answerId = cleanUrl.trimEnd('/').substringAfterLast('/')
        val doc = Jsoup.parse(raw.json)
        val scriptTag = doc.select("script#js-initialData[type=\"text/json\"]").first()
            ?: throw Exception("script#js-initialData tag NOT found in HTML!")

        val fullJson = JsonParser.parseString(scriptTag.html()).asJsonObject
        val answerData = fullJson
            .getAsJsonObject("initialState")
            ?.getAsJsonObject("answers")
            ?.getAsJsonObject(answerId)
            ?: throw Exception("Answer data not found for id: $answerId")

        val content = answerData.safeString("content")

        // 知乎新版的 author 可能是个对象或者字符串，这里做兼容
        var authorName = "知乎用户"
        var authorId = ""
        if (answerData.has("author") && answerData.get("author").isJsonObject) {
            val authorObj = answerData.getAsJsonObject("author")
            authorName = authorObj.safeString("name")
            authorId = authorObj.safeString("id")
        } else {
            authorName = answerData.safeString("author")
        }

        val questionData = answerData.getAsJsonObject("question")
        val questionTitle = questionData?.safeString("title") ?: "知乎回答 $answerId"

        val extraJson = JsonObject()
        extraJson.addProperty("parse_mode", "html_script")

        // 尝试提取更多元数据
        val fieldsToExtract = listOf("voteupCount", "commentCount", "updatedTime", "createdTime", "ipInfo")
        for (field in fieldsToExtract) {
            if (answerData.has(field)) {
                val value = answerData.get(field)
                // 统一字段名风格
                val targetField = when(field) {
                    "voteupCount" -> "voteup_count"
                    "commentCount" -> "comment_count"
                    "updatedTime" -> "updated"
                    "createdTime" -> "created"
                    "ipInfo" -> "ip_info"
                    else -> field
                }
                extraJson.add(targetField, value)
            }
        }

        // 调试输出：打印提取到的元数据
        Log.d(TAG, "Extracted extra metadata for answer $answerId: ${gson.toJson(extraJson)}")

        val createdTime = if (answerData.has("createdTime")) answerData.get("createdTime").asLong * 1000 else raw.timestamp
        val updatedTime = if (answerData.has("updatedTime")) answerData.get("updatedTime").asLong * 1000 else createdTime

        return UniversalRecord(
            platform = "zhihu",
            recordType = "answer",
            sourceApp = raw.hostApp,
            platformId = "zhihu_answer_$answerId",
            title = questionTitle,
            content = content,
            authorId = authorId,
            authorName = authorName,
            sourceUrl = raw.url,
            publishTime = updatedTime, // 使用更新时间作为发布时间参考
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extraJson)
        )
        }

        private fun JsonObject.safeString(key: String): String {

        return if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asString else ""
    }
}
