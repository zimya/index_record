package me.melon.record.data.repository

import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder

import kotlinx.coroutines.flow.Flow
import me.melon.record.ObjectBox
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.immutableList
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.model.RecordIdentity
import me.melon.record.data.model.RecordRelation
import me.melon.record.data.model.directRelation
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_

internal class DoubanSubjectSnapshot(
    val record: DetailRecord,
    relatedRecords: List<DetailRecord> = emptyList()
) {
    val relatedRecords: List<DetailRecord> = immutableList(relatedRecords)
}

internal class DoubanSubjectRepository(private val store: BoxStore = ObjectBox.store) {
    fun observe(record: UniversalRecord): Flow<Result<DoubanSubjectSnapshot>> {
        val fallback = record.copy()
        return observeRecordSnapshots(store) { query(fallback) }
    }

    private fun query(record: UniversalRecord): DoubanSubjectSnapshot {
        val box = store.boxFor(UniversalRecord::class.java)
        return assembleDoubanSubjectSnapshot(
            record = record,
            findSubject = { id ->
                val query = box.query()
                    .equal(UniversalRecord_.platform, "douban", StringOrder.CASE_SENSITIVE)
                    .equal(UniversalRecord_.platformId, id, StringOrder.CASE_SENSITIVE)
                    .equal(UniversalRecord_.recordType, "subject", StringOrder.CASE_SENSITIVE)
                    .build()
                try { query.findFirst() } finally { query.close() }
            },
            findRelated = { id ->
                val query = box.query()
                    .equal(UniversalRecord_.platform, "douban", StringOrder.CASE_SENSITIVE)
                    .equal(UniversalRecord_.parentId, id, StringOrder.CASE_SENSITIVE)
                    .orderDesc(UniversalRecord_.publishTime)
                    .build()
                try { query.find() } finally { query.close() }
            }
        )
    }
}

/** Both lookups run inside the shared observer's read transaction on every record change. */
internal fun assembleDoubanSubjectSnapshot(
    record: UniversalRecord,
    findSubject: (String) -> UniversalRecord?,
    findRelated: (String) -> List<UniversalRecord>
): DoubanSubjectSnapshot = DoubanSubjectSnapshot(
    record = (findSubject(record.platformId)?.takeIf {
        it.platform == "douban" && it.platformId == record.platformId && it.recordType == "subject"
    } ?: record).toDetailRecord(),
    relatedRecords = findRelated(record.platformId).filter {
        val relation = it.directRelation()
        relation is RecordRelation.BelongsToSubject &&
            relation.target == RecordIdentity.of("douban", record.platformId)
    }.map { it.toDetailRecord() }
)
