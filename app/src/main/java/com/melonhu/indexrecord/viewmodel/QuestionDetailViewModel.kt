package com.melonhu.indexrecord.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.toDetailRecord
import com.melonhu.indexrecord.data.model.immutableList
import com.melonhu.indexrecord.data.repository.AnswerSort
import com.melonhu.indexrecord.data.repository.QuestionDetailRepository
import com.melonhu.indexrecord.data.repository.QuestionDetailSnapshot
import com.melonhu.indexrecord.data.repository.ZhihuAnswerRef
import com.melonhu.indexrecord.data.repository.assembleQuestionDetailSnapshot

internal data class QuestionDetailUiState(
    val snapshot: QuestionDetailSnapshot,
    val loadState: DetailLoadState = DetailLoadState.Loading,
    val sort: AnswerSort = AnswerSort.VOTEUP
) {
    val sortedAnswerRefs: List<ZhihuAnswerRef> = immutableList(when (sort) {
        AnswerSort.LATEST -> snapshot.answerRefs.sortedByDescending { maxOf(it.updatedTimeSec, it.createdTimeSec) }
        AnswerSort.VOTEUP -> snapshot.answerRefs.sortedWith(
            compareByDescending<ZhihuAnswerRef> { it.voteupCount }
                .thenByDescending { maxOf(it.updatedTimeSec, it.createdTimeSec) }
        )
    })
}

internal class QuestionDetailViewModel(
    record: UniversalRecord,
    repository: QuestionDetailRepository = QuestionDetailRepository()
) : ViewModel() {
    private val seed = record.toDetailRecord()
    private val sort = MutableStateFlow(AnswerSort.VOTEUP)
    private val reads = DetailRetry { repository.observe(seed) }
    val isRetrying = reads.isRetrying
    fun retry() = reads.retry()

    val state: StateFlow<QuestionDetailUiState> = questionDetailStates(seed, reads.snapshots)
        .combine(sort) { state, order -> state.copy(sort = order) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            QuestionDetailUiState(assembleQuestionDetailSnapshot(seed) { null }))

    fun setSort(value: AnswerSort) { sort.value = value }
}

internal fun questionDetailStates(
    seed: DetailRecord,
    snapshots: Flow<Result<QuestionDetailSnapshot>>
): Flow<QuestionDetailUiState> = flow {
    var latest = assembleQuestionDetailSnapshot(seed) { null }
    snapshots.catch {
        if (it is CancellationException) throw it
        emit(Result.failure(it))
    }.collect { result ->
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val state = result.fold(
            onSuccess = { latest = it; DetailLoadState.Ready },
            onFailure = { DetailLoadState.Failed("无法读取本地记录，请重试") }
        )
        emit(QuestionDetailUiState(latest, state))
    }
}
