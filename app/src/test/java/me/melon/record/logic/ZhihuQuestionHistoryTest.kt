package me.melon.record.logic

import me.melon.record.data.model.RawData
import me.melon.record.data.model.RecordIdentity
import me.melon.record.data.model.RecordSnapshot
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.identity
import me.melon.record.data.model.recordSnapshot
import me.melon.record.data.model.toRecord
import me.melon.record.data.repository.assembleAllHistoricalRecords
import me.melon.record.data.repository.assembleRecordHistory
import me.melon.record.parser.zhihu.ZhihuQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhihuQuestionHistoryTest {

    private class TestHistory : RecordSnapshotStorage {
        val rows = linkedMapOf<String, RecordSnapshot>()
        override fun contains(key: String): Boolean = key in rows
        override fun hasIdentity(identity: RecordIdentity): Boolean = rows.values.any {
            it.platform == identity.platform && it.identityId == identity.contentId
        }
        override fun append(snapshot: RecordSnapshot) {
            check(snapshot.key !in rows) { "history must be append-only: ${snapshot.key}" }
            rows[snapshot.key] = snapshot.copy()
        }
    }

    private class TestRepository(val current: MemoryRecordRepository = MemoryRecordRepository()) :
        RecordRepository by current {
        override val history = TestHistory()
        override fun put(record: UniversalRecord) {
            archiveLegacyIndex(record, this, history)
            current.put(record)
        }
    }

    private val questionDetailJson = """
    {
        "id": "618885757",
        "type": "question",
        "title": "禁止早恋的副作用是什么？",
        "detail": "<p>问题描述正文</p>",
        "author": {
            "id": "author_1",
            "name": "提问者",
            "avatar_url": "https://pic.zhimg.com/avatar.jpg"
        },
        "created": 1600000000,
        "updated_time": 1600001000
    }
    """.trimIndent()

    private val questionFeedsJson = """
    {
        "data": [
            {
                "type": "question_feed_card",
                "target_type": "answer",
                "target": {
                    "id": "1917896120379610729",
                    "type": "answer",
                    "excerpt": "回答摘要内容",
                    "question": {
                        "id": "618885757",
                        "title": "禁止早恋的副作用是什么？"
                    },
                    "author": {
                        "id": "ans_author_1",
                        "name": "回答者王芷汵",
                        "avatar_url": "https://pic.zhimg.com/ans_avatar.jpg"
                    }
                }
            }
        ]
    }
    """.trimIndent()

    @Test
    fun auxiliaryFeedsDoesNotSnapshotAndDetailProducesSingleQuestionSnapshot() {
        val repository = TestRepository()
        val persistence = RecordPersistence(repository)

        val detailRaw = RawData(
            id = 10,
            url = "https://api.zhihu.com/questions/618885757?include=detail",
            json = questionDetailJson,
            hostApp = "com.zhihu.android",
            timestamp = 1000L
        )
        val feedsRaw = RawData(
            id = 11,
            url = "https://api.zhihu.com/questions/618885757/feeds?include=big_card_summary",
            json = questionFeedsJson,
            hostApp = "com.zhihu.android",
            timestamp = 2000L
        )

        val detailRecords = ZhihuQuestionParser.parse(detailRaw, detailRaw.url.substringBefore("?"))
        val feedsRecords = ZhihuQuestionParser.parse(feedsRaw, feedsRaw.url.substringBefore("?"))

        assertEquals(1, detailRecords.size)
        assertEquals(1, feedsRecords.size)

        assertFalse(ZhihuQuestionParser.isAuxiliaryQuestion(detailRecords[0]))
        assertTrue(ZhihuQuestionParser.isAuxiliaryQuestion(feedsRecords[0]))

        // Persist detail then feeds
        persistence.persistCompleted(detailRecords[0], detailRecords[0], "raw:10:detail")
        persistence.persistCompleted(feedsRecords[0], feedsRecords[0], "raw:11:feeds")

        // Only detail record should be in history
        assertEquals(1, repository.history.rows.size)
        val snapshot = repository.history.rows.values.single().toRecord()
        assertEquals("raw:10:detail", repository.history.rows.keys.single())
        assertEquals("禁止早恋的副作用是什么？", snapshot.title)
        assertEquals("问题描述正文", snapshot.content)
        assertEquals("提问者", snapshot.authorName)

        // Index should contain merged data (content from detail, answer_refs from feeds)
        val index = repository.find("zhihu_question_618885757")
        assertNotNull(index)
        assertEquals("问题描述正文", index!!.content)
        assertTrue(index.extraMetadata.contains("1917896120379610729"))
    }

    @Test
    fun feedsArrivingBeforeDetailDoesNotProduceSpuriousLegacySnapshot() {
        val repository = TestRepository()
        val persistence = RecordPersistence(repository)

        val detailRaw = RawData(
            id = 20,
            url = "https://api.zhihu.com/questions/618885757?include=detail",
            json = questionDetailJson,
            hostApp = "com.zhihu.android",
            timestamp = 1000L
        )
        val feedsRaw = RawData(
            id = 21,
            url = "https://api.zhihu.com/questions/618885757/feeds?include=big_card_summary",
            json = questionFeedsJson,
            hostApp = "com.zhihu.android",
            timestamp = 2000L
        )

        val detailRecords = ZhihuQuestionParser.parse(detailRaw, detailRaw.url.substringBefore("?"))
        val feedsRecords = ZhihuQuestionParser.parse(feedsRaw, feedsRaw.url.substringBefore("?"))

        // Persist feeds FIRST, then detail
        persistence.persistCompleted(feedsRecords[0], feedsRecords[0], "raw:21:feeds")
        assertEquals(0, repository.history.rows.size)

        persistence.persistCompleted(detailRecords[0], detailRecords[0], "raw:20:detail")

        // Should STILL have exactly 1 snapshot: the question detail
        assertEquals(1, repository.history.rows.size)
        val snapshot = repository.history.rows.values.single().toRecord()
        assertEquals("raw:20:detail", repository.history.rows.keys.single())
        assertEquals("禁止早恋的副作用是什么？", snapshot.title)
        assertEquals("问题描述正文", snapshot.content)
        assertEquals("提问者", snapshot.authorName)

        // Index should have merged both
        val index = repository.find("zhihu_question_618885757")
        assertNotNull(index)
        assertEquals("问题描述正文", index!!.content)
        assertTrue(index.extraMetadata.contains("1917896120379610729"))
    }

    @Test
    fun assembleRecordHistoryFiltersOutSpuriousAuxiliaryFeedSnapshots() {
        val seed = UniversalRecord(
            platform = "zhihu",
            recordType = "question",
            platformId = "zhihu_question_618885757",
            title = "禁止早恋的副作用是什么？",
            content = "问题正文",
            authorName = "提问者",
            sourceUrl = "https://api.zhihu.com/questions/618885757?include=detail",
            captureTime = 1000L
        )
        val feedsAuxiliary = UniversalRecord(
            platform = "zhihu",
            recordType = "question",
            platformId = "zhihu_question_618885757",
            title = "禁止早恋的副作用是什么？",
            content = "",
            authorName = "",
            sourceUrl = "https://api.zhihu.com/questions/618885757/feeds?include=big_card_summary",
            extraMetadata = """{"question_id":"618885757","answer_refs":[{"id":"1"}],"is_auxiliary":true}""",
            captureTime = 2000L
        )

        val snapshots = listOf(
            recordSnapshot("raw:detail", seed),
            recordSnapshot("raw:feeds", feedsAuxiliary)
        )

        val history = assembleRecordHistory(snapshots, listOf(seed), seed.identity)
        assertEquals(1, history.size)
        assertEquals("raw:detail", history.single().key)
        assertEquals("问题正文", history.single().record.content)

        val allHistory = assembleAllHistoricalRecords(snapshots, listOf(seed))
        assertEquals(1, allHistory.size)
        assertEquals("问题正文", allHistory.single().content)
    }
}
