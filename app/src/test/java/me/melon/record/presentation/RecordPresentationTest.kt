package me.melon.record.presentation

import me.melon.record.data.model.UniversalRecord
import me.melon.record.markdown.MarkdownContentFormat
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class RecordPresentationTest {
    private fun record(platform: String, metadata: String = "{}", type: String = "post") =
        UniversalRecord(platform = platform, recordType = type, extraMetadata = metadata)

    @Test
    fun compactCountsKeepBoundariesLocaleAndNegativeClamping() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val cases = mapOf(
                Long.MIN_VALUE to "0", -1L to "0", 0L to "0", 999L to "999",
                1000L to "1.0k", 9999L to "10.0k", 10000L to "1.0万",
                12057L to "1.2万", 399205L to "39.9万", 1160320L to "116.0万",
                100000000L to "1.0亿"
            )
            cases.forEach { (count, expected) -> assertEquals(expected, formatCount(count)) }
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1,2万", formatCount(12057))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun listAndDetailUseSameCountsButKeepTheirOwnOrderAndExtras() {
        val r = record("threads", """{"likeCount":11,"commentCount":22,"repostCount":33,"quote_count":44,"reshare_count":55,"bookmarkCount":66,"view_count":77}""")
        val list = recordStatistics(r, StatisticsSurface.LIST)
        val detail = recordStatistics(r, StatisticsSurface.DETAIL)
        assertEquals(listOf(StatisticKind.REPLY, StatisticKind.REPOST, StatisticKind.LIKE, StatisticKind.VIEW), list.map { it.kind })
        assertEquals(listOf("22", "33", "11", "77"), list.map { it.text })
        assertEquals(listOf(StatisticKind.LIKE, StatisticKind.REPOST, StatisticKind.REPLY, StatisticKind.QUOTE, StatisticKind.SHARE, StatisticKind.BOOKMARK, StatisticKind.VIEW), detail.map { it.kind })
        assertEquals(listOf("11", "33", "22", "引用 44", "分享 55", "66", "77"), detail.map { it.text })
        for (stat in list) assertTrue(detail.contains(stat))
    }

    @Test
    fun countAliasesPreservePriorityAndThreadsOnlyViewKey() {
        val json = """{"like_count":0,"likeCount":99,"retweet_count":-1,"repostCount":99,"reply_count":null,"comment_count":{},"commentCount":"7","view_count":10,"views_count":20,"viewCount":30}"""
        for (platform in listOf("threads", "twitter", "weibo")) {
            val stats = recordStatistics(record(platform, json), StatisticsSurface.LIST)
            assertEquals(listOf(StatisticKind.REPLY, StatisticKind.VIEW), stats.map { it.kind })
            assertEquals(listOf("7", if (platform == "threads") "10" else "20"), stats.map { it.text })
        }
        for (jsonValue in listOf("{}", "bad json", "[]", """{"like_count":null,"reply_count":"bad","repost_count":-3}""")) {
            assertTrue(recordStatistics(record("threads", jsonValue), StatisticsSurface.LIST).isEmpty())
        }
    }

    @Test
    fun redditUnknownZeroNegativeHiddenAndRealSharesStayDistinct() {
        for (surface in StatisticsSurface.entries) {
            assertTrue(recordStatistics(record("reddit", """{"crosspost_count":2,"child_count":5,"like_count":99}"""), surface).isEmpty())
            val r = record("reddit", """{"score":-23,"comment_count":0,"share_count":0}""")
            assertEquals(listOf("0", "0", "0"), recordStatistics(r, surface).map { it.text })
            assertEquals(listOf(StatisticKind.LIKE, StatisticKind.REPLY, StatisticKind.SHARE), recordStatistics(r, surface).map { it.kind })
            val hidden = record("reddit", """{"score":999,"is_score_hidden":true,"comment_count":-1,"share_count":46}""")
            assertEquals(listOf(RecordStatistic(StatisticKind.SHARE, "46")), recordStatistics(hidden, surface))
        }
        assertEquals(-23L, commentStatistics(record("reddit", """{"score":-23}""")).likeCount)
        assertEquals(0L, commentStatistics(record("reddit", """{"score":0}""")).likeCount)
        assertNull(commentStatistics(record("reddit", """{"score":0,"is_score_hidden":true}""")).likeCount)
        assertEquals(10000L, commentStatistics(record("reddit", """{"score":10000}""")).likeCount)
    }

    @Test
    fun commentsKeepTheirNarrowerLikeAliasesAndOnlyThreadsViews() {
        val json = """{"voteup_count":9,"view_count":10,"views_count":20,"dislike_count":3}"""
        val threads = commentStatistics(record("threads", json))
        assertNull(threads.likeCount)
        assertEquals(10L, threads.viewCount)
        assertEquals(3L, threads.dislikeCount)
        assertEquals(0L, commentStatistics(record("twitter", json)).viewCount)
        assertEquals(9L, RecordMetadata(json, "threads").likeCount)
    }

    @Test
    fun legacyDetailBadgesKeepRawTextAndOrderRatherThanCompactFormatting() {
        val r = record("zhihu", """{"voteup_count":-1,"comment_count":0,"ip_info":"北京","favlists_count":12057}""")
        assertTrue(recordStatistics(r, StatisticsSurface.LIST).isEmpty())
        assertEquals(listOf("-1", "0", "北京", "12057"), recordStatistics(r, StatisticsSurface.DETAIL).map { it.text })
    }

    @Test
    fun titlesAndContextsKeepPlatformFallbacksAndOnlyRealCapturedContext() {
        val tieba = record("tieba", """{"thread_title":"主题","forum_name":"Android","floor":3}""", "floor")
        assertEquals("主题", recordDisplayTitle(tieba))
        assertEquals("Android吧 · 3 楼", recordContext(tieba))
        tieba.title = "已有标题"
        assertEquals("已有标题", recordDisplayTitle(tieba))
        assertEquals("", recordContext(record("tieba", """{"floor":0}""")))
        assertEquals("r/android · Help", recordContext(record("reddit", """{"subreddit":"android","flair":"Help"}""")))
        assertEquals("Reddit 回复", recordDisplayTitle(record("reddit", type = "comment")))
        assertEquals("微博用户的微博", recordDisplayTitle(record("weibo")))
        assertEquals("Threads 用户的帖子", recordDisplayTitle(record("threads")))
        assertEquals("无标题", recordDisplayTitle(record("zhihu")))
    }

    @Test
    fun previewsRetainQuestionSubjectImageAndMarkdownRules() {
        assertEquals("摘要", recordPreviewText(record("zhihu", """{"excerpt":"<p>摘要</p>"}""", "question")))
        val subject = record("douban", """{"intro":"简介","card_subtitle":"卡片"}""", "subject").apply { title = "标题"; content = "标题" }
        assertEquals("简介", recordPreviewText(subject))
        subject.extraMetadata = """{"card_subtitle":"卡片"}"""
        assertEquals("卡片", recordPreviewText(subject))
        subject.content = "独立内容"
        assertEquals("独立内容", recordPreviewText(subject))
        val threads = record("threads").apply { content = "Hello\\! \\~world\\~ ![图片](https://example.com/a.jpg)" }
        assertEquals("Hello! ~world~ [图片]", recordPreviewText(threads))
        val reddit = record("reddit").apply { content = "**bold** [link](https://example.com) `code`" }
        assertEquals("bold link code", recordPreviewText(reddit))
        assertEquals("**bold** [link](https://example.com) `code`", reddit.content)
    }

    @Test
    fun snippetsKeepOldWindowAndRedditSearchesFlattenedPreviewOnly() {
        val content = "a".repeat(50) + "MATCH" + "b".repeat(100)
        assertEquals("..." + content.substring(10, 130) + "...", contentSnippet(content, "match"))
        val reddit = record("reddit").apply { this.content = "[label](https://secret.example)" }
        assertNull(recordListPresentation(reddit, "secret").snippet)
        assertEquals("label", recordListPresentation(reddit, "LABEL").snippet)
        val other = record("weibo").apply { this.content = reddit.content }
        assertNotNull(recordListPresentation(other, "secret").snippet)
        assertNull(recordListPresentation(other, "").snippet)
    }

    @Test
    fun headerAndBodyPreserveDetailSpecificFallbacksAndSource() {
        val r = record("douban", """{"title_image":"cover","author_headline":"headline","star_count":0,"rating_value":4.5}""")
        val header = recordHeaderPresentation(r)
        assertEquals("豆瓣用户", header.author)
        assertEquals("", header.title)
        assertEquals("cover", header.titleImage)
        assertEquals("headline", header.authorHeadline)
        assertEquals(4.5, header.starCount, 0.0)
        assertEquals(3.0, recordHeaderPresentation(record("douban", """{"star_count":3,"rating_value":4.5}""")).starCount, 0.0)
        r.content = "<b>raw</b>"
        assertEquals(r.content, recordBodyPresentation(r).content)
        assertEquals(MarkdownContentFormat.AUTO, recordBodyPresentation(r).contentFormat)
        assertTrue(recordBodyPresentation(r).enableMath)
        val reddit = recordBodyPresentation(record("reddit"))
        assertEquals(MarkdownContentFormat.MARKDOWN, reddit.contentFormat)
        assertFalse(reddit.enableMath)
    }

    @Test
    fun subjectFallbackRequiresBothFieldsAndRetainsReferenceIdentity() {
        val r = record("douban", """{"subject_id":"123","subject_title":"电影"}""").apply { parentId = "douban_movie_123"; sourceApp = "host" }
        val reference = requireNotNull(doubanSubjectReference(r))
        assertEquals(r.parentId, reference.platformId)
        assertEquals("subject", reference.recordType)
        assertEquals("电影", reference.title)
        assertEquals("host", reference.sourceApp)
        assertEquals(r.extraMetadata, reference.extraMetadata)
        assertNull(doubanSubjectReference(record("douban", """{"subject_title":"电影"}""")))
        assertNull(doubanSubjectReference(record("douban", "bad json")))
    }
}
