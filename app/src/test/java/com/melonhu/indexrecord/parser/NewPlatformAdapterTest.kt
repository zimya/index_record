package com.melonhu.indexrecord.parser

import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.hook.BilibiliHook
import com.melonhu.indexrecord.hook.DCardHook
import com.melonhu.indexrecord.hook.GoodreadsHook
import com.melonhu.indexrecord.hook.LofterHook
import com.melonhu.indexrecord.hook.MastodonHook
import com.melonhu.indexrecord.hook.MediumHook
import com.melonhu.indexrecord.hook.NetEaseMusicHook
import com.melonhu.indexrecord.hook.WeReadHook
import com.melonhu.indexrecord.parser.bilibili.BilibiliParser
import com.melonhu.indexrecord.parser.dcard.DCardParser
import com.melonhu.indexrecord.parser.mastodon.MastodonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewPlatformAdapterTest {

    @Test
    fun mastodonStatusAndContextBecomePostsAndReplies() {
        val status = """
            {
              "id": "111",
              "created_at": "2024-05-01T10:00:00.000Z",
              "in_reply_to_id": "100",
              "content": "<p>Hello <a href=\"https://example.social/@a\">@a</a></p>",
              "spoiler_text": "",
              "url": "https://example.social/@me/111",
              "account": {"id": "9", "display_name": "Me", "acct": "me", "avatar": "https://cdn/av.png"},
              "favourites_count": 4,
              "replies_count": 1,
              "media_attachments": []
            }
        """.trimIndent()
        val url = "https://example.social/api/v1/timelines/home"
        val record = MastodonParser.parseResult(RawData(url = url, json = status), url).records.single()

        assertEquals("mastodon", record.platform)
        assertEquals("comment", record.recordType)
        assertEquals("mastodon_111", record.platformId)
        assertEquals("mastodon_100", record.parentId)
        assertEquals("html", record.contentFormat)
        assertEquals("Me", record.authorName)
        assertTrue(record.content.contains("Hello"))
    }

    @Test
    fun mastodonContextReturnsAncestorsAndDescendants() {
        val json = """
            {"ancestors": [], "descendants": [
              {"id": "201", "created_at": "2024-05-01T10:00:00.000Z", "in_reply_to_id": "200",
               "content": "<p>reply</p>", "account": {"id": "3", "acct": "bob", "display_name": "Bob"}}
            ]}
        """.trimIndent()
        val url = "https://example.social/api/v1/statuses/200/context"
        val records = MastodonParser.parseResult(RawData(url = url, json = json), url).records

        assertEquals(1, records.size)
        assertEquals("mastodon_201", records.single().platformId)
    }

    @Test
    fun bilibiliReplyKeepsArticleParent() {
        val json = """
            {"code": 0, "data": {"page": {"count": 1}, "replies": [
              {"rpid": "42", "oid": "8090", "type": 12, "ctime": 1714557600,
               "content": {"message": "写得不错"}, "like": 7, "rcount": 0,
               "member": {"mid": "5", "uname": "reader", "avatar": "https://i0.hdslb.com/a.jpg"}}
            ]}}
        """.trimIndent()
        val url = "https://api.bilibili.com/x/v2/reply?type=12&oid=8090&pn=1"
        val record = BilibiliParser.parseResult(RawData(url = url, json = json), url).records.single()

        assertEquals("comment", record.recordType)
        assertEquals("bili_reply_42", record.platformId)
        assertEquals("bili_article_8090", record.parentId)
        assertEquals("写得不错", record.content)
    }

    @Test
    fun dcardPostAndCommentsAreSeparated() {
        val post = """
            {"id": "abc123", "title": "選課心得", "content": "正文", "likeCount": 12,
             "commentCount": 3, "school": "NTU", "createdAt": "2024-05-01T10:00:00.000Z",
             "topics": [{"name": "校園"}]}
        """.trimIndent()
        val postUrl = "https://www.dcard.tw/service/api/v2/posts/abc123"
        val postRecord = DCardParser.parseResult(RawData(url = postUrl, json = post), postUrl).records.single()
        assertEquals("dcard_post_abc123", postRecord.platformId)
        assertEquals("選課心得", postRecord.title)

        val comment = """[{"id": "c1", "content": "推", "floor": 1, "createdAt": "2024-05-01T11:00:00.000Z"}]"""
        val commentUrl = "https://www.dcard.tw/service/api/v2/posts/abc123/comments"
        val commentRecord = DCardParser.parseResult(RawData(url = commentUrl, json = comment), commentUrl)
            .records.single()
        assertEquals("dcard_comment_c1", commentRecord.platformId)
        assertEquals("dcard_post_abc123", commentRecord.parentId)
        assertEquals("comment", commentRecord.recordType)
    }

    @Test
    fun newPlatformHooksKeepTheirOwnHosts() {
        assertTrue(MastodonHook.isTargetUrl("https://example.social/api/v1/statuses/1"))
        assertTrue(BilibiliHook.isTargetUrl("https://api.bilibili.com/x/article/view?id=1"))
        assertTrue(MediumHook.isTargetUrl("https://medium.com/_/graphql"))
        assertTrue(GoodreadsHook.isTargetUrl("https://www.goodreads.com/review/show/123"))
        assertTrue(LofterHook.isTargetUrl("https://www.lofter.com/post/abc_123"))
        assertTrue(DCardHook.isTargetUrl("https://www.dcard.tw/service/api/v2/posts/1"))
        assertTrue(WeReadHook.isTargetUrl("https://weread.qq.com/web/book/info?bookId=1"))
        assertTrue(NetEaseMusicHook.isTargetUrl("https://music.163.com/api/v1/comment/music?id=1"))

        assertFalse(BilibiliHook.isTargetUrl("https://api.bilibili.com/x/player/playurl?avid=1"))
        assertFalse(MastodonHook.isTargetUrl("https://example.social/oauth/token"))
        assertFalse(NetEaseMusicHook.isTargetUrl("https://example.com/api/comment"))
        assertFalse(DCardHook.isTargetUrl(null))
    }
}
