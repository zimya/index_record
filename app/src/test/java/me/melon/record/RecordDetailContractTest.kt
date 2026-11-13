package me.melon.record

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.melon.record.data.model.*
import me.melon.record.data.repository.*
import me.melon.record.presentation.StatisticKind
import me.melon.record.ui.detail.record.RecordDetailCommentSort
import me.melon.record.ui.detail.record.buildRecordDetailCommentRows
import me.melon.record.viewmodel.DetailLoadState
import me.melon.record.viewmodel.recordDetailStates
import org.junit.Assert.*
import org.junit.Test

class RecordDetailContractTest {
    private fun selectRecordComments(record: UniversalRecord, candidates: List<UniversalRecord>): List<UniversalRecord> =
        collectRecordComments(record) { parents -> candidates.filter { it.replyTarget() in parents } }

    private fun record(platform: String = "twitter", id: String = "twitter_tweet_1", type: String = "tweet", parent: String = "") =
        UniversalRecord(platform = platform, platformId = id, recordType = type, parentId = parent, captureTime = 1)

    @Test fun captureRolesShareIdentityButNotOtherPlatforms() {
        val tweet = RecordIdentity.of("twitter", "twitter_tweet_123")
        assertEquals(tweet, RecordIdentity.of("twitter", "twitter_comment_123"))
        assertEquals(setOf("twitter_tweet_123", "twitter_comment_123"), tweet.storageIds())
        assertNotEquals(tweet, RecordIdentity.of("weibo", "twitter_tweet_123"))
    }

    @Test fun identityResolutionIsLatestAndIndependentOfQueryOrder() {
        val tweet = record().apply { content = "old" }
        val comment = record(id = "twitter_comment_1", type = "comment").apply { captureTime = 2; content = "new" }
        assertEquals(comment, resolveRecordIdentity(listOf(tweet, comment)))
        assertEquals(comment, resolveRecordIdentity(listOf(comment, tweet)))
        comment.captureTime = 1
        assertEquals(resolveRecordIdentity(listOf(tweet, comment)), resolveRecordIdentity(listOf(comment, tweet)))
    }

    @Test fun relationshipKindsAreNotInterchangeable() {
        assertTrue(record("zhihu", "a", "answer", "zhihu_question_1").directRelation() is RecordRelation.AnswerToQuestion)
        assertTrue(record("douban", "a", "review", "douban_movie_1").directRelation() is RecordRelation.BelongsToSubject)
        assertTrue(record("douban", "a", "comment", "douban_review_1").directRelation() is RecordRelation.ReplyTo)
        assertTrue(record("tieba", "a", "floor", "tieba_thread_1").directRelation() is RecordRelation.FloorInThread)
        assertNull(record().directRelation())
    }

    @Test fun aliasRelationshipsWorkAtEveryCommentDepthAndExcludeCyclesAndForeignPlatforms() {
        val root = record()
        val a = record(id = "twitter_comment_2", type = "comment", parent = "twitter_comment_1")
        val b = record(id = "twitter_comment_3", type = "comment", parent = "twitter_tweet_2")
        val self = record(id = "twitter_comment_1", type = "comment", parent = b.platformId)
        val foreign = record("weibo", "foreign", "comment", root.platformId)
        val selected = selectRecordComments(root, listOf(b, self, foreign, a, a.copy()))
        assertEquals(listOf(a, b), selected)
        val rows = buildRecordDetailCommentRows(selected, RecordDetailCommentSort.DEFAULT, root)
        assertEquals(listOf(a, b), rows.map { it.record })
        assertEquals(listOf(0, 1), rows.map { it.depth })
    }

    @Test fun snapshotsRefreshAllRegionsAndRemoveMissingRelations() {
        val seed = record("zhihu", "zhihu_answer_1", "answer", "zhihu_question_1")
        val question = record("zhihu", "zhihu_question_1", "question")
        val db = mutableListOf(seed, question)
        fun snapshot() = assembleRecordDetailSnapshot(seed, { identity -> db.firstOrNull { it.identity == identity } },
            { current -> selectRecordComments(current, db) })
        val first = snapshot()
        assertEquals(question.platformId, first.related?.record?.platformId)
        seed.content = "updated"
        seed.extraMetadata = """{"voteup_count":7}"""
        val comment = record("zhihu", "comment", "comment", seed.platformId)
        db.add(comment)
        db.remove(question)
        val next = snapshot()
        assertEquals("updated", next.record.content)
        assertEquals(seed.extraMetadata, next.record.extraMetadata)
        assertEquals(listOf(comment.platformId), next.comments.map { it.platformId })
        assertNull(next.related?.record)
        assertEquals("", first.record.content)
        assertTrue(first.comments.isEmpty())
    }

    @Test fun newestAliasSuppliesContentWithoutChangingEntryRoutingRole() {
        val seed = record()
        val latest = record(id = "twitter_comment_1", type = "comment").apply {
            content = "latest"; captureTime = 2; extraMetadata = """{"like_count":9}"""
        }
        val snapshot = assembleRecordDetailSnapshot(seed, { resolveRecordIdentity(listOf(seed, latest)) }, { emptyList() })
        assertEquals(seed.platformId, snapshot.record.platformId)
        assertEquals(seed.recordType, snapshot.record.recordType)
        assertEquals("latest", snapshot.record.content)
        assertEquals(latest.extraMetadata, snapshot.record.extraMetadata)
    }

    @Test fun doubanMissingSubjectIsAnExplicitReferenceNotCapturedData() {
        val seed = record("douban", "review", "review", "douban_movie_1").apply {
            extraMetadata = """{"subject_id":"1","subject_title":"Movie"}"""
        }
        val snapshot = assembleRecordDetailSnapshot(seed, { if (it == seed.identity) seed else null }, { emptyList() })
        assertTrue(snapshot.related?.relation is RecordRelation.BelongsToSubject)
        assertTrue(snapshot.related?.isReference == true)
        assertEquals("Movie", snapshot.related?.record?.title)
    }

    @Test fun snapshotsAndNavigationDoNotExposeMutableEntitiesOrCollections() {
        val entity = record()
        val comment = record(id = "twitter_comment_2", type = "comment", parent = entity.platformId)
        val input = mutableListOf(comment.toDetailRecord())
        val snapshot = RecordDetailSnapshot(entity.toDetailRecord(), input)
        input.clear()
        entity.content = "mutated"
        snapshot.record.toEntity().content = "navigation mutation"
        assertEquals("", snapshot.record.content)
        assertEquals(1, snapshot.comments.size)
        try {
            (snapshot.comments as MutableList).clear()
            fail("collection must reject mutation")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun completeStateRecoversAfterReadFailureAndUpdatesStatisticsTogether() = runBlocking {
        val seed = record()
        val first = RecordDetailSnapshot(seed.toDetailRecord())
        val updated = RecordDetailSnapshot(seed.copy(content = "new", extraMetadata = """{"like_count":8}""").toDetailRecord())
        val states = recordDetailStates(seed, flowOf(Result.success(first), Result.failure(IllegalStateException()), Result.success(updated))).toList()
        assertEquals(listOf(DetailLoadState.Ready, DetailLoadState.Failed("无法读取本地记录，请返回后重试"), DetailLoadState.Ready), states.map { it.loadState })
        assertSame(first, states[1].snapshot)
        assertEquals("new", states.last().snapshot.record.content)
        assertTrue(states.last().statistics.any { it.kind == StatisticKind.LIKE && it.text == "8" })
    }

    @Test fun newerTweetWithoutRelationshipDoesNotEraseCapturedReplyEdge() {
        val parent = record(id = "twitter_tweet_9")
        val reply = record(id = "twitter_comment_1", type = "comment", parent = parent.platformId)
        val tweet = record().apply { captureTime = 2; content = "updated by tweet" }
        val resolved = resolveRecordIdentity(listOf(reply, tweet))!!
        assertEquals(parent.platformId, resolved.parentId)
        val snapshot = assembleCommentDetailSnapshot(reply.toDetailRecord()) { identity ->
            when (identity) {
                reply.identity -> resolved.toDetailRecord()
                parent.identity -> parent.toDetailRecord()
                else -> null
            }
        }
        assertEquals("updated by tweet", snapshot.record.content)
        assertEquals(parent.platformId, snapshot.related?.platformId)
    }

    @Test fun commentRowsUseNewestValuesButRetainMembershipAndReplyRole() {
        val root = record()
        val child = record(id = "twitter_comment_2", type = "comment", parent = root.platformId).apply {
            id = 12; content = "old"; extraMetadata = """{"like_count":1}"""
        }
        val tweet = record(id = "twitter_tweet_2").apply {
            id = 13; captureTime = 2; content = "new"; extraMetadata = """{"like_count":8}"""
        }
        val refreshed = refreshCaptureRoles(listOf(child), listOf(child, tweet))
        val selected = selectRecordComments(root, refreshed)
        assertEquals(1, selected.size)
        assertEquals(child.id, selected.single().id)
        assertEquals(child.platformId, selected.single().platformId)
        assertEquals("comment", selected.single().recordType)
        assertEquals(root.platformId, selected.single().parentId)
        assertEquals("new", selected.single().content)
        assertEquals(tweet.extraMetadata, selected.single().extraMetadata)
        assertEquals("old", child.content)
    }

    @Test fun cancellationDoesNotBecomeAReadError() = runBlocking {
        try {
            recordDetailStates(record(), flowOf(Result.failure(CancellationException("cancel")))).toList()
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
