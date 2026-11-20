package com.melonhu.indexrecord.parser.hupu

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.RecordIdentityKind
import com.melonhu.indexrecord.data.model.RecordRelation
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.directRelation
import com.melonhu.indexrecord.data.model.identity
import com.melonhu.indexrecord.data.model.replyTarget
import com.melonhu.indexrecord.parser.ParserRegistry
import com.melonhu.indexrecord.presentation.RecordContentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HupuParserTest {

    private fun readSample(name: String): String {
        val candidates = listOf(
            File("research/hupu/$name"),
            File("../research/hupu/$name")
        )
        val file = candidates.firstOrNull { it.exists() }
        requireNotNull(file) { "Sample file $name not found in candidates: $candidates" }
        return file.readText()
    }

    private fun parse(url: String, json: String): List<UniversalRecord> {
        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.hupu.games",
            timestamp = 1794858954000L
        )
        return ParserRegistry.parse(raw, url.substringBefore("?"))
    }

    @Test
    fun testParseLegacyThreadDetail() {
        val json = readSample("thread_detail.json")
        val records = parse("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/643989029?clientId=175810962", json)
        assertEquals(1, records.size)

        val record = records.first()
        assertEquals("hupu", record.platform)
        assertEquals("post", record.recordType)
        assertEquals("hupu_post_643989029", record.platformId)
        assertEquals("现在重选LPL十大选手，应该是哪些人", record.title)
        assertEquals("hupu_叽里呱啦咵", record.authorName)
        assertEquals("36399768", record.authorId)
        assertTrue(record.authorAvatar.contains("56786649925505"))
        assertTrue(record.content.contains("选项不够了少金工"))
        assertEquals("https://bbs.hupu.com/643989029.html", record.sourceUrl)
        assertEquals("", record.parentId)
        assertEquals(1794844465000L, record.publishTime)
        assertEquals(RecordBodyFormat.HTML.storageValue, record.contentFormat)

        val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals("3441", extra["fid"].asString)
        assertEquals("英雄联盟", extra["forum_name"].asString)
        assertEquals(32L, extra["replies"].asLong)
        assertEquals(9L, extra["lights"].asLong)

        assertTrue(RecordContentPolicy.isPrimaryContent(record))
        assertEquals(RecordIdentityKind.POST, record.identity.kind)
    }

    @Test
    fun testParseRefactorThreadDetail() {
        val json = readSample("thread_refactor.json")
        val records = parse("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/refactor/643971336?rebuildImg=1", json)
        assertEquals(1, records.size)

        val record = records.first()
        assertEquals("hupu", record.platform)
        assertEquals("post", record.recordType)
        assertEquals("hupu_post_643971336", record.platformId)
        assertEquals("爆了！blg上辅决裂", record.title)
        assertEquals("iGtheshywow", record.authorName)
        assertEquals("47670658", record.authorId)
        assertTrue(record.authorAvatar.contains("00224AB943F1FAD905B891D851ABA267"))
        assertTrue(record.content.contains("bin皮肤纠结武器还是格温"))
        assertTrue(record.content.contains("<img src=\"https://i10.hoopchina.com.cn/"))
        assertEquals("https://bbs.hupu.com/643971336.html", record.sourceUrl)
        assertEquals("", record.parentId)
        assertEquals(RecordBodyFormat.HTML.storageValue, record.contentFormat)

        val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals("3441", extra["fid"].asString)
        assertEquals("英雄联盟", extra["forum_name"].asString)
        assertEquals(108L, extra["replies"].asLong)

        assertTrue(RecordContentPolicy.isPrimaryContent(record))
        assertEquals(RecordIdentityKind.POST, record.identity.kind)
    }

    @Test
    fun testParsePostReplies() {
        val json = readSample("post_replies.json")
        val records = parse("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/getsThreadPostList?tid=643989029&fid=3441&page=1", json)
        assertEquals(20, records.size)

        val r0 = records.first()
        assertEquals("hupu", r0.platform)
        assertEquals("comment", r0.recordType)
        assertEquals("hupu_comment_44192", r0.platformId)
        assertEquals("马修斯爆c", r0.authorName)
        assertEquals("114800386", r0.authorId)
        assertEquals("blg五个随便进，其他随意", r0.content)
        assertEquals("hupu_post_643989029", r0.parentId)
        assertEquals("https://bbs.hupu.com/643989029.html", r0.sourceUrl)
        assertEquals(1794845504000L, r0.publishTime)
        assertEquals(RecordBodyFormat.HTML.storageValue, r0.contentFormat)

        val extra0 = JsonParser.parseString(r0.extraMetadata).asJsonObject
        assertEquals("643989029", extra0["tid"].asString)
        assertEquals("22", extra0["floor"].asString)
        assertEquals("江苏", extra0["location"].asString)

        assertFalse(RecordContentPolicy.isPrimaryContent(r0))
        assertEquals(RecordIdentityKind.COMMENT, r0.identity.kind)
        assertTrue(r0.directRelation() is RecordRelation.ReplyTo)
        assertEquals("hupu_post_643989029", r0.replyTarget()?.contentId)

        // Reply 1 has quote
        val r1 = records[1]
        assertEquals("hupu_comment_46684", r1.platformId)
        assertTrue(r1.content.contains("<blockquote>"))
        assertTrue(r1.content.contains("主要是这个冠军的含金量太硬了"))
        assertTrue(r1.content.contains("而且真正意义上的没短板"))
        // Image normalized from lazy placeholder
        assertTrue(r1.content.contains("src=\"https://i3.hoopchina.com.cn/newsPost/1742280824200_o_w_200_h_178_881282dd.jpg"))
    }

    @Test
    fun testParseLightReplies() {
        val json = readSample("light_replies.json")
        val records = parse("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/getsThreadLightReplyList?tid=643989029&fid=3441", json)
        assertEquals(11, records.size)

        val r0 = records.first()
        assertEquals("hupu", r0.platform)
        assertEquals("comment", r0.recordType)
        assertEquals("hupu_comment_44998", r0.platformId)
        assertEquals("正义球迷不回脑残言论", r0.authorName)
        assertTrue(r0.content.contains("主要是这个冠军的含金量太硬了"))
        assertTrue(r0.content.contains("<blockquote>引用 @马修斯爆c 发表的"))
        assertEquals("hupu_post_643989029", r0.parentId)

        val extra0 = JsonParser.parseString(r0.extraMetadata).asJsonObject
        assertEquals(86L, extra0["light_count"].asLong)
    }

    @Test
    fun testParseCheckReply() {
        val json = readSample("check_reply.json")
        val records = parse(
            "https://bbs.mobileapi.hupu.com/1/8.2.51/threads/getCheckReply?pid=38446&tid=643879180&fid=34&order=light&type=1",
            json
        )
        assertEquals(9, records.size)

        val r0 = records.first()
        assertEquals("hupu", r0.platform)
        assertEquals("comment", r0.recordType)
        assertEquals("hupu_comment_38940", r0.platformId)
        assertEquals("深聊", r0.authorName)
        assertEquals("117645466", r0.authorId)
        assertTrue(r0.content.contains("狂是我草率了"))
        assertEquals("hupu_comment_38446", r0.parentId)
        assertEquals("https://bbs.hupu.com/643879180.html", r0.sourceUrl)
        assertEquals(1794480856000L, r0.publishTime)
        assertEquals(RecordBodyFormat.HTML.storageValue, r0.contentFormat)
        assertTrue(r0.directRelation() is RecordRelation.ReplyTo)
        assertEquals("hupu_comment_38446", r0.replyTarget()?.contentId)

        val extra0 = JsonParser.parseString(r0.extraMetadata).asJsonObject
        assertEquals("643879180", extra0["tid"].asString)
        assertEquals("38446", extra0["quote_pid"].asString)
        assertEquals(245L, extra0["light_count"].asLong)
    }

    @Test
    fun testHupuParserCanHandle() {
        val raw = RawData(url = "", json = "", hostApp = "com.hupu.games", timestamp = 0L)
        assertTrue(HupuParser.canHandle("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/643989029", raw))
        assertTrue(HupuParser.canHandle("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/refactor/643971336", raw))
        assertTrue(HupuParser.canHandle("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/getsThreadPostList", raw))
        assertTrue(HupuParser.canHandle("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/getsThreadLightReplyList", raw))
        assertTrue(HupuParser.canHandle("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/getCheckReply", raw))

        assertFalse(HupuParser.canHandle("https://games.mobileapi.hupu.com/1/8.2.51/bplcommentapi/relation/score/statement", raw))
        assertFalse(HupuParser.canHandle("https://bbs.mobileapi.hupu.com/1/8.2.51/bbsevaluateapi/evaluate/info", raw))
        assertFalse(HupuParser.canHandle("https://api.coolapk.com/v6/feed/detail", raw))
    }
}
