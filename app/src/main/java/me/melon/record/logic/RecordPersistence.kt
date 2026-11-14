package me.melon.record.logic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.parser.reddit.RedditRelations
import me.melon.record.parser.threads.ThreadsRelations
import me.melon.record.parser.tieba.TiebaRelations
import me.melon.record.parser.zhihu.ZhihuRelations

/** Merge/link reads observe preceding writes; only parse-field completion uses the pre-write chunk view. */
internal interface RecordRepository {
    fun find(platformId: String): UniversalRecord?
    fun children(parentId: String): List<UniversalRecord>
    fun firstChild(parentId: String): UniversalRecord? = children(parentId).firstOrNull()
    fun unboundZhihuAnswers(): List<UniversalRecord>
    fun findIds(platformIds: List<String>): List<UniversalRecord>
    fun put(record: UniversalRecord)
}

internal class ObjectBoxRecordRepository(private val box: Box<UniversalRecord>) : RecordRepository {
    override fun find(platformId: String): UniversalRecord? {
        val query = box.query().equal(UniversalRecord_.platformId, platformId, CASE_SENSITIVE).build()
        return try { query.findFirst() } finally { query.close() }
    }
    override fun children(parentId: String): List<UniversalRecord> {
        val query = box.query().equal(UniversalRecord_.parentId, parentId, CASE_SENSITIVE).build()
        return try { query.find() } finally { query.close() }
    }
    override fun firstChild(parentId: String): UniversalRecord? {
        val query = box.query().equal(UniversalRecord_.parentId, parentId, CASE_SENSITIVE).build()
        return try { query.findFirst() } finally { query.close() }
    }
    override fun unboundZhihuAnswers(): List<UniversalRecord> {
        val query = box.query().equal(UniversalRecord_.platform, "zhihu", CASE_SENSITIVE)
            .equal(UniversalRecord_.recordType, "answer", CASE_SENSITIVE)
            .equal(UniversalRecord_.parentId, "", CASE_SENSITIVE).build()
        return try { query.find() } finally { query.close() }
    }
    override fun findIds(platformIds: List<String>): List<UniversalRecord> {
        if (platformIds.isEmpty()) return emptyList()
        val query = box.query().`in`(UniversalRecord_.platformId, platformIds.distinct().toTypedArray(), CASE_SENSITIVE).build()
        return try { query.find() } finally { query.close() }
    }
    override fun put(record: UniversalRecord) { box.put(record) }
}

/** Hooks deliberately straddle generic merging: body policy first, metadata policy last, links after put. */
internal interface PlatformPersistence {
    val preserveGenericContent: Boolean get() = true
    fun complete(record: UniversalRecord, repository: RecordRepository) {}
    fun completeChunk(records: List<UniversalRecord>, repository: RecordRepository) {
        records.forEach { complete(it, repository) }
    }
    fun completeFailure(error: Exception, repository: RecordRepository) {}
    fun completeEvidence(evidence: CompletionEvidence, repository: RecordRepository) {}
    fun mergeBody(existing: UniversalRecord, incoming: UniversalRecord) {}
    fun mergeExtra(existing: UniversalRecord, incoming: UniversalRecord) {}
    fun afterPut(record: UniversalRecord, repository: RecordRepository) {}
}

/** Platform-owned capture evidence; the coordinator only routes it. */
internal interface CompletionEvidence {
    val platform: String
}

/** Process-lifetime collaborators own their completion state, independently of database rollback. */
internal class RecordPersistenceContext {
    internal val platforms: Map<String, PlatformPersistence> = defaultPersistencePlatforms()
}

internal class RecordPersistence(
    private val repository: RecordRepository,
    context: RecordPersistenceContext = RecordPersistenceContext(),
    private val platforms: Map<String, PlatformPersistence> = context.platforms
) {
    /** Preserve parse-before-write visibility: no completion sees a newly written row in this chunk. */
    fun completeChunk(records: List<UniversalRecord>) {
        records.groupBy { it.platform }.forEach { (name, items) ->
            (platforms[name] ?: DefaultPersistence).completeChunk(items, repository)
        }
    }

    /** Failure evidence is completed before logging/consuming the raw, without writing partial records. */
    fun completeFailure(error: Exception) {
        platforms.values.forEach { it.completeFailure(error, repository) }
    }

    fun complete(evidence: CompletionEvidence) {
        (platforms[evidence.platform] ?: DefaultPersistence).completeEvidence(evidence, repository)
    }

    fun persist(record: UniversalRecord) {
        (platforms[record.platform] ?: DefaultPersistence).complete(record, repository)
        persistCompleted(record)
    }

    fun persistCompleted(record: UniversalRecord) {
        val platform = platforms[record.platform] ?: DefaultPersistence
        val existing = repository.find(record.platformId)
        if (existing != null) {
            record.id = existing.id
            platform.mergeBody(existing, record)
            if (record.parentId.isBlank()) record.parentId = existing.parentId
            if (record.title.isBlank()) record.title = existing.title
            if (platform.preserveGenericContent && (record.content.isBlank() || (record.recordType == "subject" && record.content == record.title))) {
                if (existing.content.isNotBlank() && !(existing.recordType == "subject" && existing.content == existing.title)) {
                    record.content = existing.content
                    record.contentFormat = existing.contentFormat
                }
            }
            if (record.authorId.isBlank()) record.authorId = existing.authorId
            if (record.authorName.isBlank()) record.authorName = existing.authorName
            if (record.authorAvatar.isBlank()) record.authorAvatar = existing.authorAvatar
            if (record.sourceUrl.isBlank()) record.sourceUrl = existing.sourceUrl
            record.publishTime = maxOf(existing.publishTime, record.publishTime)
            record.captureTime = maxOf(existing.captureTime, record.captureTime)
            platform.mergeExtra(existing, record)
        }
        repository.put(record)
        platform.afterPut(record, repository)
    }
}

private object DefaultPersistence : PlatformPersistence
private object WeiboPersistence : PlatformPersistence {
    override fun mergeBody(existing: UniversalRecord, incoming: UniversalRecord) = mergeWeiboRecord(existing, incoming)
}
private object ThreadsPersistence : PlatformPersistence {
    override fun mergeBody(existing: UniversalRecord, incoming: UniversalRecord) = ThreadsRelations.mergeRecord(existing, incoming)
}
private object RedditPersistence : PlatformPersistence {
    override val preserveGenericContent = false
    override fun mergeBody(existing: UniversalRecord, incoming: UniversalRecord) = RedditRelations.mergeRecord(existing, incoming)
}
private object TiebaPersistence : PlatformPersistence {
    override fun mergeExtra(existing: UniversalRecord, incoming: UniversalRecord) {
        val body = TiebaRelations.mergeRecordContent(existing, incoming)
        incoming.content = body.content
        incoming.contentFormat = body.contentFormat
        incoming.extraMetadata = TiebaRelations.mergeExtra(existing.extraMetadata, incoming.extraMetadata)
    }
}
private fun metadata(record: UniversalRecord): JsonObject = JsonParser.parseString(record.extraMetadata).asJsonObject
private fun JsonObject.text(key: String): String = get(key)?.asString ?: ""

private object ZhihuPersistence : PlatformPersistence {
    override fun complete(record: UniversalRecord, repository: RecordRepository) {
        if (record.recordType != "answer" || record.parentId.isNotBlank()) return
        val questionId = runCatching { metadata(record).text("question_id") }.getOrDefault("")
        if (questionId.isBlank()) return
        val questionPlatformId = "zhihu_question_$questionId"
        // A question_id alone is not a parent: bind only to an already persisted question.
        if (repository.find(questionPlatformId) != null) record.parentId = questionPlatformId
    }
    override fun mergeExtra(existing: UniversalRecord, incoming: UniversalRecord) {
        if (incoming.recordType == "question") {
            incoming.extraMetadata = ZhihuRelations.mergeQuestionExtra(existing.extraMetadata, incoming.extraMetadata)
        }
    }
    override fun afterPut(record: UniversalRecord, repository: RecordRepository) {
        if (record.recordType != "question") return
        val qid = runCatching { metadata(record).text("question_id") }.getOrDefault("")
        if (qid.isNotBlank()) {
            for (answer in repository.unboundZhihuAnswers()) {
                val answerQid = runCatching { metadata(answer).text("question_id") }.getOrDefault("")
                if (answerQid == qid) {
                    answer.parentId = record.platformId
                    repository.put(answer)
                }
            }
        }
        val answerIds = runCatching { ZhihuRelations.extractAnswerIdsFromQuestionExtra(record.extraMetadata) }.getOrDefault(emptyList())
        for (answer in repository.findIds(answerIds.map { "zhihu_answer_$it" })) {
            if (answer.parentId.isBlank()) answer.parentId = record.platformId
            repository.put(answer)
        }
    }
}

private fun defaultPersistencePlatforms(): Map<String, PlatformPersistence> = mapOf(
    "weibo" to WeiboPersistence, "threads" to ThreadsPersistence, "reddit" to RedditPersistence,
    "zhihu" to ZhihuPersistence, "douban" to DoubanPersistence(),
    "tieba" to TiebaPersistence, "twitter" to TwitterPersistence
)
