package me.melon.record.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.immutableList
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.model.toEntity
import me.melon.record.data.repository.RecordDetailRepository
import me.melon.record.data.repository.RecordDetailSnapshot
import me.melon.record.presentation.RecordMetadata
import me.melon.record.presentation.RecordStatistic
import me.melon.record.presentation.StatisticsSurface
import me.melon.record.presentation.recordStatistics

internal class RecordDetailUiState(
    val snapshot: RecordDetailSnapshot,
    val loadState: DetailLoadState = DetailLoadState.Loading
) {
    private val metadata = RecordMetadata(snapshot.record.extraMetadata, snapshot.record.platform)
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
    val state: StateFlow<RecordDetailUiState> = recordDetailStates(initial, repository.observe(initial)).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
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

private const val READ_ERROR = "无法读取本地记录，请返回后重试"
