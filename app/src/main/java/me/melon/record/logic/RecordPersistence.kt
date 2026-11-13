package me.melon.record.logic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.parser.douban.DoubanRelations
import me.melon.record.parser.douban.DoubanInterestsResult
import me.melon.record.parser.douban.DoubanTitleLookup
import me.melon.record.parser.douban.DoubanTitleParseException
import me.melon.record.parser.reddit.RedditRelations
import me.melon.record.parser.threads.ThreadsRelations
import me.melon.record.parser.tieba.TiebaRelations
import me.melon.record.parser.x.XRelations
import me.melon.record.parser.zhihu.ZhihuRelations

/** Merge/link reads observe preceding writes; only parse-field completion uses the pre-write chunk view. */
internal interface RecordRepository {
    fun find(platformId: String): UniversalRecord?
    fun children(parentId: String): List<UniversalRecord>
    fun firstChild(parentId: String): UniversalRecord? = children(parentId).firstOrNull()
    fun unboundZhihuAnswers(): List<UniversalRecord>
    fun findIds(platformIds: List<String>): List<UniversalRecord>
    fun put(record: UniversalRecord)
    fun linkTweet(record: UniversalRecord)
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
    override fun linkTweet(record: UniversalRecord) { XRelations.linkTweetRelations(box, record) }
}

/** Hooks deliberately straddle generic merging: body policy first, metadata policy last, links after put. */
internal interface PlatformPersistence {
    val preserveGenericContent: Boolean get() = true
    fun complete(record: UniversalRecord, repository: RecordRepository) {}
    fun completeChunk(records: List<UniversalRecord>, repository: RecordRepository) {
        records.forEach { complete(it, repository) }
    }
    fun completeFailure(error: Exception, repository: RecordRepository) {}
    fun mergeBody(existing: UniversalRecord, incoming: UniversalRecord) {}
    fun mergeExtra(existing: UniversalRecord, incoming: UniversalRecord) {}
    fun afterPut(record: UniversalRecord, repository: RecordRepository) {}
}

/** Explicit process-lifetime completion state, used by the serialized processor, never by parsers. */
internal class RecordPersistenceContext {
    private val doubanTitles = mutableMapOf<String, String>()

    internal fun doubanTitle(subjectId: String): String =
        doubanTitles[subjectId] ?: DoubanRelations.sampleSubjectTitle(subjectId)

    internal fun rememberDoubanTitle(subjectId: String, title: String) {
        if (subjectId.isNotBlank() && title.isNotBlank()) doubanTitles[subjectId] = title
    }
}

internal class RecordPersistence(
    private val repository: RecordRepository,
    private val context: RecordPersistenceContext = RecordPersistenceContext(),
    private val platforms: Map<String, PlatformPersistence> = defaultPersistencePlatforms(context)
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

    fun completeDoubanInterests(result: DoubanInterestsResult) {
        (platforms["douban"] as? DoubanPersistence)?.completeInterests(result, repository)
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
        incoming.content = TiebaRelations.mergeRecordContent(existing, incoming)
        incoming.extraMetadata = TiebaRelations.mergeExtra(existing.extraMetadata, incoming.extraMetadata)
    }
}
private object TwitterPersistence : PlatformPersistence {
    override fun mergeExtra(existing: UniversalRecord, incoming: UniversalRecord) {
        incoming.extraMetadata = XRelations.mergeTweetExtra(existing.extraMetadata, incoming.extraMetadata)
    }
    override fun afterPut(record: UniversalRecord, repository: RecordRepository) {
        try { repository.linkTweet(record) } catch (_: Exception) {}
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

private class DoubanPersistence(private val context: RecordPersistenceContext) : PlatformPersistence {
    // The policy is per-run, but its injected context MUST survive runs like the former cache:
    // a review can teach later runs a title even when the first stored child predates that title.
    // Keys intentionally remain subjectId-only (not type+ID), and context updates intentionally
    // survive transaction rollback. Both are legacy behaviors, not new persistence guarantees.

    override fun completeChunk(records: List<UniversalRecord>, repository: RecordRepository) {
        // Former subject parsing registered a title immediately, before any chunk writes.
        // Apply capture-order title evidence to the injected persistence context before writes;
        // no parser object owns or consults implicit mutable title state.
        for (record in records) {
            if (record.recordType == "subject") {
                runCatching {
                    val id = metadata(record).text("subject_id")
                    context.rememberDoubanTitle(id, record.title)
                }
            }
            complete(record, repository)
        }
    }

    override fun completeFailure(error: Exception, repository: RecordRepository) {
        if (error !is DoubanTitleParseException) return
        if (error.subjectTitle != null) {
            context.rememberDoubanTitle(error.subjectId, error.subjectTitle)
        } else {
            completeTitleLookup(DoubanTitleLookup(error.subjectId, error.subjectType), repository)
        }
    }

    private fun completeTitleLookup(lookup: DoubanTitleLookup, repository: RecordRepository): String {
        val cached = context.doubanTitle(lookup.subjectId)
        if (cached.isNotBlank()) return cached
        return try {
            val parentId = "douban_${lookup.subjectType}_${lookup.subjectId}"
            // 从该条目下的长评或讨论帖元数据中查找 subject_title
            // Keep the original lookup order (subject, first child), not a search over all children.
            val title = repository.find(parentId)?.title.orEmpty().ifBlank {
                repository.firstChild(parentId)?.let { metadata(it).text("subject_title") }.orEmpty()
            }
            // Database hits used to warm the cache during parsing, before subsequent items
            // in this same chunk. Preserve that precedence over later database lookups.
            context.rememberDoubanTitle(lookup.subjectId, title)
            title
        } catch (_: Exception) { "" }
    }

    fun completeInterests(result: DoubanInterestsResult, repository: RecordRepository) {
        val lookup = result.titleLookup ?: return
        // One lookup per capture, including empty lists. Never repeat it for every item or write.
        val title = completeTitleLookup(lookup, repository)
        for (record in result.records) applyTitle(record, title)
    }

    private fun applyTitle(record: UniversalRecord, title: String) {
        if (title.isBlank()) return
        val meta = metadata(record)
        record.title = "${interestLabel(meta.text("subject_type"))}：$title"
        meta.addProperty("subject_title", title)
        record.extraMetadata = meta.toString()
    }

    override fun complete(record: UniversalRecord, repository: RecordRepository) {
        if (record.recordType != "interest") return
        try {
            val meta = metadata(record)
            val id = meta.text("subject_id")
            val title = completeTitleLookup(DoubanTitleLookup(id, meta.text("subject_type")), repository)
                .ifBlank { meta.text("subject_title") }
            context.rememberDoubanTitle(id, title)
            applyTitle(record, title)
        } catch (_: Exception) {}
    }
    override fun mergeExtra(existing: UniversalRecord, incoming: UniversalRecord) {
        if (incoming.recordType == "subject") {
            incoming.extraMetadata = DoubanRelations.mergeSubjectExtra(existing.extraMetadata, incoming.extraMetadata)
        }
    }
    override fun afterPut(record: UniversalRecord, repository: RecordRepository) {
        try {
            val meta = metadata(record)
            val id = meta.text("subject_id")
            val title = meta.text("subject_title")
            val type = meta.text("subject_type").ifBlank { "movie" }
            if (id.isBlank() || title.isBlank()) return
            context.rememberDoubanTitle(id, title)
            val expectedTitle = "${interestLabel(type)}：$title"
            // Backfill only titles, after the source record is written. Do not synthesize metadata
            // on earlier interests: their original capture/merge order must remain observable.
            for (interest in repository.children("douban_${type}_${id}")) {
                if (interest.platform == "douban" && interest.recordType == "interest" && interest.title != expectedTitle) {
                    interest.title = expectedTitle
                    repository.put(interest)
                }
            }
        } catch (_: Exception) {}
    }
}

private fun interestLabel(type: String): String = when (type) {
    "movie" -> "电影短评"
    "book" -> "图书短评"
    "music" -> "音乐短评"
    "game" -> "游戏短评"
    "tv" -> "剧集短评"
    "drama" -> "舞台剧短评"
    else -> "豆瓣短评"
}

private fun defaultPersistencePlatforms(context: RecordPersistenceContext): Map<String, PlatformPersistence> = mapOf(
    "weibo" to WeiboPersistence, "threads" to ThreadsPersistence, "reddit" to RedditPersistence,
    "zhihu" to ZhihuPersistence, "douban" to DoubanPersistence(context),
    "tieba" to TiebaPersistence, "twitter" to TwitterPersistence
)
