package com.melonhu.indexrecord.parser.nga

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.hook.NgaHook
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.ParserRegistry
import com.melonhu.indexrecord.presentation.RecordContentPolicy
import com.melonhu.indexrecord.presentation.platformEngagement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NgaParserTest {

    @Test
    fun `parses nga post and replies with nested quote parent`() {
        val json = """
            {
              "code": 0,
              "msg": "操作成功",
              "vrows": 57,
              "attachPrefix": "https://img.nga.cn/attachments/",
              "tsubject": "[新游杂谈]FF7评测",
              "tauthorid": 15403840,
              "tauthor": "hjyx01",
              "fid": 414,
              "forum_name": "游戏综合讨论",
              "result": [
                {
                  "pid": 0,
                  "fid": 414,
                  "tid": 47912491,
                  "lou": 0,
                  "subject": "",
                  "content": "正文第一段<br/>[img]https://img.nga.cn/attachments/mon_202611/13/a.jpg[/img]<br/>[tid=30040656]往期评测[/tid]",
                  "postdatetimestamp": 1794564555,
                  "vote_good": 43,
                  "vote_bad": 0,
                  "from_client": "0 /",
                  "comment_to_id": "",
                  "author": {
                    "uid": 15403840,
                    "username": "hjyx01",
                    "avatar": "http://img.nga.cn/avatars/15403840_0.jpg?88%7c.a/15403840_1.jpg?37"
                  }
                },
                {
                  "pid": 888012054,
                  "fid": 414,
                  "tid": 47912491,
                  "lou": 2,
                  "subject": "",
                  "content": "确实做得很不错[s:a2:doge]",
                  "postdatetimestamp": 1794755910,
                  "vote_good": 28,
                  "vote_bad": 0,
                  "from_client": "8 Android",
                  "comment_to_id": "",
                  "author": {
                    "uid": 64754401,
                    "username": "我很难不变态",
                    "avatar": "http://img.nga.cn/avatars/64754401_0.jpg"
                  }
                },
                {
                  "pid": 888078033,
                  "fid": 414,
                  "tid": 47912491,
                  "lou": 9,
                  "subject": "",
                  "content": "[quote][pid=888012054,47912491,1]Reply[/pid] <b>Post by [uid=64754401]我很难不变态[/uid]:</b><br/>确实做得很不错[/quote]异度2地图引导稀烂",
                  "postdatetimestamp": 1794799000,
                  "vote_good": 5,
                  "vote_bad": 0,
                  "from_client": "8 Android",
                  "comment_to_id": "",
                  "author": {
                    "uid": 10002,
                    "username": "回复者B",
                    "avatar": ""
                  }
                }
              ]
            }
        """.trimIndent()

        val url = "https://ngabbs.com/app_api.php?__lib=post&__act=list&tid=47912491&page=1"
        val cleanUrl = "https://ngabbs.com/app_api.php"
        val raw = RawData(
            url = url,
            hostApp = NgaHook.HOST_APP,
            json = json,
            timestamp = 1795000000000L
        )

        val result = ParserRegistry.parseResult(raw, cleanUrl)
        assertTrue(result is ParseResult.Success)
        val records = result.records
        assertEquals(3, records.size)

        val post = records[0]
        assertEquals("nga_post_47912491", post.platformId)
        assertEquals("nga", post.platform)
        assertEquals("post", post.recordType)
        assertEquals("[新游杂谈]FF7评测", post.title)
        assertEquals("hjyx01", post.authorName)
        assertEquals("15403840", post.authorId)
        assertEquals("https://img.nga.cn/avatars/15403840_0.jpg?88", post.authorAvatar)
        assertEquals("", post.parentId)
        assertEquals(1794564555000L, post.publishTime)
        assertEquals(RecordBodyFormat.HTML.storageValue, post.contentFormat)
        assertTrue(post.content.contains("""<img src="https://img.nga.cn/attachments/mon_202611/13/a.jpg"/>"""))
        assertTrue(post.content.contains("""<a href="https://ngabbs.com/read.php?tid=30040656">往期评测</a>"""))
        assertTrue(RecordContentPolicy.isPrimaryContent(post))
        val postEngagement = platformEngagement("nga", JsonParser.parseString(post.extraMetadata).asJsonObject)
        assertEquals(43L, postEngagement.likeCount)
        assertEquals(56L, postEngagement.replyCount)

        val reply1 = records[1]
        assertEquals("nga_comment_888012054", reply1.platformId)
        assertEquals("comment", reply1.recordType)
        assertEquals("nga_post_47912491", reply1.parentId)
        assertTrue(reply1.content.contains("[doge]"))
        assertFalse(RecordContentPolicy.isPrimaryContent(reply1))
        assertEquals(28L, platformEngagement("nga", JsonParser.parseString(reply1.extraMetadata).asJsonObject).likeCount)

        val reply2 = records[2]
        assertEquals("nga_comment_888078033", reply2.platformId)
        assertEquals("nga_comment_888012054", reply2.parentId)
        assertTrue(reply2.content.contains("<blockquote>"))
        assertTrue(reply2.content.contains("@我很难不变态"))
        assertTrue(reply2.content.contains("<p>异度2地图引导稀烂</p>"))
    }

    @Test
    fun `converts direct reply-to header into blockquote like hupu`() {
        val url = "https://ngabbs.com/app_api.php?__lib=post&__act=list&tid=47912491&page=1"
        val raw = RawData(
            url = url,
            hostApp = NgaHook.HOST_APP,
            json = """
                {
                  "code": 0,
                  "tsubject": "测试",
                  "result": [
                    {
                      "pid": 888128148,
                      "tid": 47912491,
                      "lou": 19,
                      "content": "<b>Reply to [pid=888099240,47912491,1]Reply[/pid] Post by [uid=65052284]地狱叉[/uid] (2026-11-16 18:16)</b> <br/>[s:a2:doge]老实说你在看什么二创？",
                      "postdatetimestamp": 1794800000,
                      "author": {"uid": 65642294, "username": "UnicornOverlord", "avatar": ""}
                    }
                  ]
                }
            """.trimIndent(),
            timestamp = 1795000000000L
        )
        val result = ParserRegistry.parseResult(raw, "https://ngabbs.com/app_api.php")
        assertTrue(result is ParseResult.Success)
        val reply = result.records.single()
        assertEquals("nga_comment_888099240", reply.parentId)
        assertEquals("<blockquote>回复 @地狱叉 (2026-11-16 18:16)</blockquote><p>[doge]老实说你在看什么二创？</p>", reply.content)
    }

    @Test
    fun `ignores non-zero error code responses`() {
        val url = "https://ngabbs.com/app_api.php?__lib=post&__act=list&tid=123"
        val raw = RawData(
            url = url,
            hostApp = NgaHook.HOST_APP,
            json = """{"code": 1, "msg": "帖子不存在"}""",
            timestamp = 1795000000000L
        )
        val result = ParserRegistry.parseResult(raw, "https://ngabbs.com/app_api.php")
        assertTrue(result is ParseResult.Success)
        assertTrue(result.records.isEmpty())
    }
}
