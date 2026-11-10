package me.melon.record.parser.zhihu

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.objectbox.Box
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_

/**
 * 知乎关系链接器：处理问题与回答之间的双向绑定、合并元数据。
 */
object ZhihuRelations {

    private val gson = Gson()

    fun mergeQuestionExtra(existing: String, incoming: String): String {
        val oldObj = try { JsonParser.parseString(existing).asJsonObject } catch (_: Exception) { JsonObject() }
        val newObj = try { JsonParser.parseString(incoming).asJsonObject } catch (_: Exception) { JsonObject() }

        for ((key, value) in oldObj.entrySet()) {
            if (!newObj.has(key)) newObj.add(key, value)
        }

        if (oldObj.has("answer_refs") && newObj.has("answer_refs") && oldObj.get("answer_refs").isJsonArray && newObj.get("answer_refs").isJsonArray) {
            val merged = JsonArray()
            val seen = HashSet<String>()
            fun addAll(arr: JsonArray) {
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

    fun extractAnswerIdsFromQuestionExtra(extra: String): List<String> {
        val obj = try { JsonParser.parseString(extra).asJsonObject } catch (_: Exception) { return emptyList() }
        val arr = obj.getAsJsonArray("answer_refs") ?: return emptyList()
        val ids = ArrayList<String>(arr.size())
        for (e in arr) {
            if (!e.isJsonObject) continue
            val id = e.asJsonObject.safeString("id")
            if (id.isNotBlank()) ids.add(id)
        }
        return ids
    }

    fun linkAnswersByIds(recordBox: Box<UniversalRecord>, questionPlatformId: String, answerIds: List<String>) {
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

    fun linkExistingAnswersToQuestion(recordBox: Box<UniversalRecord>, questionId: String, questionPlatformId: String) {
        val query = recordBox.query()
            .equal(UniversalRecord_.platform, "zhihu", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
            .equal(UniversalRecord_.recordType, "answer", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
            .equal(UniversalRecord_.parentId, "", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
            .build()
        val answers = query.find()
        query.close()
        if (answers.isEmpty()) return
        val changedAnswers = ArrayList<UniversalRecord>()
        for (a in answers) {
            val qid = try {
                JsonParser.parseString(a.extraMetadata).asJsonObject.safeString("question_id")
            } catch (_: Exception) {
                ""
            }
            if (qid == questionId) {
                a.parentId = questionPlatformId
                changedAnswers.add(a)
            }
        }
        if (changedAnswers.isNotEmpty()) recordBox.put(changedAnswers)
    }

    internal fun JsonObject.safeString(key: String): String {
        return if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asString else ""
    }
}
