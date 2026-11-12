package me.melon.record.ui.detail.question


import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_

internal enum class AnswerSort { LATEST, VOTEUP }

internal data class ZhihuAnswerRef(
    val id: String,
    val excerpt: String,
    val createdTimeSec: Long,
    val updatedTimeSec: Long,
    val voteupCount: Long,
    val commentCount: Long,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String
)

internal data class ZhihuTopicRef(
    val id: String,
    val name: String
)

internal data class QuestionDetailData(
    val excerpt: String,
    val topics: List<ZhihuTopicRef>,
    val sortedAnswerRefs: List<ZhihuAnswerRef>,
    val existingAnswers: Map<String, UniversalRecord>
)

@Composable
internal fun rememberQuestionDetailData(record: UniversalRecord, sort: AnswerSort): QuestionDetailData {
    val meta = remember(record.extraMetadata) {
        try {
            JsonParser.parseString(record.extraMetadata).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }
    val excerpt = remember(meta) { meta.safeString("excerpt") }

    val topics = remember(meta) {
        val arr = meta.getAsJsonArray("topics") ?: JsonArray()
        val out = ArrayList<ZhihuTopicRef>(arr.size())
        for (e in arr) {
            if (!e.isJsonObject) continue
            val obj = e.asJsonObject
            val id = obj.safeString("id")
            val name = obj.safeString("name")
            if (id.isNotBlank() && name.isNotBlank()) out.add(ZhihuTopicRef(id, name))
        }
        out
    }

    val answerRefs = remember(meta) {
        val arr = meta.getAsJsonArray("answer_refs") ?: JsonArray()
        val out = ArrayList<ZhihuAnswerRef>(arr.size())
        for (e in arr) {
            if (!e.isJsonObject) continue
            val obj = e.asJsonObject
            val id = obj.safeString("id")
            if (id.isBlank()) continue
            val excerpt = obj.safeString("excerpt")
            val created = obj.safeLong("created_time")
            val updated = obj.safeLong("updated_time")
            val voteup = obj.safeLong("voteup_count")
            val comment = obj.safeLong("comment_count")
            val authorObj = obj.getAsJsonObject("author")
            out.add(
                ZhihuAnswerRef(
                    id = id,
                    excerpt = excerpt,
                    createdTimeSec = created,
                    updatedTimeSec = updated,
                    voteupCount = voteup,
                    commentCount = comment,
                    authorId = authorObj?.safeString("id") ?: "",
                    authorName = authorObj?.safeString("name") ?: "知乎用户",
                    authorAvatar = authorObj?.safeString("avatar_url") ?: ""
                )
            )
        }
        out
    }

    var existingAnswers by remember { mutableStateOf<Map<String, UniversalRecord>>(emptyMap()) }
    LaunchedEffect(record.platformId, answerRefs.size) {
        withContext(Dispatchers.IO) {
            val ids = answerRefs.map { "zhihu_answer_${it.id}" }.distinct()
            if (ids.isEmpty()) {
                existingAnswers = emptyMap()
                return@withContext
            }
            val box = ObjectBox.store.boxFor(UniversalRecord::class.java)
            val found = box.query()
                .`in`(UniversalRecord_.platformId, ids.toTypedArray(), io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                .build()
                .find()
            existingAnswers = found.associateBy { it.platformId.removePrefix("zhihu_answer_") }
        }
    }

    val sortedAnswerRefs = remember(answerRefs, sort) {
        when (sort) {
            AnswerSort.LATEST -> answerRefs.sortedByDescending { maxOf(it.updatedTimeSec, it.createdTimeSec) }
            AnswerSort.VOTEUP -> answerRefs.sortedWith(compareByDescending<ZhihuAnswerRef> { it.voteupCount }.thenByDescending { maxOf(it.updatedTimeSec, it.createdTimeSec) })
        }
    }

    return QuestionDetailData(excerpt, topics, sortedAnswerRefs, existingAnswers)
}

private fun JsonObject.safeString(key: String): String {
    return if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asString else ""
}

private fun JsonObject.safeLong(key: String): Long {
    return try {
        if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asLong else 0L
    } catch (_: Exception) {
        0L
    }
}
