package com.melonhu.indexrecord.parser.coolapk

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.directRelation
import com.melonhu.indexrecord.data.model.replyTarget
import com.melonhu.indexrecord.data.model.RecordRelation
import com.melonhu.indexrecord.parser.ParserRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoolapkParserTest {

    private fun readSample(name: String): String {
        val candidates = listOf(
            File("research/coolapk/$name"),
            File("../research/coolapk/$name")
        )
        val file = candidates.firstOrNull { it.exists() }
        requireNotNull(file) { "Sample file $name not found" }
        return file.readText()
    }

    private fun parse(url: String, json: String): List<com.melonhu.indexrecord.data.model.UniversalRecord> {
        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.coolapk.market",
            timestamp = 1783000000000L
        )
        return ParserRegistry.parse(raw, url.substringBefore("?"))
    }

    @Test
    fun testParseFeed() {
        val json = readSample("feed.json")
        val records = parse("https://api2.coolapk.com/v6/feed/detail?id=75046118", json)
        assertFalse(records.isEmpty())
        val record = records.first()

        assertEquals("coolapk", record.platform)
        assertEquals("feed", record.recordType)
        assertEquals("coolapk_feed_75046118", record.platformId)
        assertEquals("随风124", record.authorName)
        assertEquals("14912300", record.authorId)
        assertTrue(record.content.contains("今年双11这半个多月"))
        assertTrue(record.content.contains("<img src=\"https://image.coolapk.com/feed/"))

        val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals("一加15", extra["device_title"].asString)
        assertEquals("一加15", extra["ttitle"].asString)
        assertEquals(147, extra["likenum"].asInt)
        assertEquals(181, extra["replynum"].asInt)
        assertEquals(28, extra["favnum"].asInt)
        assertEquals(1, extra["share_num"].asInt)
    }

    @Test
    fun testParseReview() {
        val json = readSample("review.json")
        val records = parse("https://api2.coolapk.com/v6/feed/detail?id=75106984", json)
        assertFalse(records.isEmpty())
        val record = records.first()

        assertEquals("coolapk", record.platform)
        assertEquals("rating", record.recordType)
        assertEquals("coolapk_rating_75106984", record.platformId)
        assertEquals("一只九七", record.authorName)
        assertEquals("REDMI K100 Pro Max 的点评", record.title)
        assertTrue(record.content.contains("评分：5.0 ★ (10分)"))
        assertTrue(record.content.contains("续航 10"))
        assertTrue(record.content.contains("性价比和续航这方面还是非常不错的"))

        val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals("REDMI K100 Pro Max", extra["device_title"].asString)
        assertEquals("REDMI K100 Pro Max", extra["ttitle"].asString)
        assertEquals(10.0, extra["rating_score"].asDouble, 0.01)
    }

    @Test
    fun testParseArticle() {
        val json = readSample("article.json")
        val records = parse("https://api2.coolapk.com/v6/feed/detail?id=74683270", json)
        assertFalse(records.isEmpty())
        val record = records.first()

        assertEquals("coolapk", record.platform)
        assertEquals("feedArticle", record.recordType)
        assertEquals("coolapk_article_74683270", record.platformId)
        assertEquals("米饭菌", record.authorName)
        assertEquals("一年买了四台二手电车的小作文与买车建议（超长版）", record.title)
        assertTrue(record.content.contains("二手电车选购+整备攻略"))
        assertTrue(record.content.contains("<img src=\"https://image.coolapk.com/feed/"))

        val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals("小米14", extra["device_title"].asString)
        assertEquals(1962, extra["likenum"].asInt)
        assertEquals(850, extra["replynum"].asInt)

        // Verify top reply rows parsed from detail
        val replies = records.filter { it.recordType == "comment" }
        assertFalse("Should parse embedded replies", replies.isEmpty())
        val topReply = replies.first { it.platformId == "coolapk_comment_614550866" }
        assertEquals("coolapk_feed_74683270", topReply.parentId)
        assertEquals("传奇山寨企业小米", topReply.authorName)
        assertTrue(topReply.content.contains("4月份2.9买了开了两个月亏2000出的"))

        val subReply = replies.first { it.platformId == "coolapk_comment_615129184" }
        assertEquals("coolapk_comment_615099771", subReply.parentId)
        assertTrue(subReply.content.contains("回复 @必胜客自由最好的一天:"))
    }

    @Test
    fun testParseQuestionAndAnswer() {
        val qJson = readSample("question.json")
        val qRecords = parse("https://api2.coolapk.com/v6/feed/detail?id=73027319", qJson)
        val qRecord = qRecords.first()

        assertEquals("coolapk", qRecord.platform)
        assertEquals("question", qRecord.recordType)
        assertEquals("coolapk_question_73027319", qRecord.platformId)
        assertEquals("三月青禾", qRecord.authorName)
        assertEquals("想问各位大佬手环控制手机播放器问题？", qRecord.title)
        assertTrue(qRecord.content.contains("小米手环9pro"))

        val aJson = readSample("answer.json")
        val aRecords = parse("https://api2.coolapk.com/v6/feed/detail?id=72663763", aJson)
        val aRecord = aRecords.first()

        assertEquals("coolapk", aRecord.platform)
        assertEquals("answer", aRecord.recordType)
        assertEquals("coolapk_answer_72663763", aRecord.platformId)
        assertEquals("画舫", aRecord.authorName)
        assertEquals("coolapk_question_64712256", aRecord.parentId)
        assertEquals("小米手环9 Pro充电时变慢显示接触不良并让检查清理连接处或更换充电器后重试，这可咋办呀？", aRecord.title)
        assertTrue(aRecord.content.contains("怎么样 你的修好了吗？"))

        val relation = aRecord.directRelation()
        assertNotNull(relation)
        assertTrue(relation is RecordRelation.AnswerToQuestion)
        assertEquals("coolapk_question_64712256", (relation as RecordRelation.AnswerToQuestion).target.contentId)
    }

    @Test
    fun testParseReplyList() {
        val json = readSample("reply_list.json")
        val records = parse(
            "https://api.coolapk.com/v6/feed/replyList?id=75091227&listType=lastupdate_desc&page=1&discussMode=1&feedType=feed&blockStatus=0&fromFeedAuthor=0",
            json
        )
        assertFalse(records.isEmpty())
        assertTrue("Expected multiple replies including sub-replies", records.size > 25)

        val firstReply = records.first { it.platformId == "coolapk_comment_617293084" }
        assertEquals("coolapk", firstReply.platform)
        assertEquals("comment", firstReply.recordType)
        assertEquals("coolapk_feed_75091227", firstReply.parentId)
        assertEquals("酷安小编", firstReply.authorName)
        assertTrue(firstReply.content.contains("太精致了，我这每天用清水洗脸的人觉得好复杂"))

        val target = firstReply.replyTarget()
        assertNotNull(target)
        assertEquals("coolapk_feed_75091227", target?.contentId)

        val subReply = records.first { it.platformId == "coolapk_comment_617390245" }
        assertEquals("coolapk_comment_617293084", subReply.parentId)
        assertEquals("じ湳宫の冰舞ぺ", subReply.authorName)
        assertTrue(subReply.content.contains("回复 @酷安小编:"))
        assertEquals("coolapk_comment_617293084", subReply.replyTarget()?.contentId)
    }

    @Test
    fun testEmptyOrInvalidData() {
        val raw = RawData(
            url = "https://api2.coolapk.com/v6/feed/detail?id=1",
            json = "{}",
            hostApp = "com.coolapk.market"
        )
        val records = CoolapkParser.parse(raw, raw.url)
        assertTrue(records.isEmpty())
    }
}
