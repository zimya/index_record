package com.melonhu.indexrecord.data.repository

import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder
import kotlinx.coroutines.flow.Flow
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.model.RecordIdentity
import com.melonhu.indexrecord.data.model.RecordRelation
import com.melonhu.indexrecord.data.model.RelatedTargetState
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.UniversalRecord_
import com.melonhu.indexrecord.data.model.bilibiliTopic
import com.melonhu.indexrecord.data.model.directRelation
import com.melonhu.indexrecord.data.model.neteaseThread

import com.melonhu.indexrecord.data.model.identity
import com.melonhu.indexrecord.data.model.inCaptureRole
import com.melonhu.indexrecord.data.model.refreshCaptureRoles
import com.melonhu.indexrecord.data.model.immutableList
import com.melonhu.indexrecord.data.model.resolveRecordIdentity
import com.melonhu.indexrecord.data.model.toDetailRecord
import com.melonhu.indexrecord.presentation.doubanSubjectReference

internal data class RelatedContent(
    val relation: RecordRelation,
    val record: DetailRecord?,
    val isReference: Boolean = false
) {
    val targetState: RelatedTargetState
        get() = when {
            record == null -> RelatedTargetState.MISSING
            isReference -> RelatedTargetState.REFERENCE_ONLY
            else -> RelatedTargetState.RECORDED
        }
}

internal class RecordDetailSnapshot(
    val record: DetailRecord,
    comments: List<DetailRecord> = emptyList(),
    val related: RelatedContent? = null
) {
    val comments: List<DetailRecord> = immutableList(comments)
}

/** Strategies differ, but each publishes one complete, transaction-consistent snapshot. */
internal class RecordDetailRepository(private val store: BoxStore = ObjectBox.store) {
    fun observe(record: UniversalRecord): Flow<Result<RecordDetailSnapshot>> {
        val seed = record.copy()
        return observeRecordSnapshots(store) {
            val source = RecordDetailQueries(store.boxFor(UniversalRecord::class.java), seed.platform)
            assembleRecordDetailSnapshot(seed, source::find, source::comments)
        }
    }
}

internal fun assembleRecordDetailSnapshot(
    seed: UniversalRecord,
    find: (RecordIdentity) -> UniversalRecord?,
    comments: (UniversalRecord) -> List<UniversalRecord>
): RecordDetailSnapshot {
    val captured = find(seed.identity) ?: seed
    // Preserve the entry's capture role for routing while taking content/statistics from the newest capture.
    val current = if (seed.platform == "twitter") captured.inCaptureRole(seed)
        else captured.copy()
    val relation = current.directRelation()
    val related = relation?.let {
        val target = find(it.target)
        val reference = if (target == null && it is RecordRelation.BelongsToSubject) doubanSubjectReference(current) else null
        RelatedContent(it, (target ?: reference)?.toDetailRecord(), isReference = reference != null)
    }
    return RecordDetailSnapshot(current.toDetailRecord(), comments(current).map { it.toDetailRecord() }, related)
}

private class RecordDetailQueries(private val box: Box<UniversalRecord>, private val platform: String) {
    fun find(identity: RecordIdentity): UniversalRecord? {
        val query = box.query().equal(UniversalRecord_.platform, platform, StringOrder.CASE_SENSITIVE)
            .`in`(UniversalRecord_.platformId, identity.storageIds().toTypedArray(), StringOrder.CASE_SENSITIVE).build()
        return try { resolveRecordIdentity(query.find()) } finally { query.close() }
    }

    fun comments(record: UniversalRecord): List<UniversalRecord> {
        if (platform == "bilibili") {
            val topic = record.bilibiliTopic() ?: return emptyList()
            val query = box.query().equal(UniversalRecord_.platform, platform, StringOrder.CASE_SENSITIVE)
                .equal(UniversalRecord_.recordType, "comment", StringOrder.CASE_SENSITIVE)
                .startsWith(UniversalRecord_.platformId, topic.commentPrefix, StringOrder.CASE_SENSITIVE).build()
            return try { selectBilibiliComments(record, topic, query.find()) } finally { query.close() }
        }
        if (platform == "netease") {
            val thread = record.neteaseThread() ?: return emptyList()
            val query = box.query().equal(UniversalRecord_.platform, platform, StringOrder.CASE_SENSITIVE)
                .equal(UniversalRecord_.recordType, "comment", StringOrder.CASE_SENSITIVE)
                .startsWith(UniversalRecord_.platformId, thread.commentPrefix, StringOrder.CASE_SENSITIVE).build()
            return try { selectNeteaseComments(record, thread, query.find()) } finally { query.close() }
        }
        if (platform == "reddit") {
            // Disconnected Reddit branches may identify their post only through post_id.
            val query = box.query().equal(UniversalRecord_.platform, platform, StringOrder.CASE_SENSITIVE)
                .equal(UniversalRecord_.recordType, "comment", StringOrder.CASE_SENSITIVE).build()
            return try { selectRedditComments(record, query.find()) } finally { query.close() }
        }
        return collectRecordComments(record) { parents ->
            val ids = parents.flatMap { it.storageIds() }.distinct().toTypedArray()
            val query = box.query().equal(UniversalRecord_.platform, platform, StringOrder.CASE_SENSITIVE)
                .equal(UniversalRecord_.recordType, "comment", StringOrder.CASE_SENSITIVE)
                .`in`(UniversalRecord_.parentId, ids, StringOrder.CASE_SENSITIVE).build()
            val members = try { query.find() } finally { query.close() }
            if (platform != "twitter" || members.isEmpty()) members else {
                // Membership and reply edges come from comment captures; values may be newer in tweet captures.
                val roleIds = members.flatMap { it.identity.storageIds() }.distinct().toTypedArray()
                val roles = box.query().equal(UniversalRecord_.platform, platform, StringOrder.CASE_SENSITIVE)
                    .`in`(UniversalRecord_.platformId, roleIds, StringOrder.CASE_SENSITIVE).build()
                try { refreshCaptureRoles(members, roles.find()) } finally { roles.close() }
            }
        }
    }
}
