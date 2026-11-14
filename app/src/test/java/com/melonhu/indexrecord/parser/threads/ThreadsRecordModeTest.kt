package com.melonhu.indexrecord.parser.threads

import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.settings.ThreadsRecordMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadsRecordModeTest {
    @Test fun defaultsToReadAndRestoresStoredMode() {
        listOf(null, "unknown", "", 0, true).forEach {
            assertEquals(ThreadsRecordMode.READ, ThreadsRecordMode.fromStoredValue(it))
        }
        assertEquals(ThreadsRecordMode.READ, ThreadsRecordMode.fromStoredValue("READ"))
        assertEquals(ThreadsRecordMode.PUSH, ThreadsRecordMode.fromStoredValue("PUSH"))
    }

    @Test fun readProcessesDetailAndReplyPagination() {
        listOf(
            "https://i.instagram.com/api/v1/text_feed/8100000000000000001/replies_streaming/",
            "https://i.instagram.com/api/v1/text_feed/8100000000000000001/replies_streaming/?paging_token=next&sort_order=top",
            "https://i.instagram.com/api/v1/text_feed/8100000000000000002/replies_streaming",
            "https://i.instagram.com/api/v1/feed/text_post_app_replies/?media_id=8100000000000000001"
        ).forEach { assertTrue(it, ThreadsRecordMode.READ.shouldProcess(it)) }
    }

    @Test fun readExcludesTimelineProfilesPrefetchesAndUnknownGraphql() {
        listOf(
            "https://i.instagram.com/api/v1/feed/text_post_app_timeline/",
            "https://i.instagram.com/api/v1/text_feed/90001/profile/",
            "https://i.instagram.com/api/v1/text_feed/text_app_notifications/",
            "https://i.instagram.com/api/v1/media/info/",
            "https://i.instagram.com/api/v1/media/8100000000000000001/info/",
            "https://i.instagram.com/graphql/query",
            "https://i.instagram.com/api/v1/feed/text_post_app_timeline/?next=/api/v1/text_feed/1/replies_streaming/",
            "https://i.instagram.com/api/v1/text_feed/user/replies_streaming/",
            "https://i.instagram.com/api/v1/text_feed/1/replies_streaming/prefetch/",
            "", "not a valid URI"
        ).forEach { assertFalse(it, ThreadsRecordMode.READ.shouldProcess(it)) }
    }

    @Test fun pushIncludesTimelineAndDetails() {
        listOf(
            "https://i.instagram.com/api/v1/feed/text_post_app_timeline/",
            "https://i.instagram.com/api/v1/text_feed/8100000000000000001/replies_streaming/"
        ).forEach { assertTrue(it, ThreadsRecordMode.PUSH.shouldProcess(it)) }
    }

    @Test fun readKeepsContainingPostAndRepliesButSkipsTimelineFixture() {
        fun records(name: String, url: String, mode: ThreadsRecordMode) =
            if (mode.shouldProcess(url)) {
                ThreadsParser.parse(RawData(
                    hostApp = "com.instagram.barcelona", url = url,
                    json = javaClass.getResource("/threads/$name")!!.readText()
                ), url)
            } else emptyList()

        val detailUrl = "https://i.instagram.com/api/v1/text_feed/8100000000000000001/replies_streaming/"
        val detail = records("replies_streaming_initial.ndjson", detailUrl, ThreadsRecordMode.READ)
        assertTrue(detail.any { it.recordType == "thread" })
        assertTrue(detail.any { it.recordType == "comment" && it.parentId.isNotBlank() })
        val timelineUrl = "https://i.instagram.com/api/v1/feed/text_post_app_timeline/"
        assertTrue(records("timeline_1.ndjson", timelineUrl, ThreadsRecordMode.READ).isEmpty())
        assertTrue(records("timeline_1.ndjson", timelineUrl, ThreadsRecordMode.PUSH).isNotEmpty())
    }
}
