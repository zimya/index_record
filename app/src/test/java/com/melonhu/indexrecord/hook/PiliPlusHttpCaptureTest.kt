package com.melonhu.indexrecord.hook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class PiliPlusHttpCaptureTest {
    private val videoPath = "/x/web-interface/wbi/view"
    private val mainPath = "/bilibili.main.community.reply.v1.Reply/MainList"
    private val videoUrl = "https://api.bilibili.com$videoPath"
    private val mainUrl = "https://grpc.biliapi.net$mainPath"
    private val captured = mutableListOf<Pair<String, ByteArray>>()
    private val framer = PiliPlusHttpCapture { url, bytes -> captured.add(url to bytes) }

    @Test fun fragmentedContentLengthPreservesRawJson() {
        val json = " \n{\"data\":[{\"title\":\"你好\"}]}\n".toByteArray()
        fragmented(1, false, request("GET", "$videoPath?bvid=BV1&token=private", "API.BILIBILI.COM:443"))
        val res = response(json, "200 OK", "content-type: application/json; charset=utf-8\r\n")
        fragmented(1, true, res.copyOf(res.size - 1))
        assertTrue(captured.isEmpty())
        framer.accept(1, true, res.copyOfRange(res.size - 1, res.size))

        assertEquals(listOf(videoUrl), captured.map { it.first })
        assertArrayEquals(json, captured.single().second)
    }

    @Test fun chunkedGzipAndTrailersCompleteBeforeDelivery() {
        val proto = gzip(byteArrayOf(8, 1))
        val frame = byteArrayOf(1, 0, 0, 0, proto.size.toByte()) + proto
        val zipped = gzip(frame)
        fragmented(1, false, request("POST", mainPath, "app.bilibili.com", "Transfer-Encoding: chunked\r\n") +
            "3\r\nabc\r\n0\r\nX-Request-Trailer: ignored\r\n\r\n".toByteArray())
        val headers = "HTTP/1.1 200 OK\r\nTransfer-Encoding: Chunked\r\nContent-Encoding: GZip\r\n" +
            "Content-Type: application/grpc+proto\r\n\r\n"
        fragmented(1, true, headers.toByteArray() + "3; x=\"a;b\"\r\n".toByteArray() +
            zipped.copyOfRange(0, 3) + "\r\n${(zipped.size - 3).toString(16)}\r\n".toByteArray() +
            zipped.copyOfRange(3, zipped.size) + "\r\n0\r\nGrpc-Status: 0\r\n".toByteArray())
        assertTrue(captured.isEmpty())
        fragmented(1, true, "\r\n".toByteArray())

        assertEquals(listOf(mainUrl), captured.map { it.first })
        assertEquals("""{"grpc":"${Base64.getEncoder().encodeToString(frame)}"}""", captured.single().second.decodeToString())
    }

    @Test fun pipelinedResponsesConsumeUninterestingRequestsAndDoNotCrossConnections() {
        val hidden = "{\"private\":true}".toByteArray()
        val selectedA = "{\"id\":1}".toByteArray()
        val selectedB = "{\"id\":2}".toByteArray()
        val incidentalBody = request("GET", videoPath, "api.bilibili.com")
        framer.accept(1, false,
            request("POST", "/unrelated", "api.bilibili.com", "Content-Length: ${incidentalBody.size}\r\n") + incidentalBody +
                request("GET", videoPath, "api.bilibili.com.evil.test") +
                request("POST", videoPath, "api.bilibili.com") +
                request("GET", "$videoPath/extra", "api.bilibili.com") +
                request("HEAD", videoPath, "api.bilibili.com") +
                request("GET", "https://api.bilibili.com$videoPath", "api.bilibili.com") +
                request("GET", videoPath, "api.bilibili.com"))
        framer.accept(2, false, request("GET", videoPath, "api.bilibili.com"))
        framer.accept(2, true, response(selectedB))
        framer.accept(1, true, response(hidden) + response(hidden) + response(hidden) + response(hidden) +
            "HTTP/1.1 200 OK\r\nContent-Length: 123\r\n\r\n".toByteArray() +
            "HTTP/1.1 304 Not Modified\r\nContent-Length: 123\r\n\r\n".toByteArray() + response(selectedA))

        assertEquals(listOf(videoUrl, videoUrl), captured.map { it.first })
        assertArrayEquals(selectedB, captured[0].second)
        assertArrayEquals(selectedA, captured[1].second)
    }

    @Test fun informationalResponsesDoNotPopRequestAndNonSuccessIsNotCaptured() {
        val frame = byteArrayOf(0, 0, 0, 0, 2, 8, 1)
        framer.accept(1, false, request("POST", mainPath, "app.bilibili.com", "Content-Length: 3\r\nExpect: 100-continue\r\n"))
        framer.accept(1, true, "HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
        framer.accept(1, false, "abc".toByteArray())
        framer.accept(1, false, request("GET", videoPath, "api.bilibili.com") + request("GET", videoPath, "api.bilibili.com"))
        framer.accept(1, true, "HTTP/1.1 103 Early Hints\r\nLink: </elsewhere>\r\n\r\n".toByteArray() + response(frame) +
            response("{}".toByteArray(), "404 Not Found") + "HTTP/1.1 204 No Content\r\n\r\n".toByteArray())

        assertEquals(listOf(mainUrl), captured.map { it.first })
        assertEquals("""{"grpc":"AAAAAAIIAQ=="}""", captured.single().second.decodeToString())
    }

    @Test fun grpcRequiresExactRouteAndOneCompleteFrame() {
        val frame = byteArrayOf(0, 0, 0, 0, 2, 8, 1)
        val wrongRoutes = listOf(
            request("POST", mainPath, "grpc.biliapi.net"), request("POST", "$mainPath?extra=1", "app.bilibili.com"),
            request("POST", "$mainPath/extra", "app.bilibili.com"), request("GET", mainPath, "app.bilibili.com")
        )
        for (req in wrongRoutes) exchange(1, req, response(frame))
        val right = request("POST", mainPath, "app.bilibili.com")
        for (body in listOf(frame.copyOf(frame.size - 1), frame + frame, byteArrayOf(0x80.toByte()) + frame.copyOfRange(1, frame.size))) {
            exchange(1, right, response(body))
        }
        assertTrue(captured.isEmpty())
        exchange(1, right, response(frame))
        assertEquals(listOf(mainUrl), captured.map { it.first })
    }

    @Test fun invalidOrUnsupportedFramingDoesNotResynchronizeUntilClose() {
        val goodReply = response("{}".toByteArray())
        val badReplies = listOf(
            "HTTP/1.1 200 OK\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nContent-Length: 2\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n bad-fold\r\n\r\n",
            "HTTP/1.1 101 Switching Protocols\r\n\r\n",
            "HTTP/1.1 204 No Content\r\nContent-Length: 1\r\n\r\n"
        )
        val goodRequest = request("GET", videoPath, "api.bilibili.com")
        for (bad in badReplies) {
            framer.accept(1, false, goodRequest + goodRequest)
            framer.accept(1, true, bad.toByteArray() + goodReply)
            exchange(1, goodRequest, goodReply)
            assertTrue(bad, captured.isEmpty())
            framer.close(1)
        }
        framer.accept(1, false, "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray())
        exchange(1, goodRequest, goodReply)
        assertTrue(captured.isEmpty())
        framer.close(1)
        exchange(1, goodRequest, goodReply)
        assertEquals(listOf(videoUrl), captured.map { it.first })
    }

    @Test fun noMappingFromOrphanReplyOrFinalReplyBeforeRequestBodyCompletes() {
        val req = request("GET", videoPath, "api.bilibili.com")
        val reply = response("{}".toByteArray())
        framer.accept(1, true, reply.copyOfRange(0, 1))
        framer.accept(1, false, req)
        framer.accept(1, true, reply.copyOfRange(1, reply.size))
        framer.accept(2, false, request("POST", mainPath, "app.bilibili.com", "Content-Length: 20\r\n"))
        framer.accept(2, true, response(byteArrayOf(0, 0, 0, 0, 0)))
        exchange(2, req, reply)
        assertTrue(captured.isEmpty())
        exchange(3, req, reply)
        assertEquals(listOf(videoUrl), captured.map { it.first })
    }

    @Test fun bodyAndEncodingLimitsSkipOnlyTheResponseWhenItsBoundaryIsKnown() {
        val oversized = ByteArray(4 * 1024 * 1024 + 6) { ' '.code.toByte() }.also {
            it[it.lastIndex - 1] = '{'.code.toByte()
            it[it.lastIndex] = '}'.code.toByte()
        }
        val json = "{}".toByteArray()
        val req = request("GET", videoPath, "api.bilibili.com")
        exchange(1, req, response(oversized))
        exchange(1, req, response(gzip(oversized), extra = "Content-Encoding: gzip\r\n"))
        exchange(1, req, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n${oversized.size.toString(16)}\r\n".toByteArray() +
            oversized + "\r\n0\r\n\r\n".toByteArray())
        exchange(1, req, response(json, extra = "Content-Encoding: br\r\n"))
        exchange(1, req, response("{}{}".toByteArray()))
        assertTrue(captured.isEmpty())
        exchange(1, req, response(gzip(json), extra = "Content-Encoding: gzip\r\n"))
        assertEquals(listOf(videoUrl), captured.map { it.first })
        assertArrayEquals(json, captured.single().second)
    }

    @Test fun slotAndPendingLimitsDoNotAllowMidstreamAdmission() {
        val other = request("GET", "/other", "api.bilibili.com")
        val req = request("GET", videoPath, "api.bilibili.com")
        val reply = response("{}".toByteArray())
        for (id in 1L..64L) framer.accept(id, false, other)
        framer.accept(65, false, other)
        framer.close(1)
        exchange(65, req, reply)
        repeat(33) { framer.accept(2, false, other) }
        exchange(2, req, reply)
        assertTrue(captured.isEmpty())
        exchange(66, req, reply)
        assertEquals(listOf(videoUrl), captured.map { it.first })
        framer.close(65)
        framer.close(66)
        exchange(65, req, reply)
        assertEquals(listOf(videoUrl, videoUrl), captured.map { it.first })
    }

    private fun exchange(id: Long, request: ByteArray, response: ByteArray) {
        framer.accept(id, false, request)
        framer.accept(id, true, response)
    }

    private fun fragmented(id: Long, inbound: Boolean, data: ByteArray) {
        for (byte in data) framer.accept(id, inbound, byteArrayOf(byte))
    }

    private fun request(method: String, target: String, host: String, extra: String = "") =
        "$method $target HTTP/1.1\r\nHost: $host\r\n$extra\r\n".toByteArray()

    private fun response(body: ByteArray, status: String = "200 OK", extra: String = "") =
        "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n$extra\r\n".toByteArray() + body

    private fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(data) }
        output.toByteArray()
    }
}
