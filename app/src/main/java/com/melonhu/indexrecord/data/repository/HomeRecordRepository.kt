package com.melonhu.indexrecord.data.repository

import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.melonhu.indexrecord.ObjectBox
import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.UniversalRecord_
import com.melonhu.indexrecord.presentation.HomeQueryFilter
import com.melonhu.indexrecord.presentation.HomeQueryPolicy
import com.melonhu.indexrecord.presentation.RecordContentPolicy

class HomeRecordRepository(private val store: BoxStore = ObjectBox.store) {
    private val recordBox = store.boxFor(UniversalRecord::class.java)
    private val rawBox = store.boxFor(RawData::class.java)

    private fun observeRecords(filter: HomeQueryFilter): Flow<List<UniversalRecord>> = callbackFlow {
        val builder = recordBox.query()
        filter.startMillis?.let { builder.greaterOrEqual(UniversalRecord_.captureTime, it) }
        filter.endMillis?.let { builder.lessOrEqual(UniversalRecord_.captureTime, it) }
        val query = builder.orderDesc(UniversalRecord_.captureTime).build()
        val subscription = query.subscribe().observer { trySend(it) }
        trySend(query.find())
        awaitClose {
            subscription.cancel()
            query.close()
        }
    }.flowOn(Dispatchers.IO)

    fun records(filter: HomeQueryFilter): Flow<List<UniversalRecord>> =
        observeRecords(filter).map { HomeQueryPolicy.apply(it, filter) }.flowOn(Dispatchers.IO)

    // Filter options project only indexed identity fields, not large content/metadata strings.
    private fun <T> observeOptions(snapshot: () -> T): Flow<T> = observeRecordSnapshots(store, snapshot)
        .map { it.getOrThrow() }

    val availablePlatforms: Flow<List<String>> = observeOptions {
        val query = recordBox.query().build()
        try {
            query.property(UniversalRecord_.platform).distinct().findStrings().orEmpty()
                .filter { !it.isNullOrBlank() }
                .distinct()
                .sorted()
        } finally {
            query.close()
        }
    }

    val availableTypes: Flow<List<String>> = observeOptions {
        val query = recordBox.query().build()
        val platforms = try {
            query.property(UniversalRecord_.platform).distinct().findStrings().orEmpty()
        } finally {
            query.close()
        }
        platforms.flatMap { platform ->
            val typesQuery = recordBox.query(UniversalRecord_.platform.equal(platform)).build()
            try {
                typesQuery.property(UniversalRecord_.recordType).distinct().findStrings().orEmpty()
                    .filter { !it.isNullOrBlank() }
                    .map { RecordContentPolicy.filterTypeKey(platform, it) }
            } finally {
                typesQuery.close()
            }
        }.distinct().sortedBy(RecordContentPolicy::filterTypeLabel)
    }

    val rawPendingCount: Flow<Long> = callbackFlow {
        val subscription = store.subscribe(RawData::class.java).observer { trySend(rawBox.count()) }
        trySend(rawBox.count())
        awaitClose { subscription.cancel() }
    }.flowOn(Dispatchers.IO)
}
