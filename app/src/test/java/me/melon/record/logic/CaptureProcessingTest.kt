package me.melon.record.logic

import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.data.model.RedditPendingRecord
import me.melon.record.data.model.UniversalRecord
import me.melon.record.parser.douban.DoubanSubjectParser
import me.melon.record.parser.douban.DoubanTitleParseException
import me.melon.record.parser.reddit.RedditParser
import me.melon.record.parser.reddit.RedditReadBuffer
import org.junit.Assert.*
import org.junit.Test

class CaptureProcessingTest {
    private class MemoryPendingStorage : RedditReadBuffer.Storage {
        val rows = linkedMapOf<String, RedditPendingRecord>()
        var inTransaction = false
        var fail = false
        override fun find(platformId: String) = rows[platformId]?.copy()
        override fun forPost(postId: String) = rows.values.filter { it.postId == postId }.map { it.copy() }
        override fun put(row: RedditPendingRecord) {
            check(inTransaction) { "staging outside transaction" }
            check(!fail) { "injected staging failure" }
            rows[row.platformId] = row.copy()
        }
        override fun remove(row: RedditPendingRecord) { check(inTransaction); rows.remove(row.platformId) }
        override fun pending() = rows.values.map { it.copy() }
    }
    private fun reddit(json: String, id: Long = 1) = RawData(
        id = id, url = "https://gql-fed.reddit.com/", hostApp = RedditParser.HOST_APP, json = json, timestamp = 100
    )
    private fun fixture(name: String) = javaClass.getResource("/reddit/$name.json")!!.readText()
    private fun rootDetail(id: String = "organic") = reddit(
        """{"data":{"postInfoById":{"id":"t3_$id","title":"detail","commentForest":{"trees":[]}}}}"""
    )
    private fun capture(records: List<UniversalRecord>, id: Long = 1, policy: CapturePolicy = PassThroughPolicy) =
        ParsedCapture(id, records, policy)
    private object PassThroughPolicy : CapturePolicy {
        override fun canHandle(raw: RawData, cleanUrl: String) = true
        override fun parse(raw: RawData, cleanUrl: String) = ParsedCapture(raw.id, emptyList(), this)
    }

    @Test fun advertisingPreflightSeesLaterCapturesBeforeSparseDetailParsing() {
        val sparse = rootDetail("advert")
        val ad = reddit("""{"data":{"postsInfoByIds":[{"id":"t3_advert","isSponsored":true,"title":"advert"}]}}""", 2)
        val policy = RedditCapturePolicy(null)
        val processing = CaptureProcessing(listOf(policy)) { consume -> listOf(sparse, ad).forEach(consume) }
        assertEquals(1, RedditParser.parse(sparse, sparse.url).size)
        processing.prepare()
        assertTrue(processing.parse(sparse) { throw it }.records!!.isEmpty())
        assertTrue(processing.parse(ad) { throw it }.records!!.isEmpty())
    }

    @Test fun preflightContextDoesNotLeakAcrossSessions() {
        val ad = reddit("""{"data":{"postsInfoByIds":[{"id":"t3_advert","isSponsored":true}]}}""")
        val first = CaptureProcessing(listOf(RedditCapturePolicy(null))) { it(ad) }
        first.prepare()
        assertTrue(first.parse(rootDetail("advert")) { throw it }.records!!.isEmpty())
        val second = CaptureProcessing(listOf(RedditCapturePolicy(null)))
        second.prepare()
        assertEquals(1, second.parse(rootDetail("advert")) { throw it }.records!!.size)
    }

    @Test fun readParsingIsSideEffectFreeAndAdmissionRunsWithinCallerTransaction() {
        val storage = MemoryPendingStorage()
        val policy = RedditCapturePolicy(RedditReadBuffer(storage)) { 200 }
        val processing = CaptureProcessing(listOf(policy))
        val repository = MemoryRecordRepository()
        val persistence = RecordPersistence(repository)
        val posts = processing.parse(reddit(fixture("posts")), persistence) { throw it }
        assertTrue(storage.rows.isEmpty())
        storage.inTransaction = true
        assertArrayEquals(longArrayOf(1), processing.persist(listOf(posts), persistence))
        assertTrue(repository.rows.isEmpty())
        assertTrue(storage.rows.isNotEmpty())
        storage.inTransaction = false
        val thread = processing.parse(reddit(fixture("thread"), 2), persistence) { throw it }
        assertTrue(repository.rows.isEmpty())
        storage.inTransaction = true
        assertArrayEquals(longArrayOf(2), processing.persist(listOf(thread), persistence))
        assertEquals(11, repository.rows.size)
        assertTrue(repository.find("reddit_post_1xr0ctd")!!.content.contains("preview.redd.it"))
        assertTrue(storage.rows.getValue("reddit_post_1xr0ctd").confirmed)
        assertEquals("", storage.rows.getValue("reddit_post_1xr0ctd").recordJson)
    }

    @Test fun redditParseFailurePropagatesWhileOtherParseFailureConsumesRaw() {
        val error = IllegalArgumentException("injected parser failure")
        fun failing(retain: Boolean) = object : CapturePolicy {
            override val retainOnParseFailure = retain
            override fun canHandle(raw: RawData, cleanUrl: String) = true
            override fun parse(raw: RawData, cleanUrl: String): ParsedCapture = throw error
        }
        var logged: Exception? = null
        val consumed = CaptureProcessing(listOf(failing(false))).parse(RawData(id = 7)) { logged = it }
        assertSame(error, logged)
        assertTrue(consumed.records!!.isEmpty())
        assertEquals(7L, consumed.rawId)
        assertTrue(RedditCapturePolicy(null).retainOnParseFailure)
        try {
            CaptureProcessing(listOf(failing(true))).parse(RawData(id = 8)) { logged = it }
            fail("retryable parse failure must abort the chunk")
        } catch (e: IllegalArgumentException) { assertSame(error, e) }
    }

    @Test fun completedDoubanCaptureKeepsTitleWhenLaterRedditParseAbortsChunkAndNextRunReusesIt() {
        val context = RecordPersistenceContext()
        val repository = MemoryRecordRepository()
        val persistence = RecordPersistence(repository, context)
        val error = IllegalStateException("later Reddit parse failed")
        val failingReddit = object : CapturePolicy by RedditCapturePolicy(null) {
            override fun parse(raw: RawData, cleanUrl: String): ParsedCapture = throw error
        }
        val processing = CaptureProcessing(listOf(DoubanCapturePolicy, failingReddit))
        val subject = RawData(id = 1, url = "https://frodo.douban.com/api/v2/movie/42",
            json = """{"id":"42","title":"已解析标题","subtype":"movie"}""")
        val rawIds = mutableListOf(1L, 2L)
        var logged: Exception? = null
        try {
            val captures = listOf(subject, rootDetail().copy(id = 2)).map {
                processing.parse(it, persistence) { e -> logged = e }
            }
            rawIds.removeAll(processing.persist(captures, persistence).toSet())
            fail("Reddit parse failure must abort before chunk writes/deletion")
        } catch (e: IllegalStateException) { assertSame(error, e) }
        assertSame(error, logged)
        assertEquals("已解析标题", context.doubanTitle("42"))
        assertTrue(repository.rows.isEmpty())
        assertEquals(listOf(1L, 2L), rawIds)

        val nextRun = RecordPersistence(repository, context)
        val interest = RawData(id = 3, url = "https://frodo.douban.com/api/v2/movie/42/interests",
            json = """{"interests":[{"id":"i","comment":"正文"}]}""")
        val parsed = processing.parse(interest, nextRun) { throw it }
        assertEquals("电影短评：已解析标题", parsed.records!!.single().title)
        assertArrayEquals(longArrayOf(3), processing.persist(listOf(parsed), nextRun))
        assertEquals("电影短评：已解析标题", repository.find("douban_interest_i")!!.title)
    }

    @Test fun completionRunsOncePerCaptureBeforeNextParseAndNeverDuringWrites() {
        val repository = MemoryRecordRepository()
        var completions = 0
        val completionPolicy = object : PlatformPersistence {
            override fun complete(record: UniversalRecord, repository: RecordRepository) { completions++ }
        }
        val persistence = RecordPersistence(repository, platforms = mapOf("custom" to completionPolicy))
        val policy = object : CapturePolicy {
            override fun canHandle(raw: RawData, cleanUrl: String) = true
            override fun parse(raw: RawData, cleanUrl: String): ParsedCapture {
                assertEquals(raw.id.toInt() - 1, completions)
                assertTrue(repository.rows.isEmpty())
                return ParsedCapture(raw.id, listOf(UniversalRecord(platform = "custom", platformId = "r${raw.id}")), this)
            }
        }
        val processing = CaptureProcessing(listOf(policy))
        val captures = listOf(1L, 2L).map { processing.parse(RawData(id = it), persistence) { throw it } }
        assertEquals(2, completions)
        processing.persist(captures, persistence)
        assertEquals(2, completions)
        assertEquals(listOf("r1", "r2"), repository.writes)
        try {
            processing.complete(captures.first(), persistence)
            fail("completed captures must not be completed a second time")
        } catch (_: IllegalStateException) {}
    }

    @Test fun persistRejectsUncompletedCapturesBeforeAnyAdmissionOrWrite() {
        val repository = MemoryRecordRepository()
        val processing = CaptureProcessing(listOf(PassThroughPolicy))
        val uncompleted = capture(listOf(UniversalRecord(platformId = "not-written")))
        try {
            processing.persist(listOf(uncompleted), RecordPersistence(repository))
            fail("a persistence test must not bypass the production completion phase")
        } catch (_: IllegalStateException) {}
        assertTrue(repository.writes.isEmpty())
    }

    @Test fun realDoubanCapturesPreserveTitleMetadataTimingWithoutRepeatingCompletionInPersist() {
        val subject = RawData(id = 1, url = "https://frodo.douban.com/api/v2/movie/42",
            json = """{"id":"42","title":"捕获标题","subtype":"movie"}""")
        val interest = RawData(id = 2, url = "https://frodo.douban.com/api/v2/movie/42/interests",
            json = """{"interests":[{"id":"i"}]}""")
        for (subjectFirst in listOf(true, false)) {
            val repository = MemoryRecordRepository()
            val persistence = RecordPersistence(repository)
            val processing = CaptureProcessing(listOf(DoubanCapturePolicy))
            val raws = if (subjectFirst) listOf(subject, interest) else listOf(interest, subject)
            val captures = raws.map { processing.parse(it, persistence) { throw it } }
            assertTrue(repository.rows.isEmpty())
            val parsedInterest = captures.flatMap { it.records.orEmpty() }.single { it.recordType == "interest" }
            assertEquals(subjectFirst, parsedInterest.extraMetadata.contains("subject_title"))
            processing.persist(captures, persistence)
            val stored = repository.find("douban_interest_i")!!
            assertEquals("电影短评：捕获标题", stored.title)
            assertEquals(subjectFirst, stored.extraMetadata.contains("subject_title"))
        }
    }

    @Test fun subjectInternalFailureRetainsEvidenceOnlyAfterLegacyRegistrationBoundary() {
        for ((field, crossedBoundary) in listOf("original_title" to true, "subtype" to false)) {
            val context = RecordPersistenceContext()
            val repository = MemoryRecordRepository()
            val persistence = RecordPersistence(repository, context)
            val failure = IllegalStateException("injected field conversion failure")
            val subject = JsonParser.parseString("""{"id":"42","title":"失败前已提取标题","subtype":"movie"}""").asJsonObject
            // Fault injection into the pure object parser, after JSON decoding. This exercises
            // the real subject parser's catch boundary, not a fabricated completion exception.
            subject.addProperty(field, object : Number() {
                override fun toByte(): Byte = 0
                override fun toShort(): Short = 0
                override fun toInt(): Int = 0
                override fun toLong(): Long = 0
                override fun toFloat(): Float = 0f
                override fun toDouble(): Double = 0.0
                override fun toString(): String = throw failure
            })
            val policy = object : CapturePolicy {
                override fun canHandle(raw: RawData, cleanUrl: String) = true
                override fun parse(raw: RawData, cleanUrl: String) = ParsedCapture(raw.id,
                    listOfNotNull(DoubanSubjectParser.parseSubjectFromObject(subject, raw, cleanUrl)), this)
            }
            val processing = CaptureProcessing(listOf(policy))
            var logged: Exception? = null
            val parsed = processing.parse(RawData(id = 1), persistence) { logged = it }
            assertTrue(parsed.records!!.isEmpty())
            if (crossedBoundary) {
                assertTrue(logged is DoubanTitleParseException)
                assertSame(failure, logged!!.cause)
                assertEquals("失败前已提取标题", context.doubanTitle("42"))
            } else {
                assertSame(failure, logged)
                assertEquals("", context.doubanTitle("42"))
            }
            assertArrayEquals(longArrayOf(1), processing.persist(listOf(parsed), persistence))
            assertTrue(repository.rows.isEmpty())
        }
    }

    @Test fun emptyInterestLookupWarmsContextBeforeLaterFailureButInvalidArrayDoesNot() {
        for ((array, lookupReached) in listOf("[]" to true, "{}" to false)) {
            val context = RecordPersistenceContext()
            val repository = MemoryRecordRepository()
            repository.put(UniversalRecord(platform = "douban", recordType = "subject",
                platformId = "douban_movie_42", title = "数据库标题"))
            val persistence = RecordPersistence(repository, context)
            val failure = IllegalStateException("later Reddit parse failed")
            val failingReddit = object : CapturePolicy by RedditCapturePolicy(null) {
                override fun parse(raw: RawData, cleanUrl: String): ParsedCapture = throw failure
            }
            val processing = CaptureProcessing(listOf(DoubanCapturePolicy, failingReddit))
            val raw = RawData(id = 1, url = "https://frodo.douban.com/api/v2/movie/42/interests",
                json = """{"interests":$array}""")
            val empty = processing.parse(raw, persistence) {}
            assertTrue(empty.records!!.isEmpty())
            try {
                processing.parse(rootDetail().copy(id = 2), persistence) {}
                fail("Reddit parse failure must abort")
            } catch (e: IllegalStateException) { assertSame(failure, e) }
            assertEquals(if (lookupReached) "数据库标题" else "", context.doubanTitle("42"))
            assertEquals(listOf("douban_movie_42"), repository.writes)
        }
    }

    @Test fun stagingFailurePropagatesWithoutReturningRawIdsForDeletion() {
        val storage = MemoryPendingStorage().apply { inTransaction = true; fail = true }
        val policy = RedditCapturePolicy(RedditReadBuffer(storage)) { 200 }
        val processing = CaptureProcessing(listOf(policy))
        val persistence = RecordPersistence(MemoryRecordRepository())
        val parsed = processing.parse(rootDetail(), persistence) { throw it }
        val rawIds = mutableListOf(1L)
        try {
            val completed = processing.persist(listOf(parsed), persistence)
            rawIds.removeAll(completed.toSet())
            fail("staging failure must abort the raw-delete transaction")
        } catch (_: IllegalStateException) {}
        assertEquals(listOf(1L), rawIds)
        assertTrue(storage.rows.isEmpty())
    }

    @Test fun writeFailureRollsBackReadProofPayloadAndRawDeletionAtTransactionBoundary() {
        val storage = MemoryPendingStorage().apply { inTransaction = true }
        val policy = RedditCapturePolicy(RedditReadBuffer(storage)) { 200 }
        val processing = CaptureProcessing(listOf(policy))
        val repository = MemoryRecordRepository()
        val persistence = RecordPersistence(repository)
        processing.persist(listOf(processing.parse(reddit(fixture("posts")), persistence) { throw it }), persistence)
        val pendingBefore = storage.rows.mapValues { it.value.copy() }
        val parsed = processing.parse(reddit(fixture("thread"), 2), persistence) { throw it }
        val rawIds = mutableListOf(2L)
        repository.failOnPut = true
        // In-memory transaction fixture snapshots BOTH stores, just as the production runInTx does.
        try {
            val ids = processing.persist(listOf(parsed), persistence)
            rawIds.removeAll(ids.toSet())
            fail("record write failure must propagate")
        } catch (_: IllegalStateException) {
            storage.rows.clear()
            storage.rows.putAll(pendingBefore)
        }
        assertEquals(listOf(2L), rawIds)
        assertFalse(storage.rows.getValue("reddit_post_1xr0ctd").confirmed)
        assertTrue(storage.rows.getValue("reddit_post_1xr0ctd").recordJson.isNotEmpty())
        repository.failOnPut = false
        assertArrayEquals(longArrayOf(2), processing.persist(listOf(parsed), persistence))
        assertEquals(11, repository.rows.size)
    }

    @Test fun rawAndRecordMergeOrderIsNeverSortedOrDeduplicated() {
        val repository = MemoryRecordRepository()
        val processing = CaptureProcessing(listOf(PassThroughPolicy))
        fun record(id: String, title: String) = UniversalRecord(platform = "unknown", platformId = id, title = title)
        val captures = listOf(capture(listOf(record("b", "first"), record("a", "second")), 5),
            capture(listOf(record("b", "last")), 3))
        val persistence = RecordPersistence(repository)
        val completed = captures.map { processing.complete(it, persistence) }
        assertArrayEquals(longArrayOf(5, 3), processing.persist(completed, persistence))
        assertEquals(listOf("b", "a", "b"), repository.writes)
        assertEquals("last", repository.find("b")!!.title)
    }

    @Test fun questionWrittenInSameChunkDoesNotBecomeParseTimeBindingProof() {
        val repository = MemoryRecordRepository()
        val processing = CaptureProcessing(listOf(PassThroughPolicy))
        val question = UniversalRecord(platform = "zhihu", recordType = "question", platformId = "zhihu_question_q",
            extraMetadata = "{}")
        val answer = UniversalRecord(platform = "zhihu", recordType = "answer", platformId = "zhihu_answer_a",
            extraMetadata = """{"question_id":"q"}""")
        val persistence = RecordPersistence(repository)
        val completed = processing.complete(capture(listOf(question, answer)), persistence)
        processing.persist(listOf(completed), persistence)
        assertEquals("", repository.find(answer.platformId)!!.parentId)
        assertNotNull(repository.find(question.platformId))
    }

    @Test fun doubanChunkCompletionPreservesSubjectBeforeVersusAfterInterestMetadataTiming() {
        fun subject() = UniversalRecord(platform = "douban", recordType = "subject", platformId = "douban_movie_42", title = "标题",
            extraMetadata = """{"subject_id":"42","subject_type":"movie","subject_title":"标题"}""")
        fun interest() = UniversalRecord(platform = "douban", recordType = "interest", platformId = "douban_interest_i",
            title = "电影短评：42", parentId = "douban_movie_42", extraMetadata = """{"subject_id":"42","subject_type":"movie"}""")
        val processing = CaptureProcessing(listOf(PassThroughPolicy))
        for (subjectFirst in listOf(true, false)) {
            val repository = MemoryRecordRepository()
            val records = if (subjectFirst) listOf(subject(), interest()) else listOf(interest(), subject())
            val persistence = RecordPersistence(repository)
            val completed = processing.complete(capture(records), persistence)
            processing.persist(listOf(completed), persistence)
            val result = repository.find("douban_interest_i")!!
            assertEquals("电影短评：标题", result.title)
            assertEquals(subjectFirst, result.extraMetadata.contains("subject_title"))
        }
    }
}
