package com.melonhu.indexrecord.parser.weibo

import com.melonhu.indexrecord.settings.WeiboRecordMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeiboRecordModeTest {
    @Test fun defaultsToRead() {
        listOf(null, "unknown", 0, true).forEach {
            assertEquals(WeiboRecordMode.READ, WeiboRecordMode.fromStoredValue(it))
        }
        assertEquals(WeiboRecordMode.PUSH, WeiboRecordMode.fromStoredValue("PUSH"))
        assertEquals(WeiboRecordMode.READ, WeiboRecordMode.fromStoredValue("READ"))
    }

    @Test fun readProcessesDetailAndComments() {
        listOf("statuses/container_detail", "statuses/container_detail_comment",
            "comments/build_comments", "statuses/show").forEach {
            assertTrue(it, WeiboRecordMode.READ.shouldProcess("https://api.weibo.cn/2/$it?id=123"))
        }
        assertTrue(WeiboRecordMode.READ.shouldProcess("https://m.weibo.cn/statuses/show?id=123"))
    }

    @Test fun readExcludesFeedsAndPrefetches() {
        listOf("statuses/friends_timeline", "statuses/container_positive", "statuses/longtext_show_batch",
            "statuses/longtext", "cardlist", "container/getIndex").forEach {
            val url = "https://api.weibo.cn/2/$it?id=123"
            assertFalse(it, WeiboRecordMode.READ.shouldProcess(url))
            assertTrue(it, WeiboRecordMode.PUSH.shouldProcess(url))
        }
        assertFalse(WeiboRecordMode.READ.shouldProcess(""))
        assertFalse(WeiboRecordMode.READ.shouldProcess("https://api.weibo.cn/2/cardlist?next=/2/statuses/show"))
    }
}
