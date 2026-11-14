package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.TiebaMetadata
import org.junit.Assert.*
import org.junit.Test

class TiebaMetadataTest {
    private fun metadata(json: String) = TiebaMetadata(json)

    @Test
    fun namedFieldsReadCanonicalMetadata() {
        val meta = metadata("""{"thread_id":"42","thread_title":"标题","forum_name":"测试","floor":2,"sub_floor":"3","reply_to_name":"甲","reply_to_id":7,"agree_count":0,"reply_count":"9","favlists_count":4,"bookmark_count":8}""")
        assertEquals("42", meta.threadId)
        assertEquals("标题", meta.threadTitle)
        assertEquals("测试", meta.forumName)
        assertEquals(2L, meta.floorNumber)
        assertEquals(3L, meta.subFloorNumber)
        assertEquals("甲", meta.replyToName)
        assertEquals("7", meta.replyToId)
        assertEquals(0L, meta.agreeCount)
        assertEquals(9L, meta.replyCount)
        assertEquals(4L, meta.favoriteCount)
    }

    @Test
    fun malformedAndMissingValuesRemainUnknown() {
        for (json in listOf("bad json", "[]", "null", "{}", """{"thread_id":{},"floor":-1,"sub_floor":"bad","agree_count":null,"reply_count":{},"favlists_count":-3}""")) {
            val meta = metadata(json)
            assertEquals("", meta.threadId)
            assertNull(meta.floorNumber)
            assertNull(meta.subFloorNumber)
            assertNull(meta.agreeCount)
            assertNull(meta.replyCount)
            assertNull(meta.favoriteCount)
        }
    }

    @Test
    fun favoriteFallbackPreservesZeroAndInvalidPrimarySemantics() {
        assertEquals(0L, metadata("""{"favlists_count":0,"bookmark_count":8}""").favoriteCount)
        assertEquals(8L, metadata("""{"favlists_count":-1,"bookmark_count":8}""").favoriteCount)
        assertEquals(8L, metadata("""{"bookmark_count":"8"}""").favoriteCount)
    }
}
