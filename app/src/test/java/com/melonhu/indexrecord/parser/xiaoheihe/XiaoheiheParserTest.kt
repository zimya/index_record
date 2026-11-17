package com.melonhu.indexrecord.parser.xiaoheihe

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.RecordIdentityKind
import com.melonhu.indexrecord.data.model.RecordRelation
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.directRelation
import com.melonhu.indexrecord.data.model.identity
import com.melonhu.indexrecord.data.model.replyTarget
import com.melonhu.indexrecord.hook.XiaoheiheHook
import com.melonhu.indexrecord.parser.ParserRegistry
import com.melonhu.indexrecord.presentation.PlatformMetadataAdapters
import com.melonhu.indexrecord.presentation.RecordContentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XiaoheiheParserTest {

    private fun readSample(name: String): String {
        val candidates = listOf(
            File("research/xiaoheihe/$name"),
            File("../research/xiaoheihe/$name")
        )
        val file = candidates.firstOrNull { it.exists() }
        requireNotNull(file) { "Sample file $name not found in candidates: $candidates" }
        return file.readText()
    }

    private fun parse(url: String, json: String): List<UniversalRecord> {
        val raw = RawData(
            url = url,
            json = json,
            hostApp = XiaoheiheHook.HOST_APP,
            timestamp = 1794885261000L
        )
        return ParserRegistry.parse(raw, url.substringBefore("?"))
    }

    @Test
    fun testCanHandle() {
        val rawWithApp = RawData(url = "https://example.com/foo", json = "{}", hostApp = XiaoheiheHook.HOST_APP, timestamp = 0L)
        assertTrue(XiaoheiheParser.canHandle("https://api.xiaoheihe.cn/bbs/app/link/tree/v2", rawWithApp))
        assertTrue(XiaoheiheParser.canHandle("https://api.xiaoheihe.cn/bbs/app/comment/sub/comments", rawWithApp))
        assertTrue(XiaoheiheParser.canHandle("https://api.maxjia.com/bbs/app/link/tree", rawWithApp))

        val rawOtherApp = RawData(url = "https://example.com/foo", json = "{}", hostApp = "com.other.app", timestamp = 0L)
        assertTrue(XiaoheiheParser.canHandle("https://api.xiaoheihe.cn/bbs/app/link/tree/v2", rawOtherApp))
        assertFalse(XiaoheiheParser.canHandle("https://example.com/bbs/app/link/tree", rawOtherApp))
        assertFalse(XiaoheiheParser.canHandle("https://api.xiaoheihe.cn/other/endpoint", rawOtherApp))
    }

    @Test
    fun testParseLinkTreeV2() {
        val json = readSample("link_tree_v2.json")
        val records = parse("https://api.xiaoheihe.cn/bbs/app/link/tree/v2?link_id=197066485", json)

        // 1 post + 37 comments = 38 records
        assertEquals(38, records.size)

        val post = records.first { it.recordType == "post" }
        assertEquals("xiaoheihe", post.platform)
        assertEquals("xiaoheihe_post_197066485", post.platformId)
        assertEquals("为什么 GTA 6已经有1个玩家了呀[cube_惊讶]", post.title)
        assertEquals("胡咖啡", post.authorName)
        assertEquals("90299894", post.authorId)
        assertTrue(post.authorAvatar.contains("c0f8e34455286b2368b0abc0e39a604f"))
        assertEquals("", post.parentId)
        assertEquals(1794821180000L, post.publishTime)
        assertEquals(RecordBodyFormat.MARKDOWN.storageValue, post.contentFormat)
        assertTrue(post.content.contains("全小黑盒唯一吗"))
        assertTrue(post.content.contains("![image](https://imgheybox1.max-c.com/bbs/2026/11/16/6adf705f1a82959eb8a28db117e353d5/thumb.jpeg"))
        assertTrue(post.content.contains("> **相关搜索**: 侠盗猎车手6 首发平台"))

        assertEquals(RecordIdentityKind.POST, post.identity.kind)
        assertTrue(RecordContentPolicy.isPrimaryContent(post))

        val adapters = PlatformMetadataAdapters(post.extraMetadata, post.platform)
        assertNotNull(adapters.xiaoheihe)
        assertEquals("陕西", adapters.xiaoheihe?.ipLocation)
        assertEquals(22L, adapters.engagement.likeCount)
        assertEquals(50L, adapters.engagement.replyCount)

        // Verify root comment
        val rootComment = records.first { it.platformId == "xiaoheihe_comment_1002022354" }
        assertEquals("comment", rootComment.recordType)
        assertEquals("xiaoheihe_post_197066485", rootComment.parentId)
        assertEquals("过--境", rootComment.authorName)
        assertEquals("33083782", rootComment.authorId)
        assertTrue(rootComment.content.contains("这个人是小黑盒的将军"))
        assertEquals(1794822582000L, rootComment.publishTime)
        assertEquals(RecordBodyFormat.PLAIN_TEXT.storageValue, rootComment.contentFormat)
        assertEquals(RecordIdentityKind.COMMENT, rootComment.identity.kind)
        assertFalse(RecordContentPolicy.isPrimaryContent(rootComment))

        val rootCommentMeta = PlatformMetadataAdapters(rootComment.extraMetadata, rootComment.platform)
        assertEquals("3", rootCommentMeta.xiaoheihe?.floor)
        assertEquals(214L, rootCommentMeta.engagement.likeCount)
        assertEquals(8L, rootCommentMeta.engagement.replyCount)

        val relation = rootComment.directRelation()
        assertTrue(relation is RecordRelation.ReplyTo)
        assertEquals("xiaoheihe_post_197066485", (relation as RecordRelation.ReplyTo).target.contentId)

        // Verify subcomment
        val subComment = records.first { it.platformId == "xiaoheihe_comment_1002186689" }
        assertEquals("comment", subComment.recordType)
        assertEquals("xiaoheihe_comment_1002022354", subComment.parentId)
        assertEquals("楊默", subComment.authorName)
        assertEquals("回复 过--境", subComment.title)
        assertEquals("\\😭/", subComment.content)
        assertEquals(1794836759000L, subComment.publishTime)

        val subRelation = subComment.directRelation()
        assertTrue(subRelation is RecordRelation.ReplyTo)
        assertEquals("xiaoheihe_comment_1002022354", (subRelation as RecordRelation.ReplyTo).target.contentId)
    }

    @Test
    fun testParsePost2WithSubcommentReplies() {
        val json = readSample("post2.json")
        val records = parse("https://api.xiaoheihe.cn/bbs/app/link/tree/v2?link_id=196812764", json)

        // 1 post + 60 comments = 61 records
        assertEquals(61, records.size)

        val post = records.first { it.recordType == "post" }
        assertEquals("xiaoheihe_post_196812764", post.platformId)
        assertEquals("gta6的兑换码可以用手电筒直接看见", post.title)
        assertTrue(post.content.contains("现在收gta6的盘要全程录视频"))

        // Subcomment replying to another subcomment
        // cid=999595611 replies to cid=999593509
        val nestedReply = records.first { it.platformId == "xiaoheihe_comment_999595611" }
        assertEquals("xiaoheihe_comment_999593509", nestedReply.parentId)
        assertEquals("九一伍n", nestedReply.authorName)
        assertEquals("回复 酱味大几", nestedReply.title)
    }

    @Test
    fun testParseSubCommentsApi() {
        val json = """
            {
                "status": "ok",
                "result": {
                    "comments": [
                        {
                            "commentid": 888001,
                            "replyid": 777001,
                            "text": "测试楼中楼回复",
                            "create_at": 1794830000,
                            "up": 10,
                            "user": {
                                "userid": 12345,
                                "username": "小盒测试员",
                                "avatar": "https://img.example.com/avatar.png"
                            },
                            "replyuser": {
                                "userid": 67890,
                                "username": "原评论者"
                            },
                            "ip_location": "北京"
                        }
                    ],
                    "has_more": "0"
                }
            }
        """.trimIndent()

        val records = parse(
            "https://api.xiaoheihe.cn/bbs/app/comment/sub/comments?root_comment_id=777001&link_id=197066485",
            json
        )
        assertEquals(1, records.size)
        val comment = records.first()
        assertEquals("xiaoheihe", comment.platform)
        assertEquals("comment", comment.recordType)
        assertEquals("xiaoheihe_comment_888001", comment.platformId)
        assertEquals("xiaoheihe_comment_777001", comment.parentId)
        assertEquals("小盒测试员", comment.authorName)
        assertEquals("12345", comment.authorId)
        assertEquals("回复 原评论者", comment.title)
        assertEquals("测试楼中楼回复", comment.content)
        assertEquals(1794830000000L, comment.publishTime)

        val meta = PlatformMetadataAdapters(comment.extraMetadata, comment.platform)
        assertEquals("北京", meta.xiaoheihe?.ipLocation)
        assertEquals(10L, meta.engagement.likeCount)
    }
}
