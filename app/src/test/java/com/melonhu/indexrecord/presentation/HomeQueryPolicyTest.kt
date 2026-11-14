package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test

class HomeQueryPolicyTest {
    private fun record(platform: String, type: String) = UniversalRecord(
        platform = platform, recordType = type, content = "Needle 内容", captureTime = 100L
    )

    private val primary = listOf(
        record("zhihu", "question"), record("zhihu", "answer"), record("zhihu", "article"),
        record("zhihu", "pin"), record("tieba", "lou"), record("reddit", "post"),
        record("twitter", "tweet"), record("weibo", "status"), record("threads", "thread"),
        record("douban", "topic"), record("douban", "review"), record("douban", "interest"),
        record("douban", "subject")
    )
    private val secondary = listOf(
        record("tieba", "floor"), record("tieba", "comment"), record("reddit", "comment"),
        record("twitter", "comment"), record("weibo", "comment"), record("douban", "comment"),
        record("zhihu", "comment"), record("threads", "comment")
    )
    private val all = primary + secondary

    @Test fun defaultShowsOnlyExplicitPrimaryContent() {
        assertEquals(primary, HomeQueryPolicy.apply(all, ""))
        assertEquals(primary, HomeQueryPolicy.apply(all, "  \n"))
        primary.forEach { assertTrue(RecordContentPolicy.isPrimaryContent(it)) }
        secondary.forEach { assertFalse(RecordContentPolicy.isPrimaryContent(it)) }
        assertFalse(RecordContentPolicy.isPrimaryContent(record("unknown", "post")))
        assertFalse(RecordContentPolicy.isPrimaryContent(record("tieba", "post")))
        assertFalse(RecordContentPolicy.isPrimaryContent(record("reddit", "tweet")))
    }

    @Test fun nonblankSearchFindsEveryReplyPlatformIncludingTiebaFloors() {
        assertEquals(all, HomeQueryPolicy.apply(all, " nEeDlE "))
        assertTrue(HomeQueryPolicy.apply(all, "missing").isEmpty())
        assertEquals(listOf(record("twitter", "comment")),
            HomeQueryPolicy.apply(listOf(record("twitter", "comment")), "X评论"))
    }

    @Test fun eitherTimeBoundEnablesRepliesAndUsesInclusiveBounds() {
        assertEquals(all, HomeQueryPolicy.apply(all, "", HomeQueryFilter(startMillis = 100L)))
        assertEquals(all, HomeQueryPolicy.apply(all, "", HomeQueryFilter(endMillis = 100L)))
        assertTrue(HomeQueryPolicy.apply(all, "", HomeQueryFilter(startMillis = 101L)).isEmpty())
        assertTrue(HomeQueryPolicy.apply(all, "", HomeQueryFilter(endMillis = 99L)).isEmpty())
    }

    @Test fun appFilterEnablesRepliesAndResolvesMissingSourceApp() {
        val expected = all.filter { it.platform == "tieba" }
        val filter = HomeQueryFilter(apps = setOf("com.baidu.tieba"))
        assertEquals(expected, HomeQueryPolicy.apply(all, "", filter))
        val alternate = record("weibo", "comment").copy(sourceApp = "com.weico.international")
        assertEquals(listOf(alternate), HomeQueryPolicy.apply(listOf(alternate), "",
            HomeQueryFilter(apps = setOf("com.weico.international"))))
        assertTrue(HomeQueryPolicy.apply(listOf(alternate), "",
            HomeQueryFilter(apps = setOf("com.sina.weibo"))).isEmpty())
    }

    @Test fun typeKeysSeparatePlatformsWithoutChangingStorageTypes() {
        val keys = secondary.map(RecordContentPolicy::filterTypeKey)
        assertEquals(secondary.size, keys.toSet().size)
        secondary.forEach { reply ->
            val originalType = reply.recordType
            assertEquals(listOf(reply), HomeQueryPolicy.apply(all, "",
                HomeQueryFilter(types = setOf(RecordContentPolicy.filterTypeKey(reply)))))
            assertEquals(originalType, reply.recordType)
        }
        assertEquals("知乎评论", RecordContentPolicy.filterTypeLabel("zhihu:comment"))
        assertEquals("贴吧楼层", RecordContentPolicy.filterTypeLabel("tieba:floor"))
        assertEquals("贴吧楼中楼", RecordContentPolicy.filterTypeLabel("tieba:comment"))
        assertEquals("X评论", RecordContentPolicy.filterTypeLabel("twitter:comment"))
        assertEquals("豆瓣评论", RecordContentPolicy.filterTypeLabel("douban:comment"))
    }

    @Test fun combinedConditionsRemainConjunctiveAndClearingRestoresDefault() {
        val filter = HomeQueryFilter(startMillis = 100L, endMillis = 100L,
            apps = setOf("com.baidu.tieba"), types = setOf("tieba:floor", "reddit:comment"))
        assertEquals(listOf(record("tieba", "floor")), HomeQueryPolicy.apply(all, "needle", filter))
        assertTrue(HomeQueryPolicy.apply(all, "missing", filter).isEmpty())
        assertEquals(primary, HomeQueryPolicy.apply(all, "", HomeQueryFilter()))
    }

    @Test fun searchCoversExistingFieldsAndDisplayLabels() {
        val fields = listOf(
            record("zhihu", "comment").copy(content = "", title = "needle"),
            record("zhihu", "comment").copy(content = "", authorName = "needle"),
            record("zhihu", "comment").copy(content = "", extraMetadata = "{\"text\":\"needle\"}"),
            record("zhihu", "comment").copy(content = "", sourceUrl = "https://needle"),
            record("zhihu", "comment").copy(content = "", sourceApp = "needle.app")
        )
        assertEquals(fields, HomeQueryPolicy.apply(fields, "needle"))
        assertEquals(secondary.filter { it.platform == "zhihu" }, HomeQueryPolicy.apply(secondary, "zhihu"))
        assertEquals(secondary.filter { it.recordType == "comment" }, HomeQueryPolicy.apply(secondary, "comment"))
    }
}
