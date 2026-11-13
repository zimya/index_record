package me.melon.record.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.DoubanSubjectMetadata
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.repository.DoubanSubjectRepository
import me.melon.record.data.repository.DoubanSubjectSnapshot

internal data class DoubanSubjectData(
    val snapshot: DoubanSubjectSnapshot,
    val loadState: DetailLoadState = DetailLoadState.Loading
) {
    val subject: DoubanSubjectMetadata = DoubanSubjectMetadata(snapshot.record)
}

internal class DoubanSubjectViewModel(
    record: UniversalRecord,
    repository: DoubanSubjectRepository = DoubanSubjectRepository()
) : ViewModel() {
    private val initial = record.toDetailRecord()
    val state: StateFlow<DoubanSubjectData> = doubanSubjectStates(initial, repository.observe(record)).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DoubanSubjectData(DoubanSubjectSnapshot(initial))
    )
}

internal fun doubanSubjectStates(
    record: DetailRecord,
    snapshots: Flow<Result<DoubanSubjectSnapshot>>
): Flow<DoubanSubjectData> = flow {
    var latest = DoubanSubjectData(DoubanSubjectSnapshot(record))
    fun failed() = latest.copy(loadState = DetailLoadState.Failed("无法读取本地记录，请返回后重试"))
    emitAll(
        snapshots.map { result ->
            latest = result.fold(
                onSuccess = { snapshot ->
                    DoubanSubjectData(snapshot = snapshot, loadState = DetailLoadState.Ready)
                },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    failed()
                }
            )
            latest
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(failed())
        }
    )
}
