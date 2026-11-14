package com.melonhu.indexrecord

import com.melonhu.indexrecord.parser.zhihu.ZhihuCaptureDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class ZhihuCaptureDecoderTest {
    private val json = """{"id":"123","content":"decoded answer"}""".toByteArray()
    private val brotli = Base64.getDecoder().decode(
        "GyYA+I3UYs0Z7gR1slTtsoIYvSBZVzp9DYMklBSkRADlnxWILBwEmr5klw=="
    )

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    @Test fun decodesBrotliBeforeLossyTextConversion() {
        assertArrayEquals(json, ZhihuCaptureDecoder.decode(brotli, "br"))
        assertEquals("123", com.google.gson.JsonParser.parseString(
            String(ZhihuCaptureDecoder.decode(brotli, "br"), Charsets.UTF_8)
        ).asJsonObject["id"].asString)
    }

    @Test fun preservesAlreadyDecodedJsonAndHtml() {
        assertArrayEquals(json, ZhihuCaptureDecoder.decode(json, null))
        val html = "<html><script id=\"js-initialData\">知乎回答</script></html>".toByteArray()
        assertArrayEquals(html, ZhihuCaptureDecoder.decode(html, "identity"))
        assertArrayEquals(html, ZhihuCaptureDecoder.decode(gzip(html), "gzip"))
    }

    @Test fun supportsGzipDeflateAndStackedCodings() {
        assertArrayEquals(json, ZhihuCaptureDecoder.decode(gzip(json), " GZip "))
        val deflated = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(json) }
        }.toByteArray()
        assertArrayEquals(json, ZhihuCaptureDecoder.decode(deflated, "deflate"))
        assertArrayEquals(json, ZhihuCaptureDecoder.decode(gzip(brotli), "br, gzip"))
    }

    @Test(expected = IOException::class) fun rejectsUnsupportedCodingInsteadOfQueueingBinary() {
        ZhihuCaptureDecoder.decode(json, "unknown")
    }

    @Test(expected = IOException::class) fun rejectsTruncatedGzipInsteadOfQueueingGarbage() {
        ZhihuCaptureDecoder.decode(gzip(json).copyOf(12), "gzip")
    }

    @Test(expected = IOException::class) fun boundsDecompressedSize() {
        ZhihuCaptureDecoder.decode(gzip(ByteArray(ZhihuCaptureDecoder.MAX_BYTES + 1)), "gzip")
    }

    @Test(expected = IOException::class) fun rejectsOversizedUncompressedBody() {
        ZhihuCaptureDecoder.decode(ByteArray(ZhihuCaptureDecoder.MAX_BYTES + 1), null)
    }
}
