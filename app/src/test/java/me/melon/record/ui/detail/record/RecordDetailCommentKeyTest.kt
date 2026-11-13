package me.melon.record.ui.detail.record

import me.melon.record.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test

class RecordDetailCommentKeyTest {
    @Test fun everyPlatformUsesRecordIdentityInsteadOfPositionOrMutableContent() {
        for (platform in listOf("twitter", "weibo", "zhihu", "douban", "tieba", "reddit", "threads")) {
            val root = UniversalRecord(platform = platform, platformId = "root", recordType = "post")
            val first = UniversalRecord(platform = platform, platformId = "a", recordType = "comment",
                parentId = "root", publishTime = 1, extraMetadata = """{"like_count":9}""")
            val second = first.copy(platformId = "b", publishTime = 2, extraMetadata = """{"like_count":1}""")
            fun keys(records: List<UniversalRecord>, sort: RecordDetailCommentSort) =
                buildRecordDetailCommentRows(records, sort, root).associate { it.record.platformId to it.stableKey }
            val before = keys(listOf(first, second), RecordDetailCommentSort.DEFAULT)
            val reordered = keys(listOf(second.copy(content = "updated", id = 99), first), RecordDetailCommentSort.LATEST)
            assertEquals(platform, before, reordered)
            assertEquals(2, before.values.toSet().size)
            val inserted = keys(listOf(first.copy(platformId = "new"), first, second), RecordDetailCommentSort.LATEST)
            assertEquals(before["a"], inserted["a"])
            assertEquals(before["b"], inserted["b"])
        }
    }

    @Test fun aliasesAndReparentedBranchesKeepKeysButDifferentPlatformsDoNotCollide() {
        val comment = UniversalRecord(platform = "twitter", platformId = "twitter_comment_123", recordType = "comment")
        val before = RecordDetailCommentRow(comment, 0, missingParentId = "missing").stableKey
        val after = RecordDetailCommentRow(comment.copy(platformId = "twitter_tweet_123", parentId = "found", id = 42), 3).stableKey
        assertEquals(before, after)
        assertNotEquals(before, RecordDetailCommentRow(comment.copy(platform = "weibo"), 0).stableKey)
        assertNotEquals(
            RecordDetailCommentRow(comment.copy(platform = "a", platformId = "b:c"), 0).stableKey,
            RecordDetailCommentRow(comment.copy(platform = "a:b", platformId = "c"), 0).stableKey
        )
    }

    @Test fun localCountWordingIsTheSameAcrossPlatformsIncludingZero() {
        for (platform in listOf("twitter", "weibo", "zhihu", "douban", "tieba", "reddit", "threads")) {
            assertEquals("本地已记录 0 条", recordDetailCommentCountLabel(platform, 0))
            assertEquals("本地已记录 8 条", recordDetailCommentCountLabel(platform, 8))
        }
    }
}
