package me.melon.record

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.repository.AnswerSort
import me.melon.record.data.repository.QuestionDetailSnapshot
import me.melon.record.data.repository.ZhihuAnswerRef
import me.melon.record.viewmodel.DetailLoadState
import me.melon.record.viewmodel.QuestionDetailUiState
import me.melon.record.viewmodel.questionDetailStates
import org.junit.Assert.*
import org.junit.Test

class QuestionDetailViewModelTest {
    private val seed = UniversalRecord(platform = "zhihu", platformId = "zhihu_question_1", recordType = "question").toDetailRecord()

    @Test fun failureRetainsSnapshotAndSubsequentRefreshReplacesAllFields() = runBlocking {
        val before = QuestionDetailSnapshot(seed, excerpt = "old")
        val answer = UniversalRecord(platform = "zhihu", platformId = "zhihu_answer_2", content = "answer").toDetailRecord()
        val after = QuestionDetailSnapshot(seed.copy(content = "fresh"), excerpt = "new", existingAnswers = mapOf("2" to answer))
        val states = questionDetailStates(seed, flowOf(
            Result.success(before), Result.failure(IllegalStateException("query")), Result.success(after)
        )).toList()
        assertEquals(DetailLoadState.Ready, states[0].loadState)
        assertTrue(states[1].loadState is DetailLoadState.Failed)
        assertEquals(before, states[1].snapshot)
        assertEquals(DetailLoadState.Ready, states[2].loadState)
        assertEquals(after, states[2].snapshot)
    }

    @Test fun initialFailureCanRecover() = runBlocking {
        val states = questionDetailStates(seed, flowOf(
            Result.failure(IllegalStateException("initial")), Result.success(QuestionDetailSnapshot(seed))
        )).toList()
        assertEquals(seed, states.first().snapshot.record)
        assertTrue(states.first().loadState is DetailLoadState.Failed)
        assertEquals(DetailLoadState.Ready, states.last().loadState)
    }

    @Test fun observerFailureIsVisible() = runBlocking {
        val states = questionDetailStates(seed, flow<Result<QuestionDetailSnapshot>> {
            throw IllegalStateException("subscribe")
        }).toList()
        assertTrue(states.single().loadState is DetailLoadState.Failed)
    }

    @Test fun resultAndUpstreamCancellationPropagate() = runBlocking {
        val cancelled = CancellationException("cancel")
        for (source in listOf(flowOf(Result.failure<QuestionDetailSnapshot>(cancelled)), flow { throw cancelled })) {
            try {
                questionDetailStates(seed, source).toList()
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        }
    }

    @Test fun sortingPreservesVoteTieBreakAndLatestRules() {
        fun ref(id: String, vote: Long, created: Long, updated: Long) =
            ZhihuAnswerRef(id, "", created, updated, vote, 0, "", "", "")
        val snapshot = QuestionDetailSnapshot(seed, answerRefs = listOf(
            ref("a", 5, 1, 4), ref("b", 5, 5, 2), ref("c", 2, 9, 0)
        ))
        val initial = QuestionDetailUiState(snapshot)
        assertEquals(DetailLoadState.Loading, initial.loadState)
        assertEquals(listOf("b", "a", "c"), initial.sortedAnswerRefs.map { it.id })
        assertEquals(listOf("c", "b", "a"), initial.copy(sort = AnswerSort.LATEST).sortedAnswerRefs.map { it.id })
        assertEquals(listOf("a", "b", "c"), snapshot.answerRefs.map { it.id })
        assertThrows(UnsupportedOperationException::class.java) {
            (initial.sortedAnswerRefs as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (initial.copy(sort = AnswerSort.LATEST).sortedAnswerRefs as MutableList).clear()
        }
    }
}
