package com.melonhu.indexrecord.parser.threads

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.parser.ParserRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ThreadsTimelineTest {
    private val url = "https://i.instagram.com/api/v1/feed/text_post_app_timeline/"
    private fun fixture(page: Int) = javaClass.getResource("/threads/timeline_$page.ndjson")!!.readText()
    private fun parse(json: String) = ThreadsParser.parse(RawData(url = url, json = json, hostApp = "com.instagram.barcelona"), url)
    private fun chunks(page: Int) = fixture(page).lineSequence().filter { it.isNotBlank() }
        .map { JsonParser.parseString(it).asJsonObject }.toList()
    private fun items(page: Int) = chunks(page).flatMap { it.getAsJsonArray("feed_items")?.toList().orEmpty() }
    private fun firstItem() = items(1).first().asJsonObject.deepCopy()
    private fun feed(item: JsonObject) = JsonObject().apply { add("feed_items", JsonArray().apply { add(item) }) }
    private fun post(item: JsonObject) = item["text_post_app_thread"].asJsonObject["thread_items"].asJsonArray[0].asJsonObject["post"].asJsonObject

    @Test fun realTimelineWrappersRouteAndYieldFiveOrganicPostsPerStream() {
        assertSame(ThreadsParser, ParserRegistry.findParser(url, RawData(hostApp = "com.instagram.barcelona")))
        assertEquals(listOf(10, 6, 5), (1..3).map { chunks(it).size })
        for (page in 1..3) {
            val records = parse(fixture(page))
            assertEquals("page $page", 5, records.size)
            assertTrue(records.all { it.platform == "threads" && it.recordType == "thread" && it.parentId.isEmpty() })
            assertTrue(records.all { it.content.isNotBlank() && it.authorId.isNotBlank() })
            assertTrue(records.all { JsonParser.parseString(it.extraMetadata).asJsonObject.has("quote_count") })
        }
    }

    @Test fun overlappingPagesDeduplicateToThirteenStableIds() {
        val records = parse((1..3).joinToString("") { fixture(it) })
        assertEquals(13, records.size)
        assertEquals(13, records.map { it.platformId }.distinct().size)
        assertTrue(records.all { it.platformId.matches(Regex("threads_post_[0-9]+")) })
    }

    @Test fun actualInjectedAdsAreExcludedEvenWhenTheyHaveThreadsPostInfo() {
        for (page in 1..3) {
            val ads = items(page).mapNotNull { it.asJsonObject.getAsJsonObject("text_post_app_thread") }
                .flatMap { it["thread_items"].asJsonArray.toList() }.map { it.asJsonObject["post"].asJsonObject }
                .filter { it.has("injected") }
            assertEquals(1, ads.size)
            assertTrue(ThreadsParser.isAd(ads.single()))
            val adId = ThreadsParser.postId(ads.single()["pk"].asString)
            assertFalse(parse(fixture(page)).any { it.platformId == adId })
            if (page != 2) assertTrue(ads.single().has("text_post_app_info"))
        }
    }

    @Test fun suggestedUsersUnitIsIgnoredIncludingSyntheticPostLikePrefetches() {
        val recommendation = items(2).single { it.asJsonObject.has("suggested_users") }.asJsonObject.deepCopy()
        assertTrue(parse(feed(recommendation).toString()).isEmpty())
        val card = recommendation["suggested_users"].asJsonObject["suggestion_cards"].asJsonArray[0].asJsonObject["user_card"].asJsonObject
        card.add("media_infos", JsonArray().apply { add(post(firstItem())) })
        recommendation.add("text_post_app_thread", firstItem()["text_post_app_thread"])
        assertTrue(parse(feed(recommendation).toString()).isEmpty())
    }

    @Test fun onlyObservedFeedWrapperIsTraversedNotArbitraryPostDataOrPrefetch() {
        val item = firstItem()
        val thread = item["text_post_app_thread"]
        val unknown = JsonObject().apply {
            add("post", post(item))
            add("data", thread)
            add("prefetch", item)
        }
        assertTrue(parse(feed(unknown).toString()).isEmpty())
        unknown.add("text_post_app_thread", thread)
        assertEquals(1, parse(feed(unknown).toString()).size)
    }

    @Test fun injectedAdMarkerOnEveryAncestorBlocksThePostButBenignInjectedDataDoesNot() {
        // Actual samples have injected.ad_id on post; ancestor placement is a synthetic boundary case.
        for (level in 0..4) {
            val item = firstItem()
            val root = feed(item)
            val thread = item["text_post_app_thread"].asJsonObject
            val threadItem = thread["thread_items"].asJsonArray[0].asJsonObject
            listOf(root, item, thread, threadItem, post(item))[level]
                .add("injected", JsonObject().apply { addProperty("ad_id", "synthetic-ad") })
            assertTrue("ancestor $level", parse(root.toString()).isEmpty())
        }
        val item = firstItem()
        post(item).add("injected", JsonObject().apply { addProperty("ad_id", ""); addProperty("unrelated", true) })
        assertEquals(1, parse(feed(item).toString()).size)
        post(item).addProperty("ad_action", "webclick")
        assertTrue(parse(feed(item).toString()).isEmpty())
    }

    @Test fun privateTimelineStreamsReplayWhenAvailable() {
        val dir = sequenceOf(File("../research/threads"), File("research/threads")).firstOrNull { it.isDirectory } ?: return
        val names = listOf("1794502305503_47474060262096.bin", "1794502308255_47476812342563.bin", "1794502316073_47484630963342.bin")
        val combined = StringBuilder()
        for (name in names) {
            val file = File(dir, name)
            if (!file.isFile) continue
            val json = file.readText()
            val expected = linkedSetOf<String>()
            var ads = 0
            var recommendations = 0
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                val chunk = JsonParser.parseString(line).asJsonObject
                chunk.getAsJsonArray("feed_items")?.forEach { element ->
                    val wrapper = element.asJsonObject
                    if (wrapper.has("suggested_users")) recommendations++
                    wrapper.getAsJsonObject("text_post_app_thread")?.getAsJsonArray("thread_items")?.forEach { item ->
                        val p = item.asJsonObject["post"].asJsonObject
                        if (p.getAsJsonObject("injected")?.has("ad_id") == true) ads++
                        else expected.add(ThreadsParser.postId(p["pk"].asString))
                    }
                }
            }
            val records = parse(json)
            assertEquals(name, 1, ads)
            assertEquals(if (name == names[1]) 1 else 0, recommendations)
            assertEquals(name, 5, records.size)
            assertEquals(expected, records.map { it.platformId }.toSet())
            combined.append(json).append('\n')
        }
        if (names.all { File(dir, it).isFile }) assertEquals(13, parse(combined.toString()).size)
    }
}
