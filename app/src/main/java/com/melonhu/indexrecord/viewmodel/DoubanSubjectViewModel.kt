package com.melonhu.indexrecord.viewmodel

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
import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.model.DoubanSubjectMetadata
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.toDetailRecord
import com.melonhu.indexrecord.data.repository.DoubanSubjectRepository
import com.melonhu.indexrecord.data.repository.DoubanSubjectSnapshot

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
    private val reads = DetailRetry { repository.observe(record) }
    val isRetrying = reads.isRetrying
    fun retry() = reads.retry()

    val state: StateFlow<DoubanSubjectData> = doubanSubjectStates(initial, reads.snapshots).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = DoubanSubjectData(DoubanSubjectSnapshot(initial))
    )
}

internal fun doubanSubjectStates(
    record: DetailRecord,
    snapshots: Flow<Result<DoubanSubjectSnapshot>>
): Flow<DoubanSubjectData> = flow {
    var latest = DoubanSubjectData(DoubanSubjectSnapshot(record))
    fun failed() = latest.copy(loadState = DetailLoadState.Failed("无法读取本地记录，请重试"))
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
