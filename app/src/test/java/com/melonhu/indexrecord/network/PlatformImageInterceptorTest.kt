package com.melonhu.indexrecord.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformImageInterceptorTest {

    @Test
    fun appliesPlatformHeadersAndUpgradesBilibiliHttp() {
        val doubanReq = PlatformImageInterceptor.rewrite(
            Request.Builder().url("https://img9.doubanio.com/view/photo/l/public/p1.jpg").build()
        )
        assertTrue(doubanReq.header("User-Agent")?.contains("com.douban.frodo") == true)
        assertEquals("https://frodo.douban.com/", doubanReq.header("Referer"))

        val coolapkReq = PlatformImageInterceptor.rewrite(
            Request.Builder().url("https://image.coolapk.com/feed/1.jpg").build()
        )
        assertTrue(coolapkReq.header("User-Agent")?.contains("#CoolMarket#") == true)
        assertNull(coolapkReq.header("Referer"))

        val zhihuReq = PlatformImageInterceptor.rewrite(
            Request.Builder().url("https://picx.zhimg.com/v2-abc.jpg").build()
        )
        assertTrue(zhihuReq.header("User-Agent")?.contains("Chrome/130.0.0.0") == true)
        assertEquals("https://www.zhihu.com/", zhihuReq.header("Referer"))

        val biliReq = PlatformImageInterceptor.rewrite(
            Request.Builder().url("http://i0.hdslb.com/bfs/archive/cover.jpg").build()
        )
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", biliReq.url.toString())
        assertTrue(biliReq.header("User-Agent")?.contains("Chrome/130.0.0.0") == true)
    }
}
