package com.melonhu.indexrecord.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.identity
import com.melonhu.indexrecord.data.model.legacySnapshotKey
import com.melonhu.indexrecord.data.model.recordSnapshot
import org.junit.Assert.*
import org.junit.Test

class RecordHistoryRepositoryTest {
    private fun tweet(id: Long = 1, time: Long = 10, role: String = "tweet") = UniversalRecord(
        id = id, platform = "twitter", platformId = "twitter_${role}_42", recordType = role,
        content = "body", captureTime = time
    )

    @Test fun xRolesShareHistoryWithoutDeduplicatingIdenticalCaptures() {
        val seed = tweet()
        val snapshots = listOf(
            recordSnapshot("one", seed), recordSnapshot("two", tweet(time = 20, role = "comment")),
            recordSnapshot("three", tweet(time = 20)),
            recordSnapshot("other-platform", seed.copy(platform = "other"))
        )
        val history = assembleRecordHistory(snapshots, listOf(seed), seed.identity)
        assertEquals(listOf(20L, 20L, 10L), history.map { it.record.captureTime })
        assertEquals(setOf("one", "two", "three"), history.map { it.key }.toSet())
        assertTrue(history.none { it.legacy })
    }

    @Test fun fallbackBecomesFrozenLegacyWithoutMovingItsStatisticalBucketOrKey() {
        val old = tweet()
        val untouched = old.copy(id = 2, platformId = "twitter_tweet_99", captureTime = 5)
        val before = assembleRecordHistory(emptyList(), listOf(old), old.identity).single()
        assertTrue(before.legacy)
        val newer = old.copy(captureTime = 100, content = "new")
        val snapshots = listOf(recordSnapshot(legacySnapshotKey(old), old, legacy = true), recordSnapshot("new", newer))
        val after = assembleRecordHistory(snapshots, listOf(newer, untouched), old.identity)
        assertEquals(before, after.last())
        val all = assembleAllHistoricalRecords(snapshots, listOf(newer, untouched))
        assertEquals(listOf(100L, 10L, 5L), all.map { it.captureTime })
        assertEquals(listOf("new", "body", "body"), all.map { it.content })
        all.first().content = "mutated"
        assertEquals("new", assembleAllHistoricalRecords(snapshots, listOf(newer, untouched)).first().content)
    }

    @Test fun singleIdentityHistoryDoesNotDecodeUnrelatedPayloads() {
        val seed = tweet()
        val snapshots = listOf(
            recordSnapshot("matching", tweet(role = "comment")),
            recordSnapshot("other-id", seed.copy(platformId = "twitter_tweet_99")).copy(recordJson = "not JSON"),
            recordSnapshot("other-platform", seed.copy(platform = "other")).copy(recordJson = "not JSON")
        )
        val history = assembleRecordHistory(snapshots, emptyList(), seed.identity)
        assertEquals(listOf("matching"), history.map { it.key })
        assertEquals("twitter_comment_42", history.single().record.platformId)
    }

    @Test fun singleIdentityLegacyFallbackExcludesOtherPlatformsAndStorageIds() {
        val seed = tweet()
        val indexes = listOf(
            seed, tweet(id = 2, time = 20, role = "comment"),
            seed.copy(platform = "other"), seed.copy(platformId = "twitter_tweet_99")
        )
        val history = assembleRecordHistory(emptyList(), indexes, seed.identity)
        assertEquals(listOf("twitter_comment_42", "twitter_tweet_42"), history.map { it.record.platformId })
        assertTrue(history.all { it.legacy })
    }

    @Test fun readFailureIsPublishedWithoutPreventingLaterInvalidation() = runBlocking {
        val error = IllegalStateException("transient read failure")
        var reads = 0
        val histories = flowOf(Unit, Unit, Unit).readHistorySnapshots {
            when (++reads) {
                1 -> listOf("first")
                2 -> throw error
                else -> listOf("recovered")
            }
        }.toList()
        assertEquals(3, reads)
        assertEquals(3, histories.size)
        assertEquals(listOf("first"), histories[0].getOrThrow())
        assertSame(error, histories[1].exceptionOrNull())
        assertEquals(listOf("recovered"), histories[2].getOrThrow())
    }

    @Test fun initialReadFailureIsPublishedBeforeFirstSuccessfulHistory() = runBlocking {
        val error = IllegalStateException("initial read failure")
        var reads = 0
        val histories = flowOf(Unit, Unit).readHistorySnapshots {
            if (++reads == 1) throw error
            emptyList<String>()
        }.toList()
        assertEquals(2, histories.size)
        assertSame(error, histories[0].exceptionOrNull())
        assertEquals(emptyList<String>(), histories[1].getOrThrow())
        assertEquals(2, reads)
    }

    @Test fun successOnlyWrapperSkipsFailuresAndStillPublishesEmptyAndRecoveredHistories() = runBlocking {
        var reads = 0
        val histories = flowOf(Unit, Unit, Unit).readHistorySnapshots {
            when (++reads) {
                1 -> throw IllegalStateException("initial read failure")
                2 -> emptyList<String>()
                else -> listOf("recovered")
            }
        }.mapNotNull { it.getOrNull() }.toList()
        assertEquals(listOf(emptyList<String>(), listOf("recovered")), histories)
        assertEquals(3, reads)
    }

    @Test fun cancellationIsNotSwallowedAsARecoverableReadFailure() {
        val cancelled = CancellationException("cancel observation")
        var reads = 0
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                flowOf(Unit, Unit).readHistorySnapshots<List<String>> {
                    reads++
                    throw cancelled
                }.toList()
            }
        }
        assertSame(cancelled, thrown)
        assertEquals(1, reads)
    }

    @Test fun fallbackIncludesBothLegacyStorageRolesAndDoesNotUseAnUnstoredSeed() {
        val tweet = tweet()
        val reply = tweet(id = 2, time = 20, role = "comment")
        assertEquals(2, assembleRecordHistory(emptyList(), listOf(tweet, reply), tweet.identity).size)
        assertTrue(assembleRecordHistory(emptyList(), emptyList(), tweet.identity).isEmpty())
    }
}
