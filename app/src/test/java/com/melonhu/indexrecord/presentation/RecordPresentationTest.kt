package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.markdown.MarkdownContentFormat
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class RecordPresentationTest {
    private fun record(
        platform: String,
        metadata: String = "{}",
        type: String = "post",
        format: RecordBodyFormat = RecordBodyFormat.PLAIN_TEXT
    ) = UniversalRecord(
        platform = platform, recordType = type, extraMetadata = metadata,
        contentFormat = format.storageValue
    )

    @Test
    fun compactCountsKeepBoundariesLocaleAndSignedScores() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.CHINA)
            val casesChina = mapOf(
                Long.MIN_VALUE to "-92233720368.5亿", -1L to "-1", 0L to "0", 999L to "999",
                1000L to "1.0k", 9999L to "10.0k", 10000L to "1.0万",
                12057L to "1.2万", 399205L to "39.9万", 1160320L to "116.0万",
                100000000L to "1.0亿"
            )
            casesChina.forEach { (count, expected) -> assertEquals(expected, formatCount(count)) }

            Locale.setDefault(Locale.TRADITIONAL_CHINESE)
            assertEquals("1.2萬", formatCount(12057))
            assertEquals("1.0億", formatCount(100000000))

            Locale.setDefault(Locale.US)
            val casesUS = mapOf(
                Long.MIN_VALUE to "-9223372036.9B", -1L to "-1", 0L to "0", 999L to "999",
                1000L to "1.0k", 9999L to "10.0k", 10000L to "10.0k",
                12057L to "12.1k", 399205L to "399.2k", 1160320L to "1.2M",
                100000000L to "100.0M", 1000000000L to "1.0B"
            )
            casesUS.forEach { (count, expected) -> assertEquals(expected, formatCount(count)) }

            Locale.setDefault(Locale.GERMANY)
            assertEquals("12,1k", formatCount(12057))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun compactNumbersSupportMultiLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.CHINA)
            assertEquals("9,999", formatCompactNumber(9999))
            assertEquals("1.0万", formatCompactNumber(10000))
            assertEquals("134.6万", formatCompactNumber(1346000))
            assertEquals("1.0亿", formatCompactNumber(100000000))

            Locale.setDefault(Locale.TRADITIONAL_CHINESE)
            assertEquals("134.6萬", formatCompactNumber(1346000))

            Locale.setDefault(Locale.US)
            assertEquals("9,999", formatCompactNumber(9999))
            assertEquals("10.0k", formatCompactNumber(10000))
            assertEquals("86.9k", formatCompactNumber(86880))
            assertEquals("1.3M", formatCompactNumber(1346000))
            assertEquals("1.0B", formatCompactNumber(1000000000))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun listAndDetailUseTheSameOrderWithDetailExtras() {
        val r = record("threads", """{"likeCount":11,"commentCount":22,"repostCount":33,"quote_count":44,"reshare_count":55,"bookmarkCount":66,"view_count":77}""")
        val list = recordStatistics(r, StatisticsSurface.LIST)
        val detail = recordStatistics(r, StatisticsSurface.DETAIL)
        assertEquals(listOf(StatisticKind.LIKE, StatisticKind.REPLY, StatisticKind.REPOST, StatisticKind.VIEW), list.map { it.kind })
        assertEquals(listOf("11", "22", "33", "77"), list.map { it.text })
        assertEquals(listOf(StatisticKind.LIKE, StatisticKind.REPLY, StatisticKind.REPOST, StatisticKind.QUOTE, StatisticKind.SHARE, StatisticKind.VIEW), detail.map { it.kind })
        assertEquals(listOf("11", "22", "33", "44", "55", "77"), detail.map { it.text })
        for (stat in list) assertTrue(detail.contains(stat))
    }

    @Test
    fun everyPlatformAndContentRoleKeepsSharedMetricsInTheSameOrder() {
        val order = listOf(
            StatisticKind.LIKE, StatisticKind.DISLIKE, StatisticKind.REPLY, StatisticKind.REPOST,
            StatisticKind.QUOTE, StatisticKind.SHARE, StatisticKind.BOOKMARK, StatisticKind.FAVORITE,
            StatisticKind.VIEW, StatisticKind.LOCATION
        )
        val json = """{"score":-2,"voteup_count":10,"like_count":11,"agree_count":12,"dislike_count":0,"comment_count":20,"reply_count":21,"child_comment_count":22,"retweet_count":30,"repost_count":31,"quote_count":40,"share_count":50,"reshare_count":51,"bookmark_count":60,"favlists_count":61,"view_count":70,"views_count":71,"ip_info":"北京","ip_address":"上海"}"""
        for (platform in listOf("reddit", "threads", "twitter", "weibo", "zhihu", "douban", "tieba")) {
            for (type in listOf("post", "comment")) {
                val r = record(platform, json, type)
                val list = recordStatistics(r, StatisticsSurface.LIST)
                val detail = recordStatistics(r, StatisticsSurface.DETAIL)
                assertFalse(platform, list.isEmpty())
                for (statistics in listOf(list, detail)) {
                    assertEquals("$platform/$type", statistics.sortedBy { order.indexOf(it.kind) }, statistics)
                }
                val listKinds = list.map { it.kind }.toSet()
                assertEquals("$platform/$type", list, detail.filter { it.kind in listKinds })
            }
        }
    }

    @Test
    fun groupedAnswerMetricsUseCapturedVotesAndCommentsIncludingZero() {
        val answer = record("zhihu", """{"voteup_count":12057,"comment_count":0,"like_count":999,"child_comment_count":88}""", "answer")
        val statistics = recordStatistics(answer, StatisticsSurface.LIST)
        assertEquals(listOf(StatisticKind.LIKE, StatisticKind.REPLY), statistics.map { it.kind })
        assertEquals(listOf(12057L, 0L), statistics.map { it.value })
        assertEquals(listOf("赞同数 12057", "平台评论数 0"), statistics.map { it.accessibilityText })
        assertEquals(statistics, recordStatistics(answer, StatisticsSurface.DETAIL))
        assertTrue(recordStatistics(answer.copy(extraMetadata = "{}"), StatisticsSurface.LIST).isEmpty())
    }

    @Test
    fun countAliasesPreservePriorityAndThreadsOnlyViewKey() {
        val json = """{"like_count":0,"likeCount":99,"retweet_count":-1,"repostCount":99,"reply_count":null,"comment_count":{},"commentCount":"7","view_count":10,"views_count":20,"viewCount":30}"""
        for (platform in listOf("threads", "twitter", "weibo")) {
            val stats = recordStatistics(record(platform, json), StatisticsSurface.LIST)
            assertEquals(listOf(StatisticKind.LIKE, StatisticKind.REPLY, StatisticKind.REPOST, StatisticKind.VIEW), stats.map { it.kind })
            assertEquals(listOf("0", "7", "99", if (platform == "threads") "10" else "20"), stats.map { it.text })
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
            assertEquals(listOf("-23", "0", "0"), recordStatistics(r, surface).map { it.text })
            assertEquals(listOf(StatisticKind.LIKE, StatisticKind.REPLY, StatisticKind.SHARE), recordStatistics(r, surface).map { it.kind })
            val hidden = record("reddit", """{"score":999,"is_score_hidden":true,"comment_count":-1,"share_count":46}""")
            assertEquals(listOf(RecordStatistic(StatisticKind.SHARE, "46", value = 46)), recordStatistics(hidden, surface))
        }
        assertEquals(-23L, commentStatistics(record("reddit", """{"score":-23}""")).likeCount)
        assertEquals(0L, commentStatistics(record("reddit", """{"score":0}""")).likeCount)
        assertNull(commentStatistics(record("reddit", """{"score":0,"is_score_hidden":true}""")).likeCount)
        assertEquals(10000L, commentStatistics(record("reddit", """{"score":10000}""")).likeCount)
    }

    @Test
    fun commentsUseOnlyPlatformSupportedCounts() {
        val json = """{"voteup_count":9,"view_count":10,"views_count":20,"dislike_count":3}"""
        val threads = commentStatistics(record("threads", json))
        assertNull(threads.likeCount)
        assertEquals(10L, threads.viewCount)
        assertNull(threads.dislikeCount)
        assertEquals(20L, commentStatistics(record("twitter", json)).viewCount)
        assertNull(PlatformMetadataAdapters(json, "threads").engagement.likeCount)
    }

    @Test
    fun commentReplyCountsUseCapturedPlatformFieldsWithoutChangingRawMetadata() {
        val cases = listOf(
            Triple("threads", """{"reply_count":12,"comment_count":99}""", 12L),
            Triple("threads", """{"comment_count":7}""", 7L),
            Triple("zhihu", """{"child_comment_count":4,"comment_count":99}""", 4L),
            Triple("zhihu", """{"child_comment_count":0,"reply_count":99}""", 0L),
            Triple("tieba", """{"reply_count":3}""", 3L),
            Triple("weibo", """{"commentCount":"2"}""", 2L),
            Triple("twitter", """{"reply_count":6}""", 6L),
            Triple("reddit", """{"comment_count":5,"child_count":99}""", 5L)
        )
        cases.forEach { (platform, json, expected) ->
            val comment = record(platform, json, "comment")
            assertEquals(platform, expected, commentStatistics(comment).replyCount)
            assertEquals(json, comment.extraMetadata)
        }
        for (json in listOf("{}", "bad json", "[]", """{"reply_count":null}""", """{"reply_count":-1}""", """{"child_comment_count":{},"comment_count":"bad"}""")) {
            assertNull(commentStatistics(record("zhihu", json, "comment")).replyCount)
        }
        assertNull(commentStatistics(record("reddit", """{"child_count":7,"reply_count":9}""", "comment")).replyCount)
    }

    @Test
    fun detailBadgesUseTypedCountsAndOmitInvalidNegativeCounts() {
        val r = record("zhihu", """{"voteup_count":-1,"comment_count":0,"ip_info":"北京","favlists_count":12057}""")
        assertEquals(listOf("0"), recordStatistics(r, StatisticsSurface.LIST).map { it.text })
        assertEquals(listOf("0", formatCount(12057), "北京"), recordStatistics(r, StatisticsSurface.DETAIL).map { it.text })
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
        assertEquals("豆瓣用户的动态", recordDisplayTitle(record("douban", type = "feed")))
        assertEquals("豆瓣用户的日记", recordDisplayTitle(record("douban", type = "note")))
        assertEquals("Sally的日记", recordDisplayTitle(record("douban", type = "note").apply {
            authorName = "Sally"
        }))
        assertEquals("Sally的动态", recordDisplayTitle(record("douban", type = "topic").apply {
            platformId = "douban_group_topic_1"
            authorName = "Sally"
        }))
        assertEquals("无标题", recordDisplayTitle(record("douban", """{"group_name":"小组"}""", type = "topic").apply {
            platformId = "douban_group_topic_2"
        }))
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
        val threads = record("threads", format = RecordBodyFormat.MARKDOWN).apply { content = "Hello\\! \\~world\\~ ![图片](https://example.com/a.jpg)" }
        assertEquals("Hello! ~world~ [图片]", recordPreviewText(threads))
        val reddit = record("reddit", format = RecordBodyFormat.MARKDOWN).apply { content = "**bold** [link](https://example.com) `code`" }
        assertEquals("bold link code", recordPreviewText(reddit))
        assertEquals("**bold** [link](https://example.com) `code`", reddit.content)
    }

    @Test
    fun snippetsKeepWindowAndSearchVisibleTextAcrossPlatforms() {
        val content = "a".repeat(50) + "MATCH" + "b".repeat(100)
        assertEquals("..." + content.substring(10, 130) + "...", contentSnippet(content, "match"))
        val reddit = record("reddit", format = RecordBodyFormat.MARKDOWN).apply { this.content = "[label](https://secret.example)" }
        assertNull(recordListPresentation(reddit, "secret").snippet)
        assertEquals("label", recordListPresentation(reddit, "LABEL").snippet)
        val other = record("weibo", format = RecordBodyFormat.MARKDOWN).apply { this.content = reddit.content }
        assertNull(recordListPresentation(other, "secret").snippet)
        assertEquals("label", recordListPresentation(other, "LABEL").snippet)
        assertNull(recordListPresentation(other, "").snippet)
    }

    @Test
    fun headerAndBodyPreserveDetailSpecificFallbacksAndSource() {
        val r = record(
                    "douban", """{"title_image":"cover","author_headline":"headline","star_count":0,"rating_value":4.5}""",
                    format = RecordBodyFormat.MARKDOWN_MATH
                )
        val header = recordHeaderPresentation(r)
        assertEquals("作者未知", header.author)
        assertEquals("", header.title)
        assertEquals("", header.titleImage)
        assertEquals("", header.authorHeadline)
        assertEquals(4.5, header.starCount, 0.0)
        assertEquals("zhihu_cover", recordHeaderPresentation(record("zhihu", """{"title_image":"zhihu_cover"}""")).titleImage)
        assertEquals("coolapk_cover", recordHeaderPresentation(record("coolapk", """{"message_cover":"coolapk_cover"}""")).titleImage)
        assertEquals(3.0, recordHeaderPresentation(record("douban", """{"star_count":3,"rating_value":4.5}""")).starCount, 0.0)
        r.content = "<b>raw</b>"
        assertEquals(r.content, recordBodyPresentation(r).content)
        assertEquals(MarkdownContentFormat.MARKDOWN, recordBodyPresentation(r).contentFormat)
        assertTrue(recordBodyPresentation(r).enableMath)
        val reddit = recordBodyPresentation(record("reddit", format = RecordBodyFormat.MARKDOWN))
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
