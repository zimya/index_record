package com.melonhu.indexrecord.parser.tieba

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.random.Random

class TiebaParserTest {
    private val pageUrl = "https://tiebac.baidu.com/c/f/pb/page?format=protobuf"
    private val floorUrl = "https://tiebac.baidu.com/c/f/pb/floor?format=protobuf"
    private val tid = 11177080968L
    private val pid = 154118752470L

    private fun wire(vararg fields: ByteArray): ByteArray = fields.fold(ByteArray(0)) { a, b -> a + b }
    private fun varint(value: Long): ByteArray {
        var remaining = value
        val bytes = ByteArrayOutputStream()
        while (remaining and -128L != 0L) {
            bytes.write((remaining and 127).toInt() or 128)
            remaining = remaining ushr 7
        }
        bytes.write(remaining.toInt())
        return bytes.toByteArray()
    }
    private fun n(tag: Int, value: Long) = varint(tag.toLong() shl 3) + varint(value)
    private fun b(tag: Int, value: ByteArray) = varint((tag.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun s(tag: Int, value: String) = b(tag, value.toByteArray(Charsets.UTF_8))
    private fun text(value: String) = wire(n(1, 0), s(2, value))
    private fun user(id: Long = 42, name: String = "昵称") = wire(n(2, id), s(3, "username"), s(4, name), s(5, "portrait-token"))
    private fun thread(id: Long = tid) = wire(n(1, id), n(2, 999), s(3, "真实标题"), n(4, 144), n(40, 100))
    private fun post(id: Long, floor: Long, vararg other: ByteArray) = wire(n(1, id), n(3, floor), *other)
    private fun sub(id: Long, value: String = "楼中楼") = wire(n(1, id), b(2, text(value)), n(3, 1700000000), b(7, user()))
    private fun envelope(bytes: ByteArray) = JsonObject().apply {
        addProperty("encoding", "base64")
        addProperty("body", Base64.getEncoder().encodeToString(bytes))
    }.toString()
    private fun parse(bytes: ByteArray, url: String = pageUrl): List<UniversalRecord> = TiebaParser.parse(
        RawData(url = url, json = envelope(bytes), hostApp = "com.baidu.tieba", timestamp = 123456), url.substringBefore('?')
    )
    private fun page(vararg posts: ByteArray) = b(2, wire(b(8, thread()), b(2, wire(n(1, 12), s(2, "测试吧"))), *posts.map { b(6, it) }.toTypedArray()))
    private fun meta(record: UniversalRecord) = JsonParser.parseString(record.extraMetadata).asJsonObject

    @Test fun sourceUrlsUseActualIdsAndMetadataPreservesExactCaptureUrl() {
        val url = "$pageUrl&tid=999&pid=888&pn=2"
        val records = parse(page(
            post(100, 1, b(15, wire(n(1, 100), b(2, sub(300))))),
            post(pid, 5, b(15, wire(n(1, pid), b(2, sub(301)))))
        ), url)
        assertEquals(4, records.size)
        assertEquals("https://tieba.baidu.com/p/$tid", records.single { it.recordType == "lou" }.sourceUrl)
        for (record in records) {
            assertEquals(url, meta(record).text("capture_url"))
            val postId = meta(record).text("post_id")
            if (record.recordType != "lou")
                assertEquals("https://tieba.baidu.com/p/$tid?pid=$postId#$postId", record.sourceUrl)
        }
        assertEquals("https://tieba.baidu.com/p/$tid?pid=300#300", records.single { it.platformId == "tieba_post_300" }.sourceUrl)
    }

    @Test fun floorUrlsUseSchemaIdsOrValidatedQueryFallbackAndPreserveCapture() {
        val url = "$floorUrl&tid=999&pid=888"
        val records = parse(b(2, wire(b(3, post(pid, 5)), b(4, sub(301)), b(5, thread()))), url)
        assertEquals("https://tieba.baidu.com/p/$tid?pid=$pid#$pid", records[0].sourceUrl)
        assertEquals("https://tieba.baidu.com/p/$tid?pid=301#301", records[1].sourceUrl)
        assertTrue(records.all { meta(it).text("capture_url") == url })
        val firstFloor = parse(b(2, wire(b(3, post(100, 1)), b(4, sub(302)), b(5, thread()))), url)
        assertEquals("https://tieba.baidu.com/p/$tid", firstFloor[0].sourceUrl)
        assertEquals("https://tieba.baidu.com/p/$tid?pid=302#302", firstFloor[1].sourceUrl)
        val fallbackUrl = "$floorUrl&tid=123&pid=456"
        val fallback = parse(b(2, b(3, n(3, 2))), fallbackUrl).single()
        assertEquals("https://tieba.baidu.com/p/123?pid=456#456", fallback.sourceUrl)
        assertEquals(fallbackUrl, meta(fallback).text("capture_url"))
    }

    @Test fun duplicateFullBodyOutranksLongerPreviewBeforeMetadataIsMerged() {
        val bytes = b(2, wire(b(3, post(pid, 5, b(15, wire(n(1, pid), b(2, sub(303, "预览中的装饰文本使其比完整正文更长")))))),
            b(4, sub(303, "完整")), b(5, thread())))
        val records = parse(bytes, floorUrl)
        assertEquals(2, records.size)
        assertEquals("完整", records[1].content)
        assertFalse(meta(records[1]).get("is_preview").asBoolean)
    }

    @Test fun routesOnlyExactSupportedTiebaEndpoints() {
        val raw = RawData(hostApp = "com.baidu.tieba")
        assertTrue(TiebaParser.canHandle(pageUrl, raw))
        assertTrue(TiebaParser.canHandle(floorUrl, raw))
        assertTrue(TiebaParser.canHandle("https://tieba.baidu.com/c/f/pb/page", raw))
        for (url in listOf("https://example.com/c/f/pb/page", "https://tiebac.baidu.com.evil/c/f/pb/page",
            "https://tieba.baidu.com/c/f/pb/page/other", "https://tieba.baidu.com/c/f/frs/page", "invalid")) {
            assertFalse(url, TiebaParser.canHandle(url, raw))
        }
    }

    @Test fun pageMapsFirstPostDirectFloorsAndNestedComments() {
        val preview = wire(n(1, pid), b(2, sub(301)))
        val records = parse(page(
            post(100, 1, b(5, text("# 正文\n保留 **Markdown**")), n(4, 1700000000), b(23, user()), b(15, wire(n(1, 100), b(2, sub(300))))),
            post(pid, 5, b(5, text("直接回帖")), n(13, 9), n(19, 42), b(15, preview))
        ))
        assertEquals(4, records.size)
        val main = records.single { it.recordType == "lou" }
        assertEquals("tieba_thread_$tid", main.platformId)
        assertEquals("", main.parentId)
        assertEquals("真实标题", main.title)
        assertTrue(main.content.contains("**Markdown**"))
        assertEquals(1700000000000L, main.publishTime)
        assertEquals(123456L, main.captureTime)
        assertEquals("昵称", main.authorName)
        assertEquals("", main.authorAvatar)
        assertEquals("portrait-token", meta(main).text("portrait"))

        val liteBytes = page(post(100, 1, b(5, text("正文")), b(23, user())))
        val liteRecords = TiebaParser.parse(
            RawData(
                url = pageUrl,
                hostApp = com.melonhu.indexrecord.data.PlatformCatalog.TIEBA_LITE_PACKAGE,
                json = com.melonhu.indexrecord.sync.RawPayloadCodec.encode(
                    liteBytes,
                    com.melonhu.indexrecord.data.PlatformCatalog.TIEBA_LITE_PACKAGE
                ),
                timestamp = 123456
            ),
            pageUrl
        )
        assertEquals(
            "http://tb.himg.baidu.com/sys/portrait/item/portrait-token",
            liteRecords.single { it.recordType == "lou" }.authorAvatar
        )
        assertEquals(144L, meta(main).number("reply_count"))
        val floor = records.single { it.recordType == "floor" }
        assertEquals("tieba_post_$pid", floor.platformId)
        assertEquals(main.platformId, floor.parentId)
        assertEquals(9L, meta(floor).number("reply_count"))
        assertEquals("测试吧", meta(floor).text("forum_name"))
        assertEquals("真实标题", meta(floor).text("thread_title"))
        assertEquals("100", meta(main).text("post_id"))
        assertEquals(main.platformId, records.single { it.platformId == "tieba_post_300" }.parentId)
        assertEquals(floor.platformId, records.single { it.platformId == "tieba_post_301" }.parentId)
        assertTrue(meta(records.last()).get("is_preview").asBoolean)
    }

    @Test fun floorResponseParentRemainsFloorAndSchemaThreadIdBeatsQuery() {
        val response = b(2, wire(
            b(3, post(pid, 5, b(5, text("父楼")), b(23, user()))), b(4, sub(301)), b(5, thread()),
            b(1, wire(n(3, 2), n(4, 20)))
        ))
        val records = parse(response, "$floorUrl&tid=999&pid=888")
        assertEquals(listOf("floor", "comment"), records.map { it.recordType })
        assertEquals("tieba_thread_$tid", records[0].parentId)
        assertEquals("tieba_post_$pid", records[1].parentId)
        assertEquals("$tid", meta(records[1]).text("thread_id"))
        assertEquals("$pid", meta(records[1]).text("floor_post_id"))
        assertEquals(5L, meta(records[1]).number("floor"))
        assertEquals(20L, meta(records[0]).number("reply_count"))
        assertFalse(meta(records[1]).get("is_preview").asBoolean)
    }

    @Test fun floorResponseForFirstFloorAttachesCommentsToThread() {
        val records = parse(b(2, wire(b(3, post(100, 1)), b(4, sub(302)), b(5, thread()))), floorUrl)
        assertEquals(listOf("lou", "comment"), records.map { it.recordType })
        assertEquals("tieba_thread_$tid", records[1].parentId)
    }

    @Test fun noFirstFloorIsInventedForAPaginatedPage() {
        val records = parse(page(post(pid, 45, b(5, text("第45楼")))))
        assertEquals(1, records.size)
        assertEquals("floor", records.single().recordType)
        assertEquals("tieba_thread_$tid", records.single().parentId)
        assertEquals(0L, records.single().publishTime)
    }

    @Test fun firstFloorPostTagAndUserListAreSupportedAndDeduplicated() {
        val first = post(100, 0, n(19, 42), b(5, text("完整正文")))
        val records = parse(b(2, wire(b(38, first), b(6, first), b(8, thread()), b(13, user()))))
        assertEquals(1, records.size)
        assertEquals("lou", records.single().recordType)
        assertEquals("昵称", records.single().authorName)
    }

    @Test fun missingIdsUseCapturedUrlAndPostTidWithoutInventingParents() {
        val response = b(2, b(3, post(pid, 2)))
        assertTrue(parse(response, floorUrl).isEmpty())
        assertEquals("tieba_thread_123", parse(response, "$floorUrl&tid=123").single().parentId)
        assertEquals("tieba_thread_456", parse(b(2, b(3, post(pid, 2, n(46, 456)))), "$floorUrl&tid=123").single().parentId)
        assertEquals("tieba_post_789", parse(b(2, b(3, n(3, 2))), "$floorUrl&tid=123&pid=789").single().platformId)
        assertTrue(parse(page(post(0, 2))).isEmpty())
    }

    @Test fun preservesUnsignedPostIdsWithoutDoublePrecisionLoss() {
        val records = parse(page(post(-1, 2)))
        assertEquals("tieba_post_18446744073709551615", records.single().platformId)
    }

    @Test fun rendersImagesEmoticonsLinksVideoAndUnknownContentText() {
        val content = listOf(
            text("正文 **粗体**\n"), wire(n(1, 1), s(2, "链接"), s(3, "https://example.com/a?q=1&b=2")),
            wire(n(1, 2), s(2, "image_emoticon"), s(11, "微笑")),
            wire(n(1, 2), s(2, "https://img.example/emote.png"), s(11, "笑")),
            wire(n(1, 3), s(4, "https://img.example/thumb.jpg"), s(25, "https://img.example/original.jpg")),
            wire(n(1, 5), s(3, "https://video.example/v.mp4"), s(4, "https://img.example/cover.jpg")),
            wire(n(1, 10), s(12, "real-voice-md5"), n(13, 12)), wire(n(1, 99), s(2, "未知类型文字"))
        )
        val record = parse(page(post(100, 1, *content.map { b(5, it) }.toTypedArray(),
            b(29, s(2, "https://video.example/second.mp4"))))).single()
        for (expected in listOf("**粗体**", "[链接]", "[微笑]", "emote.png", "original.jpg", "cover.jpg", "v.mp4", "second.mp4", "语音 12秒", "未知类型文字")) {
            assertTrue(expected, record.content.contains(expected))
        }
        assertFalse(record.content.contains("thumb.jpg"))
        assertEquals(2, meta(record).array("video_urls").size())
        assertEquals("real-voice-md5", meta(record).objects("content_segments")[6].text("voice_md5"))
    }

    @Test fun explicitAvatarUrlIsPreservedButPortraitTokensAreNotFabricated() {
        val record = parse(page(post(100, 1, b(23, wire(n(2, 42), s(5, "https://avatar.example/a.jpg")))))).single()
        assertEquals("https://avatar.example/a.jpg", record.authorAvatar)
    }

    @Test fun capturesReplyTargetOnlyFromLeadingReplyNotOrdinaryMentions() {
        val reply = wire(n(1, 303), b(2, text("回复 ")), b(2, wire(n(1, 4), s(2, "@目标"), n(15, 987))), b(2, text(" : 内容")))
        val mention = wire(n(1, 304), b(2, wire(n(1, 4), s(2, "@路人"), n(15, 666))), b(2, text(" 你好")))
        val records = parse(b(2, wire(b(3, post(pid, 5)), b(4, reply), b(4, mention), b(5, thread()))), floorUrl)
        assertEquals("目标", meta(records[1]).text("reply_to_name"))
        assertEquals("987", meta(records[1]).text("reply_to_id"))
        assertFalse(meta(records[2]).has("reply_to_name"))
    }

    @Test fun skipsUnknownAllWireTypesAndDoesNotDecodeUnknownBytesAsUtf8() {
        val group = varint((100L shl 3) or 3) + n(1, 5) + varint((100L shl 3) or 4)
        val unknown = wire(n(100, -1), b(101, byteArrayOf(-1, -2)),
            varint((102L shl 3) or 1) + ByteArray(8), varint((103L shl 3) or 5) + ByteArray(4), group)
        val records = parse(unknown + page(post(100, 1)) + unknown)
        assertEquals(1, records.size)
        val nestedUnknown = page(post(100, 1, unknown, b(5, text("正文"))))
        assertEquals("正文", parse(nestedUnknown).single().content)
    }

    @Test fun malformedWireFailsClosedWithoutPartialRecords() {
        val malformed = listOf(
            byteArrayOf(0), byteArrayOf(14), byteArrayOf(12), byteArrayOf(-128),
            ByteArray(11) { -128 }, ByteArray(9) { -1 } + byteArrayOf(2),
            byteArrayOf(18, -1, -1, -1, -1, 7), byteArrayOf(18, 8, 1),
            byteArrayOf(9, 0), byteArrayOf(13, 0), byteArrayOf(11, 20),
            byteArrayOf(11), b(2, b(8, s(3, "valid").dropLast(1).toByteArray())),
            b(2, b(8, b(3, byteArrayOf(-1))))
        )
        for (bytes in malformed) {
            assertTrue(bytes.joinToString(), parse(bytes).isEmpty())
            assertTrue("No partial result", parse(page(post(100, 1)) + bytes).isEmpty())
        }
    }

    @Test fun readerDefendsDepthFieldBudgetAndMessageSize() {
        val groupStart = varint((100L shl 3) or 3)
        val groupEnd = varint((100L shl 3) or 4)
        val deep = wire(*List(40) { groupStart }.toTypedArray()) + wire(*List(40) { groupEnd }.toTypedArray())
        assertTrue(parse(deep + page(post(100, 1))).isEmpty())
        val lots = ByteArrayOutputStream().apply { repeat(200001) { write(n(100, 0)) } }.toByteArray()
        assertTrue(parse(lots).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { TiebaWireReader(ByteArray(TiebaWireReader.MAX_BYTES + 1)) }
    }

    @Test fun malformedEnvelopesErrorsAndDeepJsonAreIgnored() {
        for (json in listOf("", "[]", "{", "{\"encoding\":\"base64\",\"body\":\"***\"}",
            "{\"encoding\":\"gzip\",\"body\":\"\"}", "[".repeat(70) + "]".repeat(70))) {
            assertTrue(TiebaParser.parse(RawData(url = pageUrl, json = json), pageUrl).isEmpty())
        }
        assertTrue(parse(b(1, n(1, 4)) + page(post(100, 1))).isEmpty())
        assertTrue(TiebaParser.parse(RawData(json = "{\"error\":{\"errorno\":\"4\"},\"data\":{}}"), pageUrl).isEmpty())
    }

    @Test fun deterministicRandomPayloadsNeverEscapeAsExceptions() {
        val random = Random(1234)
        repeat(1000) {
            val bytes = random.nextBytes(random.nextInt(0, 256))
            parse(bytes, if (it % 2 == 0) pageUrl else floorUrl)
        }
    }

    @Test fun repeatedSingularMessagesMergeAccordingToProtobufSemantics() {
        val bytes = wire(b(2, b(8, n(1, tid))), b(2, wire(b(8, s(3, "合并后的标题")), b(6, post(100, 1)))))
        assertEquals("合并后的标题", parse(bytes).single().title)
    }

    @Test fun wrongKnownWireTypeIsSkippedWithoutGuessingItsValue() {
        val bytes = b(2, wire(b(8, wire(s(1, "not-an-id"), n(2, tid))), b(6, post(100, 1))))
        assertEquals("tieba_thread_$tid", parse(bytes).single().platformId)
    }

    @Test fun capturedJsonFloorRegressionUsesRealFifthFloorAndSubpost() {
        val json = javaClass.getResource("/tieba/floor-decoded.json")!!.readText()
        val records = TiebaParser.parse(RawData(url = floorUrl, json = json), floorUrl)
        assertEquals(listOf("floor", "comment"), records.map { it.recordType })
        assertEquals("tieba_post_154118752470", records[0].platformId)
        assertEquals("tieba_thread_11177080968", records[0].parentId)
        assertEquals("身子和腿还在", records[0].content)
        assertEquals("tieba_post_154131512696", records[1].platformId)
        assertEquals(records[0].platformId, records[1].parentId)
        assertEquals("器官活化，互相掐架", records[1].content)
        assertEquals("抗压背锅", meta(records[0]).text("forum_name"))
        assertEquals("反T1装甲就剩俩了", meta(records[1]).text("thread_title"))
        assertEquals(1794407752000L, records[1].publishTime)
        assertEquals("920696726", records[1].authorName)
    }

    @Test fun modernPageFirstFloorAndFloorReplyCountUseDecompiledTags() {
        val first = parse(b(2, wire(b(97, post(100, 0)), b(8, thread())))).single()
        assertEquals("lou", first.recordType)
        val floor = parse(b(2, wire(b(3, post(pid, 5)), b(5, thread()), n(13, 27))), floorUrl).single()
        assertEquals(27L, meta(floor).number("reply_count"))
    }

    @Test fun pbListUsesItsOwnSchemaInsteadOfPbPageFieldNumbers() {
        val bytes = b(2, wire(b(1, thread()), b(2, s(2, "列表贴吧")), b(5, user()),
            b(6, post(100, 1, n(19, 42))), b(7, post(pid, 5)), b(8, n(3, 2))))
        val url = "https://tiebac.baidu.com/c/f/pb/list?format=protobuf"
        assertTrue(TiebaParser.canHandle(url, RawData()))
        val records = parse(bytes, url)
        assertEquals(listOf("lou", "floor"), records.map { it.recordType })
        assertEquals("昵称", records[0].authorName)
        assertEquals("列表贴吧", meta(records[1]).text("forum_name"))
        assertEquals(2L, meta(records[1]).number("page"))
    }

    @Test fun firstFloorCanUseExplicitThreadAuthorBodyAndCreationTime() {
        val bytes = b(2, wire(b(6, post(100, 1)), b(8, wire(n(1, tid), b(18, user()),
            b(142, text("线程保存的首楼正文")), n(45, 1700000000)))))
        val record = parse(bytes).single()
        assertEquals("42", record.authorId)
        assertEquals("昵称", record.authorName)
        assertEquals("线程保存的首楼正文", record.content)
        assertEquals(1700000000000L, record.publishTime)
    }

    @Test fun actualFloorReplyReplacesDuplicatePreviewMetadataAndKeepsOneRecord() {
        val bytes = b(2, wire(b(3, post(pid, 5, b(15, wire(n(1, pid), b(2, sub(303, "预览")))))),
            b(4, sub(303, "完整的楼中楼内容")), b(5, thread())))
        val records = parse(bytes, floorUrl)
        assertEquals(2, records.size)
        assertEquals("完整的楼中楼内容", records[1].content)
        assertFalse(meta(records[1]).get("is_preview").asBoolean)
        assertEquals("完整的楼中楼内容", meta(records[1]).objects("content_segments").single().text("text"))
    }

    @Test fun camelCaseDecodedSchemaJsonAndCaptureWrapperAreCompatible() {
        val json = """{"response":{"error":{"error_code":0},"data":{"thread":{"id":"123","title":"JSON"},"postList":[{"id":"999","floor":1,"author":{"nameShow":"作者"},"content":[{"type":3,"originSrc":"https://img.example/p.jpg"}]}]}}}"""
        val record = TiebaParser.parse(RawData(url = pageUrl, json = json), pageUrl).single()
        assertEquals("tieba_thread_123", record.platformId)
        assertEquals("作者", record.authorName)
        assertTrue(record.content.contains("p.jpg"))
    }
}
