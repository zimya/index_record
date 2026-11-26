package com.melonhu.indexrecord

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.logic.StatsCalculator
import com.melonhu.indexrecord.presentation.RedditMetadata
import com.melonhu.indexrecord.presentation.PlatformMetadataAdapters
import com.melonhu.indexrecord.presentation.recordPreviewText
import com.melonhu.indexrecord.ui.components.filterPlatformLabel
import com.melonhu.indexrecord.ui.components.recordTypeLabel
import com.melonhu.indexrecord.presentation.redditPreviewText
import com.melonhu.indexrecord.presentation.relatedRecordLabel
import com.melonhu.indexrecord.data.model.RecordIdentity
import com.melonhu.indexrecord.data.model.RecordRelation
import com.melonhu.indexrecord.presentation.signedScore
import com.melonhu.indexrecord.ui.components.typeLabel
import com.melonhu.indexrecord.ui.detail.record.recordDetailCommentCountLabel
import com.melonhu.indexrecord.presentation.redditMissingParentNotice

import com.melonhu.indexrecord.ui.stats.appLabel
import com.melonhu.indexrecord.ui.stats.colorFromPackage
import com.melonhu.indexrecord.ui.theme.RedditOrange
import com.melonhu.indexrecord.ui.theme.getPlatformThemeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditUiIntegrationTest {
    @Test
    fun signedScorePreservesZeroNegativeAndLongBounds() {
        for (score in listOf(Long.MIN_VALUE, -23L, 0L, 46L, Long.MAX_VALUE)) {
            val metadata = JsonParser.parseString("""{"score":$score}""").asJsonObject
            assertEquals(score, requireNotNull(metadata.signedScore()))
            assertEquals(score, requireNotNull(RedditMetadata(metadata.toString()).score))
        }
    }

    @Test
    fun missingAndMalformedScoresStayUnknown() {
        for (json in listOf(
            "{}", """{"score":null}""", """{"score":"bad"}""", """{"score":{}}""",
            """{"score":[]}""", """{"score":true}""", """{"score":1.5}""",
            """{"score":9223372036854775808}""", """{"like_count":100,"voteup_count":200}"""
        )) {
            assertNull(JsonParser.parseString(json).asJsonObject.signedScore())
            assertNull(RedditMetadata(json).score)
        }
        assertNull(RedditMetadata("not json").score)
        assertNull(RedditMetadata("[]").score)
        assertEquals(-7L, requireNotNull(JsonParser.parseString("""{"score":"-7"}""").asJsonObject.signedScore()))
    }

    @Test
    fun hiddenScorePlaceholderIsNotPresentedAsARealScore() {
        val json = """{"score":999,"is_score_hidden":true}"""
        assertEquals(999L, requireNotNull(JsonParser.parseString(json).asJsonObject.signedScore()))
        val hidden = RedditMetadata(json)
        assertEquals(true, hidden.isScoreHidden)
        assertNull(hidden.score)
        val visible = RedditMetadata("""{"score":0,"is_score_hidden":false}""")
        assertEquals(false, visible.isScoreHidden)
        assertEquals(0L, requireNotNull(visible.score))
    }

    @Test
    fun realSharesAndZeroCountsAreKeptWithoutInferringOtherCounts() {
        val actual = RedditMetadata("""{"score":-1,"comment_count":100,"share_count":46}""")
        assertEquals(46L, requireNotNull(actual.shareCount))
        assertEquals(100L, requireNotNull(actual.commentCount))
        val zeros = RedditMetadata("""{"comment_count":0,"share_count":0}""")
        assertEquals(0L, requireNotNull(zeros.commentCount))
        assertEquals(0L, requireNotNull(zeros.shareCount))
        val unrelated = RedditMetadata("""{"crosspost_count":3,"child_count":91}""")
        assertEquals(3L, requireNotNull(unrelated.crosspostCount))
        assertEquals(91L, requireNotNull(unrelated.childCount))
        assertNull(unrelated.commentCount)
        assertNull(unrelated.shareCount)
        val invalid = RedditMetadata("""{"comment_count":-1,"share_count":"bad"}""")
        assertNull(invalid.commentCount)
        assertNull(invalid.shareCount)
    }

    @Test
    fun platformCountsAreSeparateFromTheRecordedReplyHeader() {
        val metadata = RedditMetadata("""{"comment_count":1000,"comments_has_next_page":true}""")
        assertEquals(true, metadata.commentsHasNextPage)
        assertEquals(1000L, requireNotNull(metadata.commentCount))
        assertEquals("本地已记录 8 条", recordDetailCommentCountLabel("reddit", 8))
        assertEquals("本地已记录 0 条", recordDetailCommentCountLabel("reddit", 0))
        assertEquals("本地已记录 8 条", recordDetailCommentCountLabel("douban", 8))
    }

    @Test
    fun optionalFlagsAndAdditionalFieldsComeOnlyFromCapturedMetadata() {
        val empty = RedditMetadata("{}")
        assertNull(empty.isNsfw)
        assertNull(empty.isSpoiler)
        assertNull(empty.commentsHasNextPage)
        assertTrue(empty.additionalFields.isEmpty())
        val actual = RedditMetadata("""{"subreddit":"android","is_nsfw":false,"is_spoiler":true,"edited_at":123,"locked":false}""")
        assertEquals("android", actual.subreddit)
        assertEquals(false, actual.isNsfw)
        assertEquals(true, actual.isSpoiler)
        assertEquals("123", actual.editedAt)
        assertEquals(listOf("locked" to "false"), actual.additionalFields)
        assertTrue(RedditMetadata("""{"body_source":"markdown","body_complete":true,"body_length":100,"media_complete":true,"content_format":"markdown","comment_forest_count":318}""").additionalFields.isEmpty())
    }

    @Test
    fun platformAndPackageUseTheSameRedditNameAndTheme() {
        for (key in listOf("reddit", "com.reddit.frontpage")) {
            assertEquals("Reddit", appLabel(key))
            assertEquals("Reddit", filterPlatformLabel(key))
            assertEquals(RedditOrange, colorFromPackage(key))
        }
        for (dark in listOf(false, true)) {
            assertEquals(getPlatformThemeColor("reddit", dark), getPlatformThemeColor("com.reddit.frontpage", dark))
        }
        for (type in listOf("post", "comment")) {
            assertEquals("com.reddit.frontpage", StatsCalculator.resolveSourceApp(UniversalRecord(platform = "reddit", recordType = type)))
        }
    }

    @Test
    fun postAndReplyLabelsUseSharedRecordVocabulary() {
        assertEquals("Reddit 帖子", typeLabel("post"))
        assertEquals("评论", typeLabel("comment"))
        assertEquals("帖子", recordTypeLabel(UniversalRecord(platform = "reddit", recordType = "post")))
        assertEquals("回复", recordTypeLabel(UniversalRecord(platform = "reddit", recordType = "comment")))

        assertEquals("楼中楼", recordTypeLabel(UniversalRecord(platform = "tieba", recordType = "comment")))
        assertEquals("回复对象", relatedRecordLabel(RecordRelation.ReplyTo(RecordIdentity.of("reddit", "reddit_post_1"))))
        assertEquals("回复对象", relatedRecordLabel(RecordRelation.ReplyTo(RecordIdentity.of("reddit", "reddit_comment_1"))))
        assertEquals("引用内容", relatedRecordLabel(RecordRelation.ReferencesContent(RecordIdentity.of("threads", "threads_1"))))
    }

    @Test
    fun missingParentsAreNotPresentedAsFabricatedRecordsOrGuessedTypes() {
        assertEquals("本地尚未记录关联内容：reddit_comment_abc", redditMissingParentNotice("reddit_comment_abc"))
        assertEquals("本地尚未记录关联内容：reddit_post_def", redditMissingParentNotice("reddit_post_def"))
        assertEquals("本地尚未记录关联内容：unknown", redditMissingParentNotice("unknown"))
        assertEquals("本地尚未记录关联内容", redditMissingParentNotice(""))
    }

    @Test
    fun previewsRemoveMarkdownSyntaxWithoutChangingTheCapturedSource() {
        val source = "**Cost**: \$5 or \$10. [link](https://example.com).\n\n`<p>\$5</p>`"
        val record = UniversalRecord(
                    platform = "reddit", recordType = "post", content = source,
                    contentFormat = RecordBodyFormat.MARKDOWN.storageValue
                )
        assertEquals("Cost: \$5 or \$10. link. <p>\$5</p>", redditPreviewText(record.content))
        assertEquals(source, record.content)
        assertEquals(redditPreviewText(source), recordPreviewText(record))
    }

    @Test
    fun previewSeparatorsPreserveInlinePunctuationAndSeparateBlocks() {
        assertEquals("bold, italic! link.", redditPreviewText("**bold**, *italic*! [link](https://example.com)."))
        assertEquals("heading quote first second", redditPreviewText("# heading\n\n> quote\n\n- first\n- second"))
        assertEquals("[图片] [GIF]", redditPreviewText("![alt](https://example.com/a.jpg)\n\n[GIF](https://example.com/a.mp4)"))
        assertEquals("<p>\$5</p>", redditPreviewText("```html\n<p>\$5</p>\n```"))
    }

    @Test
    fun invalidLikeCountsStayUnknownInsteadOfInventingZero() {
        assertNull(PlatformMetadataAdapters("""{"like_count":-7}""", "threads").engagement.likeCount)
        assertEquals("Threads", appLabel("threads"))
        assertEquals("Threads 帖子", typeLabel("thread"))
    }
}
