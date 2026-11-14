package com.melonhu.indexrecord.parser.weibo

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.parser.ParserRegistry
import org.junit.Assert.*
import org.junit.Test

class WeiboParserTest {
    private fun status(id: String = "90071992547409931", extra: String = "") = """{"idstr":"$id","id":1,"text":"正文<br>第二行<img alt='[笑]' src='https://example.invalid/emoji'>","created_at":"Wed Nov 11 10:00:00 +0800 2026","user":{"idstr":"88","screen_name":"合成作者","description":"简介","avatar_hd":"https://example.invalid/avatar"}$extra}"""
    private fun comment(id: String, extra: String = "") = """{"idstr":"$id","text":"评论$id","mid":"$id","rootidstr":"200","user":{"idstr":"99","screen_name":"读者"}$extra}"""
    private fun parse(path: String, json: String, query: String = "") = ParserRegistry.parse(
        RawData(url = "https://api.weibo.cn/2/$path$query", json = json, hostApp = "com.sina.weibo", timestamp = 123),
        "https://api.weibo.cn/2/$path")!!

    @Test fun detailMetadataImagesAndLongText() {
        val extra = """, "longText":{"longTextContent":"完整长文<br>结尾"},"isLongText":true,"attitudes_count":12,"reposts_count":3,"comments_count":4,"reads_count":500,"source":"<a>手机</a>","pic_ids":["b","a"],"pic_infos":{"a":{"original":{"url":"https://example.invalid/a.jpg"}},"b":{"largest":{"url":"https://example.invalid/b.jpg"}}}"""
        val r = parse("statuses/container_detail", """{"detailInfo":{"status":${status(extra = extra)}}}""").single()
        assertEquals("weibo_status_90071992547409931", r.platformId)
        assertEquals("status", r.recordType)
        assertEquals("weibo", r.platform)
        assertEquals(123L, r.captureTime)
        assertTrue(r.publishTime > 0)
        assertEquals("88", r.authorId)
        assertTrue(r.content.startsWith("完整长文\n结尾"))
        assertTrue(r.content.indexOf("b.jpg") < r.content.indexOf("a.jpg"))
        val meta = JsonParser.parseString(r.extraMetadata).asJsonObject
        assertEquals(12, meta["like_count"].asInt)
        assertEquals(3, meta["retweet_count"].asInt)
        assertEquals(4, meta["comment_count"].asInt)
        assertEquals(500, meta["views_count"].asInt)
        assertEquals("简介", meta["author_headline"].asString)
        assertEquals("手机", meta["source"].asString)
        assertFalse(r.sourceUrl.contains("api.weibo"))
    }

    @Test fun timelineGroupsRepostsAndFilters() {
        val repost = status("101", """, "retweeted_status":${status("100")}""")
        val json = """{"items":[{"category":"group","items":[{"category":"feed","data":$repost}]},{"category":"feed","data":${status("102", ",\"ad_marked\":1")}},{"category":"heartbeat","data":${status("103")}},{"category":"cell","data":${status("104")}},{"category":"feed","data":$repost}],"user":${status("999")}}"""
        val rs = parse("statuses/container_timeline_hot", json)
        assertEquals(setOf("weibo_status_100", "weibo_status_101"), rs.map { it.platformId }.toSet())
        assertEquals("weibo_status_100", rs.last().parentId)
        assertTrue(rs.first().content.contains("第二行[笑]"))
        assertEquals(2, parse("statuses/container_positive", json).size)
    }

    @Test fun buildCommentsImmediateParentsAndType42() {
        val reply = comment("202", """, "reply_comment":${comment("201")},"like_counts":7""")
        val json = """{"status":${status("100")},"rootComment":${comment("200")},"datas":[{"type":42,"data":$reply},{"type":1,"data":${comment("999")}}]}"""
        val rs = parse("comments/build_comments", json).associateBy { it.platformId }
        assertEquals(4, rs.size)
        assertEquals("weibo_status_100", rs.getValue("weibo_comment_200").parentId)
        assertEquals("weibo_comment_200", rs.getValue("weibo_comment_201").parentId)
        assertEquals("weibo_comment_201", rs.getValue("weibo_comment_202").parentId)
        assertEquals(7, JsonParser.parseString(rs.getValue("weibo_comment_202").extraMetadata).asJsonObject["like_count"].asInt)
    }

    @Test fun detailCommentsNestedRepliesAndQueryContext() {
        val root = comment("200", """, "comments":[${comment("201")}]""")
        val rs = parse("statuses/container_detail_comment", """{"items":[{"type":"comment_header_tip","data":${comment("999")}},{"type":"comment","data":$root}]}""", "?source_mid=mid%3A100&token=synthetic-secret")
        assertEquals(2, rs.size)
        assertEquals("weibo_status_100", rs[0].parentId)
        assertEquals("https://m.weibo.cn/detail/100?cid=200", rs[0].sourceUrl)
        assertEquals("weibo_comment_200", rs[1].parentId)
        assertTrue(rs.none { it.sourceUrl.contains("token") })
    }

    @Test fun superTopicCardsAndMixedPictures() {
        val s = status("100", """, "mix_media_info":{"items":[{"type":"pic","data":{"original":{"url":"https://example.invalid/mixed.jpg"}}},{"type":"video","data":{"id":"777"}}]}""")
        val rs = parse("container/getIndex", """{"data":{"cards":[{"card_type":11,"card_group":[{"card_type":9,"mblog":$s},{"card_type":10,"mblog":${status("999")}}]}]}}""")
        assertEquals(1, rs.size)
        assertTrue(rs.single().content.contains("mixed.jpg"))
    }

    @Test fun weicoStatusesAndTopicGroups() {
        val s = status("100")
        val rs = parse("statuses/unread_hot_timeline", """{"statuses":[$s,${status("101", ",\"is_ad\":true")}],"items":[{"category":"feed","data":$s}]}""")
        assertEquals(listOf("weibo_status_100"), rs.map { it.platformId })
        val topic = parse("statuses/container_timeline_topicpage", """{"items":[{"category":"group","items":[{"category":"feed","data":$s},{"category":"cell","data":${status("999")}}]}]}""")
        assertEquals(listOf("weibo_status_100"), topic.map { it.platformId })
    }

    @Test fun weicoTypeZeroAndTopLevelReplies() {
        val root = comment("200")
        val rs = parse("comments/build_comments", """{"status":${status("100")},"rootComment":$root,"datas":[{"type":0,"data":${comment("201")}},{"type":6,"data":${comment("999")}}],"comments":[${comment("202", """, "reply_comment":${comment("201")}""")},${comment("203").replace("\"rootidstr\":\"200\"", "\"rootidstr\":\"0\"")}]}""").associateBy { it.platformId }
        assertEquals(5, rs.size)
        assertEquals("weibo_status_100", rs.getValue("weibo_comment_200").parentId)
        assertEquals("weibo_comment_200", rs.getValue("weibo_comment_201").parentId)
        assertEquals("weibo_comment_201", rs.getValue("weibo_comment_202").parentId)
        assertEquals("weibo_comment_200", rs.getValue("weibo_comment_203").parentId)
    }

    @Test fun expandedReplyTargetCannotBecomeItsOwnParent() {
        val expandedRoot = comment("200").replace("评论200", "评论200更完整的引用正文")
        val rs = parse("comments/build_comments", """{"status":${status("100")},"rootComment":${comment("200")},"comments":[${comment("201", """, "reply_comment":$expandedRoot""")}]}""").associateBy { it.platformId }
        assertEquals("weibo_status_100", rs.getValue("weibo_comment_200").parentId)
        assertEquals("weibo_comment_200", rs.getValue("weibo_comment_201").parentId)
        assertTrue(rs.values.none { it.platformId == it.parentId })
    }

    @Test fun weicoBatchLongTextRequiresAuthorAndPreservesFields() {
        val full = JsonParser.parseString(status("100", """, "longText":{"longTextContent":"完整全文<br>最后一行"},"content":"备用全文","attitudes_count":8""")).asJsonObject.apply { remove("text") }
        val sparse = full.deepCopy().apply { addProperty("idstr", "101"); remove("user") }
        val emptyAuthor = full.deepCopy().apply { addProperty("idstr", "102"); add("user", JsonParser.parseString("{}")) }
        val r = parse("statuses/longtext_show_batch", """{"longtexts":{"data":[$full,$sparse,$emptyAuthor]}}""").single()
        assertEquals("weibo_status_100", r.platformId)
        assertEquals("完整全文\n最后一行", r.content)
        assertEquals("88", r.authorId)
        assertEquals("合成作者", r.authorName)
        assertTrue(r.authorAvatar.isNotBlank())
        assertEquals(8, JsonParser.parseString(r.extraMetadata).asJsonObject["like_count"].asInt)
        full.remove("longText")
        assertEquals("备用全文", parse("statuses/longtext_show_batch", """{"longtexts":{"data":[$full]}}""").single().content)
        assertTrue(parse("statuses/show", full.toString()).isEmpty())
    }

    @Test fun weicoCard89AndPermissions() {
        val rs = parse("cardlist", """{"cards":[{"card_type":89,"mblog":${status("100")}},{"card_type":89,"is_ad":1,"mblog":${status("101")}},{"card_type":90,"mblog":${status("999")}}]}""")
        assertEquals(listOf("weibo_status_100"), rs.map { it.platformId })
        assertTrue(parse("comments/allowed", """{"id":100,"allowed":true,"user":${status()}}""").isEmpty())
    }

    @Test fun malformedAndNonContentDoNotCreateRecords() {
        for (body in listOf("{}", "null", "[]", "broken", "{\"user\":{\"id\":123}}")) {
            assertTrue(parse("statuses/container_detail", body).isEmpty())
        }
        assertTrue(parse("statuses/extend", status()).isEmpty())
        assertTrue(parse("comments/bullet_screens", "{}").isEmpty())
        assertFalse(WeiboParser.canHandle("https://api.weibo.cn.evil.invalid/2/statuses/show", RawData()))
    }
}
