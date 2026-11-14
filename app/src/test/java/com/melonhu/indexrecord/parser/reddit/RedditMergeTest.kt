package com.melonhu.indexrecord.parser.reddit

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test

class RedditMergeTest {
    private fun record(content: String, metadata: String, captured: Long = 100) = UniversalRecord(
        platform = "reddit", recordType = "comment", platformId = "reddit_comment_abc", parentId = "reddit_comment_parent",
        content = content, captureTime = captured, extraMetadata = metadata
    )
    private fun meta(record: UniversalRecord) = JsonParser.parseString(record.extraMetadata).asJsonObject

    @Test fun fullMarkdownSurvivesPreviewAndSparseDetailsWhileRealZeroAndNegativeCountsUpdate() {
        val full = record("**full source**  \nnext line ", """{"body_source":"markdown","body_complete":true,"body_length":27,"score":9,"comment_count":8,"share_count":4,"is_score_hidden":false,"post_id":"reddit_post_root"}""")
        // The parser supplies the exact UTF-16 text length, not a normalized/trimmed body.
        meta(full).also { it.addProperty("body_length", full.content.length); full.extraMetadata = it.toString() }
        val preview = record("short", """{"body_source":"preview","body_complete":false,"body_length":5,"score":0,"share_count":0}""", 200)
        preview.parentId = ""
        RedditRelations.mergeRecord(full, preview)
        assertEquals(full.content, preview.content)
        assertEquals(full.parentId, preview.parentId)
        assertEquals("markdown", meta(preview)["body_source"].asString)
        assertTrue(meta(preview)["body_complete"].asBoolean)
        assertEquals(0, meta(preview)["score"].asInt)
        assertEquals(0, meta(preview)["share_count"].asInt)
        assertEquals(8, meta(preview)["comment_count"].asInt)
        val sparse = record("", """{"score":-3,"share_count":null,"images":[],"is_score_hidden":false}""", 300)
        RedditRelations.mergeRecord(preview, sparse)
        assertEquals(full.content, sparse.content)
        assertEquals(-3, meta(sparse)["score"].asInt)
        assertEquals(0, meta(sparse)["share_count"].asInt)
        assertEquals("reddit_post_root", meta(sparse)["post_id"].asString)
    }

    @Test fun laterCompleteEditedMarkdownCanBeShorterOrExplicitlyEmpty() {
        val existing = record("long old body", """{"body_source":"markdown","body_complete":true,"body_length":13,"edited_at":1000}""")
        val edited = record("new", """{"body_source":"markdown","body_complete":true,"body_length":3,"edited_at":2000}""", 200)
        RedditRelations.mergeRecord(existing, edited)
        assertEquals("new", edited.content)
        val cleared = record("", """{"body_source":"markdown","body_complete":true,"body_length":0,"edited_at":3000}""", 300)
        RedditRelations.mergeRecord(edited, cleared)
        assertEquals("", cleared.content)
        assertTrue(meta(cleared)["body_complete"].asBoolean)
    }

    @Test fun mediaIsPreservedWhenOmittedAndFullMediaReplacesFeedThumbnailsWithoutDuplication() {
        val feed = record("preview\n\n![图片](https://example.test/thumb)", """{"body_source":"preview","body_complete":false,"body_length":7,"media_markdown":["![图片](https://example.test/thumb)"],"media_complete":false}""")
        val full = record("full\n\n![图片](https://example.test/original)", """{"body_source":"markdown","body_complete":true,"body_length":4,"media_markdown":["![图片](https://example.test/original)"],"media_complete":true}""", 200)
        RedditRelations.mergeRecord(feed, full)
        assertEquals("full\n\n![图片](https://example.test/original)", full.content)
        val textUpdate = record("edited", """{"body_source":"markdown","body_complete":true,"body_length":6}""", 300)
        RedditRelations.mergeRecord(full, textUpdate)
        assertEquals("edited\n\n![图片](https://example.test/original)", textUpdate.content)
        val laterFeed = record("preview\n\n![图片](https://example.test/thumb)", feed.extraMetadata, 400)
        RedditRelations.mergeRecord(textUpdate, laterFeed)
        assertEquals(textUpdate.content, laterFeed.content)
        assertTrue(meta(laterFeed)["media_complete"].asBoolean)
    }

    @Test fun olderMediaAndAuthorFillNewerSparseDetailWithoutRegressingMetadata() {
        val detail = record("", """{"score":0,"media_markdown":[]}""", 300)
        val media = record("![图片](https://example.test/original)", """{"score":10,"media_markdown":["![图片](https://example.test/original)"],"media_complete":true}""", 100).apply {
            authorId = "author_id"
            authorName = "author"
            authorAvatar = "https://example.test/avatar"
        }
        RedditRelations.mergeRecord(detail, media)
        assertEquals("![图片](https://example.test/original)", media.content)
        assertEquals("![图片](https://example.test/original)", meta(media)["media_markdown"].asJsonArray[0].asString)
        assertTrue(meta(media)["media_complete"].asBoolean)
        assertEquals("author_id", media.authorId)
        assertEquals("author", media.authorName)
        assertEquals("https://example.test/avatar", media.authorAvatar)
        assertEquals(0, meta(media)["score"].asInt)
        assertEquals(300, media.captureTime)

        val preview = record("![图片](https://example.test/thumb)", """{"media_markdown":["![图片](https://example.test/thumb)"],"media_complete":false}""", 400)
        RedditRelations.mergeRecord(media, preview)
        assertEquals(media.content, preview.content)
        assertTrue(meta(preview)["media_complete"].asBoolean)
    }

    @Test fun olderAuthorCannotOverwriteNewerNonblankIdentityFields() {
        val detail = record("", "{}", 300).apply {
            authorId = "new_id"
            authorName = "new_name"
            authorAvatar = "https://example.test/new_avatar"
        }
        val older = record("", "{}", 100).apply {
            authorId = "old_id"
            authorName = "old_name"
            authorAvatar = "https://example.test/old_avatar"
        }
        RedditRelations.mergeRecord(detail, older)
        assertEquals(detail.authorId, older.authorId)
        assertEquals(detail.authorName, older.authorName)
        assertEquals(detail.authorAvatar, older.authorAvatar)
        assertEquals(300, older.captureTime)
    }

    @Test fun outOfOrderCaptureCannotRegressKnownBodyOrMetadata() {
        val new = record("new body", """{"body_source":"markdown","body_complete":true,"body_length":8,"score":0}""", 300)
        val old = record("old body is longer", """{"body_source":"markdown","body_complete":true,"body_length":18,"score":10,"share_count":2}""", 100)
        RedditRelations.mergeRecord(new, old)
        assertEquals("new body", old.content)
        assertEquals(0, meta(old)["score"].asInt)
        assertEquals(2, meta(old)["share_count"].asInt) // An older observation can still fill an unknown field.
        assertEquals(300, old.captureTime)
    }

    @Test fun hiddenPlaceholderCannotReplacePreviouslyObservedVisibleScore() {
        val visible = record("body", """{"score":-5,"is_score_hidden":false}""")
        val hidden = record("", """{"is_score_hidden":true}""", 200)
        RedditRelations.mergeRecord(visible, hidden)
        assertEquals(-5, meta(hidden)["score"].asInt)
        assertTrue(meta(hidden)["is_score_hidden"].asBoolean)
    }

    @Test fun invalidMetadataIsSafeAndOtherPlatformsAndIdsAreUntouched() {
        val old = record("complete old content", """{"score":-1}""")
        val sparse = record("", "not json", 200)
        RedditRelations.mergeRecord(old, sparse)
        assertEquals(old.content, sparse.content)
        assertEquals(-1, meta(sparse)["score"].asInt)
        for (platform in listOf("twitter", "zhihu", "douban", "weibo", "threads")) {
            val incoming = record("short", "{}").apply { this.platform = platform }
            val before = incoming.copy()
            RedditRelations.mergeRecord(old, incoming)
            assertEquals(before, incoming)
        }
        val unrelated = record("other", "{}").apply { platformId = "reddit_comment_other" }
        val before = unrelated.copy()
        RedditRelations.mergeRecord(old, unrelated)
        assertEquals(before, unrelated)
    }
}
