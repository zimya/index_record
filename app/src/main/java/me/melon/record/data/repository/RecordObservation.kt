package me.melon.record.data.repository

import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import java.util.concurrent.Callable

/** One invalidation stream and one read transaction per snapshot, never per page section. */
internal fun <T> observeRecordSnapshots(
    store: BoxStore = ObjectBox.store,
    snapshot: () -> T
): Flow<Result<T>> = callbackFlow {
    val subscription = store.subscribe(UniversalRecord::class.java).observer { trySend(Unit) }
    trySend(Unit)
    awaitClose { subscription.cancel() }
}.conflate().map {
    try {
            Result.success(store.callInReadTx(Callable { snapshot() }))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A failed read is one snapshot result, not the end of the live subscription.
            Result.failure(error)
        }
}.flowOn(Dispatchers.IO)
