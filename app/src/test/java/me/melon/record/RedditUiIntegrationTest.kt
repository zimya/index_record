package me.melon.record

import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.logic.StatsCalculator
import me.melon.record.ui.components.RedditMetadata
import me.melon.record.ui.components.engagementCount
import me.melon.record.ui.components.filterAppLabel
import me.melon.record.ui.components.recordTypeLabel
import me.melon.record.ui.components.redditPreviewText
import me.melon.record.ui.components.relatedRecordLabel
import me.melon.record.ui.components.signedScore
import me.melon.record.ui.components.typeLabel
import me.melon.record.ui.detail.record.recordDetailCommentCountLabel
import me.melon.record.ui.detail.record.redditMissingParentNotice
import me.melon.record.ui.home.usesRecordDetailForComment
import me.melon.record.ui.stats.appLabel
import me.melon.record.ui.stats.colorFromPackage
import me.melon.record.ui.theme.RedditOrange
import me.melon.record.ui.theme.getPlatformThemeColor
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
        assertEquals("已记录回复 (8)", recordDetailCommentCountLabel("reddit", 8))
        assertEquals("已记录回复 (0)", recordDetailCommentCountLabel("reddit", 0))
        assertEquals("评论 (8)", recordDetailCommentCountLabel("douban", 8))
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
            assertEquals("Reddit", filterAppLabel(key))
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
    fun postAndReplyLabelsAndNavigationUseSharedRecordUi() {
        assertEquals("Reddit 帖子", typeLabel("post"))
        assertEquals("评论", typeLabel("comment"))
        assertEquals("帖子", recordTypeLabel(UniversalRecord(platform = "reddit", recordType = "post")))
        assertEquals("回复", recordTypeLabel(UniversalRecord(platform = "reddit", recordType = "comment")))
        for (platform in listOf("reddit", "threads", "weibo")) assertTrue(usesRecordDetailForComment(platform))
        for (platform in listOf("zhihu", "douban", "twitter")) assertFalse(usesRecordDetailForComment(platform))
        assertEquals("楼中楼", recordTypeLabel(UniversalRecord(platform = "tieba", recordType = "comment")))
        assertEquals("所属 Reddit 帖子", relatedRecordLabel("reddit", "post"))
        assertEquals("回复的 Reddit 评论", relatedRecordLabel("reddit", "comment"))
        assertEquals("关联 Threads 主帖", relatedRecordLabel("threads", "thread"))
    }

    @Test
    fun missingParentsAreNotPresentedAsFabricatedRecordsOrGuessedTypes() {
        assertEquals("父回复未捕获：reddit_comment_abc", redditMissingParentNotice("reddit_comment_abc"))
        assertEquals("父帖子未捕获：reddit_post_def", redditMissingParentNotice("reddit_post_def"))
        assertEquals("父节点未捕获：unknown", redditMissingParentNotice("unknown"))
        assertEquals("父节点关系未记录", redditMissingParentNotice(""))
    }

    @Test
    fun previewsRemoveMarkdownSyntaxWithoutChangingTheCapturedSource() {
        val source = "**Cost**: \$5 or \$10. [link](https://example.com).\n\n`<p>\$5</p>`"
        val record = UniversalRecord(platform = "reddit", recordType = "post", content = source)
        assertEquals("Cost: \$5 or \$10. link. <p>\$5</p>", redditPreviewText(record.content))
        assertEquals(source, record.content)
        val method = Class.forName("me.melon.record.ui.components.RecordItemKt")
            .getDeclaredMethod("getPreviewText", UniversalRecord::class.java)
        method.isAccessible = true
        assertEquals(redditPreviewText(source), method.invoke(null, record))
    }

    @Test
    fun previewSeparatorsPreserveInlinePunctuationAndSeparateBlocks() {
        assertEquals("bold, italic! link.", redditPreviewText("**bold**, *italic*! [link](https://example.com)."))
        assertEquals("heading quote first second", redditPreviewText("# heading\n\n> quote\n\n- first\n- second"))
        assertEquals("[图片] [GIF]", redditPreviewText("![alt](https://example.com/a.jpg)\n\n[GIF](https://example.com/a.mp4)"))
        assertEquals("<p>\$5</p>", redditPreviewText("```html\n<p>\$5</p>\n```"))
    }

    @Test
    fun legacyLikeCountsStillClampInsteadOfChangingOtherPlatformSemantics() {
        assertEquals(0L, JsonParser.parseString("""{"like_count":-7}""").asJsonObject.engagementCount("like_count"))
        assertEquals("Threads", appLabel("threads"))
        assertEquals("Threads 帖子", typeLabel("thread"))
    }
}
