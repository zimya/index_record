package me.melon.record.ui.detail.record

import me.melon.record.data.model.UniversalRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordDetailTitleTest {
    @Test
    fun titlesIdentifyPlatformAndContentType() {
        val cases = listOf(
            Triple("reddit", "post", "Reddit · 帖子"),
            Triple("reddit", "comment", "Reddit · 回复"),
            Triple("threads", "thread", "Threads · 主帖"),
            Triple("threads", "comment", "Threads · 回复"),
            Triple("zhihu", "pin", "知乎 · 想法"),
            Triple("zhihu", "answer", "知乎 · 回答"),
            Triple("zhihu", "article", "知乎 · 文章"),
            Triple("zhihu", "question", "知乎 · 问题"),
            Triple("douban", "interest", "豆瓣 · 短评"),
            Triple("douban", "review", "豆瓣 · 长评"),
            Triple("douban", "topic", "豆瓣 · 帖子"),
            Triple("douban", "subject", "豆瓣 · 条目"),
            Triple("douban", "comment", "豆瓣 · 评论"),
            Triple("tieba", "lou", "贴吧 · 主帖"),
            Triple("tieba", "floor", "贴吧 · 楼层"),
            Triple("tieba", "comment", "贴吧 · 楼中楼"),
            Triple("twitter", "tweet", "𝕏 · 推文"),
            Triple("weibo", "status", "微博 · 微博"),
            Triple("weibo", "comment", "微博 · 评论"),
            Triple("custom", "custom_type", "Custom · custom_type")
        )
        cases.forEach { (platform, type, expected) ->
            assertEquals(expected, recordDetailTitle(UniversalRecord(platform = platform, recordType = type)))
        }
    }
}
