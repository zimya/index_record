package me.melon.record.logic

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.ObjectBox
import me.melon.record.data.model.Article
import me.melon.record.data.model.RawData
import org.jsoup.Jsoup
import java.util.regex.Pattern

object DataProcessor {

    private const val TAG = "ZhihuProcessor" // 定义日志标签

    // 1. JSON 类型的文章接口
    private val PATTERN_JSON_ARTICLE = Pattern.compile("https?://api\\.zhihu\\.com/articles/[^/]+/?")
    // 2. HTML 类型的回答接口
    private val PATTERN_HTML_ANSWER = Pattern.compile("https?://www\\.zhihu\\.com/appview/v2/answer/[^/]+/?")

    private val gson = Gson()

    fun processPendingData() {
        val rawBox = ObjectBox.store.boxFor(RawData::class.java)
        val articleBox = ObjectBox.store.boxFor(Article::class.java)

        val allRawData = rawBox.all
        if (allRawData.isEmpty()) {
            Log.d(TAG, "No pending data to process.")
            return
        }

        Log.i(TAG, "Found ${allRawData.size} raw records. Starting process...")

        val newArticles = mutableListOf<Article>()
        val processedRawIds = mutableListOf<Long>()

        for (raw in allRawData) {
            // 打印正在处理的 URL
            Log.d(TAG, ">>> Processing ID: ${raw.id} | URL: ${raw.url}")

            try {
                // 去参数
                val cleanUrl = if (raw.url.contains("?")) raw.url.substringBefore("?") else raw.url
                Log.d(TAG, "    Clean URL: $cleanUrl")

                var article: Article? = null

                if (PATTERN_JSON_ARTICLE.matcher(cleanUrl).matches()) {
                    Log.d(TAG, "    Matched Type: JSON Article")
                    article = parseJsonArticle(raw)
                } else if (PATTERN_HTML_ANSWER.matcher(cleanUrl).matches()) {
                    Log.d(TAG, "    Matched Type: HTML Answer")
                    article = parseHtmlAnswer(raw, cleanUrl)
                } else {
                    Log.w(TAG, "    [WARNING] No Regex matched for: $cleanUrl")
                }

                if (article != null) {
                    newArticles.add(article)
                    Log.d(TAG, "    Parse SUCCESS: ${article.title}")
                    // 只有解析成功才删除？或者你想不管成不成功都删除，就把下面这行移到 finally 块里
                    processedRawIds.add(raw.id)
                }

            } catch (e: Exception) {
                // 打印详细错误堆栈！！！
                Log.e(TAG, "    [ERROR] Failed to process ID: ${raw.id}", e)
            }
        }

        if (newArticles.isNotEmpty()) {
            ObjectBox.store.runInTx {
                articleBox.put(newArticles)
                // 注意这里的 * 号
                rawBox.remove(*processedRawIds.toLongArray())
            }
            Log.i(TAG, "Saved ${newArticles.size} new articles and cleaned up RawData.")
        } else {
            Log.i(TAG, "No valid articles parsed.")
        }
    }

    private fun parseJsonArticle(raw: RawData): Article {
        val jsonParams = JsonParser.parseString(raw.json).asJsonObject

        val title = jsonParams.safeString("title")
        val content = jsonParams.safeString("content")
        val createTime = raw.timestamp

        val authorObj = jsonParams.getAsJsonObject("author")
        val authorName = if (authorObj != null) authorObj.safeString("name") else "Unknown"

        val extraJson = JsonObject()
        val fieldsToExtract = listOf("url", "created", "updated", "ip_info", "voteup_count", "comment_count", "favlists_count")

        for (field in fieldsToExtract) {
            if (jsonParams.has(field)) {
                extraJson.add(field, jsonParams.get(field))
            }
        }

        return Article(
            title = title,
            contentMarkdown = content,
            author = authorName,
            createTime = createTime,
            sourceUrl = raw.url,
            extraData = gson.toJson(extraJson)
        )
    }

    private fun parseHtmlAnswer(raw: RawData, cleanUrl: String): Article {
        val answerId = cleanUrl.trimEnd('/').substringAfterLast('/')
        Log.d(TAG, "    Target Answer ID: $answerId")

        // 检查 HTML 内容长度
        Log.d(TAG, "    HTML Length: ${raw.json.length}")

        val doc = Jsoup.parse(raw.json)
        val scriptTag = doc.select("script#js-initialData[type=\"text/json\"]").first()

        if (scriptTag == null) {
            // 如果这里报错，说明抓下来的 HTML 不对，可能是登录页或者验证码页
            throw Exception("Critical: script#js-initialData tag NOT found in HTML!")
        }

        val jsonText = scriptTag.html()
        if (jsonText.isEmpty()) throw Exception("js-initialData is empty")

        val fullJson = JsonParser.parseString(jsonText).asJsonObject

        val answerData = fullJson
            .getAsJsonObject("initialState")
            ?.getAsJsonObject("answers")
            ?.getAsJsonObject(answerId)

        if (answerData == null) {
            // 打印一下 keys 看看有哪些 ID，方便调试
            val availableIds = fullJson.getAsJsonObject("initialState")
                ?.getAsJsonObject("entities")?.getAsJsonObject("answers")?.keySet()
            throw Exception("Answer data not found for id: $answerId. Available IDs: $availableIds")
        }

        val content = answerData.safeString("content")
        val authorName = answerData.safeString("author") ?: "知乎用户"
        val questionData = answerData.getAsJsonObject("question")
        val questionTitle = questionData?.safeString("title") ?: "知乎回答 $answerId"

        val extraJson = JsonObject()
        extraJson.addProperty("parse_mode", "html_script")
        if (answerData.has("voteupCount")) extraJson.add("voteup_count", answerData.get("voteupCount"))
        if (answerData.has("commentCount")) extraJson.add("comment_count", answerData.get("commentCount"))
        if (answerData.has("createdTime")) extraJson.add("created", answerData.get("createdTime"))

        return Article(
            title = questionTitle,
            contentMarkdown = content,
            author = authorName,
            createTime = raw.timestamp,
            sourceUrl = raw.url,
            extraData = gson.toJson(extraJson)
        )
    }

    private fun JsonObject.safeString(key: String): String {
        return if (this.has(key) && !this.get(key).isJsonNull) {
            this.get(key).asString
        } else {
            ""
        }
    }
}
