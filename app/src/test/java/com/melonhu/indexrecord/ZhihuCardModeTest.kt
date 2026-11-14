package com.melonhu.indexrecord

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.logic.DataProcessor
import com.melonhu.indexrecord.markdown.markdownToMdAst
import com.melonhu.indexrecord.markdown.zhihuStructuredContentToMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ZhihuCardModeTest {

    private fun loadExample(filename: String): String {
        val candidates = listOf(
            File("research/zhihu", filename),
            File("../research/zhihu", filename),
            File("../../research/zhihu", filename)
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Cannot find file $filename in candidates: $candidates")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun testPinCommentsParsing() {
        val raw = RawData(
            id = 5,
            url = "https://api.zhihu.com/comment_v5/pins/2103900882941261531/root_comment?order_by=score&limit=20&offset=",
            json = loadExample("comment_v5_pins.json"),
            hostApp = "com.zhihu.android",
            timestamp = 1794439353000L
        )
        val parser = com.melonhu.indexrecord.parser.zhihu.ZhihuParser
        val cleanUrl = raw.url.substringBefore('?')
        assertTrue(parser.canHandle(cleanUrl, raw))
        val records = parser.parse(raw, cleanUrl)
        val root = records.single { it.platformId == "zhihu_comment_11615615183" }
        assertEquals("zhihu_pin_2103900882941261531", root.parentId)
        assertEquals("comment", root.recordType)
        assertEquals("最多四两", root.authorName)
        assertTrue(root.content.contains("让蝽娘拿个钝器"))
        assertEquals(1794427437000L, root.publishTime)
        val child = records.single { it.platformId == "zhihu_comment_11616082446" }
        assertEquals(root.platformId, child.parentId)
        assertTrue(child.content.contains("这不是娘"))
        val ids = records.map { it.platformId }.toSet()
        assertTrue(records.all { it.parentId == "zhihu_pin_2103900882941261531" || it.parentId in ids })

        for ((resource, type) in listOf("answers" to "answer", "articles" to "article")) {
            val url = "https://api.zhihu.com/comment_v5/$resource/123/root_comment"
            assertTrue(parser.canHandle(url, raw))
            val comments = parser.parse(raw, url)
            assertEquals("zhihu_${type}_123", comments.first().parentId)
        }
        val childUrl = "https://api.zhihu.com/comment_v5/comment/11615615183/child_comment"
        assertTrue(parser.canHandle(childUrl, raw))
    }

    @Test
    fun testPinV2Parsing() {
        val raw = RawData(
            id = 4,
            url = "https://api.zhihu.com/pins/v2/2103900882941261531?scene=follow&single_content=1",
            json = loadExample("pin_v2_example.json"),
            hostApp = "com.zhihu.android",
            timestamp = 1732554840000L
        )
        val cleanUrl = raw.url.substringBefore('?')
        val parser = com.melonhu.indexrecord.parser.zhihu.ZhihuParser
        assertTrue(parser.canHandle(cleanUrl, raw))
        val record = parser.parse(raw, cleanUrl).single()
        assertEquals("pin", record.recordType)
        assertEquals("zhihu_pin_2103900882941261531", record.platformId)
        assertEquals("拟人设计练习 麻皮蝽", record.title)
        assertEquals("斜绿天蛾", record.authorName)
        assertTrue(record.authorAvatar.contains("v2-eb665fa780d6c2ffe7908debeb029b04"))
        assertEquals("https://www.zhihu.com/pin/2103900882941261531", record.sourceUrl)
        assertTrue(record.parentId.isBlank())
        assertTrue(record.content.contains("麻皮蝽 Erthesina fullo"))
        assertTrue(record.content.contains("![](https://pic1.zhimg.com/v2-31b18de43ad4af65c9fad36b8bb36b7b.png?"))
        assertTrue(markdownToMdAst(record.content).children.isNotEmpty())
        val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals("广东", extra.get("ip_info").asString)
        assertEquals(21, extra.get("voteup_count").asInt)
        assertEquals(7, extra.get("comment_count").asInt)
        assertEquals(3, extra.get("favlists_count").asInt)

        val item = JsonParser.parseString(raw.json).asJsonObject
        item.remove("type")
        item.remove("header")
        val untitled = DataProcessor.parseZhihuItemV2(item, raw, defaultType = "pin")!!
        assertEquals("pin", untitled.recordType)
        assertTrue(untitled.title.isBlank())
        assertFalse(parser.canHandle("https://api.zhihu.com/pins/v2/123/comments", raw))
    }

    @Test
    fun testAnswerV2Conversion() {
        val jsonStr = loadExample("answer_v2_example.json")
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val sc = json.getAsJsonObject("structured_content")
        assertNotNull(sc)

        val markdown = zhihuStructuredContentToMarkdown(sc)
        assertFalse(markdown.isBlank())

        // Check essential elements converted to Markdown
        assertTrue("Should contain heading/text", markdown.contains("Wordle规则"))
        assertTrue("Should contain link", markdown.contains("[ pubsonline.informs.org/...](https://pubsonline.informs.org/doi/abs/10.1287/opre.2022.0434)"))
        assertTrue("Should contain list", markdown.contains("1. 第一次猜的最佳单词是SALET；"))
        assertTrue("Should contain math formula", markdown.contains("\$s_t\$"))
        assertTrue("Should contain image", markdown.contains("![游戏规则示意图](https://pic1.zhimg.com/v2-a783039f32642378ec48b9f634c53324.jpg?source=7e7ef6e2&needBackground=1)"))

        // Should be parseable as Markdown AST
        val ast = markdownToMdAst(markdown)
        assertTrue(ast.children.isNotEmpty())
    }

    @Test
    fun testArticleV2Conversion() {
        val jsonStr = loadExample("article_v2_example.json")
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val sc = json.getAsJsonObject("structured_content")
        assertNotNull(sc)

        val markdown = zhihuStructuredContentToMarkdown(sc)
        assertFalse(markdown.isBlank())

        // Check blockquotes
        assertTrue("Should contain blockquote", markdown.contains("> 台球店老板在粉丝群发红包，手气最佳者可以免台费，是否存在**最佳策略**？"))
        // Check code block
        assertTrue("Should contain code block", markdown.contains("```python\ndef split_red_envelope("))
        // Check table
        assertTrue("Should contain table header", markdown.contains("| X^2 | 自由度 | p-value |"))
        assertTrue("Should contain table separator", markdown.contains("| --- | --- | --- |"))
        assertTrue("Should contain table values", markdown.contains("| 15.211 | 8 | 0.05516 |"))
        // Check footnotes
        assertTrue("Should contain footnote references", markdown.contains("[^1]"))
        assertTrue("Should contain footnote definitions", markdown.contains("[^1]: 揭秘微信红包：架构、抢红包算法、高并发和降级方案 https://www.cnblogs.com/8hao/p/5383143.html"))

        val ast = markdownToMdAst(markdown)
        assertTrue(ast.children.isNotEmpty())
    }

    @Test
    fun testNextRenderConversion() {
        val jsonStr = loadExample("next_render_example.json")
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val dataArray = json.getAsJsonArray("data")
        assertNotNull(dataArray)
        assertEquals(5, dataArray.size())

        for (i in 0 until dataArray.size()) {
            val item = dataArray[i].asJsonObject
            val sc = item.getAsJsonObject("structured_content")
            assertNotNull(sc)
            val markdown = zhihuStructuredContentToMarkdown(sc)
            assertFalse("Markdown for item $i should not be blank", markdown.isBlank())
            val ast = markdownToMdAst(markdown)
            assertTrue(ast.children.isNotEmpty())
        }
    }

    @Test
    fun testDataProcessorAnswerV2() {
        val jsonStr = loadExample("answer_v2_example.json")
        val raw = RawData(
            id = 1,
            url = "https://api.zhihu.com/answers/v2/40711040936",
            json = jsonStr,
            hostApp = "com.zhihu.android",
            timestamp = 1732554840000L
        )
        val jsonParams = JsonParser.parseString(raw.json).asJsonObject
        val record = DataProcessor.parseZhihuItemV2(jsonParams, raw, defaultType = "answer")
        assertNotNull(record)
        assertEquals("zhihu", record!!.platform)
        assertEquals("answer", record.recordType)
        assertEquals("zhihu_answer_40711040936", record.platformId)
        assertEquals("Wordle游戏在最优决策下6步内是否必定有解？", record.title)
        assertEquals("Yixiao Huang", record.authorName)
        assertTrue("Content should not be blank", record.content.isNotBlank())
        assertTrue("Content should contain markdown text", record.content.contains("Wordle规则"))

        val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals("card_mode_v2", extra.get("parse_mode").asString)
        assertEquals("4998185061", extra.get("question_id").asString)
        assertEquals("北京", extra.get("ip_info").asString)
        assertEquals(340, extra.get("voteup_count").asInt)
        assertEquals(26, extra.get("comment_count").asInt)
    }

    @Test
    fun testDataProcessorArticleV2() {
        val jsonStr = loadExample("article_v2_example.json")
        val raw = RawData(
            id = 2,
            url = "https://api.zhihu.com/articles/v2/2007896917322003867",
            json = jsonStr,
            hostApp = "com.zhihu.android",
            timestamp = 1771635660000L
        )
        val jsonParams = JsonParser.parseString(raw.json).asJsonObject
        val record = DataProcessor.parseZhihuItemV2(jsonParams, raw, defaultType = "article")
        assertNotNull(record)
        assertEquals("zhihu", record!!.platform)
        assertEquals("article", record.recordType)
        assertEquals("zhihu_article_2007896917322003867", record.platformId)
        assertEquals("为什么越后抢的人越容易手气最佳？微信红包分配算法的逆向", record.title)
        assertEquals("东瓜瓜瓜瓜", record.authorName)
        assertTrue("Content should not be blank", record.content.isNotBlank())
        assertTrue("Content should contain blockquote", record.content.contains("> 台球店老板在粉丝群发红包"))

        val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals("card_mode_v2", extra.get("parse_mode").asString)
        assertEquals("上海", extra.get("ip_info").asString)
        assertEquals(94, extra.get("voteup_count").asInt)
        assertEquals(12, extra.get("comment_count").asInt)
        assertTrue("Should contain topics", extra.has("topics"))
    }

    @Test
    fun testDataProcessorNextRender() {
        val jsonStr = loadExample("next_render_example.json")
        val raw = RawData(
            id = 3,
            url = "https://api.zhihu.com/next-render?context_expand=1&id=40711040936&is_native=1&limit=5&page_id=4&scenes=unknown&session_id=1794324240071238241&type=answer",
            json = jsonStr,
            hostApp = "com.zhihu.android",
            timestamp = 1717055760000L
        )
        val records = DataProcessor.parseZhihuNextRender(raw, "https://api.zhihu.com/next-render")
        assertEquals(5, records.size)

        val first = records[0]
        assertEquals("zhihu", first.platform)
        assertEquals("answer", first.recordType)
        assertEquals("zhihu_answer_3514967325", first.platformId)
        assertEquals("你玩过时间最长的游戏是什么？", first.title)
        assertEquals("肖寒", first.authorName)
        assertEquals("https://www.zhihu.com/question/656790296/answer/3514967325", first.sourceUrl)
        assertTrue(first.content.isNotBlank())
    }
}
