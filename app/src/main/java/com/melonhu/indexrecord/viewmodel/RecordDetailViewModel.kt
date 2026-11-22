package com.melonhu.indexrecord.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.immutableList
import com.melonhu.indexrecord.data.model.toDetailRecord
import com.melonhu.indexrecord.data.model.toEntity
import com.melonhu.indexrecord.data.repository.RecordDetailRepository
import com.melonhu.indexrecord.data.repository.RecordDetailSnapshot
import com.melonhu.indexrecord.presentation.PlatformMetadataAdapters
import com.melonhu.indexrecord.presentation.RecordStatistic
import com.melonhu.indexrecord.presentation.StatisticsSurface
import com.melonhu.indexrecord.presentation.recordStatistics

internal class RecordDetailUiState(
    val snapshot: RecordDetailSnapshot,
    val loadState: DetailLoadState = DetailLoadState.Loading
) {
    private val metadata = PlatformMetadataAdapters(snapshot.record.extraMetadata, snapshot.record.platform)
    val hasMetadata: Boolean = metadata.hasValues
    val statistics: List<RecordStatistic> = immutableList(
        recordStatistics(snapshot.record.toEntity(), StatisticsSurface.DETAIL, metadata)
    )
}

internal class RecordDetailViewModel(
    record: UniversalRecord,
    repository: RecordDetailRepository = RecordDetailRepository()
) : ViewModel() {
    private val initial = record.copy()
    private val reads = DetailRetry { repository.observe(initial) }
    val isRetrying = reads.isRetrying
    fun retry() = reads.retry()

    val state: StateFlow<RecordDetailUiState> = recordDetailStates(initial, reads.snapshots).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = RecordDetailUiState(RecordDetailSnapshot(initial.toDetailRecord()))
    )
}

internal fun recordDetailStates(
    record: UniversalRecord,
    snapshots: Flow<Result<RecordDetailSnapshot>>
): Flow<RecordDetailUiState> {
    val initial = RecordDetailSnapshot(record.toDetailRecord())
    return flow {
        var latest = initial
        snapshots.catch {
            if (it is CancellationException) throw it
            emit(Result.failure(it))
        }.collect { result ->
            result.fold(
                onSuccess = {
                    latest = it
                    emit(RecordDetailUiState(it, DetailLoadState.Ready))
                },
                onFailure = {
                    if (it is CancellationException) throw it
                    emit(RecordDetailUiState(latest, DetailLoadState.Failed(READ_ERROR)))
                }
            )
        }
    }
}

private const val READ_ERROR = "无法读取本地记录，请重试"
