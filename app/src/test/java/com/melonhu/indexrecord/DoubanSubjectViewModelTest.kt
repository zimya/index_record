package com.melonhu.indexrecord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.repository.DoubanSubjectSnapshot
import com.melonhu.indexrecord.viewmodel.DetailLoadState
import com.melonhu.indexrecord.viewmodel.doubanSubjectStates
import org.junit.Assert.*
import org.junit.Test

class DoubanSubjectViewModelTest {
    private val seed = DetailRecord(platform = "douban", platformId = "douban_movie_1", recordType = "subject")

    @Test fun queryFailureDoesNotTerminateSubsequentRefreshes() = runBlocking {
        val before = DoubanSubjectSnapshot(seed.copy(content = "before", extraMetadata = """{"rating_count":1}"""))
        val related = DetailRecord(platform = "douban", platformId = "review", recordType = "review",
            parentId = seed.platformId, content = "new review", extraMetadata = """{"voteup_count":8}""")
        val after = DoubanSubjectSnapshot(seed.copy(content = "after", extraMetadata = """{"rating_count":9}"""), listOf(related))
        val states = doubanSubjectStates(seed, flowOf(
            Result.success(before), Result.failure(IllegalStateException("query failed")), Result.success(after)
        )).toList()
        assertEquals(3, states.size)
        assertEquals(DetailLoadState.Ready, states[0].loadState)
        assertTrue(states[1].loadState is DetailLoadState.Failed)
        assertTrue((states[1].loadState as DetailLoadState.Failed).message.isNotBlank())
        assertSame(before, states[1].snapshot)
        assertEquals("before", states[1].subject.intro)
        assertEquals(1L, states[1].subject.ratingCount)
        assertEquals(DetailLoadState.Ready, states[2].loadState)
        assertEquals("after", states[2].subject.intro)
        assertEquals(9L, states[2].subject.ratingCount)
        assertEquals(listOf(related), states[2].snapshot.relatedRecords)
    }

    @Test fun firstQueryFailureCanRecoverOnNextChange() = runBlocking {
        val states = doubanSubjectStates(seed, flowOf(
            Result.failure(IllegalStateException("first query failed")),
            Result.success(DoubanSubjectSnapshot(seed.copy(title = "captured")))
        )).toList()
        assertTrue(states.first().loadState is DetailLoadState.Failed)
        assertEquals(DetailLoadState.Ready, states.last().loadState)
        assertEquals("captured", states.last().subject.subjectTitle)
    }

    @Test fun repeatedQueryFailuresPreserveTheLastCompleteSnapshot() = runBlocking {
        val related = seed.copy(platformId = "review", recordType = "review", content = "cached review")
        val snapshot = DoubanSubjectSnapshot(seed.copy(content = "cached body"), listOf(related))
        val states = doubanSubjectStates(seed, flowOf(
            Result.success(snapshot),
            Result.failure(IllegalStateException("first failure")),
            Result.failure(IllegalStateException("second failure"))
        )).toList()
        for (state in states.drop(1)) {
            assertTrue(state.loadState is DetailLoadState.Failed)
            assertSame(snapshot, state.snapshot)
            assertEquals(listOf(related), state.snapshot.relatedRecords)
        }
    }

    @Test fun terminalObserverFailureAlsoRetainsTheLastSnapshot() = runBlocking {
        val snapshot = DoubanSubjectSnapshot(seed.copy(content = "cached body"))
        val states = doubanSubjectStates(seed, flow<Result<DoubanSubjectSnapshot>> {
            emit(Result.success(snapshot))
            throw IllegalStateException("observer failed")
        }).toList()
        assertTrue(states.last().loadState is DetailLoadState.Failed)
        assertSame(snapshot, states.last().snapshot)
        assertEquals("cached body", states.last().subject.intro)
    }

    @Test fun observerFailureBecomesVisibleInsteadOfRemainingLoading() = runBlocking {
        val states = doubanSubjectStates(seed, flow<Result<DoubanSubjectSnapshot>> {
            throw IllegalStateException("subscription failed")
        }).toList()
        assertTrue(states.single().loadState is DetailLoadState.Failed)
    }

    @Test fun cancellationIsNeverConvertedToFailureState() = runBlocking {
        val cancellation = CancellationException("cancelled")
        try {
            doubanSubjectStates(seed, flowOf(Result.failure<DoubanSubjectSnapshot>(cancellation))).toList()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
