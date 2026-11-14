package me.melon.record.logic

import me.melon.record.data.model.RawData
import me.melon.record.data.model.RecordIdentity
import me.melon.record.data.model.RecordSnapshot
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toRecord
import me.melon.record.parser.ParseResult
import me.melon.record.parser.ParseDiagnostic
import org.junit.Assert.*
import org.junit.Test

class CaptureHistoryTest {
    private class History : RecordSnapshotStorage {
        val rows = linkedMapOf<String, RecordSnapshot>()
        override fun contains(key: String) = key in rows
        override fun hasIdentity(identity: RecordIdentity) = rows.values.any {
            it.platform == identity.platform && it.identityId == identity.contentId
        }
        override fun append(snapshot: RecordSnapshot) {
            check(snapshot.key !in rows) { "history must be append-only" }
            rows[snapshot.key] = snapshot.copy()
        }
    }
    private class Repository(val current: MemoryRecordRepository = MemoryRecordRepository()) : RecordRepository by current {
        override val history = History()
        override fun put(record: UniversalRecord) {
            archiveLegacyIndex(record, this, history)
            current.put(record)
        }
    }
    private object Policy : CapturePolicy {
        override fun canHandle(raw: RawData, cleanUrl: String) = true
        override fun parse(raw: RawData, cleanUrl: String): ParsedCapture = error("unused")
    }
    private fun record(id: String = "p", time: Long = 10) = UniversalRecord(
        platform = "test", platformId = id, content = "parsed", captureTime = time
    )
    private fun processing() = CaptureProcessing(listOf(Policy))

    @Test fun capturesFreezeBeforeCompletionMergeAndAfterPutWhileIndexRemainsMutable() {
        val repository = Repository()
        repository.current.put(record().copy(title = "old title", content = "old body", captureTime = 90))
        val platform = object : PlatformPersistence {
            override fun complete(record: UniversalRecord, repository: RecordRepository) { record.authorName = "completed" }
            override fun afterPut(record: UniversalRecord, repository: RecordRepository) {
                record.parentId = "linked"
                repository.put(record)
            }
        }
        val persistence = RecordPersistence(repository, platforms = mapOf("test" to platform))
        val rawRecord = record().copy(content = "")
        val capture = ParsedCapture(1, listOf(rawRecord), Policy)
        val processing = processing()
        val completed = processing.complete(capture, persistence)
        assertTrue(repository.history.rows.isEmpty())
        assertArrayEquals(longArrayOf(1), processing.persist(listOf(completed), persistence))
        val history = repository.history.rows.values
        assertEquals(2, history.size)
        val event = history.single { !it.legacy }.toRecord()
        assertEquals("", event.content)
        assertEquals("", event.authorName)
        assertEquals("", event.parentId)
        assertEquals(10L, event.captureTime)
        assertEquals("old body", history.single { it.legacy }.toRecord().content)
        val current = repository.find("p")!!
        assertEquals("old body", current.content)
        assertEquals("old title", current.title)
        assertEquals("completed", current.authorName)
        assertEquals("linked", current.parentId)
        assertEquals(90L, current.captureTime)
    }

    @Test fun partialRetryIsIdempotentAcrossSessionsAndRecordReorderingButAnotherRawIsAnEvent() {
        val repository = Repository()
        fun run(rawId: Long, records: List<UniversalRecord>): LongArray {
            val persistence = RecordPersistence(repository)
            val capture = ParsedCapture(rawId, records, Policy,
                result = ParseResult.Partial(records, listOf(ParseDiagnostic("test", "one fragment failed"))))
            val processing = processing()
            return processing.persist(listOf(processing.complete(capture, persistence)), persistence)
        }
        assertArrayEquals(longArrayOf(), run(1, listOf(record("a"), record("b"))))
        val first = repository.history.rows.values.map { it.recordJson }
        assertArrayEquals(longArrayOf(), run(1, listOf(record("b", 99), record("a", 99))))
        assertEquals(first, repository.history.rows.values.map { it.recordJson })
        run(2, listOf(record("a"), record("b")))
        assertEquals(4, repository.history.rows.size)
        assertEquals(2, repository.current.rows.size)
    }

    @Test fun repeatedSameRoleInsideOneRawIsNotContentDeduplicated() {
        val repository = Repository()
        val persistence = RecordPersistence(repository)
        val processing = processing()
        val capture = processing.complete(ParsedCapture(1, listOf(record(), record()), Policy), persistence)
        processing.persist(listOf(capture), persistence)
        processing.persist(listOf(capture), persistence)
        assertEquals(2, repository.history.rows.size)
        assertTrue(repository.history.rows.values.none { it.legacy })
    }

    @Test fun firstXCaptureArchivesBothLegacyRolesAndLaterRepairDoesNotRewriteSnapshots() {
        val repository = Repository()
        val tweet = record("twitter_tweet_42").copy(platform = "twitter")
        val reply = tweet.copy(platformId = "twitter_comment_42", content = "old reply")
        repository.current.put(tweet)
        repository.current.put(reply)
        val persistence = RecordPersistence(repository)
        persistence.persist(tweet.copy(id = 0, content = "new"))
        assertEquals(2, repository.history.rows.values.count { it.legacy })
        val before = repository.history.rows.values.map { it.recordJson }
        repository.put(reply.copy(content = "repair"))
        assertEquals(before, repository.history.rows.values.map { it.recordJson })
    }

    @Test fun afterPutRepairFreezesAnOtherwiseUncapturedLegacyRow() {
        val repository = Repository()
        repository.current.put(record("legacy").copy(content = "before repair"))
        repository.put(repository.find("legacy")!!.copy(content = "after repair"))
        val backup = repository.history.rows.values.single()
        assertTrue(backup.legacy)
        assertEquals("before repair", backup.toRecord().content)
    }

    @Test fun rejectedRecordsAndFailedCapturesDoNotCreateHistory() {
        val repository = Repository()
        val persistence = RecordPersistence(repository)
        val reject = object : CapturePolicy by Policy {
            override fun admit(capture: ParsedCapture): List<UniversalRecord> = emptyList()
        }
        val processing = processing()
        val capture = processing.complete(ParsedCapture(1, listOf(record()), reject), persistence)
        assertArrayEquals(longArrayOf(1), processing.persist(listOf(capture), persistence))
        assertTrue(repository.history.rows.isEmpty())
        val failed = processing.complete(ParsedCapture(2, emptyList(), Policy,
            result = ParseResult.failure("test", IllegalArgumentException("malformed"))), persistence)
        assertArrayEquals(longArrayOf(), processing.persist(listOf(failed), persistence))
        assertTrue(repository.history.rows.isEmpty())
    }

    @Test fun admissionEnrichmentDoesNotReplaceTheOriginalParsedPayload() {
        val repository = Repository()
        val persistence = RecordPersistence(repository)
        val enriching = object : CapturePolicy by Policy {
            override fun admit(capture: ParsedCapture) = capture.records.map { it.copy(content = "staged merge") }
        }
        val processing = processing()
        val capture = processing.complete(ParsedCapture(1, listOf(record()), enriching), persistence)
        processing.persist(listOf(capture), persistence)
        assertEquals("parsed", repository.history.rows.values.single().toRecord().content)
        assertEquals("staged merge", repository.find("p")!!.content)
    }
}
