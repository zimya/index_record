package me.melon.record.logic

import me.melon.record.data.model.RawData
import me.melon.record.data.model.PendingRecordSnapshot
import me.melon.record.data.model.RecordIdentity
import me.melon.record.data.model.RecordSnapshot
import me.melon.record.data.model.RedditPendingRecord
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toRecord
import me.melon.record.data.model.toSnapshot
import me.melon.record.parser.ParseDiagnostic
import me.melon.record.parser.ParseResult
import me.melon.record.parser.reddit.RedditReadBuffer
import me.melon.record.parser.reddit.RedditParser
import me.melon.record.parser.reddit.RedditReadVisit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RedditPendingHistoryTest {
    private class History : RecordSnapshotStorage {
        val rows = linkedMapOf<String, RecordSnapshot>()
        val waiting = linkedMapOf<String, PendingRecordSnapshot>()
        var inTransaction = false
        override fun contains(key: String) = key in rows
        override fun hasIdentity(identity: RecordIdentity) = rows.values.any {
            it.platform == identity.platform && it.identityId == identity.contentId
        }
        override fun findRedditVisit(key: String) = rows.values.firstOrNull {
            it.key.startsWith("$key:source:")
        }?.copy()
        override fun hasRedditPostSource(identity: RecordIdentity, sourceKey: String) = rows.values.any {
            it.platform == identity.platform && it.identityId == identity.contentId && it.key.endsWith(":source:$sourceKey")
        }
        override fun redditVisitsNear(identity: RecordIdentity, time: Long) = rows.values.filter {
            it.key.startsWith(REDDIT_VISIT_KEY_PREFIX) && it.platform == identity.platform && it.identityId == identity.contentId &&
                it.captureTime in (time - REDDIT_LEGACY_VISIT_WINDOW)..(time + REDDIT_LEGACY_VISIT_WINDOW)
        }.map { it.copy() }
        override fun append(snapshot: RecordSnapshot) {
            check(inTransaction)
            check(snapshot.key !in rows)
            rows[snapshot.key] = snapshot.copy()
        }
        override fun findPending(key: String) = waiting[key]?.copy()
        override fun putPending(snapshot: PendingRecordSnapshot) {
            check(inTransaction)
            check(snapshot.key !in waiting)
            waiting[snapshot.key] = snapshot.copy()
        }
        override fun pending() = waiting.values.map { it.copy() }
        override fun removePending(snapshot: PendingRecordSnapshot) {
            check(inTransaction)
            waiting.remove(snapshot.key)
        }
    }
    private class Buffer : RedditReadBuffer.Storage {
        val rows = linkedMapOf<String, RedditPendingRecord>()
        var inTransaction = false
        override fun find(platformId: String) = rows[platformId]?.copy()
        override fun forPost(postId: String) = rows.values.filter { it.postId == postId }.map { it.copy() }
        override fun pending() = rows.values.map { it.copy() }
        override fun put(row: RedditPendingRecord) {
            check(inTransaction)
            rows[row.platformId] = row.copy()
        }
        override fun remove(row: RedditPendingRecord) {
            check(inTransaction)
            rows.remove(row.platformId)
        }
    }
    private class Harness {
        val current = MemoryRecordRepository()
        val history = History()
        val buffer = Buffer()
        val repository = object : RecordRepository by current {
            override val history get() = this@Harness.history
            override fun put(record: UniversalRecord) {
                check(history.inTransaction)
                archiveLegacyIndex(record, this, history)
                current.put(record)
            }
        }
        fun <T> transaction(action: () -> T): T {
            val oldCurrent = current.rows.mapValues { it.value.copy() }
            val oldWrites = current.writes.toList()
            val oldHistory = history.rows.mapValues { it.value.copy() }
            val oldWaiting = history.waiting.mapValues { it.value.copy() }
            val oldBuffer = buffer.rows.mapValues { it.value.copy() }
            history.inTransaction = true
            buffer.inTransaction = true
            return try { action() } catch (error: Exception) {
                current.rows.clear(); current.rows.putAll(oldCurrent)
                current.writes.clear(); current.writes.addAll(oldWrites)
                history.rows.clear(); history.rows.putAll(oldHistory)
                history.waiting.clear(); history.waiting.putAll(oldWaiting)
                buffer.rows.clear(); buffer.rows.putAll(oldBuffer)
                throw error
            } finally {
                history.inTransaction = false
                buffer.inTransaction = false
            }
        }
        fun processRaw(
            vararg raws: RawData, failAfterPersist: Boolean = false, readMode: Boolean = true, allowPartial: Boolean = false
        ): LongArray {
            val policy = RedditCapturePolicy(if (readMode) RedditReadBuffer(buffer) else null)
            val persistence = RecordPersistence(repository)
            val processing = CaptureProcessing(listOf(policy)) { consume -> raws.forEach(consume) }
            processing.prepare()
            val captures = raws.map { processing.parse(it, persistence) { error -> if (!allowPartial) throw error } }
            return transaction {
                val consumed = processing.persist(captures, persistence)
                check(!failAfterPersist) { "raw deletion failed" }
                consumed
            }
        }
        fun process(
            rawId: Long, records: List<UniversalRecord>, proof: Set<String> = emptySet(),
            partial: Boolean = false, readMode: Boolean = true
        ): LongArray {
            // New session collaborators, same durable stores, as on a retained raw's next processing run.
            val policy = RedditCapturePolicy(if (readMode) RedditReadBuffer(buffer) else null) { 1000 }
            val persistence = RecordPersistence(repository)
            val processing = CaptureProcessing(listOf(policy))
            val inputs = records.map { it.copy() }
            val result = if (partial) ParseResult.Partial(inputs, listOf(ParseDiagnostic("test", "bad fragment")))
                else ParseResult.Success(inputs)
            val capture = processing.complete(ParsedCapture(rawId, inputs, policy, readPosts = proof, result = result), persistence)
            return transaction { processing.persist(listOf(capture), persistence) }
        }
    }
    private fun post(time: Long = 10, body: String = "A body", id: String = "reddit_post_1") = UniversalRecord(
        platform = "reddit", recordType = "post", platformId = id, title = "post", content = body, captureTime = time
    )

    private fun comment(time: Long = 10, body: String = "A body", id: String = "reddit_comment_c", root: String = "reddit_post_1") =
        post(time, body, id).copy(recordType = "comment", parentId = root, extraMetadata = "{\"post_id\":\"$root\"}")

    @Test fun partialThenConfirmationThenPartialRetryPreservesOriginalKeysAndNeverArchivesSyntheticValue() {
        val h = Harness()
        val a = comment()
        assertArrayEquals(longArrayOf(), h.process(1, listOf(a), partial = true))
        assertTrue(h.history.rows.isEmpty())
        assertTrue(h.current.rows.isEmpty())
        val pendingA = h.history.waiting.values.single().copy()
        assertEquals(rawSnapshotKey(1, a, 0), pendingA.key)

        val b = comment(time = 20, body = "")
        assertArrayEquals(longArrayOf(2), h.process(2, listOf(b), setOf(a.parentId)))
        assertTrue(h.history.waiting.isEmpty())
        assertEquals(2, h.history.rows.size)
        assertEquals(pendingA.recordJson, h.history.rows.getValue(pendingA.key).recordJson)
        assertEquals(a, h.history.rows.getValue(rawSnapshotKey(1, a, 0)).toRecord())
        assertEquals(b, h.history.rows.getValue(rawSnapshotKey(2, b, 0)).toRecord())
        assertEquals("A body", h.current.rows.getValue(a.platformId).content)
        assertEquals(20L, h.current.rows.getValue(a.platformId).captureTime)
        assertTrue(h.history.rows.values.none { it.legacy })

        val before = h.history.rows.mapValues { it.value.copy() }
        h.process(1, listOf(a.copy(captureTime = 99)), partial = true)
        assertEquals(before, h.history.rows)
        assertTrue(h.history.waiting.isEmpty())
        h.process(3, listOf(a))
        assertEquals(3, h.history.rows.size)
        assertEquals(a, h.history.rows.getValue(rawSnapshotKey(3, a, 0)).toRecord())
    }

    @Test fun confirmationReleasesAllOriginalCapturesForEachAdmittedIdentityButNotOtherPosts() {
        val h = Harness()
        val root = comment(id = "reddit_comment_a")
        val comment = comment()
        val other = comment(id = "reddit_comment_other", root = "reddit_post_other")
        h.process(1, listOf(root, comment, other))
        h.process(2, listOf(root.copy(content = "second"), comment.copy(content = "second comment")))
        h.process(3, emptyList(), setOf(root.parentId))
        assertEquals(4, h.history.rows.size)
        assertEquals(setOf(root.platformId, comment.platformId), h.history.rows.values.map { it.toRecord().platformId }.toSet())
        assertEquals(other.platformId, h.history.waiting.values.single().platformId)
        assertFalse(other.platformId in h.current.rows)
    }

    @Test fun repeatedSameRoleInOneRawKeepsBothOriginalsDespiteReadBufferCollapsingThem() {
        val h = Harness()
        val a = comment()
        h.process(1, listOf(a, a.copy()), setOf(a.parentId))
        assertEquals(setOf(rawSnapshotKey(1, a, 0), rawSnapshotKey(1, a, 1)), h.history.rows.keys)
        h.process(1, listOf(a, a.copy()), setOf(a.parentId))
        assertEquals(2, h.history.rows.size)
    }

    @Test fun oldSyntheticBufferWithoutPendingOriginalsIsExplicitlyLegacy() {
        val h = Harness()
        val old = comment(body = "old merged buffer")
        h.transaction { RedditReadBuffer(h.buffer).accept(listOf(old), emptySet(), 1000) }
        h.process(2, emptyList(), setOf(old.parentId))
        val archived = h.history.rows.values.single()
        assertTrue(archived.legacy)
        assertTrue(archived.key.startsWith("legacy:"))
        assertEquals(old, archived.toRecord())
        assertEquals(old.content, h.current.rows.getValue(old.platformId).content)
    }

    @Test fun allModeStillCapturesImmediatelyWithoutUsingPendingHistory() {
        val h = Harness()
        val a = post()
        h.process(1, listOf(a), readMode = false)
        assertEquals(a, h.history.rows.values.single().toRecord())
        assertTrue(h.history.waiting.isEmpty())
        assertTrue(h.buffer.rows.isEmpty())
        assertEquals(a.content, h.current.rows.getValue(a.platformId).content)
    }

    @Test fun oldBufferReleaseIsLegacyEvenWhenTheIdentityAlreadyHasRealHistory() {
        val h = Harness()
        val a = comment()
        h.process(1, listOf(a), readMode = false)
        val old = comment(time = 20, body = "old synthetic")
        h.transaction { RedditReadBuffer(h.buffer).accept(listOf(old), emptySet(), 1000) }
        h.process(2, emptyList(), setOf(old.parentId))
        assertEquals(2, h.history.rows.size)
        assertEquals(old, h.history.rows.values.single { it.legacy }.toRecord())
        assertEquals(a, h.history.rows.values.single { !it.legacy }.toRecord())
    }

    @Test fun partialRetryDoesNotRefreshCreationTimeAndExpiredOriginalsArePruned() {
        val h = Harness()
        val persistence = RecordPersistence(h.repository)
        val a = comment()
        val b = comment(id = "reddit_comment_2")
        h.transaction {
            persistence.stageRedditHistory(1, listOf(a), now = 100)
            persistence.stageRedditHistory(1, listOf(a.copy(content = "retry")), now = 200)
            persistence.stageRedditHistory(2, listOf(b), now = 200)
        }
        val pending = h.history.waiting.getValue(rawSnapshotKey(1, a, 0))
        assertEquals(100L, pending.createdAt)
        assertEquals("A body", pending.toSnapshot().toRecord().content)
        h.transaction { prunePendingHistory(h.history, 100 + PENDING_HISTORY_TTL_MILLIS) }
        assertEquals(2, h.history.waiting.size)
        h.transaction { prunePendingHistory(h.history, 101 + PENDING_HISTORY_TTL_MILLIS) }
        assertEquals(b.platformId, h.history.waiting.values.single().platformId)
        assertTrue(h.history.rows.isEmpty())
    }

    @Test fun pendingQueueHasDeterministicCountBoundAndEvictsOldestFirst() {
        val h = Harness()
        val persistence = RecordPersistence(h.repository)
        val oldest = comment(id = "reddit_comment_old")
        h.transaction {
            persistence.stageRedditHistory(1, listOf(oldest), now = 100)
            persistence.stageRedditHistory(2, List(MAX_PENDING_HISTORY + 1) { comment(id = "reddit_comment_$it") }, now = 101)
        }
        assertEquals(MAX_PENDING_HISTORY, h.history.waiting.size)
        assertFalse(rawSnapshotKey(1, oldest, 0) in h.history.waiting)
        assertTrue(h.history.rows.isEmpty())
        assertTrue(h.current.rows.isEmpty())
    }

    @Test fun transactionRollbackRestoresPendingOriginalsReadProofAndHistoryForRetry() {
        val h = Harness()
        val a = comment()
        h.process(1, listOf(a), partial = true)
        val pending = h.history.waiting.mapValues { it.value.copy() }
        h.current.failOnPut = true
        assertThrows(IllegalStateException::class.java) { h.process(2, listOf(comment(time = 20, body = "")), setOf(a.parentId)) }
        assertEquals(pending, h.history.waiting)
        assertTrue(h.history.rows.isEmpty())
        assertFalse(h.buffer.rows[a.parentId]?.confirmed == true)
        assertTrue(h.current.rows.isEmpty())
        h.current.failOnPut = false
        assertArrayEquals(longArrayOf(2), h.process(2, listOf(comment(time = 20, body = "")), setOf(a.parentId)))
        assertEquals(2, h.history.rows.size)
        assertTrue(h.history.waiting.isEmpty())
    }

    private fun gql(rawId: Long, time: Long, operation: String, data: JSONObject, variables: JSONObject = JSONObject()) = RawData(
        id = rawId, url = "https://gql-fed.reddit.com/", hostApp = RedditParser.HOST_APP, timestamp = time,
        json = JSONObject().put("request", JSONObject().put("operationName", operation).put("variables", variables))
            .put("response", JSONObject().put("data", data)).toString()
    )

    private fun nativePost() = JSONObject().put("__typename", "SubredditPost").put("id", "t3_1").put("title", "post")
    private fun hydration(id: Long, time: Long, body: String = "Complete **body**", media: Boolean = false): RawData {
        val post = nativePost()
        if (media) post.put("media", JSONObject().put("still", JSONObject()
            .put("source", JSONObject().put("url", "https://preview.redd.it/image.jpg"))))
        else post.put("content", JSONObject().put("markdown", body))
        return gql(id, time, "PostsByIds", JSONObject().put("postsInfoByIds", JSONArray().put(post)),
            JSONObject().put("ids", JSONArray().put("t3_1")))
    }
    private fun comments(id: Long, time: Long, token: String? = "visit", truncate: Int = 0, withComment: Boolean = false): RawData {
        val trees = JSONArray()
        if (withComment) trees.put(JSONObject().put("depth", 0).put("parentId", "t3_1").put("node", JSONObject()
            .put("__typename", "Comment").put("id", "t1_c").put("postId", "t3_1")
            .put("content", JSONObject().put("markdown", "A captured comment"))))
        val post = nativePost().put("commentForest", JSONObject().put("__typename", "CommentForest").put("trees", trees))
        val variables = JSONObject().put("id", "t3_1").put("truncate", truncate).put("includeConsistencyToken", truncate != 0)
        if (token != null) variables.put("pdpConsistencyToken", token)
        return gql(id, time, "PostComments", JSONObject().put("postInfoById", post), variables)
    }
    private fun adaptive(id: Long, time: Long) = gql(id, time, "AdaptivePDP",
        JSONObject().put("postInfoById", nativePost().put("detailComponents", JSONObject())),
        JSONObject().put("postId", "t3_1"))
    private fun preview(id: Long, time: Long): RawData {
        val cells = JSONArray().put(JSONObject().put("__typename", "TitleCell").put("title", "post"))
            .put(JSONObject().put("__typename", "PreviewTextCell").put("text", "short preview"))
        val node = JSONObject().put("__typename", "CellGroup").put("groupId", "t3_1").put("cells", cells)
        val feed = JSONObject().put("elements", JSONObject().put("edges", JSONArray().put(JSONObject().put("node", node))))
        return gql(id, time, "HomeFeedSdui", JSONObject().put("homeV3", feed))
    }
    private fun postSnapshots(h: Harness) = h.history.rows.values.filter { it.toRecord().recordType == "post" }

    @Test fun productionPipelineGroupsHydrationLayoutAndThreeCommentRequestsAcrossRuns() {
        val h = Harness()
        val time = System.currentTimeMillis()
        val sparseComments = listOf(comments(4, time + 300, truncate = 8),
            comments(5, time + 400, withComment = true), comments(6, time + 500, truncate = 8))
        sparseComments.forEach { raw ->
            assertEquals("", RedditParser.parse(raw, raw.url).single { it.recordType == "post" }.content)
        }
        val raws = listOf(preview(1, time), hydration(2, time + 100), adaptive(3, time + 200)) +
            sparseComments + hydration(7, time + 600)
        assertArrayEquals((1L..7L).toList().toLongArray(), h.processRaw(*raws.toTypedArray()))
        val snapshot = postSnapshots(h).single()
        assertTrue(snapshot.key.startsWith("reddit-visit:reddit_post_1:token:"))
        assertTrue(snapshot.key.endsWith(":source:reddit-content:2:reddit_post_1:0"))
        assertFalse(snapshot.legacy)
        assertEquals("Complete **body**", snapshot.toRecord().content)
        assertEquals(time + 300, snapshot.captureTime)
        assertEquals(time + 300, snapshot.toRecord().captureTime)
        assertEquals("Complete **body**", h.current.rows.getValue("reddit_post_1").content)
        assertEquals("A captured comment", h.current.rows.getValue("reddit_comment_c").content)
        assertEquals(1, h.history.rows.values.count { it.toRecord().recordType == "comment" })

        h.processRaw(comments(8, time + 700), hydration(9, time + 800))
        assertEquals(listOf(snapshot), postSnapshots(h))
        h.processRaw(comments(10, time + 1000, token = "reopened"), hydration(11, time + 1100))
        assertEquals(2, postSnapshots(h).size)
        assertEquals(setOf(time + 300, time + 1000), postSnapshots(h).map { it.captureTime }.toSet())
        assertTrue(postSnapshots(h).all { it.toRecord().content == "Complete **body**" && !it.legacy })
    }

    @Test fun productionAuxiliaryResponsesNeverCreateSnapshotsOrSparseFirstIndex() {
        val h = Harness()
        val time = System.currentTimeMillis()
        h.processRaw(preview(1, time), adaptive(2, time + 10))
        assertTrue(h.history.rows.isEmpty())
        assertTrue(h.history.waiting.isEmpty())
        assertTrue(h.current.rows.isEmpty())
        h.processRaw(hydration(3, time + 20), adaptive(4, time + 30), preview(5, time + 40))
        assertTrue(h.history.rows.isEmpty())
        assertTrue(h.current.rows.isEmpty())
        assertTrue(h.history.waiting.values.all { it.key.startsWith("reddit-content:") })
        assertEquals("Complete **body**", h.history.waiting.values.single().toSnapshot().toRecord().content)
    }

    @Test fun productionTokenlessVisitsMergeWithinWindowAcrossRunsButReopenOutsideIt() {
        val h = Harness()
        val time = System.currentTimeMillis()
        h.processRaw(hydration(1, time - 10), comments(2, time, token = null))
        h.processRaw(comments(3, time + 30_000, token = null), hydration(4, time + 30_001))
        assertEquals(1, postSnapshots(h).size)
        assertEquals(time, postSnapshots(h).single().captureTime)
        h.processRaw(comments(5, time + 30_001, token = null))
        assertEquals(2, postSnapshots(h).size)
        assertEquals(setOf(time, time + 30_001), postSnapshots(h).map { it.captureTime }.toSet())
        assertTrue(postSnapshots(h).all { it.toRecord().content == "Complete **body**" })
    }

    @Test fun productionProofWithoutBodyWaitsAcrossRunsAndUsesProofTimeWhenHydrated() {
        val h = Harness()
        val time = System.currentTimeMillis()
        h.processRaw(comments(1, time))
        assertTrue(h.history.rows.isEmpty())
        assertTrue(h.current.rows.isEmpty())
        val pending = h.history.waiting.values.single()
        assertTrue(pending.key.startsWith(REDDIT_VISIT_KEY_PREFIX))
        assertEquals(time, pending.captureTime)
        assertEquals("", pending.recordJson)
        h.processRaw(comments(2, time + 10), hydration(3, time + 1000))
        val snapshot = postSnapshots(h).single()
        assertEquals(time, snapshot.captureTime)
        assertEquals("Complete **body**", snapshot.toRecord().content)
        assertTrue(h.history.waiting.isEmpty())
        h.processRaw(comments(4, time + 2000), hydration(5, time + 2100))
        assertEquals(listOf(snapshot), postSnapshots(h))
    }

    @Test fun productionTokenlessPendingProofMergesBeforeBodyArrives() {
        val h = Harness()
        val time = System.currentTimeMillis()
        h.processRaw(comments(1, time, token = null))
        h.processRaw(comments(2, time + 20_000, token = null))
        assertEquals(1, h.history.waiting.size)
        assertTrue(h.history.rows.isEmpty())
        h.processRaw(hydration(3, time + 21_000))
        assertEquals(time, postSnapshots(h).single().captureTime)
        assertTrue(h.history.waiting.isEmpty())
    }

    @Test fun productionExplicitlyEmptyMarkdownAndMediaAreValidBodiesNotSparseShells() {
        val time = System.currentTimeMillis()
        for (media in listOf(false, true)) {
            val h = Harness()
            h.processRaw(comments(1, time), hydration(2, time + 100, body = "", media = media))
            val snapshot = postSnapshots(h).single()
            val record = snapshot.toRecord()
            assertEquals(time, snapshot.captureTime)
            val meta = JSONObject(record.extraMetadata)
            if (media) {
                assertTrue(meta.getBoolean("media_complete"))
                assertTrue(record.content.contains("https://preview.redd.it/image.jpg"))
            } else {
                assertTrue(meta.getBoolean("body_complete"))
                assertEquals("", record.content)
            }
            assertEquals(record.content, h.current.rows.getValue("reddit_post_1").content)
            assertFalse(snapshot.legacy)
        }
    }

    @Test fun productionFailureAfterAllWritesRestoresSourcesVisitsCommentsIndexAndProofForRetry() {
        val h = Harness()
        val time = System.currentTimeMillis()
        h.processRaw(hydration(1, time))
        val waiting = h.history.waiting.mapValues { it.value.copy() }
        val buffer = h.buffer.rows.mapValues { it.value.copy() }
        val detail = comments(2, time + 100, withComment = true)
        assertThrows(IllegalStateException::class.java) { h.processRaw(detail, failAfterPersist = true) }
        assertEquals(waiting, h.history.waiting)
        assertEquals(buffer, h.buffer.rows)
        assertTrue(h.history.rows.isEmpty())
        assertTrue(h.current.rows.isEmpty())
        assertTrue(h.current.writes.isEmpty())
        assertArrayEquals(longArrayOf(2), h.processRaw(detail))
        assertEquals(1, postSnapshots(h).size)
        assertEquals(time + 100, postSnapshots(h).single().captureTime)
        assertEquals(1, h.history.rows.values.count { it.toRecord().recordType == "comment" })
        assertTrue(h.buffer.rows.getValue("reddit_post_1").confirmed)
        assertTrue(h.history.waiting.isEmpty())
    }

    @Test fun productionPutFailureRollsBackAlreadyAppendedVisitAndCanRetry() {
        val h = Harness()
        val time = System.currentTimeMillis()
        h.processRaw(comments(1, time, withComment = true))
        val waiting = h.history.waiting.mapValues { it.value.copy() }
        val history = h.history.rows.mapValues { it.value.copy() }
        val current = h.current.rows.mapValues { it.value.copy() }
        val buffer = h.buffer.rows.mapValues { it.value.copy() }
        h.current.failOnPut = true
        assertThrows(IllegalStateException::class.java) { h.processRaw(hydration(2, time + 100)) }
        assertEquals(waiting, h.history.waiting)
        assertEquals(history, h.history.rows)
        assertEquals(current, h.current.rows)
        assertEquals(buffer, h.buffer.rows)
        h.current.failOnPut = false
        h.processRaw(hydration(2, time + 100))
        assertEquals(1, postSnapshots(h).size)
        assertEquals(time, postSnapshots(h).single().captureTime)
        assertEquals("Complete **body**", postSnapshots(h).single().toRecord().content)
    }

    @Test fun productionPartialHydrationRetryPreservesOriginalSourceAndOneVisitSnapshot() {
        val h = Harness()
        val time = System.currentTimeMillis()
        val complete = hydration(1, time)
        val envelope = JSONObject(complete.json)
        envelope.getJSONObject("response").put("errors", JSONArray().put(JSONObject().put("message", "bad fragment")))
        val partial = complete.copy(json = envelope.toString())
        assertTrue(RedditParser.parseResult(partial, partial.url) is ParseResult.Partial)
        assertArrayEquals(longArrayOf(), h.processRaw(partial, allowPartial = true))
        val original = h.history.waiting.values.single().copy()
        val pending = original.copy(createdAt = original.createdAt - 1000)
        h.transaction {
            h.history.removePending(original)
            h.history.putPending(pending)
        }
        assertEquals("reddit-content:1:reddit_post_1:0", pending.key)
        assertArrayEquals(longArrayOf(), h.processRaw(partial, allowPartial = true))
        assertEquals(pending, h.history.waiting.values.single())
        assertArrayEquals(longArrayOf(2), h.processRaw(comments(2, time + 100)))
        val snapshot = postSnapshots(h).single().copy()
        assertEquals(time + 100, snapshot.captureTime)
        assertEquals("Complete **body**", snapshot.toRecord().content)
        assertTrue(snapshot.key.endsWith(":source:${pending.key}"))
        assertTrue(h.history.hasRedditPostSource(RecordIdentity.of("reddit", "reddit_post_1"), pending.key))
        assertTrue(h.history.waiting.isEmpty())
        repeat(2) {
            assertArrayEquals(longArrayOf(), h.processRaw(partial, allowPartial = true))
            assertEquals(listOf(snapshot), postSnapshots(h))
            assertTrue("Consumed Partial source must not reappear as a candidate", h.history.waiting.isEmpty())
        }
        assertTrue(postSnapshots(h).none { it.key.startsWith("raw:") || it.legacy })
    }

    @Test fun productionPushStillSnapshotsEveryRawIncludingSparsePostsWithoutVisitGrouping() {
        val h = Harness()
        val time = System.currentTimeMillis()
        val raws = arrayOf(hydration(1, time), comments(2, time + 100), comments(3, time + 200))
        assertArrayEquals(longArrayOf(1, 2, 3), h.processRaw(*raws, readMode = false))
        val snapshots = postSnapshots(h)
        assertEquals(3, snapshots.size)
        assertEquals(listOf("Complete **body**", "", ""), snapshots.map { it.toRecord().content })
        assertEquals(listOf(time, time + 100, time + 200), snapshots.map { it.captureTime })
        assertTrue(snapshots.all { it.key.startsWith("raw:") && !it.legacy })
        assertTrue(h.history.waiting.isEmpty())
        assertTrue(h.buffer.rows.isEmpty())
        assertEquals("Complete **body**", h.current.rows.getValue("reddit_post_1").content)
        h.processRaw(*raws, readMode = false)
        assertEquals(snapshots, postSnapshots(h))
    }

    @Test fun productionFirstVisitFreezesExistingIndexBeforeAppendingNewSnapshot() {
        val h = Harness()
        val time = System.currentTimeMillis()
        val old = post(time - 60_000, "Old indexed body").copy(
            id = 42, title = "Old title", extraMetadata = "{\"body_complete\":true}"
        )
        h.current.rows[old.platformId] = old.copy()
        assertTrue(h.history.rows.isEmpty())
        h.processRaw(hydration(1, time), comments(2, time + 100))
        val snapshots = postSnapshots(h)
        assertEquals(2, snapshots.size)
        val legacy = snapshots.single { it.legacy }
        assertTrue(legacy.key.startsWith("legacy:"))
        assertEquals(old, legacy.toRecord())
        val visit = snapshots.single { !it.legacy }
        assertEquals(time + 100, visit.captureTime)
        assertEquals("Complete **body**", visit.toRecord().content)
        assertTrue(visit.key.endsWith(":source:reddit-content:1:reddit_post_1:0"))
        assertEquals(listOf(legacy, visit), snapshots)
        assertEquals("Complete **body**", h.current.rows.getValue(old.platformId).content)
        assertEquals(old.id, h.current.rows.getValue(old.platformId).id)
        h.processRaw(comments(3, time + 200))
        assertEquals(snapshots, postSnapshots(h))
    }

    @Test fun productionNearestVisitGetsItsBodyBeforeOlderPendingVisit() {
        val h = Harness()
        val time = System.currentTimeMillis()
        h.processRaw(comments(1, time, token = "A"), comments(2, time + 60_000, token = "B"))
        val visits = h.history.waiting.values.associateBy { it.captureTime }
        assertEquals(setOf(time, time + 60_000), visits.keys)
        assertTrue(h.history.rows.isEmpty())
        h.processRaw(hydration(3, time + 61_000, body = "B body"))
        val b = postSnapshots(h).single()
        assertEquals(time + 60_000, b.captureTime)
        assertEquals("B body", b.toRecord().content)
        assertEquals("${visits.getValue(time + 60_000).key}:source:reddit-content:3:reddit_post_1:0", b.key)
        assertEquals(listOf(visits.getValue(time)), h.history.waiting.values.toList())
        assertNull(h.history.findRedditVisit(visits.getValue(time).key))
        assertEquals(b, h.history.findRedditVisit(visits.getValue(time + 60_000).key))

        h.processRaw(hydration(4, time + 1000, body = "A body"))
        assertTrue(h.history.waiting.isEmpty())
        val a = postSnapshots(h).single { it.captureTime == time }
        assertEquals("A body", a.toRecord().content)
        assertTrue(a.key.endsWith(":source:reddit-content:4:reddit_post_1:0"))
        assertEquals(b, postSnapshots(h).single { it.captureTime == time + 60_000 })
    }

    @Test fun matchingOneSourceKeepsOtherUnconsumedSourcesWithOriginalRetentionTime() {
        val h = Harness()
        val now = System.currentTimeMillis()
        val early = post(now, "Earlier source").copy(extraMetadata = "{\"body_complete\":true}")
        val near = post(now + 60_000, "Nearest source").copy(extraMetadata = "{\"body_complete\":true}")
        val persistence = RecordPersistence(h.repository)
        h.transaction {
            persistence.captureRedditPostVisits(1, listOf(early, near), emptyList(), now)
        }
        val retained = h.history.waiting.getValue("reddit-content:1:reddit_post_1:0").copy()
        h.transaction {
            persistence.captureRedditPostVisits(2, emptyList(), listOf(RedditReadVisit(early.platformId, "B", now + 60_000)), now + 100)
        }
        val snapshot = postSnapshots(h).single()
        assertEquals("Nearest source", snapshot.toRecord().content)
        assertTrue(snapshot.key.endsWith(":source:reddit-content:1:reddit_post_1:1"))
        assertEquals(listOf(retained), h.history.waiting.values.toList())
        assertFalse(h.history.hasRedditPostSource(RecordIdentity.of("reddit", early.platformId), retained.key))
    }

    @Test fun productionOldTokenlessPartialRetryFindsEarlierVisitRatherThanOnlyLatest() {
        val h = Harness()
        val time = System.currentTimeMillis()
        h.processRaw(hydration(1, time - 100), comments(2, time, token = null))
        val retry = comments(3, time + 5000, token = null)
        val envelope = JSONObject(retry.json)
        envelope.getJSONObject("response").put("errors", JSONArray().put(JSONObject().put("message", "bad fragment")))
        val partial = retry.copy(json = envelope.toString())
        assertTrue(RedditParser.parseResult(partial, partial.url) is ParseResult.Partial)
        assertArrayEquals(longArrayOf(), h.processRaw(partial, allowPartial = true))
        h.processRaw(hydration(4, time + 60_000), comments(5, time + 60_000, token = null))
        val snapshots = postSnapshots(h).map { it.copy() }
        assertEquals(setOf(time, time + 60_000), snapshots.map { it.captureTime }.toSet())
        val identity = RecordIdentity.of("reddit", "reddit_post_1")
        assertEquals(listOf(snapshots.single { it.captureTime == time }), h.history.redditVisitsNear(identity, time + 5000))
        assertEquals(2, h.history.redditVisitsNear(identity, time + 30_000).size)
        assertTrue(h.history.waiting.isEmpty())
        repeat(2) {
            assertArrayEquals(longArrayOf(), h.processRaw(partial, allowPartial = true))
            assertEquals(snapshots, postSnapshots(h))
            assertTrue(h.history.waiting.isEmpty())
        }
    }

    @Test fun expiredPendingVisitOrSourceIsPrunedBeforeMatchingButExactTtlBoundaryRemains() {
        for (visitFirst in listOf(false, true)) {
            for (expired in listOf(false, true)) {
                val h = Harness()
                val persistence = RecordPersistence(h.repository)
                val createdAt = 1000L
                val record = post(time = createdAt).copy(extraMetadata = "{\"body_complete\":true}")
                val visit = RedditReadVisit(record.platformId, "visit", createdAt)
                h.transaction {
                    persistence.captureRedditPostVisits(1, if (visitFirst) emptyList() else listOf(record),
                        if (visitFirst) listOf(visit) else emptyList(), createdAt)
                }
                val original = h.history.waiting.values.single().copy()
                val now = createdAt + PENDING_HISTORY_TTL_MILLIS + if (expired) 1 else 0
                h.transaction {
                    persistence.captureRedditPostVisits(2, if (visitFirst) listOf(record) else emptyList(),
                        if (visitFirst) emptyList() else listOf(visit), now)
                }
                if (expired) {
                    assertTrue("Expired candidate must not match: visitFirst=$visitFirst", h.history.rows.isEmpty())
                    assertFalse(original.key in h.history.waiting)
                    assertTrue("Both captures are too old to queue: visitFirst=$visitFirst", h.history.waiting.isEmpty())
                } else {
                    val snapshot = postSnapshots(h).single()
                    assertEquals(visit.captureTime, snapshot.captureTime)
                    assertEquals(record.content, snapshot.toRecord().content)
                    assertTrue(h.history.waiting.isEmpty())
                }
            }
        }
    }

    @Test fun postSourceAndVisitRetentionStartsAtEarlierOfProcessingAndCaptureTime() {
        val now = 10_000L
        for (captureTime in listOf(5000L, 15_000L)) {
            for (visitOnly in listOf(false, true)) {
                val h = Harness()
                val record = post(time = captureTime).copy(extraMetadata = "{\"body_complete\":true}")
                h.transaction {
                    RecordPersistence(h.repository).captureRedditPostVisits(1,
                        if (visitOnly) emptyList() else listOf(record),
                        if (visitOnly) listOf(RedditReadVisit(record.platformId, "visit", captureTime)) else emptyList(), now)
                }
                val pending = h.history.waiting.values.single()
                assertEquals(captureTime, pending.captureTime)
                assertEquals(minOf(now, captureTime), pending.createdAt)
                assertEquals(visitOnly, pending.key.startsWith(REDDIT_VISIT_KEY_PREFIX))
            }
        }
    }

    private fun expiredPartialCannotRevivePendingBeforeCounterpartArrives(visitFirst: Boolean) {
        val h = Harness()
        // Stage at the original processing time, then retry through production's real clock.
        val capturedAt = System.currentTimeMillis() - PENDING_HISTORY_TTL_MILLIS - 60_000
        val original = if (visitFirst) comments(1, capturedAt) else hydration(1, capturedAt)
        val envelope = JSONObject(original.json)
        envelope.getJSONObject("response").put("errors", JSONArray().put(JSONObject().put("message", "retained fragment")))
        val partial = original.copy(json = envelope.toString())
        val result = RedditParser.parseResult(partial, partial.url)
        assertTrue(result is ParseResult.Partial)
        h.transaction {
            RecordPersistence(h.repository).captureRedditPostVisits(partial.id, result.records,
                RedditParser.readVisits(partial), capturedAt + 1000)
        }
        val pending = h.history.waiting.values.single().copy()
        assertEquals(capturedAt, pending.createdAt)
        assertEquals(visitFirst, pending.key.startsWith(REDDIT_VISIT_KEY_PREFIX))
        repeat(2) {
            assertArrayEquals(longArrayOf(), h.processRaw(partial, allowPartial = true))
            assertTrue("Expired Partial must not refresh its retention deadline", h.history.waiting.isEmpty())
            assertTrue(h.history.rows.isEmpty())
        }
        // Its delayed counterpart is temporally close enough to match, but also past the hard capture-age limit.
        val counterpart = if (visitFirst) hydration(2, capturedAt + 100) else comments(2, capturedAt + 100)
        assertArrayEquals(longArrayOf(2), h.processRaw(counterpart))
        assertTrue("Late counterpart must not resurrect expired captures", h.history.waiting.isEmpty())
        assertTrue(h.history.rows.isEmpty())
    }

    @Test fun expiredPartialPostSourceRetryCannotRevivePendingOrMatchDelayedVisit() {
        expiredPartialCannotRevivePendingBeforeCounterpartArrives(visitFirst = false)
    }

    @Test fun expiredPartialVisitRetryCannotRevivePendingOrMatchDelayedPostSource() {
        expiredPartialCannotRevivePendingBeforeCounterpartArrives(visitFirst = true)
    }

    @Test fun oldPostBufferWithoutOriginalSourceUpdatesOnlyIndexAndNeverCreatesLegacyHistory() {
        val h = Harness()
        val old = post().copy(extraMetadata = "{\"body_complete\":true}")
        h.transaction { RedditReadBuffer(h.buffer).accept(listOf(old), emptySet(), System.currentTimeMillis()) }
        h.process(2, emptyList(), setOf(old.platformId))
        assertTrue(h.history.rows.isEmpty())
        assertTrue(h.history.waiting.isEmpty())
        assertEquals(old.content, h.current.rows.getValue(old.platformId).content)
    }
}
