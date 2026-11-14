package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.RecordIdentity
import com.melonhu.indexrecord.data.model.RecordRelation
import com.melonhu.indexrecord.data.model.RelatedTargetState
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.identity
import com.melonhu.indexrecord.data.model.toDetailRecord
import com.melonhu.indexrecord.data.repository.RelatedContent
import com.melonhu.indexrecord.data.repository.assembleRecordDetailSnapshot
import org.junit.Assert.*
import org.junit.Test

class RelatedContentContractTest {
    @Test fun relationLabelsDependOnExplicitEdgeNotTargetTypeOrPlatform() {
        for (platform in listOf("zhihu", "douban", "twitter", "reddit", "threads", "weibo", "tieba")) {
            for (id in listOf("question_1", "post_1", "comment_1", "unknown")) {
                val target = RecordIdentity.of(platform, id)
                assertEquals("所属问题", relatedRecordLabel(RecordRelation.AnswerToQuestion(target)))
                assertEquals("回复对象", relatedRecordLabel(RecordRelation.ReplyTo(target)))
                assertEquals("所属条目", relatedRecordLabel(RecordRelation.BelongsToSubject(target)))
                assertEquals("所属主帖", relatedRecordLabel(RecordRelation.FloorInThread(target)))
                assertEquals("引用内容", relatedRecordLabel(RecordRelation.ReferencesContent(target)))
            }
        }
    }

    @Test fun targetStatesHaveDistinctLabelsAndMissingCopyIsPlatformIndependent() {
        assertEquals("已记录", relatedTargetStateLabel(RelatedTargetState.RECORDED))
        assertEquals("仅引用", relatedTargetStateLabel(RelatedTargetState.REFERENCE_ONLY))
        assertEquals("本地缺失", relatedTargetStateLabel(RelatedTargetState.MISSING))
        assertNull(relatedTargetNotice(RelatedTargetState.RECORDED))
        assertEquals("仅有引用信息，尚未记录目标正文", relatedTargetNotice(RelatedTargetState.REFERENCE_ONLY))
        assertEquals("本地尚未记录关联内容", relatedTargetNotice(RelatedTargetState.MISSING))
        for (id in listOf("reddit_comment_1", "reddit_post_1", "weibo_1", "unknown")) {
            assertEquals("本地尚未记录关联内容：$id", missingRelatedContentNotice(id))
        }
    }

    @Test fun absentTargetsAreRetainedForEveryExplicitRelationWithoutFabrication() {
        val seeds = listOf(
            record("zhihu", "answer", "zhihu_question_1"),
            record("twitter", "comment", "twitter_tweet_1"),
            record("reddit", "comment", "reddit_post_1"),
            record("threads", "comment", "threads_1"),
            record("weibo", "weibo", "weibo_1"),
            record("tieba", "floor", "tieba_thread_1")
        )
        for (seed in seeds) {
            val snapshot = assembleRecordDetailSnapshot(seed, { if (it == seed.identity) seed else null }, { emptyList() })
            val related = requireNotNull(snapshot.related)
            assertEquals(RelatedTargetState.MISSING, related.targetState)
            assertNull(related.record)
            assertFalse(related.isReference)
        }
    }

    @Test fun subjectReferenceBecomesRecordedWhenRealCaptureArrives() {
        val seed = record("douban", "review", "douban_movie_1").copy(
            extraMetadata = """{"subject_id":"1","subject_title":"Referenced movie"}"""
        )
        var target: UniversalRecord? = null
        fun snapshot() = assembleRecordDetailSnapshot(seed, {
            if (it == seed.identity) seed else target
        }, { emptyList() })
        val reference = requireNotNull(snapshot().related)
        assertEquals(RelatedTargetState.REFERENCE_ONLY, reference.targetState)
        assertEquals("Referenced movie", reference.record?.title)
        target = UniversalRecord(platform = "douban", platformId = seed.parentId, recordType = "subject", title = "Captured movie")
        val captured = requireNotNull(snapshot().related)
        assertEquals(RelatedTargetState.RECORDED, captured.targetState)
        assertEquals("Captured movie", captured.record?.title)
        assertFalse(captured.isReference)
        assertEquals(RelatedTargetState.REFERENCE_ONLY, reference.targetState)
    }

    @Test fun statusDoesNotDependOnTargetIdOrCapturedLookingTitle() {
        val target = UniversalRecord(platform = "douban", platformId = "douban_movie_1", title = "Captured-looking title").toDetailRecord()
        val relation = RecordRelation.BelongsToSubject(target.identity)
        assertEquals(RelatedTargetState.RECORDED, RelatedContent(relation, target).targetState)
        assertEquals(RelatedTargetState.REFERENCE_ONLY, RelatedContent(relation, target, isReference = true).targetState)
        assertEquals(RelatedTargetState.MISSING, RelatedContent(relation, null).targetState)
    }

    private fun record(platform: String, type: String, parent: String) = UniversalRecord(
        platform = platform, platformId = "${platform}_source", recordType = type, parentId = parent
    )
}
