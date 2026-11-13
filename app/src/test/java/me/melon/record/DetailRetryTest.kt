package me.melon.record

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.repository.RecordDetailSnapshot
import me.melon.record.viewmodel.DetailLoadState
import me.melon.record.viewmodel.DetailRetry
import me.melon.record.viewmodel.recordDetailStates
import org.junit.Assert.*
import org.junit.Test

class DetailRetryTest {
    @Test fun retryRecoversEvenWhenObserverConstructionThrows() = runBlocking {
        var subscriptions = 0
        val reads = DetailRetry {
            if (++subscriptions == 1) throw IllegalStateException("subscribe")
            flowOf(Result.success("recovered"))
        }
        val results = Channel<Result<String>>(Channel.UNLIMITED)
        val job = launch { reads.snapshots.collect { results.send(it) } }
        try {
            assertTrue(withTimeout(2_000) { results.receive() }.isFailure)
            assertFalse(reads.isRetrying.value)
            reads.retry()
            assertTrue(reads.isRetrying.value)
            assertEquals("recovered", withTimeout(2_000) { results.receive() }.getOrThrow())
            assertFalse(reads.isRetrying.value)
            assertEquals(2, subscriptions)
        } finally { job.cancel() }
    }

    @Test fun repeatedRetryFailuresKeepTheLastSnapshotUntilRecovery() = runBlocking {
        val seed = UniversalRecord(platform = "reddit", platformId = "reddit_post_1", recordType = "post")
        val cached = RecordDetailSnapshot(seed.copy(content = "cached").toDetailRecord())
        val fresh = RecordDetailSnapshot(seed.copy(content = "fresh").toDetailRecord())
        var subscriptions = 0
        val reads = DetailRetry {
            flow {
                when (++subscriptions) {
                    1 -> {
                        emit(Result.success(cached))
                        throw IllegalStateException("observer died")
                    }
                    2 -> emit(Result.failure(IllegalStateException("still unavailable")))
                    else -> emit(Result.success(fresh))
                }
            }
        }
        val states = Channel<me.melon.record.viewmodel.RecordDetailUiState>(Channel.UNLIMITED)
        val job = launch { recordDetailStates(seed, reads.snapshots).collect { states.send(it) } }
        try {
            assertSame(cached, withTimeout(2_000) { states.receive() }.snapshot)
            val failed = withTimeout(2_000) { states.receive() }
            assertTrue(failed.loadState is DetailLoadState.Failed)
            assertSame(cached, failed.snapshot)
            reads.retry()
            val failedAgain = withTimeout(2_000) { states.receive() }
            assertTrue(failedAgain.loadState is DetailLoadState.Failed)
            assertSame(cached, failedAgain.snapshot)
            reads.retry()
            val recovered = withTimeout(2_000) { states.receive() }
            assertEquals(DetailLoadState.Ready, recovered.loadState)
            assertSame(fresh, recovered.snapshot)
        } finally { job.cancel() }
    }

    @Test fun retryCancelsOldLiveObserverAndIgnoresDuplicatePendingRequests() = runBlocking {
        var subscriptions = 0
        var cancellations = 0
        val gate = Channel<Unit>()
        val results = Channel<Result<Int>>(Channel.UNLIMITED)
        val reads = DetailRetry {
            flow {
                val attempt = ++subscriptions
                try {
                    if (attempt > 1) gate.receive()
                    emit(if (attempt == 1) Result.failure(IllegalStateException()) else Result.success(attempt))
                    awaitCancellation()
                } finally { cancellations++ }
            }
        }
        val job = launch { reads.snapshots.collect { results.send(it) } }
        try {
            assertTrue(withTimeout(2_000) { results.receive() }.isFailure)
            reads.retry()
            reads.retry()
            withTimeout(2_000) { gate.send(Unit) }
            assertEquals(2, withTimeout(2_000) { results.receive() }.getOrThrow())
            assertEquals(2, subscriptions)
            assertEquals(1, cancellations)
            assertFalse(reads.isRetrying.value)
        } finally { job.cancel() }
    }

    @Test fun cancellationIsNotConvertedToAReadFailure() = runBlocking {
        val cancelled = CancellationException("leaving detail")
        val results = Channel<Result<String>>(Channel.UNLIMITED)
        val cancellations = Channel<Unit>(Channel.UNLIMITED)
        var subscriptions = 0
        val reads = DetailRetry<String> {
            flow {
                if (++subscriptions == 1) {
                    try { throw cancelled } finally { cancellations.send(Unit) }
                } else emit(Result.success("recovered"))
            }
        }
        val job = launch { reads.snapshots.collect { results.send(it) } }
        try {
            withTimeout(2_000) { cancellations.receive() }
            assertTrue(results.tryReceive().isFailure)
            assertFalse(reads.isRetrying.value)
            reads.retry()
            assertEquals("recovered", withTimeout(2_000) { results.receive() }.getOrThrow())
        } finally { job.cancel() }
    }
}
