package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test

class ContentStructureTest {
    @Test fun emptyLocalCollectionIsNotProofOfAnEmptyPlatform() {
        for (platform in listOf("zhihu", "douban", "twitter", "weibo", "threads", "reddit", "future")) {
            val comment = UniversalRecord(platform = platform, recordType = "comment")
            assertTrue(contentStructure(comment).supportsLocalReplies)
            assertEquals("本地尚未采集到回复", replySectionPresentation(comment, 0).message)
            assertNull(replySectionPresentation(comment, 1).message)
        }
    }

    @Test fun capturedZeroDiffersFromUnknownAndPositiveCounts() {
        val record = UniversalRecord(platform = "zhihu", recordType = "answer")
        assertNull(replySectionPresentation(record, 0).platformCount)
        record.extraMetadata = """{"comment_count":0}"""
        assertEquals("平台报告回复数为 0，本地尚无回复记录", replySectionPresentation(record, 0).message)
        record.extraMetadata = """{"comment_count":42}"""
        val missing = replySectionPresentation(record, 0)
        assertEquals("本地尚未采集到回复", missing.message)
        assertEquals(42L, missing.platformCount)
        assertNull(replySectionPresentation(record, 3).message)
    }

    @Test fun contradictoryPaginationDoesNotClaimThePlatformIsEmpty() {
        val record = UniversalRecord(platform = "reddit", recordType = "post",
            extraMetadata = """{"comment_count":0,"comments_has_next_page":true}""")
        val section = replySectionPresentation(record, 0)
        assertEquals(true, section.hasMore)
        assertEquals("本地尚未采集到回复", section.message)
    }

    @Test fun unsupportedTypeStillPreservesActualCapturedReplies() {
        val record = UniversalRecord(platform = "future", recordType = "unregistered")
        assertEquals("当前内容类型暂不支持展示本地回复", replySectionPresentation(record, 0).message)
        assertNull(replySectionPresentation(record, 2).message)
    }

    @Test fun availableRepliesHaveNoRedundantNoticeButKeepPagination() {
        val record = UniversalRecord(platform = "reddit", recordType = "post",
            extraMetadata = """{"comment_count":42,"comments_has_next_page":true}""")
        val section = replySectionPresentation(record, 3)

        assertNull(section.message)
        assertEquals(true, section.hasMore)
        assertEquals(42L, section.platformCount)
        assertNotNull(replySectionPresentation(record, 0).message)
    }

    @Test fun titleAndRelationAreIndependentOfPlatform() {
        val record = UniversalRecord(platform = "future", recordType = "comment", title = "标题", parentId = "parent")
        assertEquals(ContentStructure(true, true, true), contentStructure(record))
        assertEquals(ContentStructure(false, true, false), contentStructure(record.copy(title = "", parentId = "")))
    }
}
