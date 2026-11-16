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
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/answers/67890"))
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/pins/v2/11111"))
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/appview/v2/answer/22222"))
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/questions/33333"))
        assertTrue(ZhihuHook.isTargetUrl("https://api.zhihu.com/comment_v5/answers/67890/root_comment"))
        assertTrue(ZhihuHook.isTargetUrl("https://www.zhihu.com/next-render"))

        assertFalse(ZhihuHook.isTargetUrl("https://api.zhihu.com/user/profile"))
        assertFalse(ZhihuHook.isTargetUrl("https://example.com/answers/123"))
        assertFalse(ZhihuHook.isTargetUrl(null))
    }

    @Test
    fun testDoubanTargetUrls() {
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/group/topic/12345"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/status/hot_interests"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/review/12345"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/forum_topic/12345"))
        assertTrue(DoubanHook.isTargetUrl("https://frodo.douban.com/api/v2/movie/12345"))

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
