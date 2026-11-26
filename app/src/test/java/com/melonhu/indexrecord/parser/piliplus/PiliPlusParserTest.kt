package com.melonhu.indexrecord.parser.piliplus

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.ParserRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.GZIPOutputStream

class PiliPlusParserTest {
    private val host = "com.example.piliplus"
    private val official = "tv.danmaku.bili"
    private val video = "https://api.bilibili.com/x/web-interface/wbi/view"
    private val main = "https://grpc.biliapi.net/bilibili.main.community.reply.v1.Reply/MainList"
    private val officialVideo = "https://grpc.biliapi.net/bilibili.app.viewunite.v1.View/View"
    private val aid = 9007199254740993L

    @Test fun videoMatchesOfficialRecordIdentityAndMetadata() {
        val result = parse(video, """{"code":0,"data":{
            "aid":$aid,"bvid":"BV1xx411c7mD","title":"标题","desc":"<b>literal</b>",
            "pic":"//cdn.example/cover.jpg","pubdate":1700000000,"duration":62,
            "owner":{"name":"Uploader","face":"//cdn.example/face.jpg"},
            "stat":{"view":55,"danmaku":5,"reply":0,"favorite":3,"coin":2,"share":1,"like":4}
        }}""")
        assertTrue(result is ParseResult.Success)
        val record = result.records.single()
        val expected = parse(officialVideo, """{
            "kind":"video","aid":"$aid","bvid":"BV1xx411c7mD","title":"标题","description":"<b>literal</b>",
            "cover":"//cdn.example/cover.jpg","published_at":1700000000,"duration":62,
            "author":"Uploader","author_avatar":"//cdn.example/face.jpg",
            "view":55,"danmaku":5,"reply":0,"favorite":3,"coin":2,"share":1,"like":4
        }""", official).records.single()
        assertEquals(expected.copy(sourceApp = host), record)
        assertEquals("bilibili_video_$aid", record.platformId)
    }

    @Test fun videoRequiresSuccessfulResponseAndAnAidFromTheBody() {
        for (body in listOf("""{"code":-404,"data":{"aid":42}}""", """{"code":0,"data":[]}""",
            """{"code":0,"data":{"bvid":"BV1xx411c7mD"}}""", """{"code":0,"data":{"aid":0}}""", "{")) {
            assertTrue(body, parse(video, body) is ParseResult.Failure)
        }
        val sparse = parse(video, """{"code":0,"data":{"aid":42}}""").records.single()
        assertEquals("https://www.bilibili.com/video/av42", sparse.sourceUrl)
        assertFalse(metadata(sparse).has("reply"))
    }

    @Test fun routesOnlyCanonicalUrlsAndKeepsOfficialEnvelopesSeparate() {
        for (url in listOf("$video?aid=42", "$main/", main.replace("MainList", "DetailList"),
            main.replace("grpc.biliapi.net", "app.bilibili.com"), officialVideo)) {
            assertTrue(url, parse(url, "{}") is ParseResult.Ignored)
        }
        val normalized = """{"kind":"comments","topic_type":1,"topic_id":42,"replies":[]}"""
        assertEquals(ParseResult.Success(emptyList()), parse(main, normalized, official))
        assertTrue(parse(main, normalized) is ParseResult.Failure)
        assertTrue(parse(main, envelope(frame(byteArrayOf())), official) is ParseResult.Failure)
        assertTrue(parse(main, "{}", "bilibili") is ParseResult.Ignored)
        assertTrue(parse(video, "{}", official) is ParseResult.Ignored)
    }

    @Test fun mainListPreservesOrderRelationsAndFieldsWithEitherCompressionFlag() {
        val child = reply(12, root = 11, parent = 11)
        val original = reply(11, extra = field(1, child) + scalar(9, 0) + scalar(10, 1700000000) +
            scalar(11, 1) + field(12, text(1, "<原文> **literal**") +
                field(9, text(1, "//cdn.example/one.jpg")) + field(9, text(1, "https://cdn.example/two.jpg"))) +
            field(13, text(2, "Author") + text(4, "//cdn.example/face.jpg")))
        val later = reply(7, extra = field(15, field(1, text(2, "Member V2") + text(4, "https://cdn.example/v2.jpg"))))
        val body = field(4, reply(9)) + field(2, original) + field(100, byteArrayOf(0)) + field(2, later) +
            field(14, reply(11)) + field(14, reply(5)) + field(5, reply(4)) + field(6, reply(3))
        for (gzip in listOf(false, true)) {
            val result = parse(main, envelope(frame(body, gzip)))
            assertTrue(result.toString(), result is ParseResult.Success)
            assertEquals(listOf(11L, 12L, 7L, 5L, 9L, 4L, 3L).map { "bilibili_comment_1_${aid}_$it" },
                result.records.map { it.platformId })
            val (top, nested, v2) = result.records
            assertEquals("bilibili", top.platform)
            assertEquals(host, top.sourceApp)
            assertEquals(main, top.sourceUrl)
            assertEquals(42L, top.captureTime)
            assertEquals(1700000000000L, top.publishTime)
            assertEquals("bilibili_video_$aid", top.parentId)
            assertEquals(top.platformId, nested.parentId)
            assertEquals("11", metadata(nested).get("root").asString)
            assertEquals("<原文> **literal**", top.content)
            assertEquals("plain_text", top.contentFormat)
            assertEquals("Author", top.authorName)
            assertEquals("https://cdn.example/face.jpg", top.authorAvatar)
            assertEquals("Member V2", v2.authorName)
            assertEquals("https://cdn.example/v2.jpg", v2.authorAvatar)
            assertEquals(0, metadata(top).get("like").asInt)
            assertEquals(1, metadata(top).get("reply").asInt)
            assertFalse(metadata(nested).has("like"))
            assertEquals(listOf("https://cdn.example/one.jpg", "https://cdn.example/two.jpg"),
                metadata(top).getAsJsonArray("images").map { it.asString })
        }
    }

    @Test fun mainListNeverInheritsTopicOrRewritesAncestry() {
        val valid = reply(10, type = 17, extra =
            field(1, reply(11, oid = 0, type = 17, root = 10, parent = 10)) +
                field(1, reply(12, type = 17, root = 9, parent = 9)) +
                field(1, reply(13, type = 17, root = 10, parent = 99)))
        val body = field(2, valid) + field(2, reply(14, type = 1)) + field(4, reply(15, oid = 42, type = 17))
        val result = parse(main, envelope(frame(body)))
        assertTrue(result is ParseResult.Partial)
        assertFalse(result.consumeRaw)
        assertEquals(4, result.diagnostics.size)
        val (top, nested) = result.records
        assertEquals("bilibili_opus_17_$aid", top.parentId)
        assertEquals("bilibili_comment_17_${aid}_99", nested.parentId)
        assertTrue(parse(main, envelope(frame(field(2, reply(16, type = 0))))) is ParseResult.Failure)
    }

    @Test fun mainListSeparatesEmptyMalformedFramesAndMalformedRows() {
        assertEquals(ParseResult.Success(emptyList()), parse(main, envelope(frame(field(1, byteArrayOf())))))
        for (bad in listOf(byteArrayOf(), frame(byteArrayOf()).copyOf(4),
            frame(byteArrayOf(0)).copyOf(5), frame(byteArrayOf()) + frame(byteArrayOf()),
            frame(byteArrayOf()).apply { this[0] = 2 }, frame(byteArrayOf(1)).apply { this[0] = 1 },
            frame(byteArrayOf(0x12, 0x02, 0x01)), frame(byteArrayOf(0x12) + ByteArray(10) { 0xff.toByte() }))) {
            val result = parse(main, envelope(bad))
            assertTrue(result is ParseResult.Failure)
            assertFalse(result.consumeRaw)
        }
        assertTrue(parse(main, """{"grpc":"@@@"}""") is ParseResult.Failure)
        assertTrue(parse(main, """{"grpc":12}""") is ParseResult.Failure)
        val nonObject = parse(main, """["captured content"]""")
        assertTrue(nonObject is ParseResult.Failure)
        assertEquals("Expected PiliPlus JSON object", nonObject.diagnostics.single().message)
        val malformedRow = field(2, byteArrayOf(0x10, 0x80.toByte()))
        val result = parse(main, envelope(frame(malformedRow + field(2, reply(31)))))
        assertTrue(result is ParseResult.Partial)
        assertEquals(1, result.diagnostics.size)
        assertEquals("bilibili_comment_1_${aid}_31", result.records.single().platformId)
    }

    private fun parse(url: String, body: String, pkg: String = host) = ParserRegistry.parseResult(
        RawData(url = url, json = body, hostApp = pkg, timestamp = 42), url.substringBefore('?')
    )

    private fun metadata(record: UniversalRecord) = JsonParser.parseString(record.extraMetadata).asJsonObject

    private fun envelope(frame: ByteArray) = """{"grpc":"${Base64.getEncoder().encodeToString(frame)}"}"""

    private fun frame(proto: ByteArray, gzip: Boolean = false): ByteArray {
        val data = if (!gzip) proto else ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(proto) }
        }.toByteArray()
        return ByteBuffer.allocate(5 + data.size).put(if (gzip) 1.toByte() else 0.toByte())
            .putInt(data.size).put(data).array()
    }

    // Hand-encoded stock-schema fixtures; keep field numbers independent of the decoder.
    private fun reply(id: Long, oid: Long = aid, type: Long = 1, root: Long = 0, parent: Long = 0,
                      extra: ByteArray = byteArrayOf()): ByteArray =
        scalar(2, id) + scalar(3, oid) + scalar(4, type) +
            (if (root == 0L) byteArrayOf() else scalar(6, root)) +
            (if (parent == 0L) byteArrayOf() else scalar(7, parent)) + extra

    private fun scalar(number: Int, value: Long) = varint(number.toLong() shl 3) + varint(value)
    private fun text(number: Int, value: String) = field(number, value.toByteArray(Charsets.UTF_8))
    private fun field(number: Int, payload: ByteArray) =
        varint((number.toLong() shl 3) or 2) + varint(payload.size.toLong()) + payload

    private fun varint(value: Long): ByteArray {
        var left = value
        return ByteArrayOutputStream().apply {
            while (left and 0x7fL.inv() != 0L) {
                write((left.toInt() and 0x7f) or 0x80)
                left = left ushr 7
            }
            write(left.toInt())
        }.toByteArray()
    }
}
