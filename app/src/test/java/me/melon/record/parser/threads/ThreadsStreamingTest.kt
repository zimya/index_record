package me.melon.record.parser.threads

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.parser.ParserRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ThreadsStreamingTest {
    private val url = "https://i.instagram.com/api/v1/text_feed/8100000000000000001/replies_streaming/"
    private fun fixture(name: String) = javaClass.getResource("/threads/replies_streaming_$name.ndjson")!!.readText()
    private fun parse(json: String) = ThreadsParser.parse(RawData(url = url, json = json, hostApp = "com.instagram.barcelona"), url)
    private fun chunks(name: String) = fixture(name).lineSequence().filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }.toList()
    private fun mainChunk() = chunks("initial")[1]

    @Test fun textFeedStreamingEndpointRoutesThroughRegistry() {
        val raw = RawData(url = url, hostApp = "com.instagram.barcelona")
        assertSame(ThreadsParser, ParserRegistry.findParser(url, raw))
        assertFalse(ThreadsParser.canHandle(url, raw.copy(hostApp = "com.instagram.android")))
    }

    @Test fun initialStreamKeepsContainingPostAndAllRepliesAcrossHeartbeatsAndDuplicateChunks() {
        val records = parse(fixture("initial"))
        assertEquals(5, records.size)
        val main = records.single { it.recordType == "thread" }
        assertEquals("threads_post_8100000000000000001", main.platformId)
        assertEquals(6, Regex("!\\[图片]").findAll(main.content).count())
        assertEquals(4, records.count { it.recordType == "comment" && it.parentId == main.platformId })
        val meta = JsonParser.parseString(main.extraMetadata).asJsonObject
        assertEquals(22, meta["like_count"].asInt)
        assertEquals(10, meta["reply_count"].asInt)
        assertEquals(2, meta["repost_count"].asInt)
        assertEquals(4, meta["reshare_count"].asInt)
        assertEquals(0, meta["quote_count"].asInt)
        assertFalse(meta.has("view_count"))
    }

    @Test fun paginationStreamWithoutContainingPostStillKeepsExactDirectParents() {
        val initialRoot = parse(fixture("initial")).single { it.recordType == "thread" }.platformId
        val page = parse(fixture("page"))
        assertEquals(5, page.size)
        assertTrue(page.all { it.recordType == "comment" && it.parentId == initialRoot })
        assertEquals(10, parse(fixture("initial") + fixture("page")).size)
    }

    @Test fun consecutiveObjectsAlsoSupportPrettyPrintedChunksWithoutNewlineSeparators() {
        val documents = chunks("initial") + chunks("page")
        val compact = documents.joinToString("")
        val pretty = documents.joinToString("") { com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(it) }
        val expected = parse(fixture("initial") + fixture("page")).associate { it.platformId to it.content }
        assertEquals(expected, parse(compact).associate { it.platformId to it.content })
        assertEquals(expected, parse(pretty).associate { it.platformId to it.content })
    }

    @Test fun malformedNdjsonLineDoesNotDiscardChunksBeforeOrAfterIt() {
        val corrupted = fixture("initial") + "{\"reply_threads\": [broken\n" + fixture("page")
        assertEquals(10, parse(corrupted).size)
        assertEquals(10, parse("{bad\n" + fixture("initial") + fixture("page") + "{truncated").size)
    }

    @Test fun nonPostChunkDoesNotDiscardFollowingChunk() {
        val nonPost = """{"reply_threads":null,"containing_thread":{"thread_items":[{"post":null}]},"status":"ok"}"""
        assertEquals(10, parse(fixture("initial") + nonPost + "\n" + fixture("page")).size)
    }

    @Test fun siblingThreadsAreExplicitlySupportedButRelatedThreadsAndPrefetchAreNot() {
        // Captures expose these keys but their arrays are empty; nonempty variants are synthetic.
        val chunk = mainChunk()
        val thread = chunk["containing_thread"]
        chunk.remove("containing_thread")
        chunk.add("related_threads", com.google.gson.JsonArray().apply { add(thread) })
        chunk.add("prefetch", JsonObject().apply { add("containing_thread", thread) })
        assertTrue(parse(chunk.toString()).isEmpty())
        chunk.add("sibling_threads", com.google.gson.JsonArray().apply { add(thread) })
        assertEquals(1, parse(chunk.toString()).size)
        chunk.addProperty("ads", true)
        assertTrue(parse(chunk.toString()).isEmpty())
    }

    @Test fun stringCountersSupportBothLocationsAndNullPostValueDoesNotMaskInfo() {
        val chunk = mainChunk()
        val p = chunk["containing_thread"].asJsonObject["thread_items"].asJsonArray[0].asJsonObject["post"].asJsonObject
        val info = p["text_post_app_info"].asJsonObject
        // These captures have no view_count; exercise numeric-string/null variants synthetically.
        p.add("view_count", com.google.gson.JsonNull.INSTANCE)
        info.addProperty("view_count", "9007199254740993")
        info.addProperty("quote_count", "7")
        val meta = JsonParser.parseString(parse(chunk.toString()).single().extraMetadata).asJsonObject
        assertEquals("9007199254740993", meta["view_count"].asString)
        assertEquals("7", meta["quote_count"].asString)
        assertEquals(4, meta["reshare_count"].asInt)
        p.addProperty("view_count", "0")
        assertEquals("0", JsonParser.parseString(parse(chunk.toString()).single().extraMetadata).asJsonObject["view_count"].asString)
        p.addProperty("view_count", "unavailable")
        assertEquals("9007199254740993", JsonParser.parseString(parse(chunk.toString()).single().extraMetadata).asJsonObject["view_count"].asString)
    }

    @Test fun privateStreamsReplayWhenAvailableWithoutCopyingPrivateDataIntoResources() {
        val dir = sequenceOf(File("../research/threads"), File("research/threads")).firstOrNull { it.isDirectory } ?: return
        val names = listOf("1794502041440_47209997275061.bin", "1794502042068_47210625315113.bin")
        for (name in names) {
            val file = File(dir, name)
            if (!file.isFile) continue
            val records = parse(file.readText())
            assertEquals(name, 5, records.size)
            val expected = linkedMapOf<String, String>()
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                val chunk = JsonParser.parseString(line).asJsonObject
                val threads = listOf("reply_threads", "sibling_threads").flatMap { chunk[it].asJsonArray.toList() } +
                    listOfNotNull(chunk["containing_thread"]?.takeIf { it.isJsonObject })
                threads.forEach { thread -> thread.asJsonObject["thread_items"].asJsonArray.forEach { item ->
                    val post = item.asJsonObject["post"].asJsonObject
                    val id = ThreadsParser.postId(post["pk"].asString)
                    val parent = post["text_post_app_info"].asJsonObject["reply_to_post_id"]?.asString.orEmpty()
                    expected[id] = ThreadsParser.postId(parent)
                } }
            }
            assertEquals(expected, records.associate { it.platformId to it.parentId })
        }
    }
}
