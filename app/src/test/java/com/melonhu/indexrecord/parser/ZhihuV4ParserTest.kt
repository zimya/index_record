package com.melonhu.indexrecord.parser

import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.hook.ZhihuPlusHook
import com.melonhu.indexrecord.parser.zhihu.ZhihuV4Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhihuV4ParserTest {

    private val answerUrl =
        "https://www.zhihu.com/api/v4/answers/123456?include=content%2Cexcerpt%2Cauthor"

    @Test
    fun answerBodyBecomesMarkdownWithQuestionRelation() {
        val json = """
            {
              "id": 123456,
              "type": "answer",
              "content": "<p>先看结论：</p><p><b>能用</b></p><img src=\"https://pic1.zhimg.com/a.jpg\">",
              "excerpt": "先看结论",
              "author": {"id": "u1", "name": "张三", "headline": "写代码", "avatar_url": "https://pic/av.jpg"},
              "question": {"id": 987, "title": "如何评价知乎++？"},
              "voteup_count": 42,
              "comment_count": 7,
              "created_time": 1714557600
            }
        """.trimIndent()
        val record = ZhihuV4Parser.parse(RawData(url = answerUrl, json = json), answerUrl).single()

        assertEquals("zhihu", record.platform)
        assertEquals("answer", record.recordType)
        assertEquals("zhihu_answer_123456", record.platformId)
        assertEquals("zhihu_question_987", record.parentId)
        assertEquals("如何评价知乎++？", record.title)
        assertEquals("张三", record.authorName)
        assertTrue(record.content.contains("能用"))
        assertTrue(record.content.contains("![](https://pic1.zhimg.com/a.jpg)"))
        assertEquals(1714557600000L, record.publishTime)
        assertTrue(record.extraMetadata.contains("42"))
    }

    @Test
    fun articleAndQuestionKeepTheirOwnIdentities() {
        val articleUrl = "https://www.zhihu.com/api/v4/articles/555"
        val article = ZhihuV4Parser.parse(
            RawData(
                url = articleUrl,
                json = """{"id":555,"title":"标题","content":"<p>正文</p>",
                          "author":{"id":"u2","name":"李四"},"created":1714557600,"voteup_count":9}"""
            ),
            articleUrl
        ).single()
        assertEquals("zhihu_article_555", article.platformId)
        assertEquals("标题", article.title)
        assertEquals("正文", article.content.trim())

        val questionUrl = "https://www.zhihu.com/api/v4/questions/987"
        val question = ZhihuV4Parser.parse(
            RawData(
                url = questionUrl,
                json = """{"id":987,"title":"问题标题","detail":"<p>问题描述</p>",
                          "answer_count":12,"follower_count":34}"""
            ),
            questionUrl
        ).single()
        assertEquals("zhihu_question_987", question.platformId)
        assertEquals("问题标题", question.title)
        assertEquals("问题描述", question.content.trim())
    }

    @Test
    fun pinContentArrayBecomesTextAndImages() {
        val pinUrl = "https://www.zhihu.com/api/v4/pins/777"
        val json = """
            {"id": 777, "type": "pin", "created": 1714557600,
             "author": {"id": "u3", "name": "王五"},
             "content": [
               {"type": "text", "content": "今天写完了适配"},
               {"type": "image", "urls": {"original": "https://pic1.zhimg.com/pin.jpg"}}
             ]}
        """.trimIndent()
        val record = ZhihuV4Parser.parse(RawData(url = pinUrl, json = json), pinUrl).single()

        assertEquals("zhihu_pin_777", record.platformId)
        assertEquals("pin", record.recordType)
        assertEquals("markdown", record.contentFormat)
        assertTrue(record.content.contains("今天写完了适配"))
        assertTrue(record.content.contains("![](https://pic1.zhimg.com/pin.jpg)"))
    }

    @Test
    fun questionFeedsProduceAnswersInsteadOfEmptyQuestion() {
        val url = "https://www.zhihu.com/api/v4/questions/987/feeds?limit=6"
        val json = """
            {"data":[
              {"type":"answer","target":{"id":4321,"content":"<p>回答一</p>",
                "author":{"id":"u9","name":"答主"},"question":{"id":987,"title":"问题标题"},
                "voteup_count":3,"created_time":1714557600}},
              {"type":"answer","target":{"id":4322,"content":"<p>回答二</p>",
                "author":{"name":"答主二"},"question":{"id":987,"title":"问题标题"}}}
            ]}
        """.trimIndent()
        val records = ZhihuV4Parser.parse(RawData(url = url, json = json), url)

        assertEquals(2, records.size)
        assertEquals("answer", records.first().recordType)
        assertEquals("zhihu_question_987", records.first().parentId)
        assertTrue(records.all { it.recordType == "answer" })
    }

    @Test
    fun hookMatchesV4EndpointsOnly() {
        assertTrue(ZhihuPlusHook.isTargetUrl(answerUrl))
        assertTrue(ZhihuPlusHook.isTargetUrl("https://www.zhihu.com/api/v4/articles/1"))
        assertTrue(ZhihuPlusHook.isTargetUrl("https://www.zhihu.com/api/v4/comment_v5/answers/1/root_comment?limit=20"))
        assertTrue(ZhihuPlusHook.isTargetUrl("https://www.zhihu.com/api/v4/pins/1"))
        assertFalse(ZhihuPlusHook.isTargetUrl("https://www.zhihu.com/api/v4/me"))
        assertFalse(ZhihuPlusHook.isTargetUrl("https://pic1.zhimg.com/a.jpg"))
        assertFalse(ZhihuPlusHook.isTargetUrl(null))
    }
}
