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
    // 2.1 知乎问题详情接口
    private val PATTERN_ZHIHU_QUESTION_DETAIL = Pattern.compile(
        "(?:https?://(?:www\\.)?zhihu\\.com/api/v4/questions/(\\d+)/?)|(?:https?://api\\.zhihu\\.com/(?:api/v4/)?questions/(\\d+)/?)"
    )
    // 2.2 知乎问题页回答列表接口
    private val PATTERN_ZHIHU_QUESTION_FEEDS = Pattern.compile("https?://api\\.zhihu\\.com/questions/(\\d+)/feeds/?")
    // 3. 知乎根评论接口 (支持 answers 和 articles)
    private val PATTERN_ZHIHU_ROOT_COMMENT = Pattern.compile("https?://api\\.zhihu\\.com/comment_v5/(answers|articles)/(\\d+)/root_comment.*")
    // 4. 知乎子评论接口
    private val PATTERN_ZHIHU_CHILD_COMMENT = Pattern.compile("https?://api\\.zhihu\\.com/comment_v5/comment/(\\d+)/child_comment.*")

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

                    var records: List<UniversalRecord>? = null

                    if (PATTERN_JSON_ARTICLE.matcher(cleanUrl).matches()) {
                        val r = parseJsonArticle(raw, cleanUrl)
                        records = listOf(r)
                    } else if (PATTERN_HTML_ANSWER.matcher(cleanUrl).matches()) {
                        val r = parseHtmlAnswer(raw, cleanUrl)
                        records = listOf(r)
                    } else if (PATTERN_ZHIHU_QUESTION_DETAIL.matcher(cleanUrl).matches()) {
                        val r = parseZhihuQuestionDetail(raw, cleanUrl)
                        records = listOf(r)
                    } else if (PATTERN_ZHIHU_QUESTION_FEEDS.matcher(cleanUrl).matches()) {
                        val r = parseZhihuQuestionFeeds(raw, cleanUrl)
                        records = listOf(r)
                    } else if (PATTERN_ZHIHU_ROOT_COMMENT.matcher(cleanUrl).matches()) {
                        records = parseZhihuComments(raw, cleanUrl, true)
                    } else if (PATTERN_ZHIHU_CHILD_COMMENT.matcher(cleanUrl).matches()) {
                        records = parseZhihuComments(raw, cleanUrl, false)
                    } else {
                        // 仅输出日志，不再存入数据库
                        Log.i(TAG, "Unmatched URL (Log Only): $cleanUrl")
                    }

                    if (records != null) {
                        for (record in records) {
                            // 【去重逻辑】检查是否已经存过该 platformId
                            val existing = recordBox.query()
                                .equal(UniversalRecord_.platformId, record.platformId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                                .build()
                                .findFirst()

                            if (existing != null) {
                                record.id = existing.id // 复用现有主键，触发 Update 而不是 Insert
                                if (record.platform == "zhihu" && record.recordType == "question") {
                                    record.extraMetadata = mergeQuestionExtra(existing.extraMetadata, record.extraMetadata)
                                    if (record.title.isBlank()) record.title = existing.title
                                    if (record.content.isBlank()) record.content = existing.content
                                    if (record.authorId.isBlank()) record.authorId = existing.authorId
                                    if (record.authorName.isBlank()) record.authorName = existing.authorName
                                    if (record.authorAvatar.isBlank()) record.authorAvatar = existing.authorAvatar
                                    if (record.sourceUrl.isBlank()) record.sourceUrl = existing.sourceUrl
                                    record.publishTime = maxOf(existing.publishTime, record.publishTime)
                                    record.captureTime = maxOf(existing.captureTime, record.captureTime)
                                }
                            }
                            recordBox.put(record)
                            Log.d(TAG, "Parse SUCCESS & Saved: ${record.title} [${record.platformId}]")

                            if (record.platform == "zhihu" && record.recordType == "question") {
                                val qid = try {
                                    JsonParser.parseString(record.extraMetadata).asJsonObject.safeString("question_id")
                                } catch (_: Exception) {
                                    ""
                                }
                                if (qid.isNotBlank()) {
                                    linkExistingAnswersToQuestion(recordBox, qid, record.platformId)
                                }
                                val answerIds = try {
                                    extractAnswerIdsFromQuestionExtra(record.extraMetadata)
                                } catch (_: Exception) {
                                    emptyList()
                                }
                                if (answerIds.isNotEmpty()) {
                                    linkAnswersByIds(recordBox, record.platformId, answerIds)
                                }
                            }
                        }
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
        val authorAvatar = authorObj?.safeString("avatar_url") ?: ""
        val authorHeadline = authorObj?.safeString("headline") ?: ""

        val extraJson = JsonObject()
        val fieldsToExtract = listOf("url", "created", "updated", "ip_info", "voteup_count", "comment_count", "favlists_count", "title_image")
        for (field in fieldsToExtract) {
            if (jsonParams.has(field)) extraJson.add(field, jsonParams.get(field))
        }
        if (authorHeadline.isNotBlank()) extraJson.addProperty("author_headline", authorHeadline)

        return UniversalRecord(
            platform = "zhihu",
            recordType = "article",
            sourceApp = raw.hostApp,
            platformId = "zhihu_article_$articleId",
            title = title,
            content = content,
            authorId = authorId,
            authorName = authorName,
            authorAvatar = authorAvatar,
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

        val initialState = fullJson.getAsJsonObject("initialState")

        var authorName = "知乎用户"
        var authorId = ""
        var authorAvatar = ""
        var authorHeadline = ""

        if (answerData.has("author") && answerData.get("author").isJsonObject) {
            val authorObj = answerData.getAsJsonObject("author")
            authorName = authorObj.safeString("name")
            authorId = authorObj.safeString("id")
            authorAvatar = authorObj.safeString("avatarUrl").ifBlank { authorObj.safeString("avatar_url") }
            authorHeadline = authorObj.safeString("headline")
        } else {
            val authorToken = answerData.safeString("author")
            if (authorToken.isNotBlank()) {
                val userMap = initialState?.getAsJsonObject("user")
                val userObj = userMap?.getAsJsonObject(authorToken)
                if (userObj != null) {
                    authorName = userObj.safeString("name").ifBlank { authorToken }
                    authorId = userObj.safeString("id")
                    authorAvatar = userObj.safeString("avatarUrl").ifBlank { userObj.safeString("avatar_url") }
                    authorHeadline = userObj.safeString("headline")
                } else {
                    authorName = authorToken
                }
            }
        }

        val questionData = answerData.getAsJsonObject("question")
        val questionTitle = questionData?.safeString("title") ?: "知乎回答 $answerId"
        val questionId = questionData?.safeString("id") ?: ""

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
        if (authorHeadline.isNotBlank()) extraJson.addProperty("author_headline", authorHeadline)
        if (questionId.isNotBlank()) extraJson.addProperty("question_id", questionId)

        // 调试输出：打印提取到的元数据
        Log.d(TAG, "Extracted extra metadata for answer $answerId: ${gson.toJson(extraJson)}")

        val createdTime = if (answerData.has("createdTime")) answerData.get("createdTime").asLong * 1000 else raw.timestamp
        val updatedTime = if (answerData.has("updatedTime")) answerData.get("updatedTime").asLong * 1000 else createdTime
        val questionPlatformId = if (questionId.isNotBlank()) "zhihu_question_$questionId" else ""
        val shouldBindToQuestion = if (questionPlatformId.isNotBlank()) {
            val recordBox = ObjectBox.store.boxFor(UniversalRecord::class.java)
            recordBox.query()
                .equal(UniversalRecord_.platformId, questionPlatformId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                .build()
                .findFirst() != null
        } else {
            false
        }

        return UniversalRecord(
            platform = "zhihu",
            recordType = "answer",
            sourceApp = raw.hostApp,
            platformId = "zhihu_answer_$answerId",
            title = questionTitle,
            content = content,
            authorId = authorId,
            authorName = authorName,
            authorAvatar = authorAvatar,
            sourceUrl = raw.url,
            parentId = if (shouldBindToQuestion) questionPlatformId else "",
            publishTime = updatedTime, // 使用更新时间作为发布时间参考
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extraJson)
        )
        }

    private fun parseZhihuQuestionDetail(raw: RawData, cleanUrl: String): UniversalRecord {
        val jsonParams = JsonParser.parseString(raw.json).asJsonObject
        val questionId = jsonParams.safeString("id").ifBlank {
            val matcher = PATTERN_ZHIHU_QUESTION_DETAIL.matcher(cleanUrl)
            if (matcher.find()) (matcher.group(1) ?: "").ifBlank { matcher.group(2) ?: "" } else ""
        }
        if (questionId.isBlank()) throw Exception("Question id not found")

        val title = jsonParams.safeString("title")
        val detail = jsonParams.safeString("detail").ifBlank { jsonParams.safeString("editable_detail") }

        val authorObj = jsonParams.getAsJsonObject("author")
        val authorName = authorObj?.safeString("name") ?: "知乎用户"
        val authorId = authorObj?.safeString("id") ?: ""
        val authorAvatar = authorObj?.safeString("avatar_url") ?: ""
        val authorHeadline = authorObj?.safeString("headline") ?: ""

        val extraJson = JsonObject()
        extraJson.addProperty("question_id", questionId)
        val fieldsToExtract = listOf(
            "url",
            "created",
            "updated_time",
            "answer_count",
            "comment_count",
            "follower_count",
            "collapsed_answer_count",
            "excerpt"
        )
        for (field in fieldsToExtract) {
            if (jsonParams.has(field)) extraJson.add(field, jsonParams.get(field))
        }
        if (jsonParams.has("topics")) extraJson.add("topics", jsonParams.get("topics"))
        if (authorHeadline.isNotBlank()) extraJson.addProperty("author_headline", authorHeadline)

        val createdMs = jsonParams.get("created")?.asLong?.times(1000) ?: raw.timestamp
        val updatedMs = jsonParams.get("updated_time")?.asLong?.times(1000) ?: createdMs

        return UniversalRecord(
            platform = "zhihu",
            recordType = "question",
            sourceApp = raw.hostApp,
            platformId = "zhihu_question_$questionId",
            title = title,
            content = detail,
            authorId = authorId,
            authorName = authorName,
            authorAvatar = authorAvatar,
            sourceUrl = raw.url,
            publishTime = updatedMs,
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extraJson)
        )
    }

    private fun parseZhihuQuestionFeeds(raw: RawData, cleanUrl: String): UniversalRecord {
        val matcher = PATTERN_ZHIHU_QUESTION_FEEDS.matcher(cleanUrl)
        if (!matcher.find()) throw Exception("Question feed id not found")
        val questionId = matcher.group(1) ?: throw Exception("Question feed id not found")

        val jsonParams = JsonParser.parseString(raw.json).asJsonObject
        val dataArray = jsonParams.getAsJsonArray("data")

        val answerRefs = com.google.gson.JsonArray()
        var title = ""
        if (dataArray != null) {
            for (elem in dataArray) {
                if (!elem.isJsonObject) continue
                val target = elem.asJsonObject.getAsJsonObject("target") ?: continue
                if (target.safeString("type") != "answer") continue
                val answerId = target.safeString("id")
                if (answerId.isBlank()) continue

                val questionObj = target.getAsJsonObject("question")
                if (title.isBlank()) title = questionObj?.safeString("title") ?: ""

                val ref = JsonObject()
                ref.addProperty("id", answerId)
                val excerpt = target.safeString("big_card_summary").ifBlank { target.safeString("excerpt") }
                ref.addProperty("excerpt", excerpt)
                if (target.has("created_time")) ref.add("created_time", target.get("created_time"))
                if (target.has("updated_time")) ref.add("updated_time", target.get("updated_time"))
                if (target.has("voteup_count")) ref.add("voteup_count", target.get("voteup_count"))
                if (target.has("comment_count")) ref.add("comment_count", target.get("comment_count"))

                val author = target.getAsJsonObject("author")
                if (author != null) {
                    val authorRef = JsonObject()
                    authorRef.addProperty("id", author.safeString("id"))
                    authorRef.addProperty("name", author.safeString("name"))
                    authorRef.addProperty("avatar_url", author.safeString("avatar_url"))
                    authorRef.addProperty("headline", author.safeString("headline"))
                    ref.add("author", authorRef)
                }

                answerRefs.add(ref)
            }
        }

        val extraJson = JsonObject()
        extraJson.addProperty("question_id", questionId)
        extraJson.add("answer_refs", answerRefs)

        return UniversalRecord(
            platform = "zhihu",
            recordType = "question",
            sourceApp = raw.hostApp,
            platformId = "zhihu_question_$questionId",
            title = title,
            content = "",
            authorId = "",
            authorName = "",
            authorAvatar = "",
            sourceUrl = raw.url,
            publishTime = raw.timestamp,
            captureTime = raw.timestamp,
            extraMetadata = gson.toJson(extraJson)
        )
    }

    private fun mergeQuestionExtra(existing: String, incoming: String): String {
        val oldObj = try { JsonParser.parseString(existing).asJsonObject } catch (_: Exception) { JsonObject() }
        val newObj = try { JsonParser.parseString(incoming).asJsonObject } catch (_: Exception) { JsonObject() }

        for ((key, value) in oldObj.entrySet()) {
            if (!newObj.has(key)) newObj.add(key, value)
        }

        if (oldObj.has("answer_refs") && newObj.has("answer_refs") && oldObj.get("answer_refs").isJsonArray && newObj.get("answer_refs").isJsonArray) {
            val merged = com.google.gson.JsonArray()
            val seen = HashSet<String>()
            fun addAll(arr: com.google.gson.JsonArray) {
                for (e in arr) {
                    if (!e.isJsonObject) continue
                    val id = e.asJsonObject.safeString("id")
                    if (id.isBlank() || !seen.add(id)) continue
                    merged.add(e)
                }
            }
            addAll(newObj.getAsJsonArray("answer_refs"))
            addAll(oldObj.getAsJsonArray("answer_refs"))
            newObj.add("answer_refs", merged)
        }

        return gson.toJson(newObj)
    }

    private fun extractAnswerIdsFromQuestionExtra(extra: String): List<String> {
        val obj = JsonParser.parseString(extra).asJsonObject
        val arr = obj.getAsJsonArray("answer_refs") ?: return emptyList()
        val ids = ArrayList<String>(arr.size())
        for (e in arr) {
            if (!e.isJsonObject) continue
            val id = e.asJsonObject.safeString("id")
            if (id.isNotBlank()) ids.add(id)
        }
        return ids
    }

    private fun linkAnswersByIds(recordBox: io.objectbox.Box<UniversalRecord>, questionPlatformId: String, answerIds: List<String>) {
        val targetPlatformIds = answerIds.map { "zhihu_answer_$it" }.distinct()
        if (targetPlatformIds.isEmpty()) return
        val arr = targetPlatformIds.toTypedArray()
        val answers = recordBox.query()
            .`in`(UniversalRecord_.platformId, arr, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
            .build()
            .find()
        if (answers.isEmpty()) return
        for (a in answers) {
            if (a.parentId.isBlank()) a.parentId = questionPlatformId
        }
        recordBox.put(answers)
    }

    private fun linkExistingAnswersToQuestion(recordBox: io.objectbox.Box<UniversalRecord>, questionId: String, questionPlatformId: String) {
        val answers = recordBox.query()
            .equal(UniversalRecord_.platform, "zhihu", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
            .equal(UniversalRecord_.recordType, "answer", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
            .build()
            .find()
        if (answers.isEmpty()) return
        var changed = false
        for (a in answers) {
            if (a.parentId.isNotBlank()) continue
            val qid = try {
                JsonParser.parseString(a.extraMetadata).asJsonObject.safeString("question_id")
            } catch (_: Exception) {
                ""
            }
            if (qid == questionId) {
                a.parentId = questionPlatformId
                changed = true
            }
        }
        if (changed) recordBox.put(answers)
    }

        private fun parseZhihuComments(raw: RawData, cleanUrl: String, isRoot: Boolean): List<UniversalRecord> {
        val records = mutableListOf<UniversalRecord>()
        val jsonParams = JsonParser.parseString(raw.json).asJsonObject

        val dataArray = jsonParams.getAsJsonArray("data") ?: return records

        var fallbackParentId = ""
        if (isRoot) {
            val matcher = PATTERN_ZHIHU_ROOT_COMMENT.matcher(cleanUrl)
            if (matcher.find()) {
                val resourceType = matcher.group(1)
                val resourceId = matcher.group(2)
                fallbackParentId = if (resourceType == "answers") "zhihu_answer_$resourceId" else "zhihu_article_$resourceId"
            }
        } else {
            val matcher = PATTERN_ZHIHU_CHILD_COMMENT.matcher(cleanUrl)
            if (matcher.find()) {
                val commentId = matcher.group(1)
                fallbackParentId = "zhihu_comment_$commentId"
            }
        }

        fun parseComment(commentObj: JsonObject, parentFallback: String) {
            val commentId = commentObj.safeString("id")
            if (commentId.isEmpty()) return

            val content = commentObj.safeString("content")

            val authorObj = commentObj.getAsJsonObject("author")
            val authorName = authorObj?.safeString("name") ?: "Unknown"
            val authorId = authorObj?.safeString("id") ?: ""
            val authorAvatar = authorObj?.safeString("avatar_url") ?: ""

            val createdTime = if (commentObj.has("created_time")) commentObj.get("created_time").asLong * 1000 else raw.timestamp

            val replyCommentId = commentObj.safeString("reply_comment_id")
            val parentId = if (replyCommentId.isNotEmpty() && replyCommentId != "0") {
                "zhihu_comment_$replyCommentId"
            } else {
                parentFallback
            }

            val extraJson = JsonObject()
            val fieldsToExtract = listOf("like_count", "dislike_count", "child_comment_count", "score", "reply_comment_id", "reply_root_comment_id")
            for (field in fieldsToExtract) {
                if (commentObj.has(field)) extraJson.add(field, commentObj.get(field))
            }

            records.add(
                UniversalRecord(
                    platform = "zhihu",
                    recordType = "comment",
                    sourceApp = raw.hostApp,
                    platformId = "zhihu_comment_$commentId",
                    title = "评论: $authorName",
                    content = content,
                    authorId = authorId,
                    authorName = authorName,
                    authorAvatar = authorAvatar,
                    sourceUrl = raw.url,
                    parentId = parentId,
                    publishTime = createdTime,
                    captureTime = raw.timestamp,
                    extraMetadata = gson.toJson(extraJson)
                )
            )

            if (commentObj.has("child_comments") && commentObj.get("child_comments").isJsonArray) {
                val childArray = commentObj.getAsJsonArray("child_comments")
                for (childElem in childArray) {
                    if (!childElem.isJsonObject) continue
                    parseComment(childElem.asJsonObject, "zhihu_comment_$commentId")
                }
            }
        }

        for (elem in dataArray) {
            if (!elem.isJsonObject) continue
            parseComment(elem.asJsonObject, fallbackParentId)
        }
        return records
    }

    private fun JsonObject.safeString(key: String): String {

        return if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asString else ""
    }
}
