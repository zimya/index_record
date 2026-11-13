package me.melon.record.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder
import kotlinx.coroutines.flow.Flow
import me.melon.record.ObjectBox
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.RecordBodyFormat
import me.melon.record.data.model.RecordIdentity
import me.melon.record.data.model.RecordRelation
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.data.model.directRelation
import me.melon.record.data.model.immutableList
import me.melon.record.data.model.immutableMap
import me.melon.record.data.model.toDetailRecord


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
    val authorAvatar: String,
    val excerptFormat: RecordBodyFormat = RecordBodyFormat.HTML
)

internal data class ZhihuTopicRef(val id: String, val name: String)

internal class QuestionDetailSnapshot(
    val record: DetailRecord,
    val excerpt: String = "",
    topics: List<ZhihuTopicRef> = emptyList(),
    answerRefs: List<ZhihuAnswerRef> = emptyList(),
    existingAnswers: Map<String, DetailRecord> = emptyMap()
) {
    val topics: List<ZhihuTopicRef> = immutableList(topics)
    val answerRefs: List<ZhihuAnswerRef> = immutableList(answerRefs)
    val existingAnswers: Map<String, DetailRecord> = immutableMap(existingAnswers)
}

internal class QuestionDetailRepository(private val store: BoxStore = ObjectBox.store) {
    fun observe(seed: DetailRecord): Flow<Result<QuestionDetailSnapshot>> = observeRecordSnapshots(store) {
        assembleQuestionDetailSnapshot(seed) { identity ->
            val query = store.boxFor(UniversalRecord::class.java).query()
                .equal(UniversalRecord_.platform, identity.platform, StringOrder.CASE_SENSITIVE)
                .`in`(UniversalRecord_.platformId, identity.storageIds().toTypedArray(), StringOrder.CASE_SENSITIVE)
                .build()
            try { query.findFirst()?.toDetailRecord() } finally { query.close() }
        }
    }
}

internal fun assembleQuestionDetailSnapshot(
    seed: DetailRecord,
    find: (RecordIdentity) -> DetailRecord?
): QuestionDetailSnapshot {
    val identity = RecordIdentity.of(seed.platform, seed.platformId)
    val current = find(identity) ?: seed
    val meta = runCatching { JsonParser.parseString(current.extraMetadata).asJsonObject }.getOrDefault(JsonObject())
    fun objects(key: String): List<JsonObject> = runCatching {
        meta.getAsJsonArray(key)?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
    }.getOrDefault(emptyList())
    val topics = objects("topics").mapNotNull {
        val id = it.string("id")
        val name = it.string("name")
        if (id.isBlank() || name.isBlank()) null else ZhihuTopicRef(id, name)
    }
    val refs = objects("answer_refs").mapNotNull {
        val id = it.string("id")
        if (id.isBlank()) return@mapNotNull null
        val author = runCatching { it.getAsJsonObject("author") }.getOrNull()
        ZhihuAnswerRef(id, it.string("excerpt"), it.long("created_time"), it.long("updated_time"),
            it.long("voteup_count"), it.long("comment_count"), author?.string("id").orEmpty(),
            author?.string("name") ?: "知乎用户", author?.string("avatar_url").orEmpty())
    }.distinctBy { it.id }
    val answers = refs.mapNotNull { ref ->
        val answer = find(RecordIdentity.of(current.platform, "zhihu_answer_${ref.id}")) ?: return@mapNotNull null
        val relation = answer.directRelation()
        // A reference without parentId remains navigable; an explicit different question is excluded.
        if (relation is RecordRelation.AnswerToQuestion && relation.target != identity) return@mapNotNull null
        ref.id to answer
    }.toMap()
    return QuestionDetailSnapshot(current, meta.string("excerpt"), topics, refs, answers)
}

private fun JsonObject.string(key: String): String =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")

private fun JsonObject.long(key: String): Long =
    runCatching { get(key)?.asLong ?: 0L }.getOrDefault(0L)
