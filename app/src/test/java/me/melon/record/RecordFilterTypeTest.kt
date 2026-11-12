package me.melon.record

import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.typeLabel
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
            "tweet" to "𝕏 推文",
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
