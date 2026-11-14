package com.melonhu.indexrecord

import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.ui.components.typeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordFilterTypeTest {
    @Test
    fun platformSpecificTypesHaveDistinctLabels() {
        val labels = mapOf(
            "status" to "微博",
            "question" to "知乎问题",
            "answer" to "知乎回答",
            "article" to "知乎文章",
            "pin" to "知乎想法",
            "comment" to "评论",
            "topic" to "豆瓣帖子",
            "post" to "Reddit 帖子",
            "lou" to "贴吧主帖",
            "thread" to "Threads 帖子",
            "tweet" to "X 推文",
            "floor" to "贴吧楼层",
            "review" to "豆瓣长评",
            "interest" to "豆瓣短评",
            "subject" to "豆瓣条目"
        )
        labels.forEach { (type, label) -> assertEquals(label, typeLabel(type)) }
        assertEquals(labels.size, labels.keys.map(::typeLabel).toSet().size)
        assertEquals("unknown", typeLabel("unknown"))
    }
    @Test
    fun qualifiedHomeOptionsShowPlatformSpecificReplyLabels() {
        val labels = mapOf(
            "zhihu:comment" to "知乎评论",
            "tieba:floor" to "贴吧楼层",
            "tieba:comment" to "贴吧楼中楼",
            "reddit:comment" to "Reddit回复",
            "twitter:comment" to "X评论",
            "weibo:comment" to "微博评论",
            "douban:comment" to "豆瓣评论",
            "threads:comment" to "Threads回复"
        )
        labels.forEach { (key, label) -> assertEquals(label, typeLabel(key)) }
        assertEquals(labels.size, labels.keys.map(::typeLabel).toSet().size)
    }

    @Test
    fun storedTypesAndFilterLabelsAreDistinct() {
        val records = listOf(
            UniversalRecord(platform = "tieba", recordType = "lou"),
            UniversalRecord(platform = "threads", recordType = "thread")
        )
        assertEquals(listOf("tieba"), records.filter { it.recordType == "lou" }.map { it.platform })
        assertEquals(listOf("threads"), records.filter { it.recordType == "thread" }.map { it.platform })
        assertEquals("贴吧主帖", typeLabel("lou"))
        assertEquals("Threads 帖子", typeLabel("thread"))
    }
}
