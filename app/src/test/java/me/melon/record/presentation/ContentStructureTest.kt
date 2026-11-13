package me.melon.record.presentation

import me.melon.record.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test

class ContentStructureTest {
    @Test fun emptyLocalCollectionIsNotProofOfAnEmptyPlatform() {
        for (platform in listOf("zhihu", "douban", "twitter", "weibo", "threads", "reddit", "future")) {
            val comment = UniversalRecord(platform = platform, recordType = "comment")
            assertTrue(contentStructure(comment).supportsLocalReplies)
            assertEquals(ReplySectionStatus.NOT_CAPTURED, replySectionPresentation(comment, 0).status)
            assertEquals(ReplySectionStatus.AVAILABLE, replySectionPresentation(comment, 1).status)
        }
    }

    @Test fun capturedZeroDiffersFromUnknownAndPositiveCounts() {
        val record = UniversalRecord(platform = "zhihu", recordType = "answer")
        assertNull(replySectionPresentation(record, 0).platformCount)
        record.extraMetadata = """{"comment_count":0}"""
        assertEquals(ReplySectionStatus.PLATFORM_EMPTY, replySectionPresentation(record, 0).status)
        record.extraMetadata = """{"comment_count":42}"""
        val missing = replySectionPresentation(record, 0)
        assertEquals(ReplySectionStatus.NOT_CAPTURED, missing.status)
        assertEquals(42L, missing.platformCount)
        assertEquals(ReplySectionStatus.AVAILABLE, replySectionPresentation(record, 3).status)
    }

    @Test fun contradictoryPaginationDoesNotClaimThePlatformIsEmpty() {
        val record = UniversalRecord(platform = "reddit", recordType = "post",
            extraMetadata = """{"comment_count":0,"comments_has_next_page":true}""")
        val section = replySectionPresentation(record, 0)
        assertEquals(true, section.hasMore)
        assertEquals(ReplySectionStatus.NOT_CAPTURED, section.status)
    }

    @Test fun unsupportedTypeStillPreservesActualCapturedReplies() {
        val record = UniversalRecord(platform = "future", recordType = "unregistered")
        assertEquals(ReplySectionStatus.UNSUPPORTED, replySectionPresentation(record, 0).status)
        assertEquals(ReplySectionStatus.AVAILABLE, replySectionPresentation(record, 2).status)
    }

    @Test fun titleAndRelationAreIndependentOfPlatform() {
        val record = UniversalRecord(platform = "future", recordType = "comment", title = "标题", parentId = "parent")
        assertEquals(ContentStructure(true, true, true), contentStructure(record))
        assertEquals(ContentStructure(false, true, false), contentStructure(record.copy(title = "", parentId = "")))
    }
}
