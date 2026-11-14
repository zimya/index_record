package com.melonhu.indexrecord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.repository.readHistorySnapshots
import com.melonhu.indexrecord.logic.StatsCalculator
import com.melonhu.indexrecord.viewmodel.DetailRetry
import com.melonhu.indexrecord.viewmodel.StatsUiState
import com.melonhu.indexrecord.viewmodel.statsStates
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class StatsReadStateTest {
    private val zone = ZoneId.of("UTC")
    private fun records() = listOf(
        UniversalRecord(platform = "reddit", platformId = "reddit_post_1", recordType = "post", content = "正文", captureTime = 100),
        UniversalRecord(platform = "reddit", platformId = "reddit_comment_2", recordType = "comment", content = "回复", captureTime = 200)
    )

    private class Observer(scope: CoroutineScope, source: Flow<StatsUiState>) {
        val states = Channel<StatsUiState>(Channel.UNLIMITED)
        val job = scope.launch { source.collect { states.send(it) } }
        suspend fun next(): StatsUiState = withTimeout(2_000) { states.receive() }
    }

    @Test fun initialFailureIsNotPresentedAsSuccessfulZeroStats() = runBlocking {
        val input = Channel<Result<List<UniversalRecord>>>(Channel.UNLIMITED)
        val observer = Observer(this, statsStates(input.receiveAsFlow(), flowOf(false), zone))
        try {
            val loading = observer.next()
            assertTrue(loading.isLoading)
            assertFalse(loading.hasStats)
            input.send(Result.failure(IllegalStateException("read")))
            val failed = observer.next()
            assertFalse(failed.isLoading)
            assertFalse(failed.hasStats)
            assertNotNull(failed.errorMessage)
            input.send(Result.success(emptyList()))
            val empty = observer.next()
            assertTrue(empty.hasStats)
            assertFalse(empty.isLoading)
            assertNull(empty.errorMessage)
            assertEquals(0L, empty.stats.totalRecordCount)
        } finally { observer.job.cancel() }
    }

    @Test fun errorsKeepLastSuccessfulStatsAndRecoverOnTheSameSubscription() = runBlocking {
        val input = Channel<Result<List<UniversalRecord>>>(Channel.UNLIMITED)
        val observer = Observer(this, statsStates(input.receiveAsFlow(), flowOf(false), zone))
        try {
            observer.next()
            input.send(Result.success(records()))
            val good = observer.next()
            assertEquals(StatsCalculator.computeState(records(), weeks = 52, zone = zone), good.stats)
            repeat(2) {
                input.send(Result.failure(IllegalStateException("read")))
                val failed = observer.next()
                assertSame(good.stats, failed.stats)
                assertTrue(failed.hasStats)
                assertNotNull(failed.errorMessage)
            }
            input.send(Result.success(emptyList()))
            val recovered = observer.next()
            assertTrue(recovered.hasStats)
            assertNull(recovered.errorMessage)
            assertEquals(0L, recovered.stats.totalRecordCount)
        } finally { observer.job.cancel() }
    }

    @Test fun failedReadKeepsTheFilterOfRetainedStatsUntilRecovery() = runBlocking {
        val input = Channel<Result<List<UniversalRecord>>>(Channel.UNLIMITED)
        val preferences = MutableStateFlow(false)
        val observer = Observer(this, statsStates(input.receiveAsFlow(), preferences, zone))
        try {
            observer.next()
            input.send(Result.success(records()))
            val good = observer.next()
            assertFalse(good.includeRepliesAndComments)
            input.send(Result.failure(IllegalStateException("read")))
            observer.next()
            preferences.value = true
            val failed = observer.next()
            assertFalse(failed.includeRepliesAndComments)
            assertSame(good.stats, failed.stats)
            input.send(Result.success(records()))
            val recovered = observer.next()
            assertTrue(recovered.includeRepliesAndComments)
            assertEquals(StatsCalculator.computeState(records(), weeks = 52, zone = zone, includeRepliesAndComments = true), recovered.stats)
            assertNull(recovered.errorMessage)
        } finally { observer.job.cancel() }
    }

    @Test fun changingSettingAfterSuccessStillRecomputesWithoutAnotherRead() = runBlocking {
        val input = Channel<Result<List<UniversalRecord>>>(Channel.UNLIMITED)
        val preferences = MutableStateFlow(false)
        val observer = Observer(this, statsStates(input.receiveAsFlow(), preferences, zone))
        try {
            observer.next()
            input.send(Result.success(records()))
            observer.next()
            preferences.value = true
            val updated = observer.next()
            assertTrue(updated.includeRepliesAndComments)
            assertEquals(StatsCalculator.computeState(records(), weeks = 52, zone = zone, includeRepliesAndComments = true), updated.stats)
        } finally { observer.job.cancel() }
    }

    @Test fun manualRetryAfterSubscriptionFailureRecoversWithoutResettingStats() = runBlocking {
        var subscriptions = 0
        val inputs = Channel<Result<List<UniversalRecord>>>(Channel.UNLIMITED)
        val reads = DetailRetry {
            if (++subscriptions == 2) throw IllegalStateException("subscribe")
            inputs.receiveAsFlow()
        }
        val observer = Observer(this, statsStates(reads.snapshots, flowOf(false), zone))
        try {
            observer.next()
            inputs.send(Result.success(records()))
            val good = observer.next()
            reads.retry()
            val failed = observer.next()
            assertNotNull(failed.errorMessage)
            assertSame(good.stats, failed.stats)
            assertFalse(reads.isRetrying.value)
            reads.retry()
            assertTrue(reads.isRetrying.value)
            inputs.send(Result.success(emptyList()))
            val recovered = observer.next()
            assertNull(recovered.errorMessage)
            assertTrue(recovered.hasStats)
            assertEquals(0L, recovered.stats.totalRecordCount)
            assertEquals(3, subscriptions)
        } finally { observer.job.cancel() }
    }

    @Test fun databaseInvalidationRecoversAnInitialFailureWithoutManualRetry() = runBlocking {
        val invalidations = Channel<Unit>(Channel.UNLIMITED)
        var subscriptions = 0
        var attempts = 0
        val reads = DetailRetry {
            subscriptions++
            invalidations.receiveAsFlow().readHistorySnapshots {
                if (++attempts == 1) throw IllegalStateException("read")
                records()
            }
        }
        val observer = Observer(this, statsStates(reads.snapshots, flowOf(false), zone))
        try {
            observer.next()
            invalidations.send(Unit)
            val failed = observer.next()
            assertNotNull(failed.errorMessage)
            assertFalse(failed.hasStats)
            invalidations.send(Unit)
            val recovered = observer.next()
            assertNull(recovered.errorMessage)
            assertTrue(recovered.hasStats)
            assertEquals(StatsCalculator.computeState(records(), weeks = 52, zone = zone), recovered.stats)
            assertEquals(1, subscriptions)
            assertEquals(2, attempts)
        } finally { observer.job.cancel() }
    }

    @Test fun cancellationIsNeverConvertedIntoAReadError() {
        val cancellation = CancellationException("cancel stats")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                statsStates(flowOf(Result.failure(cancellation)), flowOf(false), zone).toList()
            }
        }
        // Coroutine stack-trace recovery may copy the cancellation exception.
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === cancellation })
    }
}
