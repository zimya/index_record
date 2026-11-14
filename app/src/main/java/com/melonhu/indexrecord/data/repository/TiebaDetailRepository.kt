package com.melonhu.indexrecord.data.repository

import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder

import kotlinx.coroutines.flow.Flow
import com.melonhu.indexrecord.data.model.RecordRelation
import com.melonhu.indexrecord.data.model.directRelation
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.UniversalRecord_
import com.melonhu.indexrecord.data.model.TiebaMetadata
import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.model.toDetailRecord
import com.melonhu.indexrecord.data.model.toEntity
import com.melonhu.indexrecord.data.model.immutableList
import com.melonhu.indexrecord.data.model.immutableMap

internal class TiebaDetailSnapshot(
    val current: DetailRecord,
    val thread: DetailRecord? = null,
    val floor: DetailRecord? = null,
    floors: List<DetailRecord> = emptyList(),
    repliesByParent: Map<String, List<DetailRecord>> = emptyMap(),
    replies: List<DetailRecord> = emptyList()
) {
    val floors: List<DetailRecord> = immutableList(floors)
    val repliesByParent: Map<String, List<DetailRecord>> =
        immutableMap(repliesByParent.mapValues { immutableList(it.value) })
    val replies: List<DetailRecord> = immutableList(replies)
}

internal class TiebaDetailRepository(private val store: BoxStore = ObjectBox.store) {
    fun observe(record: DetailRecord): Flow<Result<TiebaDetailSnapshot>> =
        observeRecordSnapshots(store) { query(record.toEntity()) }

    private fun query(record: UniversalRecord): TiebaDetailSnapshot {
        val box = store.boxFor(UniversalRecord::class.java)
        return assembleTiebaDetailSnapshot(
            record = record,
            find = { id ->
                if (id.isBlank()) null else {
                    val query = box.query()
                        .equal(UniversalRecord_.platform, "tieba", StringOrder.CASE_SENSITIVE)
                        .equal(UniversalRecord_.platformId, id, StringOrder.CASE_SENSITIVE)
                        .build()
                    try { query.findFirst() } finally { query.close() }
                }
            },
            children = { ids ->
                if (ids.isEmpty()) emptyList() else {
                    val query = box.query()
                        .equal(UniversalRecord_.platform, "tieba", StringOrder.CASE_SENSITIVE)
                        .`in`(UniversalRecord_.parentId, ids.distinct().toTypedArray(), StringOrder.CASE_SENSITIVE)
                        .build()
                    try { query.find() } finally { query.close() }
                }
            }
        )
    }
}

/** All lookups are evaluated inside the observation helper's single read transaction. */
internal fun assembleTiebaDetailSnapshot(
    record: UniversalRecord,
    find: (String) -> UniversalRecord?,
    children: (List<String>) -> List<UniversalRecord>
): TiebaDetailSnapshot {
    fun findCopy(id: String): UniversalRecord? = find(id)?.copy()
    fun childCopies(ids: List<String>): List<UniversalRecord> = children(ids).map { it.copy() }
    fun UniversalRecord.relationIds(): Set<String> = when (val relation = directRelation()) {
        is RecordRelation.FloorInThread, is RecordRelation.ReplyTo -> relation.target.storageIds()
        else -> emptySet()
    }

    val current = findCopy(record.platformId) ?: record.copy()
    val relation = current.directRelation()
    val targetIds = current.relationIds()
    val parent = if (relation is RecordRelation.ReplyTo) {
        targetIds.firstNotNullOfOrNull { findCopy(it) }
    } else null
    val parentThreadIds = (parent?.directRelation() as? RecordRelation.FloorInThread)?.target?.storageIds().orEmpty()
    val threadId = when {
        current.recordType == "lou" -> current.platformId
        relation is RecordRelation.FloorInThread -> targetIds.firstOrNull().orEmpty()
        targetIds.any { it.startsWith("tieba_thread_") } -> targetIds.first { it.startsWith("tieba_thread_") }
        parent?.recordType == "lou" -> parent.platformId
        parentThreadIds.isNotEmpty() -> parentThreadIds.first()
        else -> TiebaMetadata(current.extraMetadata).threadId.takeIf { it.isNotBlank() }
            ?.let { "tieba_thread_$it" }.orEmpty()
    }
    val thread = if (current.recordType == "lou") current else findCopy(threadId)
        ?.takeIf { it.recordType == "lou" }
    val floor = when (current.recordType) {
        "lou", "floor" -> current
        else -> parent?.takeIf { it.recordType == "floor" || it.recordType == "lou" }
    }
    val roots = if (current.recordType == "lou") childCopies(listOf(current.platformId)) else emptyList()
    val floors = roots.filter { it.recordType == "floor" }
        .sortedWith(compareBy<UniversalRecord> {
            TiebaMetadata(it.extraMetadata).floorNumber?.takeIf { n -> n > 0 } ?: Long.MAX_VALUE
        }.thenBy { it.platformId })
    val replyParents = buildList {
        addAll(floors.map { it.platformId })
        if (current.recordType != "lou") floor?.let { add(it.platformId) }
        if (relation is RecordRelation.ReplyTo) addAll(targetIds)
    }
    val replies = (roots.filter { it.recordType == "comment" } +
        childCopies(replyParents).filter { it.recordType == "comment" } +
        listOfNotNull(current.takeIf { it.recordType == "comment" }))
        .distinctBy { it.platformId }
        .sortedWith(compareBy<UniversalRecord> {
            TiebaMetadata(it.extraMetadata).subFloorNumber?.takeIf { n -> n > 0 } ?: Long.MAX_VALUE
        }.thenBy { it.publishTime.takeIf { time -> time > 0 } ?: Long.MAX_VALUE }
            .thenBy { it.platformId })
    val repliesByParent = replies.groupBy { reply -> reply.relationIds().firstOrNull().orEmpty() }
    val activeReplies = if (floor != null) repliesByParent[floor.platformId].orEmpty()
        else targetIds.flatMap { repliesByParent[it].orEmpty() }.distinctBy { it.platformId }
    return TiebaDetailSnapshot(
        current = current.toDetailRecord(),
        thread = thread?.toDetailRecord(),
        floor = floor?.toDetailRecord(),
        floors = floors.map { it.toDetailRecord() },
        repliesByParent = repliesByParent.mapValues { (_, values) -> values.map { it.toDetailRecord() } },
        replies = activeReplies.map { it.toDetailRecord() }
    )
}
