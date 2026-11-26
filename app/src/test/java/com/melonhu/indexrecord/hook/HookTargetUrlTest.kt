package com.melonhu.indexrecord.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookTargetUrlTest {

    @Test
    fun testZhihuTargetUrls() {
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/articles/12345"))
        assertTrue(ZhihuHook.isTargetUrl("https://www.zhihu.com/api/v4/articles/12345"))
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/answers/67890"))
        assertTrue(ZhihuHook.isTargetUrl("https://www.zhihu.com/api/v4/answers/67890"))
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/pins/v2/11111"))
        assertTrue(ZhihuHook.isTargetUrl("https://www.zhihu.com/api/v4/pins/11111?include=topics"))
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/appview/v2/answer/22222"))
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/questions/33333"))
        assertTrue(ZhihuHook.isTargetUrl("https://www.zhihu.com/api/v4/questions/33333"))
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/comment_v5/answers/67890/root_comment"))
        assertTrue(ZhihuHook.isTargetUrl("https://www.zhihu.com/api/v4/comment_v5/answers/67890/root_comment"))
        assertTrue(ZhihuHook.isTargetUrl("https://www.zhihu.com/next-render"))

        assertTrue(TiebaHook.isTargetUrl("https://tiebac.baidu.com/c/f/pb/page?cmd=302001&format=protobuf"))
        assertTrue(TiebaHook.isTargetUrl("https://tiebac.baidu.com/c/f/pb/floor?cmd=302002&format=protobuf"))
        assertFalse(TiebaHook.isTargetUrl("https://tiebac.baidu.com/c/f/frs/page?cmd=301001"))

        assertFalse(ZhihuHook.isTargetUrl("https://api.zhihu.com/user/profile"))
        assertFalse(ZhihuHook.isTargetUrl("https://example.com/answers/123"))
        assertFalse(ZhihuHook.isTargetUrl(null))
    }

    @Test
    fun testDoubanTargetUrls() {
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/group/topic/12345"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/status/hot_interests"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/status/10033798803?apikey=abc"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/status/10033798803/comments?start=0"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/status/comment/468264521/replies"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/note/878617681?video_process=0&event_source=user_profile"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/note/878617681/comments?start=0&count=20&nested=1"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/note/comment/76509791/replies?start=3&count=5"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/review/12345"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/forum_topic/12345"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/movie/12345"))

        assertFalse(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/status/10033798803/allow_comment"))
        assertFalse(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/status/10033798803/gifts"))
        assertFalse(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/status/10033798803/reactions"))
        assertFalse(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/note/878617681/allow_comment"))
        assertFalse(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/note/878617681/gifts"))
        assertFalse(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/note/878617681/related_contents"))
        assertFalse(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/user/settings"))
        assertFalse(DoubanHook.isTargetUrl("https://example.com/group/topic/123"))
        assertFalse(DoubanHook.isTargetUrl(null))
    }

    @Test
    fun testXTargetUrls() {
        assertTrue(XHook.isTargetUrl("https://api.twitter.com/graphql/abcdef/HomeTimeline"))
        assertTrue(XHook.isTargetUrl("https://api.x.com/graphql/abcdef/ConversationTimeline"))
        assertTrue(XHook.isTargetUrl("https://twitter.com/i/api/2/timeline/home.json"))
        assertTrue(XHook.isTargetUrl("https://x.com/graphql/UserTweets"))

        // Excluded
        assertFalse(XHook.isTargetUrl("https://api.twitter.com/graphql/GenerateAttestation"))
        assertFalse(XHook.isTargetUrl("https://api.twitter.com/1.1/ClientEvent"))
        assertFalse(XHook.isTargetUrl("https://api.twitter.com/graphql/ExchangeClientDataForAttestation"))
        assertFalse(XHook.isTargetUrl("https://other.com/graphql/HomeTimeline"))
        assertFalse(XHook.isTargetUrl(null))
    }

    @Test
    fun testWeiboTargetUrls() {
        assertTrue(WeiboHook.isTargetUrl("https://api.weibo.cn/2/statuses/show"))
        assertTrue(WeiboHook.isTargetUrl("https://api.weibo.com/2/comments/build_comments"))
        assertTrue(WeiboHook.isTargetUrl("https://m.weibo.cn/api/container/getIndex?containerid=100103"))
        assertTrue(WeiboHook.isTargetUrl("https://api.weibo.cn/2/cardlist"))
        assertTrue(WeiboHook.isTargetUrl("https://api.weibo.com/2/searchall"))

        assertFalse(WeiboHook.isTargetUrl("https://api.weibo.cn/2/client/userinfo"))
        assertFalse(WeiboHook.isTargetUrl("https://notweibo.com/statuses/show"))
        assertFalse(WeiboHook.isTargetUrl(null))
    }

    @Test
    fun testThreadsTargetUrls() {
        assertTrue(ThreadsHook.isTargetUrl("https://i.instagram.com/api/v1/text_feed/timeline/"))
        assertTrue(ThreadsHook.isTargetUrl("https://www.threads.net/api/v1/feed/"))
        assertTrue(ThreadsHook.isTargetUrl("https://i.instagram.com/api/v1/media/12345/info/"))
        assertTrue(ThreadsHook.isTargetUrl("https://www.threads.net/graphql/query"))

        // Excluded
        assertFalse(ThreadsHook.isTargetUrl("https://i.instagram.com/api/v1/notifications/get/"))
        assertFalse(ThreadsHook.isTargetUrl("https://i.instagram.com/logging_client_events"))
        assertFalse(ThreadsHook.isTargetUrl("https://other.com/text_feed/"))
        assertFalse(ThreadsHook.isTargetUrl(null))
    }

    @Test
    fun testRedditTargetUrls() {
        assertTrue(RedditHook.isTargetUrl("https://gql-fed.reddit.com/graphql"))
        assertFalse(RedditHook.isTargetUrl("http://gql-fed.reddit.com/graphql"))
        assertFalse(RedditHook.isTargetUrl("https://oauth.reddit.com/api/v1/me"))
        assertFalse(RedditHook.isTargetUrl("https://reddit.com/r/all"))
    }

    @Test
    fun testCoolapkTargetUrls() {
        assertTrue(CoolapkHook.isTargetUrl("https://api2.coolapk.com/v6/feed/detail?id=75046118"))
        assertTrue(CoolapkHook.isTargetUrl("https://api.coolapk.com/v6/feed/detail?id=12345"))
        assertTrue(CoolapkHook.isTargetUrl("https://api.coolapk.com/v6/feed/replyList?id=75091227&listType=lastupdate_desc&page=1"))
        assertTrue(CoolapkHook.isTargetUrl("https://api.coolapk.com/v6/feed/replyDetail?id=617293084"))
        assertFalse(CoolapkHook.isTargetUrl("https://api.coolapk.com/v6/main/indexV8"))
        assertFalse(CoolapkHook.isTargetUrl("https://example.com/v6/feed/detail?id=123"))
        assertFalse(CoolapkHook.isTargetUrl(null))
    }

    @Test
    fun testHupuTargetUrls() {
        assertTrue(HupuHook.isTargetUrl("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/643989029"))
        assertTrue(HupuHook.isTargetUrl("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/refactor/643971336"))
        assertTrue(HupuHook.isTargetUrl("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/getsThreadPostList?tid=643989029"))
        assertTrue(HupuHook.isTargetUrl("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/getsThreadLightReplyList?tid=643989029"))
        assertTrue(HupuHook.isTargetUrl("https://bbs.mobileapi.hupu.com/1/8.2.51/threads/getCheckReply?pid=38446&tid=643879180"))

        // Excluded
        assertFalse(HupuHook.isTargetUrl("https://e-goblin.hupu.com/rm?placement_type=2"))
        assertFalse(HupuHook.isTargetUrl("http://collect.hupu.com/app"))
        assertFalse(HupuHook.isTargetUrl("https://hermes.hupu.com/server/ts"))
        assertFalse(HupuHook.isTargetUrl("https://games.mobileapi.hupu.com/1/8.2.51/bplcommentapi/relation/score/statement"))
        assertFalse(HupuHook.isTargetUrl("https://bbs.mobileapi.hupu.com/1/8.2.51/bbsevaluateapi/evaluate/info"))
        assertFalse(HupuHook.isTargetUrl("https://example.com/threads/123"))
        assertFalse(HupuHook.isTargetUrl(null))
    }

    @Test
    fun testXiaoheiheTargetUrls() {
        assertTrue(XiaoheiheHook.isTargetUrl("https://api.xiaoheihe.cn/bbs/app/link/tree/v2?link_id=197066485"))
        assertTrue(XiaoheiheHook.isTargetUrl("https://api.xiaoheihe.cn/bbs/app/comment/sub/comments?root_comment_id=1002022354"))
        assertTrue(XiaoheiheHook.isTargetUrl("https://api.xiaoheihe.cn/bbs/app/link/web/view?link_id=197066485"))
        assertTrue(XiaoheiheHook.isTargetUrl("https://api.maxjia.com/bbs/app/link/tree/v2?link_id=123"))
        assertTrue(XiaoheiheHook.isTargetUrl("https://heybox.maxjia.com/bbs/app/link/tree?link_id=123"))

        // Excluded
        assertFalse(XiaoheiheHook.isTargetUrl("https://data.xiaoheihe.cn/account/data_report/"))
        assertFalse(XiaoheiheHook.isTargetUrl("https://api.xiaoheihe.cn/bbs/app/profile/follow/user"))
        assertFalse(XiaoheiheHook.isTargetUrl("https://example.com/bbs/app/link/tree"))
        assertFalse(XiaoheiheHook.isTargetUrl(null))
    }

    @Test
    fun testBlueskyTargetUrls() {
        assertTrue(BlueskyHook.isTargetUrl("https://enoki.us-east.host.bsky.network/xrpc/app.bsky.feed.getTimeline?limit=30"))
        assertTrue(BlueskyHook.isTargetUrl("https://enoki.us-east.host.bsky.network/xrpc/app.bsky.feed.getFeed?feed=at://did:plc:abc"))
        assertTrue(BlueskyHook.isTargetUrl("https://enoki.us-east.host.bsky.network/xrpc/app.bsky.unspecced.getPostThreadV2?anchor=at://did:plc:abc"))
        assertTrue(BlueskyHook.isTargetUrl("https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread?uri=at://did:plc:abc"))

        assertFalse(BlueskyHook.isTargetUrl("https://enoki.us-east.host.bsky.network/xrpc/app.bsky.actor.getPreferences"))
        assertFalse(BlueskyHook.isTargetUrl("https://enoki.us-east.host.bsky.network/xrpc/chat.bsky.convo.getLog"))
        assertFalse(BlueskyHook.isTargetUrl(null))
    }

    @Test
    fun testNgaTargetUrls() {
        assertTrue(NgaHook.isTargetUrl("https://ngabbs.com/app_api.php?__lib=post&__act=list&tid=47912491&page=1"))
        assertTrue(NgaHook.isTargetUrl("https://bbs.nga.cn/app_api.php?__lib=post&__act=list"))
        assertTrue(NgaHook.isTargetUrl("https://nga.178.com/read.php?tid=47912491"))

        assertFalse(NgaHook.isTargetUrl("https://ngabbs.com/nuke.php?__lib=check_in&__act=get_stat"))
        assertFalse(NgaHook.isTargetUrl("https://ngabbs.com/nuke.php?__lib=app_inter&__act=static_file_list"))
        assertFalse(NgaHook.isTargetUrl("https://example.com/app_api.php?__lib=post&__act=list"))
        assertFalse(NgaHook.isTargetUrl(null))
    }

    @Test
    fun testReflectionHelper() {
        assertEquals(java.lang.Integer::class.java, ReflectionHelper.wrapPrimitive(Int::class.javaPrimitiveType))
        assertEquals(java.lang.Boolean::class.java, ReflectionHelper.wrapPrimitive(Boolean::class.javaPrimitiveType))
        assertEquals(String::class.java, ReflectionHelper.wrapPrimitive(String::class.java))

        val method = ReflectionHelper.findMethodExact(String::class.java, "substring", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        assertNotNull(method)

        val result = ReflectionHelper.callMethod("HelloWorld", "substring", 0, 5)
        assertEquals("Hello", result)
    }
}
