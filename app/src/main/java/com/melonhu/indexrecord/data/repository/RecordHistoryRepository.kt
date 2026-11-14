package com.melonhu.indexrecord.data.repository

import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.model.RecordIdentity
import com.melonhu.indexrecord.data.model.RecordSnapshot
import com.melonhu.indexrecord.data.model.RecordSnapshot_
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.UniversalRecord_
import com.melonhu.indexrecord.data.model.identity
import com.melonhu.indexrecord.data.model.immutableList
import com.melonhu.indexrecord.data.model.legacySnapshotKey
import com.melonhu.indexrecord.data.model.toDetailRecord
import com.melonhu.indexrecord.data.model.toEntity
import com.melonhu.indexrecord.data.model.toRecord
import com.melonhu.indexrecord.parser.zhihu.ZhihuQuestionParser
import java.util.concurrent.Callable

internal data class HistoricalRecord(val key: String, val record: DetailRecord, val legacy: Boolean)

internal class RecordHistoryRepository(private val store: BoxStore = ObjectBox.store) {
    fun observe(record: UniversalRecord): Flow<List<HistoricalRecord>> =
        observeResult(record).mapNotNull { it.getOrNull() }

    fun observeResult(record: UniversalRecord): Flow<Result<List<HistoricalRecord>>> {
        val identity = record.identity
        return observeHistory {
            assembleRecordHistory(snapshots(identity), indexes(identity), identity)
        }
    }

    /** All events, not distinct content: legacy fallback is replaced by its frozen backup, never dropped. */
    fun observeAll(): Flow<List<UniversalRecord>> = observeAllResult().mapNotNull { it.getOrNull() }

    fun observeAllResult(): Flow<Result<List<UniversalRecord>>> = observeHistory {
        assembleAllHistoricalRecords(snapshots(), indexes())
    }

    private fun snapshots() = store.boxFor(RecordSnapshot::class.java).all
    private fun indexes() = store.boxFor(UniversalRecord::class.java).all

    private fun snapshots(identity: RecordIdentity): List<RecordSnapshot> {
        val query = store.boxFor(RecordSnapshot::class.java).query()
            .equal(RecordSnapshot_.platform, identity.platform, CASE_SENSITIVE)
            .equal(RecordSnapshot_.identityId, identity.contentId, CASE_SENSITIVE).build()
        return try { query.find() } finally { query.close() }
    }

    private fun indexes(identity: RecordIdentity): List<UniversalRecord> {
        val query = store.boxFor(UniversalRecord::class.java).query()
            .equal(UniversalRecord_.platform, identity.platform, CASE_SENSITIVE)
            .`in`(UniversalRecord_.platformId, identity.storageIds().toTypedArray(), CASE_SENSITIVE).build()
        return try { query.find() } finally { query.close() }
    }

    private fun <T : Any> observeHistory(read: () -> T): Flow<Result<T>> = callbackFlow {
        val captures = store.subscribe(RecordSnapshot::class.java).observer { trySend(Unit) }
        val current = store.subscribe(UniversalRecord::class.java).observer { trySend(Unit) }
        trySend(Unit)
        awaitClose { captures.cancel(); current.cancel() }
    }.conflate().readHistorySnapshots {
        store.callInReadTx(Callable { read() })
    }.flowOn(Dispatchers.IO)
}

/** Publish read errors without ending the subscription; the next invalidation can recover. */
internal fun <T : Any> Flow<Unit>.readHistorySnapshots(read: () -> T): Flow<Result<T>> = map {
    try {
        Result.success(read())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

internal fun assembleRecordHistory(
    snapshots: List<RecordSnapshot>, indexes: List<UniversalRecord>, identity: RecordIdentity
): List<HistoricalRecord> = immutableList(historyEntries(
    snapshots.filter { it.platform == identity.platform && it.identityId == identity.contentId },
    indexes.filter { it.identity == identity }
).sortedWith(compareByDescending<HistoricalRecord> { it.record.captureTime }.thenBy { it.key }))

internal fun assembleAllHistoricalRecords(
    snapshots: List<RecordSnapshot>, indexes: List<UniversalRecord>
): List<UniversalRecord> = immutableList(historyEntries(snapshots, indexes)
    .sortedWith(compareByDescending<HistoricalRecord> { it.record.captureTime }.thenBy { it.key })
    .map { it.record.toEntity() })

private fun historyEntries(snapshots: List<RecordSnapshot>, indexes: List<UniversalRecord>): List<HistoricalRecord> {
    val validSnapshots = snapshots.filterNot {
        val record = it.toRecord()
        ZhihuQuestionParser.isAuxiliaryQuestion(record)
    }
    val covered = validSnapshots.map { RecordIdentity(it.platform, it.identityId) }.toSet()
    return validSnapshots.map { HistoricalRecord(it.key, it.toRecord().toDetailRecord(), it.legacy) } +
        indexes.filter { it.identity !in covered && !ZhihuQuestionParser.isAuxiliaryQuestion(it) }
            .map { HistoricalRecord(legacySnapshotKey(it), it.toDetailRecord(), true) }
}
