package com.melonhu.indexrecord.hook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.URI
import java.net.URL

class CoolapkHookTest {

    // 模拟 R8 混淆后的 OkHttp4 类型与方法签名
    interface ObfInterceptor {
        fun a(chain: ObfChain): ObfResponse
    }

    interface ObfChain {
        fun a(): ObfRequest
        fun b(request: ObfRequest): ObfResponse
    }

    class ObfHttpUrl(private val raw: String) {
        fun a(): URL = URL(raw)
        fun b(): URI = URI(raw)
        override fun toString(): String = raw
    }

    class ObfRequest(private val u: ObfHttpUrl) {
        fun g(): String = "GET"
        fun h(): ObfHttpUrl = u
    }

    abstract class ObfResponseBody {
        abstract fun a(): ByteArray
    }

    class ObfResponse(
        private val req: ObfRequest,
        private val ct: String,
        private val payload: ByteArray
    ) {
        fun a(): ObfRequest = req
        fun b(name: String): String? = if (name.equals("Content-Type", ignoreCase = true)) ct else null
        fun c(maxBytes: Long): ObfResponseBody = object : ObfResponseBody() {
            override fun a(): ByteArray = payload.copyOf(minOf(payload.size, maxBytes.toInt()))
        }
    }

    class FakeCoolMarketHeaderInterceptor : ObfInterceptor {
        override fun a(chain: ObfChain): ObfResponse = chain.b(chain.a())
    }

    @Test
    fun resolveObfuscatedMethodsFromInterceptWorksWithRenamedOkHttp() {
        val interceptMethod = FakeCoolMarketHeaderInterceptor::class.java.getDeclaredMethod(
            "a",
            ObfChain::class.java
        )
        val resolved = CoolapkHook.resolveObfuscatedMethodsFromIntercept(interceptMethod)
        assertNotNull(resolved)
        resolved!!

        val expectedUrl = "https://api.coolapk.com/v6/feed/detail?id=75046118"
        val expectedBytes = """{"data":{"id":"75046118"}}""".toByteArray()
        val req = ObfRequest(ObfHttpUrl(expectedUrl))
        val resp = ObfResponse(req, "application/json", expectedBytes)
        val fakeChain = object : ObfChain {
            override fun a(): ObfRequest = req
            override fun b(request: ObfRequest): ObfResponse = resp
        }

        val resolvedReq = resolved.responseRequest?.invoke(resp) ?: resolved.chainRequest.invoke(fakeChain)
        assertNotNull(resolvedReq)
        assertEquals(expectedUrl, resolved.requestUrl.invoke(resolvedReq)?.toString())
        assertEquals("application/json", resolved.responseHeader.invoke(resp, "Content-Type"))

        val peeked = resolved.responsePeekBody.invoke(resp, 5 * 1024 * 1024L)
        assertNotNull(peeked)
        val actualBytes = resolved.bodyBytes.invoke(peeked) as ByteArray
        assertArrayEquals(expectedBytes, actualBytes)
    }
}
