package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.RecordContent
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.bodyPlainText
import com.melonhu.indexrecord.data.model.toDetailRecord
import com.melonhu.indexrecord.ui.components.recordTypeLabel
import com.melonhu.indexrecord.ui.detail.record.RecordDetailCommentSort
import com.melonhu.indexrecord.ui.detail.record.buildRecordDetailCommentRows
import com.melonhu.indexrecord.ui.detail.record.recordDetailTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyRecordPresentationTest {
    private fun presentation(record: RecordContent): List<Any> = listOf(
        recordHeaderPresentation(record),
        recordBodyPresentation(record),
        recordDetailTitle(record),
        recordTypeLabel(record),
        recordDisplayTitle(record),
        recordContext(record),
        recordPreviewText(record),
        recordListPresentation(record, "visible"),
        commentPlainText(record),
        commentStatistics(record),
        contentStructure(record),
        replySectionPresentation(record, 0),
        recordStatistics(record, StatisticsSurface.LIST),
        recordStatistics(record, StatisticsSurface.DETAIL),
        record.bodyPlainText()
    )

    @Test
    fun snapshotsDirectlyProduceTheSamePresentationAcrossPlatformsRolesAndFormats() {
        val metadata = """{"score":-3,"voteup_count":12,"like_count":0,"agree_count":4,"comment_count":0,"reply_count":2,"child_comment_count":1,"title_image":"cover","author_headline":"headline","star_count":4,"thread_title":"thread","forum_name":"forum","floor_number":2,"subreddit":"community","flair":"flair","ip_info":"location"}"""
        for (platform in listOf("zhihu", "douban", "twitter", "weibo", "threads", "reddit", "tieba")) {
            for (type in listOf("post", "answer", "comment", "subject", "floor")) {
                for (format in RecordBodyFormat.entries) {
                    val entity = UniversalRecord(
                        platform = platform, recordType = type, platformId = "id", parentId = "parent",
                        title = "title", content = "visible **body** <b>text</b>",
                        authorName = "author", publishTime = 1700000000000L, captureTime = 1700000001000L,
                        extraMetadata = metadata, contentFormat = format.storageValue
                    )
                    val snapshot: DetailRecord = entity.toDetailRecord()
                    val before = snapshot.copy()
                    assertEquals("$platform/$type/$format", presentation(entity), presentation(snapshot))
                    entity.content = "changed storage entity"
                    entity.extraMetadata = "{}"
                    assertEquals(before, snapshot)
                    assertEquals(presentation(before), presentation(snapshot))
                }
            }
        }
    }

    @Test
    fun snapshotCommentTreesRetainOriginalValuesAndAllExistingTreeSemantics() {
        val post = DetailRecord(platform = "reddit", recordType = "post", platformId = "reddit_post_root")
        fun comment(id: String, parent: String, score: String, time: Long) = DetailRecord(
            platform = "reddit", recordType = "comment", platformId = "reddit_comment_$id", parentId = parent,
            content = "reply $id", publishTime = time, extraMetadata = score
        )
        val parent = comment("a", post.platformId, """{"score":0}""", 1)
        val child = comment("b", parent.platformId, """{"score":-2}""", 2)
        val orphan = comment("c", "reddit_comment_missing", "{}", 3)
        val cycle = comment("d", "reddit_comment_d", "{}", 4)
        // These candidates intentionally have no post_id: rendering must not re-filter repository output.
        val snapshots = listOf(child, orphan, parent, cycle, parent)
        val before = snapshots.map { it.copy() }
        for (sort in RecordDetailCommentSort.entries) {
            val rows = buildRecordDetailCommentRows(snapshots, sort, post)
            assertEquals(listOf(parent, child, orphan, cycle), rows.map { it.record })
            assertEquals(listOf(0, 1, 0, 0), rows.map { it.depth })
            assertSame(parent, rows[0].record)
            assertSame(child, rows[1].record)
            assertEquals("reddit_comment_missing", rows[2].missingParentId)
            assertTrue(rows.last().hasInvalidParentChain)
            rows.forEach { presentation(it.record) }
            assertEquals(before, snapshots)
        }
    }
}
