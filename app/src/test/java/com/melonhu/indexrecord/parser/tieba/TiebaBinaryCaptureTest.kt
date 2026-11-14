package com.melonhu.indexrecord.parser.tieba

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class TiebaBinaryCaptureTest {
    private val listUrl = "https://tiebac.baidu.com/c/f/pb/list?format=protobuf"
    private val pageUrl = "https://tiebac.baidu.com/c/f/pb/page?format=protobuf"
    private val floorUrl = "https://tiebac.baidu.com/c/f/pb/floor?format=protobuf"
    private val threadId = "tieba_thread_11180147101"
    private val title = "谁能告诉我外战看内啥来着？"

    private fun resource(name: String) = javaClass.getResourceAsStream("/tieba/$name")!!.use { it.readBytes() }
    private fun manifest() = JsonParser.parseString(resource("binary-manifest.json").toString(Charsets.UTF_8)).asJsonArray
    private fun parse(bytes: ByteArray, url: String): List<UniversalRecord> {
        val envelope = JsonObject().apply {
            addProperty("encoding", "base64")
            addProperty("body", Base64.getEncoder().encodeToString(bytes))
        }
        return TiebaParser.parse(RawData(url = url, json = envelope.toString(), hostApp = "com.baidu.tieba", timestamp = 123456), url.substringBefore('?'))
    }
    private fun meta(record: UniversalRecord) = JsonParser.parseString(record.extraMetadata).asJsonObject
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertList(records: List<UniversalRecord>) {
        assertEquals(34, records.size)
        assertEquals(34, records.map { it.platformId }.toSet().size)
        assertEquals(mapOf("lou" to 1, "floor" to 15, "comment" to 18), records.groupingBy { it.recordType }.eachCount())
        val first = records.single { it.recordType == "lou" }
        assertEquals(threadId, first.platformId)
        assertEquals("154122455593", meta(first).text("post_id"))
        assertEquals(title, first.title)
        assertTrue(first.content.startsWith("有没有那种淘汰了自家人然后吹自己外战的宝藏队伍推荐一下"))
        assertEquals(1794191960000L, first.publishTime)
        assertEquals(88L, meta(first).number("reply_count"))
                assertEquals(19L, meta(first).number("favlists_count"))
                assertTrue(records.filter { it.recordType != "lou" }.none { meta(it).has("favlists_count") })
        assertEquals(1L, meta(first).number("floor"))
        val image = meta(first).objects("content_segments").single { it.number("type") == 3L }
        assertEquals(image.text("origin_src"), meta(first).array("image_urls").single().asString)
        assertTrue(first.content.contains(image.text("origin_src")))
        assertEquals("", first.parentId)
        for (record in records) {
            assertEquals("11180147101", meta(record).text("thread_id"))
            assertEquals(title, meta(record).text("thread_title"))
            assertEquals("抗压背锅", meta(record).text("forum_name"))
            assertEquals(1L, meta(record).number("page"))
            assertEquals(123456L, record.captureTime)
        }
        val floor = records.single { it.platformId == "tieba_post_154122637221" }
        assertEquals("floor", floor.recordType)
        assertEquals(threadId, floor.parentId)
        assertTrue(floor.content.startsWith("别的都是粉丝带节奏，al经理自己说是真的闻所未闻"))
        assertTrue(floor.content.contains("[黑线]"))
        assertEquals(11L, meta(floor).number("reply_count"))
        // Ranked PbList really sends floor=0 for every direct reply; order is not a floor number.
        for (item in records.filter { it.recordType == "floor" }) {
            assertEquals(0L, meta(item).number("floor"))
            assertEquals(threadId, item.parentId)
            assertFalse(meta(item).get("is_preview").asBoolean)
        }
        val comments = records.filter { it.recordType == "comment" }
        assertEquals(5, comments.count { meta(it).has("reply_to_id") })
        assertTrue(comments.all { meta(it).get("is_preview").asBoolean })
        for (comment in comments) {
            assertEquals("tieba_post_${meta(comment).text("floor_post_id")}", comment.parentId)
            assertTrue(records.any { it.platformId == comment.parentId && it.recordType == "floor" })
        }
        val reply = records.single { it.platformId == "tieba_post_154123008959" }
        assertEquals(floor.platformId, reply.parentId)
        assertEquals("再见我的劫℃", meta(reply).text("reply_to_name"))
        assertEquals("1022753941", meta(reply).text("reply_to_id"))
        assertTrue(reply.content.contains("别人的外战：T1 hle dk geng 我的外战：blg"))
        val imageReply = records.single { it.platformId == "tieba_post_154122699285" }
        assertTrue(imageReply.content.contains("!["))
        assertEquals(1, meta(imageReply).array("image_urls").size())
        val types = records.flatMap { meta(it).objects("content_segments") }.groupingBy { it.number("type") }.eachCount()
        assertEquals(mapOf(0L to 51, 3L to 7, 2L to 11, 4L to 5), types)
    }

    @Test fun collectionCountPreservesZeroAndDoesNotInventMissingCounts() {
        val root = TiebaProto.decode(resource("list-149242.bin"), listUrl.substringBefore('?'))
        val thread = root.getAsJsonObject("data").getAsJsonObject("thread")
        assertEquals(19L, thread.number("collect_num"))
        fun mainPost() = TiebaParser.parse(
            RawData(url = listUrl, json = root.toString()), listUrl.substringBefore('?')
        ).single { it.recordType == "lou" }
        thread.addProperty("collect_num", 0)
        assertEquals(0L, meta(mainPost()).get("favlists_count").asLong)
        thread.remove("collect_num")
        thread.addProperty("collect_status", 1)
        assertFalse(meta(mainPost()).has("favlists_count"))
        thread.addProperty("collectNum", 23)
        assertEquals(23L, meta(mainPost()).get("favlists_count").asLong)
    }

    private fun assertFloor(records: List<UniversalRecord>) {
        assertEquals(12, records.size)
        assertEquals(mapOf("floor" to 1, "comment" to 11), records.groupingBy { it.recordType }.eachCount())
        val parent = records.first()
        assertEquals("tieba_post_154122637221", parent.platformId)
        assertEquals(threadId, parent.parentId)
        assertEquals(11L, meta(parent).number("reply_count"))
        assertEquals(1794195035000L, parent.publishTime)
        assertTrue(parent.content.contains("别的都是粉丝带节奏，al经理自己说是真的闻所未闻"))
        assertTrue(parent.content.contains("[黑线]"))
        assertEquals("http://tb.himg.baidu.com/sys/portrait/item/tb.1.f40997ca.Th6Curf_v1BnGYwPj9cp6g?t=1794417546", parent.authorAvatar)
        val expectedIds = listOf("154123008959", "154122699285", "154123440978", "154123055637", "154122732956",
            "154123919962", "154125527322", "154131300618", "154131172709", "154124344418", "154123927328")
        val expectedTimes = listOf(1794202300L, 1794196155L, 1794210883L, 1794203278L, 1794196778L,
            1794220089L, 1794266506L, 1794403675L, 1794401194L, 1794228270L, 1794220243L)
        assertEquals(expectedIds.map { "tieba_post_$it" }, records.drop(1).map { it.platformId })
        assertEquals(expectedTimes.map { it * 1000 }, records.drop(1).map { it.publishTime })
        assertEquals(7, records.count { meta(it).has("reply_to_id") })
        for (record in records) {
            assertEquals(5L, meta(record).number("floor"))
            assertEquals("11180147101", meta(record).text("thread_id"))
            assertEquals(title, meta(record).text("thread_title"))
            assertEquals("抗压背锅", meta(record).text("forum_name"))
            assertFalse(meta(record).get("is_preview").asBoolean)
            assertTrue(record.authorAvatar.startsWith("http://tb.himg.baidu.com/sys/portrait/item/"))
            assertTrue(record.authorAvatar.endsWith("?t=1794417546"))
            assertEquals(123456L, record.captureTime)
        }
        for (comment in records.drop(1)) {
            assertEquals(parent.platformId, comment.parentId)
            assertEquals("154122637221", meta(comment).text("floor_post_id"))
        }
        val reply = records[1]
        assertEquals("再见我的劫℃", meta(reply).text("reply_to_name"))
        assertEquals("1022753941", meta(reply).text("reply_to_id"))
        assertTrue(reply.content.contains("别人的外战：T1 hle dk geng 我的外战：blg"))
        assertEquals("http://tb.himg.baidu.com/sys/portrait/item/tb.1.a8410dbd.DCOUu20xk4lqDC-RVPHLuQ?t=1794417546", reply.authorAvatar)
        assertTrue(records[2].content.contains("!["))
        assertEquals(1, meta(records[2]).array("image_urls").size())
        assertTrue(records[3].content.contains("[笑眼]"))
    }

    @Test fun actualBinaryFloorIncludesParentAndAllElevenCompleteReplies() {
        assertFloor(parse(resource("floor-41963.bin"), floorUrl))
        val data = TiebaProto.decode(resource("floor-41963.bin"), "/c/f/pb/floor").obj("data")
        assertEquals(11L, data.number("subpost_num"))
        assertEquals(11L, data.obj("page").number("total_count"))
        assertEquals(1L, data.obj("page").number("total_page"))
        assertFalse(data.obj("thread").has("create_time"))
        assertTrue(data.obj("post").has("time"))
        assertTrue(data.objects("subpost_list").all { it.has("time") && it.number("time") > 0 })
    }

    @Test fun floorAvatarIsTheExactObservedNestedUrlNotASynthesizedPortraitUrl() {
        val data = TiebaProto.decode(resource("floor-41963.bin"), "/c/f/pb/floor").obj("data")
        val records = parse(resource("floor-41963.bin"), floorUrl)
        val posts = listOf(data.obj("post")) + data.objects("subpost_list")
        for ((post, record) in posts.zip(records)) {
            val author = post.obj("author")
            assertTrue(author.text("portrait").startsWith("tb.1."))
            assertEquals(author.obj("user_show_info").obj("feed_head").obj("image_data").text("img_url"), record.authorAvatar)
            assertEquals(author.text("portrait"), meta(record).text("portrait"))
        }
    }

    @Test fun absentFloorTimeOrAvatarStaysMissingInsteadOfUsingParentOrCaptureTime() {
        val root = TiebaProto.decode(resource("floor-41963.bin"), "/c/f/pb/floor")
        val posts = listOf(root.obj("data").obj("post")) + root.obj("data").objects("subpost_list")
        for (post in posts) {
            post.remove("time")
            post.obj("author").remove("user_show_info")
        }
        val records = TiebaParser.parse(RawData(url = floorUrl, json = root.toString(), timestamp = 123456), floorUrl)
        assertEquals(12, records.size)
        assertTrue(records.all { it.publishTime == 0L && it.authorAvatar.isEmpty() })
        assertTrue(records.all { it.captureTime == 123456L && meta(it).text("portrait").isNotEmpty() })
    }

    @Test fun completeFloorEnrichesMatchingListPreviewsWithoutChangingTheirParents() {
        val previews = parse(resource("list-156592.bin"), listUrl).associateBy { it.platformId }
        val full = parse(resource("floor-41963.bin"), floorUrl)
        val overlappingComments = full.drop(1).filter { previews.containsKey(it.platformId) }
        assertEquals(4, overlappingComments.size)
        for (record in overlappingComments) {
            val preview = previews.getValue(record.platformId)
            assertEquals(record.content, TiebaRelations.mergeRecordContent(preview, record).content)
            assertEquals(record.content, TiebaRelations.mergeRecordContent(record, preview).content)
        }
        for (record in listOf(full.first()) + overlappingComments) {
            val previous = previews.getValue(record.platformId)
            assertEquals(previous.parentId, record.parentId)
            val merged = boundedTiebaJson(TiebaRelations.mergeExtra(previous.extraMetadata, record.extraMetadata))
            assertFalse(merged.get("is_preview").asBoolean)
            assertEquals(5L, merged.number("floor"))
            assertEquals(meta(record).text("post_id"), merged.text("post_id"))
            assertEquals(meta(record).text("reply_to_id"), merged.text("reply_to_id"))
        }
        assertEquals(41, (previews.keys + full.map { it.platformId }).size)
    }

    @Test fun bothActualBinaryListProjectionsRetainAllPostsAndPreviews() {
        for (name in listOf("list-149242.bin", "list-156592.bin")) assertList(parse(resource(name), listUrl))
    }

    @Test fun repeatedListCaptureHasStableIdsAndMetadataMergesWithoutDuplicatingPreviews() {
        val old = parse(resource("list-149242.bin"), listUrl).associateBy { it.platformId }
        val incoming = parse(resource("list-156592.bin"), listUrl).associateBy { it.platformId }
        assertEquals(old.keys, incoming.keys)
        assertEquals(34, (old.keys + incoming.keys).size)
        for ((id, record) in incoming) {
            val previous = old.getValue(id)
            assertEquals(previous.parentId, record.parentId)
            val merged = boundedTiebaJson(TiebaRelations.mergeExtra(previous.extraMetadata, record.extraMetadata))
            assertEquals(meta(previous).text("post_id"), merged.text("post_id"))
            assertEquals(meta(previous).text("reply_to_id"), merged.text("reply_to_id"))
            assertEquals(meta(previous).number("reply_count"), merged.number("reply_count"))
            assertEquals(meta(previous).objects("content_segments").size, merged.objects("content_segments").size)
        }
    }

    @Test fun actualEmptyPageDoesNotInventThreadOrInterpretPageAsPost() {
        assertTrue(parse(resource("page-empty.bin"), pageUrl).isEmpty())
        val decoded = TiebaProto.decode(resource("page-empty.bin"), "/c/f/pb/page")
        assertFalse(decoded.obj("data").has("thread"))
        assertFalse(decoded.obj("data").has("post_list"))
    }

    @Test fun realBinaryRecordsExposeWebPermalinksAndKeepCaptureProvenance() {
        for (entry in manifest()) {
            val row = entry.asJsonObject
            val url = row.text("url")
            for (record in parse(resource(row.text("fixture")), url)) {
                val metadata = meta(record)
                val tid = metadata.text("thread_id")
                val pid = metadata.text("post_id")
                val expectedUrl = if (record.recordType == "lou") "https://tieba.baidu.com/p/$tid"
                    else "https://tieba.baidu.com/p/$tid?pid=$pid#$pid"
                assertEquals(expectedUrl, record.sourceUrl)
                assertEquals(url, metadata.text("capture_url"))
            }
        }
    }

    @Test fun resourcesMatchAuditableBinaryManifest() {
        for (entry in manifest()) {
            val row = entry.asJsonObject
            val bytes = resource(row.text("fixture"))
            assertEquals(row.number("fixture_bytes"), bytes.size.toLong())
            assertEquals(row.text("fixture_sha256"), hash(bytes))
        }
    }

    @Test fun localUnmodifiedDumpsProduceSameRecordsAsProjectedBinaryFixtures() {
        val dir = listOf(File("../research/tieba/lsposed"), File("research/tieba/lsposed"))
            .firstOrNull { File(it, "1794417075762_19771_127051900145646.bin").isFile }
        assumeTrue("Original research dumps are optional; binary resources are always tested", dir != null)
        for (entry in manifest()) {
            val row = entry.asJsonObject
            val file = File(dir!!, row.text("source"))
            val original = file.readBytes()
            val url = file.resolveSibling(file.nameWithoutExtension + ".meta").readLines().first()
            assertEquals(row.text("url"), url)
            assertEquals(row.number("original_bytes"), original.size.toLong())
            assertEquals(row.text("original_sha256"), hash(original))
            val actual = parse(original, url)
            val projected = parse(resource(row.text("fixture")), url)
            fun signature(records: List<UniversalRecord>) = records.map { record ->
                listOf(record.platformId, record.recordType, record.parentId, record.title, record.content,
                    record.authorId, record.authorName, record.publishTime.toString(),
                    record.sourceUrl, meta(record).text("capture_url"), meta(record).text("floor"), meta(record).text("reply_count"), meta(record).text("reply_to_name"),
                    meta(record).text("reply_to_id"), meta(record).array("image_urls").toString())
            }
            assertEquals(signature(projected), signature(actual))
            when {
                url.contains("/list") -> assertList(actual)
                url.contains("/floor") -> {
                    assertFloor(actual)
                    assertEquals(projected.map { it.authorAvatar }, actual.map { it.authorAvatar })
                }
                else -> assertTrue(actual.isEmpty())
            }
            println("${file.name}: ${original.size} bytes -> ${actual.groupingBy { it.recordType }.eachCount()}")
        }
    }

    @Test fun legacyDecodedJsonIsAnExactFieldProjectionOfOneFifthFloorAndOneReply() {
        val file = listOf(File("../research/tieba/1794413979309629000.json"), File("research/tieba/1794413979309629000.json"))
            .firstOrNull { it.isFile }
        assumeTrue("Original decoded capture is optional", file != null)
        val original = boundedTiebaJson(file!!.readText()).obj("response")
        val fixture = boundedTiebaJson(resource("floor-decoded.json").toString(Charsets.UTF_8))
        fun assertProjection(projected: com.google.gson.JsonElement, source: com.google.gson.JsonElement) {
            when {
                projected.isJsonObject -> for ((key, value) in projected.asJsonObject.entrySet()) {
                    if (key != "_fixture_source") {
                        assertTrue(key, source.asJsonObject.has(key))
                        assertProjection(value, source.asJsonObject.get(key))
                    }
                }
                projected.isJsonArray -> {
                    assertEquals(projected.asJsonArray.size(), source.asJsonArray.size())
                    projected.asJsonArray.zip(source.asJsonArray).forEach { (a, b) -> assertProjection(a, b) }
                }
                else -> assertEquals(source, projected)
            }
        }
        assertProjection(fixture, original)
        assertEquals(5L, original.obj("data").obj("post").number("floor"))
        assertEquals(1, original.obj("data").objects("subpost_list").size)
        val url = "https://tiebac.baidu.com/c/f/pb/floor?format=protobuf"
        val records = TiebaParser.parse(RawData(url = url, json = original.toString()), url)
        assertEquals(listOf("floor", "comment"), records.map { it.recordType })
    }
}
