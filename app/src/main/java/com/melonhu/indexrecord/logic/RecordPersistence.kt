package com.melonhu.indexrecord.logic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.objectbox.Box
import io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.RecordIdentity
import com.melonhu.indexrecord.data.model.RecordSnapshot
import com.melonhu.indexrecord.data.model.RecordSnapshot_
import com.melonhu.indexrecord.data.model.PendingRecordSnapshot
import com.melonhu.indexrecord.data.model.PendingRecordSnapshot_
import com.melonhu.indexrecord.data.model.encodeSnapshotRecord
import com.melonhu.indexrecord.data.model.toSnapshot
import com.melonhu.indexrecord.data.model.identity
import com.melonhu.indexrecord.data.model.legacySnapshotKey
import com.melonhu.indexrecord.data.model.recordSnapshot
import java.util.UUID
import com.melonhu.indexrecord.data.model.UniversalRecord_
import com.melonhu.indexrecord.parser.reddit.RedditRelations
import com.melonhu.indexrecord.parser.reddit.RedditReadVisit
import com.melonhu.indexrecord.parser.threads.ThreadsRelations
import com.melonhu.indexrecord.parser.bluesky.BlueskyRelations
import com.melonhu.indexrecord.parser.tieba.TiebaRelations
import com.melonhu.indexrecord.parser.zhihu.ZhihuQuestionParser
import com.melonhu.indexrecord.parser.zhihu.ZhihuRelations

/** Merge/link reads observe preceding writes; only parse-field completion uses the pre-write chunk view. */
internal interface RecordRepository {
    fun find(platformId: String): UniversalRecord?
    fun children(parentId: String): List<UniversalRecord>
    fun firstChild(parentId: String): UniversalRecord? = children(parentId).firstOrNull()
    fun unboundZhihuAnswers(): List<UniversalRecord>
    fun findIds(platformIds: List<String>): List<UniversalRecord>
    fun put(record: UniversalRecord)
    val history: RecordSnapshotStorage? get() = null
}

internal interface RecordSnapshotStorage {
    fun contains(key: String): Boolean
    fun findByKey(key: String): RecordSnapshot? = null
    fun hasIdentity(identity: RecordIdentity): Boolean
    fun append(snapshot: RecordSnapshot)
    fun findRedditVisit(key: String): RecordSnapshot? = null
    fun hasRedditPostSource(identity: RecordIdentity, sourceKey: String): Boolean = false
    fun redditVisitsNear(identity: RecordIdentity, time: Long): List<RecordSnapshot> = emptyList()
    fun findPending(key: String): PendingRecordSnapshot? = null
    fun putPending(snapshot: PendingRecordSnapshot) {}
    fun pending(): List<PendingRecordSnapshot> = emptyList()
    fun pendingFor(identity: RecordIdentity): List<PendingRecordSnapshot> =
        if (identity.platform == "reddit") pending().filter { it.platformId in identity.storageIds() } else emptyList()
    fun removePending(snapshot: PendingRecordSnapshot) {}
}

private class ObjectBoxSnapshotStorage(private val box: Box<RecordSnapshot>) : RecordSnapshotStorage {
    private val pendingBox = box.store.boxFor(PendingRecordSnapshot::class.java)
    override fun findPending(key: String): PendingRecordSnapshot? {
        val query = pendingBox.query().equal(PendingRecordSnapshot_.key, key, CASE_SENSITIVE).build()
        return try { query.findFirst() } finally { query.close() }
    }
    override fun putPending(snapshot: PendingRecordSnapshot) { pendingBox.put(snapshot) }
    override fun pending(): List<PendingRecordSnapshot> = pendingBox.all
    override fun pendingFor(identity: RecordIdentity): List<PendingRecordSnapshot> {
        if (identity.platform != "reddit") return emptyList()
        val query = pendingBox.query().`in`(PendingRecordSnapshot_.platformId,
            identity.storageIds().toTypedArray(), CASE_SENSITIVE).build()
        return try { query.find() } finally { query.close() }
    }
    override fun removePending(snapshot: PendingRecordSnapshot) { pendingBox.remove(snapshot.id) }
    override fun contains(key: String): Boolean {
        val query = box.query().equal(RecordSnapshot_.key, key, CASE_SENSITIVE).build()
        return try { query.count() > 0 } finally { query.close() }
    }
    override fun findByKey(key: String): RecordSnapshot? {
        val query = box.query().equal(RecordSnapshot_.key, key, CASE_SENSITIVE).build()
        return try { query.findFirst() } finally { query.close() }
    }
    override fun hasIdentity(identity: RecordIdentity): Boolean {
        val query = box.query().equal(RecordSnapshot_.platform, identity.platform, CASE_SENSITIVE)
            .equal(RecordSnapshot_.identityId, identity.contentId, CASE_SENSITIVE).build()
        return try { query.count() > 0 } finally { query.close() }
    }
    override fun findRedditVisit(key: String): RecordSnapshot? {
        val query = box.query().startsWith(RecordSnapshot_.key, "$key:source:", CASE_SENSITIVE).build()
        return try { query.findFirst() } finally { query.close() }
    }
    override fun hasRedditPostSource(identity: RecordIdentity, sourceKey: String): Boolean {
        val query = box.query().equal(RecordSnapshot_.platform, "reddit", CASE_SENSITIVE)
            .equal(RecordSnapshot_.identityId, identity.contentId, CASE_SENSITIVE)
            .endsWith(RecordSnapshot_.key, ":source:$sourceKey", CASE_SENSITIVE).build()
        return try { query.count() > 0 } finally { query.close() }
    }
    override fun redditVisitsNear(identity: RecordIdentity, time: Long): List<RecordSnapshot> {
        val query = box.query().equal(RecordSnapshot_.platform, "reddit", CASE_SENSITIVE)
            .equal(RecordSnapshot_.identityId, identity.contentId, CASE_SENSITIVE)
            .startsWith(RecordSnapshot_.key, REDDIT_VISIT_KEY_PREFIX, CASE_SENSITIVE)
            .between(RecordSnapshot_.captureTime, time - REDDIT_LEGACY_VISIT_WINDOW, time + REDDIT_LEGACY_VISIT_WINDOW).build()
        return try { query.find() } finally { query.close() }
    }
    override fun append(snapshot: RecordSnapshot) { box.put(snapshot) }
}

internal const val PENDING_HISTORY_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
internal const val MAX_PENDING_HISTORY = 5000

/** Retrying a partial raw must not refresh the pending capture's original retention deadline. */
internal fun prunePendingHistory(history: RecordSnapshotStorage, now: Long) {
    val (expired, retained) = history.pending().partition { it.createdAt < now - PENDING_HISTORY_TTL_MILLIS }
    expired.forEach(history::removePending)
    retained.sortedWith(compareBy<PendingRecordSnapshot> { it.createdAt }.thenBy { it.key })
        .take((retained.size - MAX_PENDING_HISTORY).coerceAtLeast(0))
        .forEach(history::removePending)
}

/** Back up every old X storage role before the first event makes identity-level fallback disappear. */
internal fun archiveLegacyIndex(record: UniversalRecord, repository: RecordRepository, history: RecordSnapshotStorage) =
    archiveLegacyIndexForIdentity(record.identity, repository, history)

internal fun archiveLegacyIndexForIdentity(
    identity: RecordIdentity,
    repository: RecordRepository,
    history: RecordSnapshotStorage
) {
    if (history.hasIdentity(identity)) return
    for (old in repository.findIds(identity.storageIds().toList()).filter { it.identity == identity }) {
        if (ZhihuQuestionParser.isAuxiliaryQuestion(old)) continue
        val key = legacySnapshotKey(old)
        if (!history.contains(key)) history.append(recordSnapshot(key, old, legacy = true))
    }
}

internal class ObjectBoxRecordRepository(private val box: Box<UniversalRecord>) : RecordRepository {
    override val history: RecordSnapshotStorage = ObjectBoxSnapshotStorage(box.store.boxFor(RecordSnapshot::class.java))
    private val persistedBatch = LinkedHashMap<Long, UniversalRecord>()

    fun drainPersisted(): List<UniversalRecord> {
        if (persistedBatch.isEmpty()) return emptyList()
        val copy = persistedBatch.values.toList()
        persistedBatch.clear()
        return copy
    }

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
    override fun put(record: UniversalRecord) {
        // afterPut can update an uncaptured legacy row too; freeze its old value before that write.
        archiveLegacyIndex(record, this, history)
        box.put(record)
        if (record.id > 0L) {
            persistedBatch[record.id] = record.copy()
        }
    }
}

/** Hooks deliberately straddle generic merging: body policy first, metadata policy last, links after put. */
internal interface PlatformPersistence {
    val preserveGenericContent: Boolean get() = true
    fun shouldSnapshot(record: UniversalRecord, captured: UniversalRecord): Boolean = true
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

internal enum class ImportRecordOutcome { INSERTED, MERGED, UNCHANGED }

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
        val captured = record.copy()
        (platforms[record.platform] ?: DefaultPersistence).complete(record, repository)
        persistCompleted(record, captured)
    }

    fun captureRedditPostVisits(rawId: Long, records: List<UniversalRecord>, visits: List<RedditReadVisit>, now: Long) {
        repository.history?.let { history ->
            RedditPostHistory(history) { archiveLegacyIndex(it, repository, history) }.accept(rawId, records, visits, now)
        }
    }

    /** Comments retain per-capture history; main posts use one snapshot per detail visit. */
    fun stageRedditHistory(
        rawId: Long, records: List<UniversalRecord>, now: Long = System.currentTimeMillis()
    ): Set<RecordIdentity> {
        val history = repository.history ?: return emptySet()
        val replayed = mutableSetOf<RecordIdentity>()
        val occurrences = mutableMapOf<Pair<String, String>, Int>()
        for (record in records) {
            if (record.platform != "reddit" || record.platformId.isBlank() || record.recordType == "post") continue
            val role = record.platform to record.platformId
            val occurrence = occurrences.getOrDefault(role, 0)
            occurrences[role] = occurrence + 1
            val key = rawSnapshotKey(rawId, record, occurrence)
            if (history.contains(key)) {
                replayed.add(record.identity)
                continue
            }
            if (history.findPending(key) != null) continue
            history.putPending(PendingRecordSnapshot(
                key = key, platformId = record.platformId, captureTime = record.captureTime,
                recordJson = encodeSnapshotRecord(record), createdAt = now
            ))
        }
        prunePendingHistory(history, now)
        return replayed
    }

    /** Release all admitted identities' original captures; the synthetic value updates only the index. */
    fun persistRedditAdmitted(record: UniversalRecord, knownCapture: Boolean = false) {
        if (record.recordType == "post") {
            // Auxiliary shells still update an existing index, but cannot create an empty
            // first record which would later be archived as a spurious legacy snapshot.
            if (hasRedditPostBody(record) || repository.find(record.platformId) != null) updateIndex(record)
            return
        }
        repository.history?.let { history ->
            val pending = history.pendingFor(record.identity)
            archiveLegacyIndex(record, repository, history)
            for (snapshot in pending) {
                if (!history.contains(snapshot.key)) history.append(snapshot.toSnapshot())
                history.removePending(snapshot)
            }
            if (pending.isEmpty() && !knownCapture) {
                // Pre-upgrade RedditPendingRecord contains only a synthetic value, not a real capture.
                val key = "legacy:reddit-buffer:${record.platformId}"
                if (!history.contains(key)) history.append(recordSnapshot(key, record, legacy = true))
            }
        }
        updateIndex(record)
    }

    /** Must run in the caller's raw-delete transaction. Completion/merge/afterPut never own the payload. */
    fun persistCompleted(
        record: UniversalRecord,
        captured: UniversalRecord = record.copy(),
        captureKey: String = "capture:${UUID.randomUUID()}"
    ) {
        val platform = platforms[record.platform] ?: DefaultPersistence
        if (platform.shouldSnapshot(record, captured)) {
            repository.history?.let { history ->
                if (!history.contains(captureKey)) {
                    archiveLegacyIndex(captured, repository, history)
                    history.append(recordSnapshot(captureKey, captured))
                }
            }
        }
        updateIndex(record)
    }

    fun mergeImportedRecord(incoming: UniversalRecord): ImportRecordOutcome {
        val platform = platforms[incoming.platform] ?: DefaultPersistence
        val existing = repository.find(incoming.platformId)
        if (existing == null) {
            val candidate = incoming.copy(id = 0L)
            platform.complete(candidate, repository)
            repository.put(candidate)
            platform.afterPut(candidate, repository)
            return ImportRecordOutcome.INSERTED
        }
        val incomingIsNewer = incoming.captureTime > existing.captureTime ||
            (incoming.captureTime == existing.captureTime && incoming.publishTime > existing.publishTime)
        val older = if (incomingIsNewer) existing else incoming
        val merged = (if (incomingIsNewer) incoming else existing).copy(id = existing.id)
        mergeFields(platform, older, merged)
        if (merged == existing) return ImportRecordOutcome.UNCHANGED
        repository.put(merged)
        platform.afterPut(merged, repository)
        return ImportRecordOutcome.MERGED
    }

    private fun updateIndex(record: UniversalRecord) {
        val platform = platforms[record.platform] ?: DefaultPersistence
        val existing = repository.find(record.platformId)
        if (existing != null) {
            record.id = existing.id
            mergeFields(platform, existing, record)
        }
        repository.put(record)
        platform.afterPut(record, repository)
    }

    private fun mergeFields(platform: PlatformPersistence, older: UniversalRecord, target: UniversalRecord) {
        platform.mergeBody(older, target)
        if (target.parentId.isBlank()) target.parentId = older.parentId
        if (target.title.isBlank()) target.title = older.title
        if (platform.preserveGenericContent && (target.content.isBlank() || (target.recordType == "subject" && target.content == target.title))) {
            if (older.content.isNotBlank() && !(older.recordType == "subject" && older.content == older.title)) {
                target.content = older.content
                target.contentFormat = older.contentFormat
            }
        }
        if (target.authorId.isBlank()) target.authorId = older.authorId
        if (target.authorName.isBlank()) target.authorName = older.authorName
        if (target.authorAvatar.isBlank()) target.authorAvatar = older.authorAvatar
        if (target.sourceUrl.isBlank()) target.sourceUrl = older.sourceUrl
        if (target.sourceApp.isNullOrBlank() && !older.sourceApp.isNullOrBlank()) target.sourceApp = older.sourceApp
        target.publishTime = maxOf(older.publishTime, target.publishTime)
        target.captureTime = maxOf(older.captureTime, target.captureTime)
        platform.mergeExtra(older, target)
    }
}

private object DefaultPersistence : PlatformPersistence
private object WeiboPersistence : PlatformPersistence {
    override fun mergeBody(existing: UniversalRecord, incoming: UniversalRecord) = mergeWeiboRecord(existing, incoming)
}
private object ThreadsPersistence : PlatformPersistence {
    override fun mergeBody(existing: UniversalRecord, incoming: UniversalRecord) = ThreadsRelations.mergeRecord(existing, incoming)
}
private object BlueskyPersistence : PlatformPersistence {
    override fun mergeBody(existing: UniversalRecord, incoming: UniversalRecord) = BlueskyRelations.mergeRecord(existing, incoming)
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
    override fun shouldSnapshot(record: UniversalRecord, captured: UniversalRecord): Boolean {
        if (ZhihuQuestionParser.isAuxiliaryQuestion(record) || ZhihuQuestionParser.isAuxiliaryQuestion(captured)) {
            return false
        }
        return true
    }
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
    "weibo" to WeiboPersistence, "threads" to ThreadsPersistence, "bluesky" to BlueskyPersistence, "reddit" to RedditPersistence,
    "zhihu" to ZhihuPersistence, "douban" to DoubanPersistence(),
    "tieba" to TiebaPersistence, "twitter" to TwitterPersistence
)
