package com.melonhu.indexrecord.parser.threads

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.parser.ParserRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ThreadsParserTest {
    private val url = "https://i.instagram.com/api/v1/feed/text_post_app_replies/"
    private fun fixture(name: String): String = javaClass.getResource("/threads/$name.json")!!.readText()
    private fun parse(json: String, endpoint: String = url) = ThreadsParser.parse(
        RawData(url = endpoint, json = json, hostApp = "com.instagram.barcelona", timestamp = 1700000012345), endpoint)
    private fun root(name: String) = JsonParser.parseString(fixture(name)).asJsonObject
    private fun post(root: JsonObject) = root["threads"].asJsonArray[0].asJsonObject["thread_items"].asJsonArray[0].asJsonObject["post"].asJsonObject

    @Test fun mainPostUsesStableIdentityAuthorMarkdownAndActualCounts() {
        val record = parse(fixture("35a98fbc")).single()
        assertEquals("threads", record.platform)
        assertEquals("thread", record.recordType)
        assertEquals("threads_post_8000000000000000001", record.platformId)
        assertEquals("", record.parentId)
        assertEquals("90001", record.authorId)
        assertEquals("Author 90001", record.authorName)
        assertTrue(record.authorAvatar.startsWith("https://example.test/"))
        assertTrue(record.content.contains("Second line \\*literal\\*"))
        assertEquals(1700000000000, record.publishTime)
        assertEquals(1700000012345, record.captureTime)
        val meta = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals(33, meta["reply_count"].asInt)
        assertEquals(0, meta["repost_count"].asInt)
        assertFalse(meta.has("organic_tracking_token"))
    }

    @Test fun repliesArePostsWithExactImmediateParentsNotCaptionComments() {
        val records = parse(fixture("402a47a0"))
        assertEquals(8, records.size)
        assertTrue(records.all { it.recordType == "comment" })
        val rootId = parse(fixture("35a98fbc")).single().platformId
        assertEquals(4, records.count { it.parentId == rootId })
        assertEquals(4, records.count { reply -> records.any { it.platformId == reply.parentId } })
        val source = root("402a47a0")["threads"].asJsonArray
        source.forEach { thread ->
            val items = thread.asJsonObject["thread_items"].asJsonArray
            val first = items[0].asJsonObject["post"].asJsonObject
            val second = items[1].asJsonObject["post"].asJsonObject
            val firstId = ThreadsParser.postId(first["pk"].asString)
            val secondId = ThreadsParser.postId(second["pk"].asString)
            assertEquals(firstId, records.single { it.platformId == secondId }.parentId)
            assertEquals(rootId, records.single { it.platformId == firstId }.parentId)
        }
    }

    @Test fun repostCarouselRendersVideoAndImagesWithoutInsertingAttachmentsAsRecords() {
        val records = parse(fixture("6b83e418"))
        assertEquals(2, records.size)
        assertTrue(records.all { it.recordType == "thread" && it.parentId.isEmpty() })
        assertEquals(2, Regex("点击播放视频").findAll(records[0].content).count())
        assertTrue(records[0].content.contains("![图片]"))
        assertTrue(records[0].content.contains("> @user_"))
        assertTrue(JsonParser.parseString(records[0].extraMetadata).asJsonObject.has("reposted_post_id"))
    }

    @Test fun registryRoutesOnlyThreadsSourceAndSupportedEndpoints() {
        val raw = RawData(hostApp = "com.instagram.barcelona", json = fixture("35a98fbc"))
        assertSame(ThreadsParser, ParserRegistry.findParser(url, raw))
        assertTrue(ThreadsParser.canHandle("https://www.threads.com/graphql/query", raw))
        assertFalse(ThreadsParser.canHandle("https://i.instagram.com/api/v1/feed/timeline/", raw.copy(hostApp = "com.instagram.android")))
        assertFalse(ThreadsParser.canHandle("https://i.instagram.com/api/v1/users/info/", raw))
        assertFalse(ThreadsParser.canHandle("https://evilthreads.com/graphql", raw.copy(hostApp = "")))
    }

    @Test fun ignoredRealContainersAndMalformedDataProduceNoRecords() {
        listOf("notifications", "users", "recommendations").forEach { assertTrue(it, parse(fixture(it)).isEmpty()) }
        listOf("{}", "{", "null", "[]", "{\"status\":\"ok\"}").forEach { assertTrue(parse(it).isEmpty()) }
    }

    @Test fun adsOnEveryAncestorAreExcludedButEmptyAdsAreNot() {
        for (level in 0..3) {
            val json = root("35a98fbc")
            val thread = json["threads"].asJsonArray[0].asJsonObject
            val item = thread["thread_items"].asJsonArray[0].asJsonObject
            val ancestors = listOf(json, thread, item, post(json))
            ancestors[level].add("ads", JsonParser.parseString("{\"campaign_id\":\"synthetic\"}"))
            assertTrue("ancestor $level", parse(json.toString()).isEmpty())
        }
        val json = root("35a98fbc")
        json.add("ads", JsonParser.parseString("[]"))
        assertEquals(1, parse(json.toString()).size)
    }

    @Test fun explicitMediaAndDataWrappersWorkWithoutTraversingPrefetchOrCaption() {
        val p = post(root("35a98fbc"))
        val wrapped = JsonObject().apply { add("data", JsonObject().apply {
            add("medias", JsonParser.parseString("[]").asJsonArray.apply { add(JsonObject().apply { add("media", p) }); add(p) })
            add("prefetch", root("402a47a0"))
        }) }
        assertEquals(1, parse(wrapped.toString()).size)
        assertTrue(parse(JsonObject().apply { add("prefetch", root("35a98fbc")) }.toString()).isEmpty())
        val suffix = p["id"].asString
        p.remove("pk")
        assertEquals("threads_post_" + suffix.substringBefore('_'), parse(wrapped.toString()).single().platformId)
    }

    @Test fun syntheticQuoteVariantRendersInlineAndAncestorAdHidesAttachment() {
        val json = root("6b83e418")
        val share = post(json)["text_post_app_info"].asJsonObject["share_info"].asJsonObject
        share.add("quoted_post", share.remove("reposted_post"))
        val records = parse(json.toString())
        assertEquals(2, records.size)
        assertTrue(records[0].content.contains("点击播放视频"))
        assertTrue(JsonParser.parseString(records[0].extraMetadata).asJsonObject.has("quoted_post_id"))
        share.addProperty("ads", true)
        assertFalse(parse(json.toString())[0].content.contains("点击播放视频"))
    }

    @Test fun syntheticCounterVariantsAndHugeIdsRemainExact() {
        val json = root("35a98fbc")
        val p = post(json)
        p.addProperty("pk", "9999999999999999999999999999_123")
        val info = p["text_post_app_info"].asJsonObject
        info.add("reply_to_post_id", JsonParser.parseString("9999999999999999999999999998"))
        info.remove("direct_reply_count")
        p.addProperty("comment_count", 17)
        p.addProperty("view_count", 123456789)
        info.addProperty("repost_count", 12)
        info.addProperty("quote_count", 4)
        val record = parse(json.toString()).single()
        assertEquals("threads_post_9999999999999999999999999999", record.platformId)
        assertEquals("threads_post_9999999999999999999999999998", record.parentId)
        val meta = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals(17, meta["comment_count"].asInt)
        assertEquals(123456789, meta["view_count"].asInt)
        assertEquals(12, meta["repost_count"].asInt)
        assertEquals(4, meta["quote_count"].asInt)
        assertFalse(meta.has("reply_count"))
    }

    @Test fun realPrivateSamplesWhenLocallyAvailable() {
        // Optional local replay never copies private dumps into test resources or test output.
        val dir = sequenceOf(File("../research/threads"), File("research/threads")).firstOrNull { it.isDirectory } ?: return
        val expected = mapOf("35a98fbc" to 1, "402a47a0" to 8, "6b83e418" to 2)
        dir.listFiles { f -> f.name.endsWith("body_gzip.clean.json") }!!.forEach { file ->
            val records = parse(file.readText())
            assertEquals(file.name, expected[file.name.substringBefore('-')] ?: 0, records.size)
            records.forEach { record -> assertTrue(record.platformId.matches(Regex("threads_post_[0-9]+"))) }
        }
    }
}
