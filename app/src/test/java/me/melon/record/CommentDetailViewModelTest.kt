package me.melon.record

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.repository.CommentDetailSnapshot
import me.melon.record.viewmodel.DetailLoadState
import me.melon.record.viewmodel.commentDetailStates
import org.junit.Assert.*
import org.junit.Test

class CommentDetailViewModelTest {
    private val seed = UniversalRecord(platform = "zhihu", platformId = "zhihu_comment_1", recordType = "comment").toDetailRecord()

    @Test fun failurePreservesLastCompleteSnapshotAndCanRecover() = runBlocking {
        val before = CommentDetailSnapshot(seed, likeCount = 1)
        val after = CommentDetailSnapshot(seed.copy(content = "fresh"), likeCount = 9)
        val states = commentDetailStates(seed, flowOf(
            Result.success(before), Result.failure(IllegalStateException("query")), Result.success(after)
        )).toList()
        assertEquals(DetailLoadState.Ready, states[0].loadState)
        assertTrue(states[1].loadState is DetailLoadState.Failed)
        assertEquals(before, states[1].snapshot)
        assertEquals(DetailLoadState.Ready, states[2].loadState)
        assertEquals("fresh", states[2].snapshot.record.content)
        assertEquals(9L, states[2].snapshot.likeCount)
    }

    @Test fun initialFailureUsesSeedAndLaterRefreshRecovers() = runBlocking {
        val states = commentDetailStates(seed, flowOf(
            Result.failure(IllegalStateException("initial")), Result.success(CommentDetailSnapshot(seed))
        )).toList()
        assertEquals(seed, states.first().snapshot.record)
        assertTrue(states.first().loadState is DetailLoadState.Failed)
        assertEquals(DetailLoadState.Ready, states.last().loadState)
    }

    @Test fun observerFailureIsVisible() = runBlocking {
        val states = commentDetailStates(seed, flow<Result<CommentDetailSnapshot>> {
            throw IllegalStateException("subscribe")
        }).toList()
        assertTrue(states.single().loadState is DetailLoadState.Failed)
    }

    @Test fun resultAndUpstreamCancellationPropagate() = runBlocking {
        val cancelled = CancellationException("cancel")
        for (source in listOf(flowOf(Result.failure<CommentDetailSnapshot>(cancelled)), flow { throw cancelled })) {
            try {
                commentDetailStates(seed, source).toList()
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        }
    }
}
