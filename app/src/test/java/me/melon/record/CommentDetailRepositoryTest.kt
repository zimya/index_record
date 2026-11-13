package me.melon.record

import me.melon.record.data.model.RecordIdentity
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.resolveRecordIdentity
import me.melon.record.data.repository.CommentDetailSnapshot
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.repository.assembleCommentDetailSnapshot
import org.junit.Assert.*
import org.junit.Test

class CommentDetailRepositoryTest {
    private fun record(id: String, parent: String = "", platform: String = "zhihu", type: String = "comment") =
        UniversalRecord(platform = platform, platformId = id, parentId = parent, recordType = type)

    private fun snapshot(seed: UniversalRecord, records: List<UniversalRecord>) =
        assembleCommentDetailSnapshot(seed.toDetailRecord()) { identity ->
            resolveRecordIdentity(records.filter { RecordIdentity.of(it.platform, it.platformId) == identity })?.toDetailRecord()
        }

    @Test fun refreshesBodyCountsAndEntireAncestorChain() {
        for (platform in listOf("zhihu", "douban", "twitter")) {
            val seed = record("${platform}_comment_1", "${platform}_comment_2", platform)
            val parent = record("${platform}_comment_2", "${platform}_post_3", platform)
            val root = record("${platform}_post_3", platform = platform, type = "article")
            val updated = seed.copy(content = "fresh", authorName = "author", publishTime = 42,
                extraMetadata = """{"like_count":12,"dislike_count":3,"rating_value":4}""")
            val result = snapshot(seed, listOf(updated, parent, root))
            assertEquals("fresh", result.record.content)
            assertEquals("author", result.record.authorName)
            assertEquals(42L, result.record.publishTime)
            assertEquals(12L, result.likeCount)
            assertEquals(3L, result.dislikeCount)
            assertEquals(4.0, result.starCount, 0.0)
            assertEquals(listOf(parent.toDetailRecord()), result.ancestors)
            assertEquals(root.toDetailRecord(), result.related)
            assertNull(snapshot(seed, listOf(updated, parent)).related)
            assertEquals(seed.toDetailRecord(), snapshot(seed, emptyList()).record)
        }
    }

    @Test fun resolvesTwitterAliasesAndStopsLogicalCycles() {
        val seed = record("twitter_comment_1", "twitter_tweet_2", "twitter")
        val root = record("twitter_comment_2", platform = "twitter", type = "tweet")
        assertEquals(root.toDetailRecord(), snapshot(seed, listOf(root)).related)
        val cycle = root.copy(recordType = "comment", parentId = "twitter_tweet_1")
        val result = snapshot(seed, listOf(seed, cycle))
        assertNull(result.related)
        assertEquals(listOf(cycle.toDetailRecord()), result.ancestors)
    }

    @Test fun brokenChainsAndMalformedMetadataAreSafe() {
        val seed = record("zhihu_comment_1", "missing").copy(extraMetadata = """{"like_count":{},"star_count":null}""")
        val result = snapshot(seed, emptyList())
        assertNull(result.related)
        assertEquals(0L, result.likeCount)
        assertEquals(0.0, result.starCount, 0.0)
        assertEquals(0L, snapshot(seed.copy(extraMetadata = "invalid"), emptyList()).likeCount)
    }

    @Test fun snapshotValuesDoNotChangeWhenSourceEntitiesMutate() {
        val seed = record("zhihu_comment_1", "zhihu_comment_2")
        val parent = record("zhihu_comment_2", "root")
        val root = record("root", type = "answer")
        val result = snapshot(seed, listOf(seed, parent, root))
        val fallback = snapshot(seed, emptyList())
        seed.content = "changed"
        parent.content = "changed"
        root.content = "changed"
        assertEquals("", result.record.content)
        assertEquals("", result.ancestors.single().content)
        assertEquals("", result.related!!.content)
        assertEquals("", fallback.record.content)
    }

    @Test fun ancestorCollectionCopiesInputsAndRejectsMutation() {
        val current = record("current").toDetailRecord()
        val parent = record("parent").toDetailRecord()
        val input = mutableListOf(parent)
        val result = CommentDetailSnapshot(current, ancestors = input)
        input.clear()
        assertEquals(listOf(parent), result.ancestors)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.ancestors as MutableList).add(current)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.ancestors as MutableList)[0] = current
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.ancestors as MutableList).clear()
        }
        assertEquals(listOf(parent), result.ancestors)
        assertThrows(UnsupportedOperationException::class.java) {
            (CommentDetailSnapshot(current).ancestors as MutableList).add(parent)
        }
    }

    @Test fun latestTwitterAliasSuppliesBodyCountsAndRelationsButKeepsEntryNavigationRole() {
        val entry = record("twitter_comment_1", "stale_parent", "twitter").copy(captureTime = 10)
        val latest = record("twitter_tweet_1", "twitter_tweet_2", "twitter", "tweet").copy(
            content = "latest body", authorName = "latest author", captureTime = 20, publishTime = 15,
            extraMetadata = """{"like_count":42,"dislike_count":2}""")
        val parent = record("twitter_tweet_2", platform = "twitter", type = "tweet")
        for (records in listOf(listOf(entry, latest, parent), listOf(parent, latest, entry))) {
            val result = snapshot(entry, records)
            assertEquals(latest.toDetailRecord().copy(platformId = entry.platformId, recordType = entry.recordType), result.record)
            assertEquals(42L, result.likeCount)
            assertEquals(2L, result.dislikeCount)
            assertEquals(parent.toDetailRecord(), result.related)
        }
        val newerEntry = entry.copy(captureTime = 30, content = "newest comment", extraMetadata = """{"like_count":99}""")
        val result = snapshot(entry, listOf(latest, newerEntry))
        assertEquals("newest comment", result.record.content)
        assertEquals(99L, result.likeCount)
    }

    @Test fun equalCaptureTimeUsesLatestPublishTimeAcrossTwitterAliases() {
        val entry = record("twitter_comment_1", platform = "twitter").copy(captureTime = 10, publishTime = 1)
        val alias = entry.copy(platformId = "twitter_tweet_1", recordType = "tweet", publishTime = 2, content = "newer")
        assertEquals("newer", snapshot(entry, listOf(entry, alias)).record.content)
        assertEquals("newer", snapshot(entry, listOf(alias, entry)).record.content)
    }

    @Test fun nonTwitterCurrentUsesResolvedRecordType() {
        val entry = record("zhihu_comment_1")
        val latest = entry.copy(recordType = "answer", content = "resolved", captureTime = 2)
        assertEquals(latest.toDetailRecord(), snapshot(entry, listOf(latest)).record)
    }

    @Test fun ancestorTraversalIsBounded() {
        val chain = (0..100).map { record("zhihu_comment_$it", "zhihu_comment_${it + 1}") }
        val result = snapshot(chain.first(), chain)
        assertEquals(30, result.ancestors.size)
        assertEquals(chain[31].toDetailRecord(), result.related)
    }
}
