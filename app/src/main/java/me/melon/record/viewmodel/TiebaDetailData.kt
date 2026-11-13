package me.melon.record.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.immutableList
import me.melon.record.data.model.immutableMap
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.repository.TiebaDetailRepository
import me.melon.record.data.repository.TiebaDetailSnapshot

internal class TiebaDetailData(
    val current: DetailRecord,
    val thread: DetailRecord? = null,
    val floor: DetailRecord? = null,
    floors: List<DetailRecord> = emptyList(),
    repliesByParent: Map<String, List<DetailRecord>> = emptyMap(),
    replies: List<DetailRecord> = emptyList(),
    val loadState: DetailLoadState = DetailLoadState.Loading
) {
    val floors: List<DetailRecord> = immutableList(floors)
    val repliesByParent: Map<String, List<DetailRecord>> =
        immutableMap(repliesByParent.mapValues { immutableList(it.value) })
    val replies: List<DetailRecord> = immutableList(replies)
}

internal class TiebaDetailViewModel(
    record: UniversalRecord,
    repository: TiebaDetailRepository = TiebaDetailRepository()
) : ViewModel() {
    private val initial = record.toDetailRecord()
    private val reads = DetailRetry { repository.observe(initial) }
    val isRetrying = reads.isRetrying
    fun retry() = reads.retry()

    val state: StateFlow<TiebaDetailData> = tiebaDetailStates(initial, reads.snapshots).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TiebaDetailData(initial)
    )
}

internal fun tiebaDetailStates(
    record: DetailRecord,
    snapshots: Flow<Result<TiebaDetailSnapshot>>
): Flow<TiebaDetailData> = flow {
    var latest = TiebaDetailData(record)
    fun failed() = TiebaDetailData(
        current = latest.current,
        thread = latest.thread,
        floor = latest.floor,
        floors = latest.floors,
        repliesByParent = latest.repliesByParent,
        replies = latest.replies,
        loadState = DetailLoadState.Failed("无法读取本地记录，请重试")
    )
    emitAll(snapshots.map { result ->
        result.fold(
            onSuccess = { snapshot ->
                TiebaDetailData(
                    current = snapshot.current,
                    thread = snapshot.thread,
                    floor = snapshot.floor,
                    floors = snapshot.floors,
                    repliesByParent = snapshot.repliesByParent,
                    replies = snapshot.replies,
                    loadState = DetailLoadState.Ready
                )
            },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                failed()
            }
        ).also { latest = it }
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(failed())
    })
}
