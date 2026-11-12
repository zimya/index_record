package me.melon.record.parser.threads

import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test

class ThreadsMergeTest {
    private fun record(body: String = "", meta: String = "{}") = UniversalRecord(
        platform = "threads", platformId = "threads_post_8000000000000000001",
        recordType = "thread", content = body, extraMetadata = meta)

    @Test fun sparseUpdatesKeepBodyParentCommentTypeAuthorTimeAndMetadata() {
        val existing = record("Full text\n\n![图片](https://example.test/image.jpg)",
            """{"like_count":8,"reply_count":4,"repost_count":3,"username":"author","quoted_post_id":"threads_post_2"}""").apply {
            recordType = "comment"
            parentId = "threads_post_8000000000000000000"
            authorId = "9"
            authorName = "Author"
            authorAvatar = "https://example.test/avatar.jpg"
            sourceUrl = "https://www.threads.com/@author/post/sample"
            publishTime = 1700000000000
            captureTime = 1700000005000
        }
        val incoming = record("Full text", """{"like_count":0,"reply_count":null,"username":"","quote_count":0}""").apply {
            captureTime = 1700000000000
        }
        ThreadsRelations.mergeRecord(existing, incoming)
        assertEquals(existing.content, incoming.content)
        assertEquals(existing.parentId, incoming.parentId)
        assertEquals("comment", incoming.recordType)
        assertEquals(existing.authorId, incoming.authorId)
        assertEquals(existing.authorName, incoming.authorName)
        assertEquals(existing.authorAvatar, incoming.authorAvatar)
        assertEquals(existing.sourceUrl, incoming.sourceUrl)
        assertEquals(existing.publishTime, incoming.publishTime)
        assertEquals(existing.captureTime, incoming.captureTime)
        val meta = JsonParser.parseString(incoming.extraMetadata).asJsonObject
        assertEquals(0, meta["like_count"].asInt)
        assertEquals(4, meta["reply_count"].asInt)
        assertEquals(3, meta["repost_count"].asInt)
        assertEquals("author", meta["username"].asString)
        assertEquals(0, meta["quote_count"].asInt)
        assertEquals("threads_post_2", meta["quoted_post_id"].asString)
    }

    @Test fun actualSparsePostPatchUsesTheSameIdentityAndDoesNotDemoteReply() {
        val endpoint = "https://i.instagram.com/api/v1/media/info/"
        fun parse(json: String) = ThreadsParser.parse(RawData(json = json, hostApp = "com.instagram.barcelona"), endpoint).single()
        val first = parse("""{"medias":[{"media":{"id":"8000000000000000001_9","caption":{"text":"Original body"},"text_post_app_info":{"is_reply":true,"reply_to_post_id":8000000000000000000}}}]}""")
        val second = parse("""{"medias":[{"media":{"pk":8000000000000000001,"like_count":0,"text_post_app_info":{}}}]}""")
        ThreadsRelations.mergeRecord(first, second)
        assertEquals(first.platformId, second.platformId)
        assertEquals(first.content, second.content)
        assertEquals("threads_post_8000000000000000000", second.parentId)
        assertEquals("comment", second.recordType)
    }

    @Test fun replayInEitherOrderNeedsNoParentPlaceholdersOrRelinking() {
        val endpoint = "https://i.instagram.com/api/v1/feed/text_post_app_replies/"
        fun parse(name: String) = ThreadsParser.parse(RawData(
            json = javaClass.getResource("/threads/$name.json")!!.readText(), hostApp = "com.instagram.barcelona"), endpoint)
        val main = parse("35a98fbc")
        val replies = parse("402a47a0")
        for (batch in listOf(replies + main, main + replies)) {
            val stored = linkedMapOf<String, UniversalRecord>()
            (batch + batch).forEach { record ->
                val incoming = record.copy()
                stored[incoming.platformId]?.let { ThreadsRelations.mergeRecord(it, incoming) }
                stored[incoming.platformId] = incoming
            }
            assertEquals(9, stored.size)
            assertEquals(1, stored.values.count { it.recordType == "thread" })
            assertTrue(stored.values.filter { it.recordType == "comment" }.all { stored.containsKey(it.parentId) })
            assertEquals(4, stored.values.count { stored[it.parentId]?.recordType == "comment" })
        }
    }

    @Test fun malformedMetadataAndEmptyValuesDoNotEraseExistingAndOtherRecordsAreUntouched() {
        val old = record("Full text", """{"like_count":9,"images":["image"]}""")
        val patch = record(meta = "invalid")
        ThreadsRelations.mergeRecord(old, patch)
        assertEquals(old.extraMetadata, patch.extraMetadata)
        val empty = record(meta = """{"images":[],"other":{},"nothing":null}""")
        ThreadsRelations.mergeRecord(old, empty)
        assertEquals(old.extraMetadata, empty.extraMetadata)
        for (incoming in listOf(record().apply { platform = "twitter" }, record().apply { platformId = "threads_post_2" })) {
            val before = incoming.copy()
            ThreadsRelations.mergeRecord(old, incoming)
            assertEquals(before, incoming)
        }
    }
}
