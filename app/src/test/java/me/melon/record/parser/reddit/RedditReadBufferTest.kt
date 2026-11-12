package me.melon.record.parser.reddit

import com.google.gson.Gson
import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.data.model.RedditPendingRecord
import me.melon.record.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test

class RedditReadBufferTest {
    private val root = "reddit_post_1xr0ctd"
    private val week = 7L * 24 * 60 * 60 * 1000

    private class MemoryStorage : RedditReadBuffer.Storage {
        val rows = linkedMapOf<String, RedditPendingRecord>()
        var pendingCalls = 0
        override fun find(platformId: String) = rows[platformId]?.copy()
        override fun forPost(postId: String) = rows.values.filter { it.postId == postId }.map { it.copy() }
        override fun put(row: RedditPendingRecord) { rows[row.platformId] = row.copy() }
        override fun remove(row: RedditPendingRecord) { rows.remove(row.platformId) }
        override fun pending(): List<RedditPendingRecord> {
            pendingCalls++
            return rows.values.map { it.copy() }
        }
    }

    private fun raw(name: String) = RawData(
        url = "https://gql-fed.reddit.com/", hostApp = RedditParser.HOST_APP,
        json = javaClass.getResource("/reddit/$name.json")!!.readText(), timestamp = 100
    )
    private fun fixture(name: String): List<UniversalRecord> = raw(name).let { RedditParser.parse(it, it.url) }
    private fun accept(storage: MemoryStorage, records: List<UniversalRecord> = emptyList(),
                       confirmed: Set<String> = emptySet(), now: Long = 100) =
        RedditReadBuffer(storage).accept(records, confirmed, now)
    private fun post(id: String = root) = UniversalRecord(
        platform = "reddit", recordType = "post", platformId = id, content = "post", captureTime = 100
    )
    private fun comment(postId: String = root, body: String = "body", captured: Long = 100) = UniversalRecord(
        platform = "reddit", recordType = "comment", platformId = "reddit_comment_child",
        content = body, captureTime = captured,
        extraMetadata = if (postId.isEmpty()) "{}" else """{"post_id":"$postId"}"""
    )
    private fun metadata(record: UniversalRecord) = JsonParser.parseString(record.extraMetadata).asJsonObject

    @Test fun postsBeforeThreadReleaseMergedCandidatesButNotUntouchedThreadAcrossInstances() {
        val storage = MemoryStorage()
        assertTrue(accept(storage, fixture("posts")).isEmpty())
        assertFalse(storage.find(root)!!.confirmed)
        assertTrue(storage.find(root)!!.recordJson.isNotEmpty())
        val result = accept(storage, fixture("thread"), RedditParser.readPostIds(raw("thread")), 200)
        assertEquals(11, result.size)
        assertEquals(11, result.map { it.platformId }.toSet().size)
        val restored = result.single { it.platformId == root }
        assertTrue(restored.content.contains("preview.redd.it"))
        assertEquals("t2_9117cvl1n", restored.authorId)
        assertEquals("IdiotStickWasTkn", restored.authorName)
        assertFalse(result.any { it.platformId == "reddit_post_1xouqzu" })
        assertTrue(storage.find("reddit_post_1xouqzu")!!.recordJson.isNotEmpty())
        assertTrue(storage.find(root)!!.confirmed)
        assertTrue(storage.forPost(root).all { it.recordJson.isEmpty() })
        assertTrue(accept(storage).isEmpty())
        assertEquals(3, storage.pendingCalls)
    }

    @Test fun sparseDetailRestoresFullMarkdownAuthorAndAvatarFromEarlierBatch() {
        val storage = MemoryStorage()
        val full = fixture("posts").single { it.platformId == "reddit_post_1xouqzu" }.apply {
            authorAvatar = "https://example.test/avatar.png"
            captureTime = 100
        }
        assertTrue(full.content.isNotBlank())
        assertTrue(full.authorName.isNotBlank())
        assertTrue(accept(storage, listOf(full)).isEmpty())
        val detail = RawData(url = "https://gql-fed.reddit.com/", hostApp = RedditParser.HOST_APP,
            timestamp = 200,
            json = """{"data":{"postInfoById":{"id":"t3_1xouqzu","title":"detail title","commentForest":{"trees":[]}}}}""")
        val sparse = RedditParser.parse(detail, detail.url)
        assertTrue(sparse.single().authorName.isEmpty())
        assertTrue(sparse.single().content.isEmpty())
        val restored = accept(storage, sparse, RedditParser.readPostIds(detail), 200).single()
        assertEquals(full.content, restored.content)
        assertEquals(full.authorId, restored.authorId)
        assertEquals(full.authorName, restored.authorName)
        assertEquals(full.authorAvatar, restored.authorAvatar)
        assertEquals(200L, restored.captureTime)
    }

    @Test fun threadBeforePostsImmediatelyEmitsLaterHydrationAcrossInstances() {
        val storage = MemoryStorage()
        val first = accept(storage, fixture("thread"), RedditParser.readPostIds(raw("thread")))
        assertEquals(11, first.size)
        val later = accept(storage, fixture("posts"), now = 200)
        assertEquals(listOf(root), later.map { it.platformId })
        assertTrue(later.single().content.contains("preview.redd.it"))
        assertEquals("", storage.find(root)!!.recordJson)
        assertFalse(storage.find("reddit_post_1xouqzu")!!.confirmed)
    }

    @Test fun stagedFragmentsMergeWithoutMutatingInputOrTreatingPayloadAsProof() {
        val storage = MemoryStorage()
        val full = comment(body = "**full body**").apply {
            parentId = "reddit_comment_parent"
            extraMetadata = """{"post_id":"$root","body_source":"markdown","body_complete":true,"score":8}"""
        }
        val sparse = comment(postId = "", body = "short", captured = 200).apply {
            extraMetadata = """{"body_source":"preview","score":0}"""
        }
        val before = sparse.copy()
        assertTrue(accept(storage, listOf(full)).isEmpty())
        assertTrue(accept(storage, listOf(sparse), now = 200).isEmpty())
        assertEquals(before, sparse)
        assertNull(storage.find(root))
        val merged = accept(storage, confirmed = setOf(root), now = 300).single()
        assertEquals(full.content, merged.content)
        assertEquals(full.parentId, merged.parentId)
        assertEquals(0, metadata(merged)["score"].asInt)
        assertEquals(root, metadata(merged)["post_id"].asString)
        assertEquals("", storage.find(full.platformId)!!.recordJson)
    }

    @Test fun laterCommentHydrationRecoversPayloadFreeMappingButUnknownCommentsStayPrivate() {
        val storage = MemoryStorage()
        val child = comment()
        assertEquals(1, accept(storage, listOf(child), setOf(root)).size)
        val unknown = comment(postId = "", body = "unknown").copy(platformId = "reddit_comment_unknown")
        val hydration = comment(postId = "", body = "hydrated", captured = 200)
        val result = accept(storage, listOf(hydration, unknown), now = 200)
        assertEquals(listOf(child.platformId), result.map { it.platformId })
        assertEquals("hydrated", result.single().content)
        assertEquals(root, metadata(result.single())["post_id"].asString)
        assertEquals("", storage.find(unknown.platformId)!!.postId)
        assertTrue(storage.find(unknown.platformId)!!.recordJson.isNotEmpty())
    }

    @Test fun unknownRootIsBufferedUntilSameIdIsMappedEvenWhenProofAlreadyExists() {
        val storage = MemoryStorage()
        val unknown = comment(postId = "", body = "full unknown body").apply {
            parentId = root // A direct parent alone must not establish a thread mapping.
        }
        assertTrue(accept(storage, listOf(unknown), setOf(root)).isEmpty())
        assertTrue(accept(storage, confirmed = setOf("reddit_post_other"), now = 200).isEmpty())
        val mapped = comment(body = "", captured = 300)
        val result = accept(storage, listOf(mapped), now = 300).single()
        assertEquals(unknown.content, result.content)
        assertEquals(root, metadata(result)["post_id"].asString)
        assertEquals("", storage.find(unknown.platformId)!!.recordJson)
    }

    @Test fun forestOnlyMarkerPersistsProofWithoutFabricatingPostOrAnyPayload() {
        val storage = MemoryStorage()
        val marker = RawData(url = "https://gql-fed.reddit.com/", hostApp = RedditParser.HOST_APP,
            json = """{"request":{"variables":{"postId":"t3_1xr0ctd"}},"response":{"data":{"comments":{"__typename":"CommentForest","trees":[]}}}}""")
        assertTrue(RedditParser.parse(marker, marker.url).isEmpty())
        assertEquals(setOf(root), RedditParser.readPostIds(marker))
        assertTrue(accept(storage, confirmed = RedditParser.readPostIds(marker)).isEmpty())
        assertEquals(RedditPendingRecord(platformId = root, postId = root, confirmed = true, updatedAt = 100), storage.find(root))
        assertEquals(listOf(root), accept(storage, listOf(post()), now = 200).map { it.platformId })
        assertTrue(accept(storage, confirmed = setOf(root), now = 300).isEmpty())
    }

    @Test fun duplicateCandidatesInOneCallEmitOnceWithMergedPayload() {
        val storage = MemoryStorage()
        val result = accept(storage, listOf(comment(body = "long body"), comment(body = "", captured = 200)), setOf(root))
        assertEquals(1, result.size)
        assertEquals("long body", result.single().content)
        assertTrue(storage.forPost(root).all { it.recordJson.isEmpty() })
    }

    @Test fun storedRecordAndCommentFlagsAreNotRootProof() {
        val storage = MemoryStorage()
        storage.put(RedditPendingRecord(platformId = root, postId = root, recordJson = Gson().toJson(post()), updatedAt = 100))
        storage.put(RedditPendingRecord(platformId = "reddit_comment_child", postId = root, confirmed = true, updatedAt = 100))
        assertTrue(accept(storage, listOf(comment())).isEmpty())
        assertFalse(storage.find(root)!!.confirmed)
        assertEquals(2, accept(storage, confirmed = setOf(root), now = 200).size)
    }

    @Test fun ttlPrunesPayloadsAndMappingsButNeverConfirmedRootProof() {
        val storage = MemoryStorage()
        accept(storage, listOf(comment()), setOf(root), now = 100)
        accept(storage, listOf(post("reddit_post_unread"), comment(postId = "").copy(platformId = "reddit_comment_unknown")), now = 100)
        accept(storage, now = 100 + week)
        assertEquals(4, storage.rows.size) // The TTL boundary itself is retained.
        accept(storage, now = 101 + week)
        assertEquals(setOf(root), storage.rows.keys)
        assertTrue(storage.find(root)!!.confirmed)
        assertEquals(1, accept(storage, listOf(post()), now = 102 + week).size)
        assertEquals(5, storage.pendingCalls)
    }

    @Test fun sizeLimitEvictsOldestPendingRowsIncludingMappingsButNotProof() {
        val storage = MemoryStorage()
        accept(storage, listOf(comment()), setOf(root), now = 1)
        val records = (0 until 5001).map { post("reddit_post_unread_$it") }
        assertTrue(accept(storage, records, now = 2).isEmpty())
        assertEquals(5001, storage.rows.size)
        assertTrue(storage.find(root)!!.confirmed)
        assertNull(storage.find("reddit_comment_child"))
        assertNull(storage.find("reddit_post_unread_0"))
        assertNotNull(storage.find("reddit_post_unread_1"))
        assertEquals(2, storage.pendingCalls)
    }

    @Test fun otherPlatformsAndEmptyIdsAreNotStagedAndBlankMarkersAreIgnored() {
        val storage = MemoryStorage()
        assertTrue(accept(storage, listOf(post().copy(platform = "twitter"), post().copy(platformId = "")), setOf("", " ")).isEmpty())
        assertTrue(storage.rows.isEmpty())
    }
}
