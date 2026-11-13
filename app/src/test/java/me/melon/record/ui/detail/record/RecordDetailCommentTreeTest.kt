package me.melon.record.ui.detail.record

import com.google.gson.JsonObject
import me.melon.record.data.model.UniversalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordDetailCommentTreeTest {
    private val post = UniversalRecord(platform = "reddit", recordType = "post", platformId = "reddit_post_abcd")

    private fun commentId(id: String): String = "reddit_comment_$id"

    private fun comment(
        id: String,
        parentId: String = post.platformId,
        score: Long? = null,
        time: Long = 0,
        postId: String? = post.platformId,
        hidden: Boolean = false
    ): UniversalRecord {
        val metadata = JsonObject().apply {
            score?.let { addProperty("score", it) }
            postId?.let { addProperty("post_id", it) }
            if (hidden) addProperty("is_score_hidden", true)
        }
        return UniversalRecord(
            platform = "reddit", recordType = "comment", platformId = commentId(id), parentId = parentId,
            publishTime = time, content = "**$id**", extraMetadata = metadata.toString()
        )
    }

    @Test
    fun allEightCapturedLevelsKeepTheirDirectParentRelationship() {
        val comments = (0..7).map { depth ->
            comment("c$depth", if (depth == 0) post.platformId else commentId("c${depth - 1}"), score = -depth.toLong())
        }
        val originalParents = comments.map { it.parentId }
        val rows = buildRecordDetailCommentRows(comments.reversed(), RecordDetailCommentSort.DEFAULT, post)
        assertEquals(comments.map { it.platformId }, rows.map { it.record.platformId })
        assertEquals((0..7).toList(), rows.map { it.depth })
        assertEquals(originalParents, comments.map { it.parentId })
        assertTrue(rows.all { it.missingParentId == null && !it.hasInvalidParentChain })
    }

    @Test
    fun defaultSortingUsesSignedScoresAndTreatsHiddenOrMissingScoresAsUnknown() {
        val high = comment("high", score = 2)
        val zero = comment("zero", score = 0)
        val negative = comment("neg", score = -3).apply {
            extraMetadata = """{"score":-3,"like_count":9000,"post_id":"${post.platformId}"}"""
        }
        val minimum = comment("min", score = Long.MIN_VALUE)
        val hidden = comment("hidden", score = 999, time = 110, hidden = true)
        val unknown = comment("unknown", time = 100)
        val rows = buildRecordDetailCommentRows(
            listOf(unknown, hidden, minimum, negative, zero, high), RecordDetailCommentSort.DEFAULT, post
        )
        assertEquals(listOf(high, zero, negative, minimum, hidden, unknown), rows.map { it.record })
    }

    @Test
    fun sortingEachSiblingGroupNeverFlattensTheTree() {
        val a = comment("a", score = 5, time = 5)
        val b = comment("b", score = 2, time = 200)
        val high = comment("hi", a.platformId, score = 3, time = 6)
        val low = comment("lo", a.platformId, score = -2, time = 7)
        val comments = listOf(a, low, b, high)
        val defaultRows = buildRecordDetailCommentRows(comments, RecordDetailCommentSort.DEFAULT, post)
        assertEquals(listOf(a, high, low, b), defaultRows.map { it.record })
        assertEquals(listOf(0, 1, 1, 0), defaultRows.map { it.depth })
        val latestRows = buildRecordDetailCommentRows(comments, RecordDetailCommentSort.LATEST, post)
        assertEquals(listOf(b, a, low, high), latestRows.map { it.record })
    }

    @Test
    fun missingParentBranchesContainOnlyRealRepliesForTheCurrentPost() {
        val connected = comment("a", score = 0)
        val orphan = comment("x", commentId("missing"), score = 10)
        val child = comment("y", orphan.platformId, score = 7)
        val foreign = comment("z", "reddit_post_other", postId = "reddit_post_other")
        val rows = buildRecordDetailCommentRows(listOf(child, foreign, orphan, connected), RecordDetailCommentSort.DEFAULT, post)
        assertEquals(listOf(connected, orphan, child), rows.map { it.record })
        assertEquals(listOf(0, 0, 1), rows.map { it.depth })
        assertEquals(commentId("missing"), rows[1].missingParentId)
        assertNull(rows[2].missingParentId)
        assertEquals(commentId("missing"), orphan.parentId)
        assertEquals(orphan.platformId, child.parentId)
    }

    @Test
    fun capturedAncestorsWithoutPostMetadataAreNotFalselyMarkedMissing() {
        val parent = comment("b", commentId("missing"), postId = null)
        val child = comment("c", parent.platformId)
        val rows = buildRecordDetailCommentRows(listOf(child, parent), RecordDetailCommentSort.DEFAULT, post)
        assertEquals(listOf(parent, child), rows.map { it.record })
        assertEquals(commentId("missing"), rows[0].missingParentId)
        assertNull(rows[1].missingParentId)
    }

    @Test
    fun directParentLinksWorkWithoutRootMetadataAndUnidentifiedOrphansAreNotGuessed() {
        val parent = comment("a", postId = null)
        val child = comment("b", parent.platformId, postId = null)
        val unidentified = comment("c", commentId("missing"), postId = null).apply {
            extraMetadata = """{"child_count":100,"comment_count":200}"""
        }
        val rows = buildRecordDetailCommentRows(listOf(child, unidentified, parent), RecordDetailCommentSort.DEFAULT, post)
        assertEquals(listOf(parent, child), rows.map { it.record })
        assertEquals(listOf(0, 1), rows.map { it.depth })
        assertTrue(rows.all { it.missingParentId == null })
    }

    @Test
    fun laterParentCaptureReattachesAnOrphanWithoutRewritingItsParentId() {
        val parent = comment("a")
        val child = comment("b", parent.platformId)
        val before = buildRecordDetailCommentRows(listOf(child), RecordDetailCommentSort.DEFAULT, post)
        assertEquals(parent.platformId, before.single().missingParentId)
        val after = buildRecordDetailCommentRows(listOf(child, parent), RecordDetailCommentSort.DEFAULT, post)
        assertEquals(listOf(parent, child), after.map { it.record })
        assertEquals(listOf(0, 1), after.map { it.depth })
        assertTrue(after.all { it.missingParentId == null })
        assertEquals(parent.platformId, child.parentId)
    }

    @Test
    fun replyDetailsShowOnlyTheirOwnDescendants() {
        val current = comment("a")
        val child = comment("b", current.platformId)
        val grandchild = comment("c", child.platformId)
        val sibling = comment("d")
        val unrelatedOrphan = comment("x", commentId("missing"))
        val rows = buildRecordDetailCommentRows(
            listOf(current, child, grandchild, sibling, unrelatedOrphan), RecordDetailCommentSort.DEFAULT, current
        )
        assertEquals(listOf(child, grandchild), rows.map { it.record })
        assertEquals(listOf(0, 1), rows.map { it.depth })
        assertTrue(rows.all { it.missingParentId == null })
    }

    @Test
    fun duplicateAndCyclicRecordsCannotLoopOrProduceFakeParents() {
        val a = comment("a", commentId("b"))
        val b = comment("b", commentId("a"))
        val rows = buildRecordDetailCommentRows(listOf(a, b, a), RecordDetailCommentSort.DEFAULT, post)
        assertEquals(setOf(a.platformId, b.platformId), rows.map { it.record.platformId }.toSet())
        assertEquals(2, rows.size)
        assertTrue(rows.first().hasInvalidParentChain)
        assertTrue(rows.all { it.missingParentId == null })
    }

    @Test
    fun selfParentCyclesStillKeepTheirRealDescendants() {
        val self = comment("a", commentId("a"))
        val child = comment("b", self.platformId)
        val rows = buildRecordDetailCommentRows(listOf(child, self), RecordDetailCommentSort.DEFAULT, post)
        assertEquals(listOf(self, child), rows.map { it.record })
        assertEquals(listOf(0, 1), rows.map { it.depth })
        assertTrue(rows.first().hasInvalidParentChain)
        assertNull(rows.first().missingParentId)
    }

    @Test
    fun missingAndCyclicParentsArePreservedForEveryPlatform() {
        for (platform in listOf("zhihu", "douban", "twitter", "weibo", "threads", "future")) {
            val root = UniversalRecord(platform = platform, recordType = "post", platformId = "root")
            fun reply(id: String, parent: String) = UniversalRecord(
                platform = platform, recordType = "comment", platformId = id, parentId = parent
            )
            val orphan = reply("orphan", "missing")
            val child = reply("child", "orphan")
            val cyclic = reply("cycle", "cycle")
            val before = buildRecordDetailCommentRows(listOf(child, orphan, cyclic, orphan), RecordDetailCommentSort.DEFAULT, root)
            assertEquals(3, before.size)
            assertEquals("missing", before.first { it.record == orphan }.missingParentId)
            assertTrue(before.first { it.record == cyclic }.hasInvalidParentChain)
            assertEquals(1, before.first { it.record == child }.depth)
            val parent = reply("missing", "root")
            val after = buildRecordDetailCommentRows(listOf(child, orphan, parent), RecordDetailCommentSort.DEFAULT, root)
            assertEquals(listOf(parent, orphan, child), after.map { it.record })
            assertTrue(after.all { it.missingParentId == null && !it.hasInvalidParentChain })
        }
    }

    @Test
    fun deepTreesAreNotLimitedByRecursiveTraversal() {
        val comments = (0 until 1500).map { index ->
            comment("d${index.toString(36)}", if (index == 0) post.platformId else commentId("d${(index - 1).toString(36)}"))
        }
        val rows = buildRecordDetailCommentRows(comments, RecordDetailCommentSort.DEFAULT, post)
        assertEquals(1500, rows.size)
        assertEquals(1499, rows.last().depth)
    }

    @Test
    fun xAliasesUseTheSameInteractionSortingAtEveryDepth() {
        val tweet = UniversalRecord(platform = "twitter", recordType = "tweet", platformId = "twitter_tweet_100")
        fun x(id: String, parent: String, likes: Long, time: Long): UniversalRecord = UniversalRecord(
            platform = "twitter", recordType = "comment", platformId = "twitter_comment_$id", parentId = parent,
            publishTime = time, extraMetadata = """{"like_count":$likes,"score":999,"is_score_hidden":true}"""
        )
        val a = x("a", tweet.platformId, 1, 10)
        val b = x("b", "twitter_comment_100", 9, 20)
        val late = x("c", a.platformId, 100, 5)
        val early = x("d", a.platformId, 0, 2)
        val rows = buildRecordDetailCommentRows(listOf(a, late, b, early), RecordDetailCommentSort.DEFAULT, tweet)
        assertEquals(listOf(b, a, late, early), rows.map { it.record })
        assertFalse(rows.any { it.hasInvalidParentChain || it.missingParentId != null })
    }
}
