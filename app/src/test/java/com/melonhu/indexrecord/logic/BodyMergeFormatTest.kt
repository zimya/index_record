package com.melonhu.indexrecord.logic

import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.bodyPlainText
import com.melonhu.indexrecord.parser.reddit.RedditRelations
import com.melonhu.indexrecord.parser.threads.ThreadsRelations
import com.melonhu.indexrecord.parser.tieba.TiebaRelations
import org.junit.Assert.*
import org.junit.Test

class BodyMergeFormatTest {
    private val formats = RecordBodyFormat.entries.map { it.storageValue }
    private fun record(platform: String, body: String, format: String, meta: String = "{}", captured: Long = 100) =
        UniversalRecord(platform = platform, platformId = "${platform}_1", content = body,
            contentFormat = format, extraMetadata = meta, captureTime = captured)

    private fun assertBody(expected: UniversalRecord, actual: UniversalRecord) {
        assertEquals(expected.content, actual.content)
        assertEquals(expected.contentFormat, actual.contentFormat)
        assertEquals(expected.bodyPlainText(), actual.bodyPlainText())
    }

    @Test fun genericBlankFallbackCopiesBodyAndExactDeclaredFormat() {
        for (oldFormat in formats) for (newFormat in formats) {
            val repository = MemoryRecordRepository()
            val persistence = RecordPersistence(repository)
            val old = record("future", "<p>**saved**</p>", oldFormat)
            persistence.persist(old)
            persistence.persist(old.copy(content = " ", contentFormat = newFormat))
            assertBody(old, repository.find(old.platformId)!!)
            val replacement = old.copy(content = "new", contentFormat = newFormat)
            persistence.persist(replacement)
            assertBody(replacement, repository.find(old.platformId)!!)
        }
    }

    @Test fun subjectTitlePlaceholderFallsBackWithTheStoredBodyFormat() {
        val repository = MemoryRecordRepository()
        val persistence = RecordPersistence(repository)
        val old = record("douban", "**literal intro**", RecordBodyFormat.PLAIN_TEXT.storageValue).copy(
            recordType = "subject", title = "title")
        persistence.persist(old)
        persistence.persist(old.copy(content = old.title, contentFormat = RecordBodyFormat.HTML.storageValue))
        assertBody(old, repository.find(old.platformId)!!)
    }

    @Test fun threadsAndWeiboLengthPoliciesKeepTheWinningFormatAndDoNotMutateExisting() {
        for (platform in listOf("threads", "weibo")) for (oldFormat in formats) for (newFormat in formats) {
            val old = record(platform, "**long saved body**", oldFormat)
            val before = old.copy()
            val merge = if (platform == "threads") ThreadsRelations::mergeRecord else ::mergeWeiboRecord
            for (body in listOf("", " ", "short")) {
                val patch = record(platform, body, newFormat)
                merge(old, patch)
                assertBody(old, patch)
            }
            val replacement = record(platform, "a new body longer than the stored body", newFormat)
            val expected = replacement.copy()
            merge(old, replacement)
            assertBody(expected, replacement)
            assertEquals(before, old)
        }
    }

    @Test fun equalLengthPolicyStillPrefersIncomingForThreadsAndWeibo() {
        for (platform in listOf("threads", "weibo")) {
            val old = record(platform, "old", RecordBodyFormat.HTML.storageValue)
            val incoming = record(platform, "new", RecordBodyFormat.PLAIN_TEXT.storageValue)
            val expected = incoming.copy()
            if (platform == "threads") ThreadsRelations.mergeRecord(old, incoming) else mergeWeiboRecord(old, incoming)
            assertBody(expected, incoming)
        }
    }

    @Test fun weiboFullBodyWinsEvenWhenShorterAndKeepsFormatAcrossPreviewRoundTrips() {
        val preview = record("weibo", "long preview with expand link", RecordBodyFormat.MARKDOWN.storageValue)
        val full = record("weibo", "**literal**", RecordBodyFormat.PLAIN_TEXT.storageValue, """{"full_text":true}""")
        mergeWeiboRecord(preview, full)
        assertEquals(RecordBodyFormat.PLAIN_TEXT.storageValue, full.contentFormat)
        assertEquals("**literal**", full.bodyPlainText())
        val nextPreview = preview.copy()
        mergeWeiboRecord(full, nextPreview)
        assertBody(full, nextPreview)
    }

    @Test fun tiebaSelectionKeepsFormatWithoutMutatingEitherSource() {
        for (oldFormat in formats) for (newFormat in formats) {
            val full = record("tieba", "full", oldFormat, """{"is_preview":false}""")
            val preview = record("tieba", "a much longer preview", newFormat, """{"is_preview":true}""")
            val beforeFull = full.copy()
            val beforePreview = preview.copy()
            for ((a, b) in listOf(full to preview, preview to full)) {
                val body = TiebaRelations.mergeRecordContent(a, b)
                assertEquals(full.content, body.content)
                assertEquals(full.contentFormat, body.contentFormat)
            }
            val tie = full.copy(content = "same", contentFormat = newFormat)
            assertEquals(oldFormat, TiebaRelations.mergeRecordContent(full, tie).contentFormat)
            val longer = full.copy(content = "longer full body", contentFormat = newFormat)
            assertEquals(newFormat, TiebaRelations.mergeRecordContent(full, longer).contentFormat)
            assertEquals(oldFormat, TiebaRelations.mergeRecordContent(full, preview.copy(content = "")).contentFormat)
            assertEquals(oldFormat, TiebaRelations.mergeRecordContent(preview.copy(content = ""), full).contentFormat)
            assertEquals(beforeFull, full)
            assertEquals(beforePreview, preview)
        }
    }

    @Test fun platformPersistenceRetainsSelectedFormatsAcrossRepeatedCaptures() {
        for (platform in listOf("weibo", "threads", "tieba")) {
            val repository = MemoryRecordRepository()
            val persistence = RecordPersistence(repository)
            val preview = record(platform, "preview", RecordBodyFormat.HTML.storageValue, """{"is_preview":true}""")
            val full = record(platform, "**long literal body**", RecordBodyFormat.PLAIN_TEXT.storageValue,
                """{"is_preview":false,"full_text":true}""")
            persistence.persist(preview)
            persistence.persist(full)
            persistence.persist(preview.copy())
            assertBody(full, repository.find(full.platformId)!!)
            persistence.persist(preview.copy(content = ""))
            assertBody(full, repository.find(full.platformId)!!)
        }
    }

    @Test fun redditBodyQualityAndCaptureOrderKeepTheSelectedFormat() {
        for (oldFormat in formats) for (newFormat in formats) {
            val full = record("reddit", "**full**", oldFormat, """{"body_source":"markdown","body_length":8}""")
            val preview = record("reddit", "a much longer preview", newFormat, """{"body_source":"preview"}""", 200)
            RedditRelations.mergeRecord(full, preview)
            assertBody(full, preview)
            val edit = record("reddit", "new", newFormat, """{"body_source":"markdown","body_length":3}""", 300)
            val expectedEdit = edit.copy()
            RedditRelations.mergeRecord(full, edit)
            assertBody(expectedEdit, edit)
            val older = full.copy(captureTime = 50)
            RedditRelations.mergeRecord(edit, older)
            assertBody(edit, older)
            val cleared = edit.copy(content = "", contentFormat = oldFormat,
                extraMetadata = """{"body_source":"markdown","body_length":0}""", captureTime = 400)
            RedditRelations.mergeRecord(edit, cleared)
            assertEquals("", cleared.content)
            assertEquals(oldFormat, cleared.contentFormat)
        }
    }

    @Test fun redditMediaReassemblyRetainsSelectedExplicitMarkdownBodySemantics() {
        val full = record("reddit", "**full**", RecordBodyFormat.MARKDOWN.storageValue, """{"body_source":"markdown","body_length":8}""")
        val media = record("reddit", "![image](https://example.test/image)", RecordBodyFormat.MARKDOWN.storageValue,
            """{"media_markdown":["![image](https://example.test/image)"]}""", 200)
        RedditRelations.mergeRecord(full, media)
        assertEquals(RecordBodyFormat.MARKDOWN.storageValue, media.contentFormat)
        assertEquals("**full**\n\n![image](https://example.test/image)", media.content)
        assertEquals("full [图片]", media.bodyPlainText())
        val repository = MemoryRecordRepository()
        val persistence = RecordPersistence(repository)
        persistence.persist(full)
        persistence.persist(media)
        assertEquals("full [图片]", repository.find(full.platformId)!!.bodyPlainText())
        assertEquals(RecordBodyFormat.MARKDOWN.storageValue, repository.find(full.platformId)!!.contentFormat)
    }
}
