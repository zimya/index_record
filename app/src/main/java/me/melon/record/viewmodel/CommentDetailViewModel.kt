package me.melon.record.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.repository.CommentDetailRepository
import me.melon.record.data.repository.CommentDetailSnapshot
import me.melon.record.data.repository.assembleCommentDetailSnapshot

internal data class CommentDetailUiState(
    val snapshot: CommentDetailSnapshot,
    val loadState: DetailLoadState = DetailLoadState.Loading
)

internal class CommentDetailViewModel(
    record: UniversalRecord,
    repository: CommentDetailRepository = CommentDetailRepository()
) : ViewModel() {
    private val seed = record.toDetailRecord()
    val state: StateFlow<CommentDetailUiState> = commentDetailStates(seed, repository.observe(seed)).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        CommentDetailUiState(assembleCommentDetailSnapshot(seed) { null })
    )
}

internal fun commentDetailStates(
    seed: DetailRecord,
    snapshots: Flow<Result<CommentDetailSnapshot>>
): Flow<CommentDetailUiState> = flow {
    var latest = assembleCommentDetailSnapshot(seed) { null }
    snapshots.catch {
        if (it is CancellationException) throw it
        emit(Result.failure(it))
    }.collect { result ->
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val state = result.fold(
            onSuccess = { latest = it; DetailLoadState.Ready },
            onFailure = { DetailLoadState.Failed("无法读取本地记录，请返回后重试") }
        )
        emit(CommentDetailUiState(latest, state))
    }
}
