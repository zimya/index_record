package me.melon.record

import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.model.toEntity
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.repository.DoubanSubjectSnapshot
import me.melon.record.data.repository.assembleDoubanSubjectSnapshot
import me.melon.record.viewmodel.DoubanSubjectData
import me.melon.record.viewmodel.DetailLoadState
import org.junit.Assert.*
import org.junit.Test

class DoubanSubjectRepositoryTest {
    private val seed = UniversalRecord(
        platform = "douban", platformId = "douban_movie_1", recordType = "subject", title = "seed"
    )

    @Test fun initialSnapshotUsesSeedWhenSubjectHasNotBeenCaptured() {
        val snapshot = assembleDoubanSubjectSnapshot(seed, { id ->
            assertEquals(seed.platformId, id)
            null
        }, { id ->
            assertEquals(seed.platformId, id)
            emptyList()
        })
        assertEquals(seed.toDetailRecord(), snapshot.record)
        assertNotSame(seed, snapshot.record)
        assertTrue(snapshot.relatedRecords.isEmpty())
        assertEquals(DetailLoadState.Loading, DoubanSubjectData(snapshot).loadState)
    }

    @Test fun everyRefreshReplacesBodyStatisticsAndAllRelatedRecordsTogether() {
        var subject = seed.copy(content = "first body", extraMetadata = """{"rating_count":1}""")
        var related = listOf(
            UniversalRecord(platform = "douban", platformId = "review", parentId = seed.platformId, recordType = "review",
                content = "first review", publishTime = 2, extraMetadata = """{"like_count":1}"""),
            UniversalRecord(platform = "douban", platformId = "interest", parentId = seed.platformId, recordType = "interest", publishTime = 1)
        )
        var subjectReads = 0
        var relatedReads = 0
        fun refresh() = DoubanSubjectData(
            snapshot = assembleDoubanSubjectSnapshot(seed, {
                subjectReads++
                subject
            }, {
                relatedReads++
                related
            }), loadState = DetailLoadState.Ready
        )
        val first = refresh()
        subject = subject.copy(content = "updated body", extraMetadata = """{"rating_count":9}""")
        related = listOf(related.first().copy(content = "updated review", extraMetadata = """{"like_count":8}"""))
        val second = refresh()
        assertEquals("first body", first.subject.intro)
        assertEquals(1L, first.subject.ratingCount)
        assertEquals(2, first.snapshot.relatedRecords.size)
        assertEquals("updated body", second.subject.intro)
        assertEquals(9L, second.subject.ratingCount)
        assertEquals(listOf("review"), second.snapshot.relatedRecords.map { it.platformId })
        assertEquals("updated review", second.snapshot.relatedRecords.single().content)
        assertEquals("""{"like_count":8}""", second.snapshot.relatedRecords.single().extraMetadata)
        assertEquals(DetailLoadState.Ready, second.loadState)
        assertEquals(2, subjectReads)
        assertEquals(2, relatedReads)
    }

    @Test fun snapshotsOwnDetachedEntitiesAndRetainRepositoryOrdering() {
        val subject = seed.copy(title = "captured")
        val newest = UniversalRecord(platform = "douban", recordType = "review", parentId = seed.platformId,
            platformId = "new", content = "before", publishTime = 2)
        val oldest = UniversalRecord(platform = "douban", recordType = "interest", parentId = seed.platformId,
            platformId = "old", publishTime = 1)
        val rows = mutableListOf(newest, oldest)
        val snapshot = assembleDoubanSubjectSnapshot(seed, { subject }, { rows })
        subject.title = "mutated"
        newest.content = "mutated"
        rows.clear()
        assertEquals("captured", snapshot.record.title)
        assertEquals(listOf("new", "old"), snapshot.relatedRecords.map { it.platformId })
        assertEquals("before", snapshot.relatedRecords.first().content)
    }

    @Test fun snapshotDefensivelyCopiesAndFreezesRelatedCollections() {
        val row = seed.copy(recordType = "review").toDetailRecord()
        val rows = mutableListOf(row)
        val snapshot = DoubanSubjectSnapshot(seed.toDetailRecord(), rows)
        rows.clear()
        assertEquals(listOf(row), snapshot.relatedRecords)
        try {
            (snapshot.relatedRecords as MutableList<DetailRecord>).clear()
            fail("Snapshot collections must reject mutation")
        } catch (_: UnsupportedOperationException) {
        }
        val navigationEntity = snapshot.relatedRecords.single().toEntity()
        navigationEntity.content = "changed by navigation"
        assertEquals(row.content, snapshot.relatedRecords.single().content)
    }

    @Test fun onlySubjectMembershipRelationsAreAggregated() {
        val interest = UniversalRecord(platform = "douban", recordType = "interest",
            platformId = "interest", parentId = seed.platformId)
        val review = interest.copy(platformId = "review", recordType = "review")
        val topic = interest.copy(platformId = "topic", recordType = "topic")
        val reply = interest.copy(platformId = "reply", recordType = "comment")
        val foreign = review.copy(platform = "zhihu", platformId = "foreign")
        val differentSubject = review.copy(platformId = "other", parentId = "douban_movie_2")
        val nestedReply = reply.copy(platformId = "nested", parentId = review.platformId)
        val result = assembleDoubanSubjectSnapshot(seed, { seed }, {
            listOf(interest, review, topic, reply, foreign, differentSubject, nestedReply)
        })
        assertEquals(listOf(interest, review, topic).map { it.toDetailRecord() }, result.relatedRecords)
    }

    @Test fun subjectLookupRejectsForeignPlatformAndNonSubjectRows() {
        for (invalid in listOf(seed.copy(platform = "zhihu"), seed.copy(recordType = "comment"),
            seed.copy(platformId = "douban_movie_2"))) {
            val result = assembleDoubanSubjectSnapshot(seed, { invalid }, { emptyList() })
            assertEquals(seed.toDetailRecord(), result.record)
        }
    }

    @Test fun deletedSubjectReturnsToSeedRatherThanRetainingStaleDatabaseContent() {
        val captured = assembleDoubanSubjectSnapshot(seed, { seed.copy(title = "captured") }, { emptyList() })
        val deleted = assembleDoubanSubjectSnapshot(seed, { null }, { emptyList() })
        assertEquals("captured", captured.record.title)
        assertEquals("seed", deleted.record.title)
    }
}
