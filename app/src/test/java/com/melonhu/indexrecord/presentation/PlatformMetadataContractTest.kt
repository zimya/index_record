package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class PlatformMetadataContractTest {
    private fun record(platform: String, json: String, type: String = "comment") =
        UniversalRecord(platform = platform, recordType = type, extraMetadata = json)

    @Test fun unsupportedFieldsNeverAcquireMeaningOnAnotherPlatform() {
        val json = """{"voteup_count":7,"agree_count":8,"score":-9,"like_count":10,"child_comment_count":11,"retweet_count":12,"bookmark_count":13,"dislike_count":14,"view_count":15}"""
        assertEquals(7L, PlatformMetadataAdapters(json, "zhihu").engagement.likeCount)
        assertEquals(10L, commentStatistics(record("zhihu", json)).likeCount)
        assertEquals(11L, commentStatistics(record("zhihu", json)).replyCount)
        assertEquals(8L, commentStatistics(record("tieba", json)).likeCount)
        assertNull(commentStatistics(record("tieba", json)).replyCount)
        assertNull(PlatformMetadataAdapters(json, "threads").engagement.repostCount)
        assertNull(PlatformMetadataAdapters(json, "threads").engagement.bookmarkCount)
        assertEquals(-9L, commentStatistics(record("reddit", json)).likeCount)
        assertNull(commentStatistics(record("reddit", json)).dislikeCount)
        assertTrue(recordStatistics(record("future", json), StatisticsSurface.DETAIL).isEmpty())
    }

    @Test fun zeroUnknownAndMalformedCountsStayDistinctForEveryAdapter() {
        for ((platform, key) in mapOf("zhihu" to "like_count", "douban" to "like_count", "tieba" to "agree_count", "twitter" to "like_count", "weibo" to "like_count", "threads" to "like_count", "reddit" to "score")) {
            assertNull(platform, commentStatistics(record(platform, "{}")).likeCount)
            assertEquals(platform, 0L, commentStatistics(record(platform, """{"$key":0}""")).likeCount)
            for (value in listOf("null", "{}", "[]", "true", "1.5", "9223372036854775808", "\"bad\"")) {
                assertNull("$platform: $value", commentStatistics(record(platform, """{"$key":$value}""")).likeCount)
            }
            for (json in listOf("", "invalid", "[]", "null", "true")) {
                assertTrue(recordStatistics(record(platform, json), StatisticsSurface.DETAIL).isEmpty())
            }
        }
    }

    @Test fun platformReplyFieldsAreNotInterchangeable() {
        val json = """{"child_comment_count":3,"reply_count":4,"comment_count":5,"child_count":6}"""
        val expected = mapOf("zhihu" to 3L, "douban" to 5L, "tieba" to 4L, "threads" to 4L, "twitter" to 4L, "weibo" to 5L, "reddit" to 5L)
        expected.forEach { (platform, replies) -> assertEquals(platform, replies, commentStatistics(record(platform, json)).replyCount) }
        assertNull(commentStatistics(record("zhihu", """{"comment_count":99}""")).replyCount)
    }

    @Test fun statisticsHaveSemanticLabelsAndUnroundedAccessibleCounts() {
        val zhihu = recordStatistics(record("zhihu", """{"voteup_count":12057,"comment_count":0}""", "answer"), StatisticsSurface.DETAIL)
        assertEquals("赞同数 12057", zhihu.first().accessibilityText)
        assertEquals("平台评论数 0", zhihu.last().accessibilityText)
        val reddit = recordStatistics(record("reddit", """{"score":-23,"comment_count":0}"""), StatisticsSurface.LIST)
        assertEquals("-23", reddit.first().text)
        assertEquals("得分 -23", reddit.first().accessibilityText)
        assertEquals("平台回复数 0", reddit.last().accessibilityText)
        assertEquals("发布地 北京", RecordStatistic(StatisticKind.LOCATION, "北京").accessibilityText)
        assertEquals("有用", recordStatistics(record("douban", """{"useful_count":3}""", "review"), StatisticsSurface.DETAIL).single().label)
    }

    @Test fun dislikeCountsReachEveryStatisticsSurfaceWithoutInventingZeros() {
        for (value in listOf(0L, 17L, 12057L)) {
            val comment = record("zhihu", """{"like_count":0,"dislike_count":$value,"child_comment_count":0}""")
            assertEquals(value, commentStatistics(comment).dislikeCount)
            for (surface in StatisticsSurface.entries) {
                val statistic = recordStatistics(comment, surface).single { it.kind == StatisticKind.DISLIKE }
                assertEquals(value, statistic.value)
                assertEquals(formatCount(value), statistic.text)
                assertEquals("点踩数 $value", statistic.accessibilityText)
            }
        }
        for (json in listOf("{}", """{"dislike_count":null}""", """{"dislike_count":-1}""", """{"dislike_count":1.5}""")) {
            assertNull(commentStatistics(record("zhihu", json)).dislikeCount)
            assertTrue(recordStatistics(record("zhihu", json), StatisticsSurface.DETAIL).none { it.kind == StatisticKind.DISLIKE })
        }
        assertTrue(recordStatistics(record("threads", """{"dislike_count":17}"""), StatisticsSurface.DETAIL).isEmpty())
    }

    @Test fun accessibilityNamesEveryQuantityWithoutConfusingScoreOrLocation() {
        val labels = mapOf(
            StatisticKind.LIKE to "点赞数", StatisticKind.DISLIKE to "点踩数",
            StatisticKind.REPLY to "平台回复数", StatisticKind.REPOST to "转发数",
            StatisticKind.VIEW to "浏览数", StatisticKind.SHARE to "分享数",
            StatisticKind.QUOTE to "引用数", StatisticKind.BOOKMARK to "书签数", StatisticKind.FAVORITE to "收藏数"
        )
        labels.forEach { (kind, label) ->
            assertEquals("$label 12057", RecordStatistic(kind, "1.2万", value = 12057).accessibilityText)
        }
        assertEquals("得分 -23", RecordStatistic(StatisticKind.LIKE, "-23", "得分", -23).accessibilityText)
        assertEquals("有用数 3", RecordStatistic(StatisticKind.LIKE, "3", "有用", 3).accessibilityText)
        assertEquals("发布地 北京", RecordStatistic(StatisticKind.LOCATION, "北京").accessibilityText)
    }

    @Test fun tiebaUsesDeclaredBodyFormatAndSharedUnknownAndStatisticsContracts() {
        val floor = record("tieba", """{"agree_count":0,"reply_count":0,"favlists_count":0}""", "floor").apply {
            content = "<b>literal</b> **not markdown**"
            contentFormat = "plain_text"
            authorName = "  "
            publishTime = 0
        }
        val body = recordBodyPresentation(floor)
        assertEquals(com.melonhu.indexrecord.markdown.MarkdownContentFormat.PLAIN_TEXT, body.contentFormat)
        assertFalse(body.enableMath)
        assertEquals(floor.content, body.content)
        assertEquals(floor.content, recordPreviewText(floor))
        assertEquals("作者未知", displayAuthor(floor.authorName))
        assertEquals("发布时间未知", displayPublishTime(floor.publishTime))
        val stats = recordStatistics(floor, StatisticsSurface.DETAIL)
        assertEquals(listOf("赞同数 0", "平台回复数 0", "收藏数 0"), stats.map { it.accessibilityText })
        val html = floor.copy(content = "<p>正文 &amp; 预览</p>", contentFormat = "html")
        assertEquals(com.melonhu.indexrecord.markdown.MarkdownContentFormat.HTML, recordBodyPresentation(html).contentFormat)
        assertEquals("正文 & 预览", recordPreviewText(html))
    }

    @Test fun listUsesSharedVocabularyAndUnknownMetadata() {
        val twitter = recordListPresentation(record("twitter", "{}", "tweet").apply { captureTime = 0 }, "")
        assertEquals("X · 推文", twitter.badgeText)
        assertEquals("作者未知", twitter.author)
        assertEquals("阅读时间未知", twitter.capturedAt)
        assertEquals("Threads · 帖子", PlatformCatalog.recordLabel("threads", "thread"))
        assertEquals("贴吧 · 楼中楼", PlatformCatalog.recordLabel("tieba", "comment"))
        assertEquals("Reddit · 回复", PlatformCatalog.recordLabel("reddit", "comment"))
        assertEquals("平台未知 · 类型未知", PlatformCatalog.recordLabel("", ""))
    }

    @Test fun commonFormattingHonorsTimezoneAndNeverInventsEpochDates() {
        assertEquals("作者未知", displayAuthor(" \n "))
        assertEquals("Alice", displayAuthor(" Alice "))
        assertEquals("时间未知", displayTime(0))
        assertEquals("阅读时间未知", displayCaptureTime(-1))
        assertEquals("发布时间未知", displayPublishTime(0))
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("1970-01-01 00:00", displayTime(1))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("阅读于 1970-01-01 08:00", displayCaptureTime(1))
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    @Test fun namedAdaptersDoNotExposeForeignPlatformFields() {
        val json = """{"thread_title":"帖子","forum_name":"吧名","floor":3,"excerpt":"摘要","intro":"简介","card_subtitle":"副标题","title_image":"封面","author_headline":"签名","star_count":3,"subject_id":"42","subject_title":"条目","ip_info":"北京","ip_address":"上海","subreddit":"android"}"""
        val platforms = listOf("zhihu", "douban", "tieba", "reddit", "twitter", "weibo", "threads", "future", "")
        for (platform in platforms) {
            val metadata = PlatformMetadataAdapters(json, platform)
            assertEquals(platform, platform == "zhihu", metadata.zhihu != null)
            assertEquals(platform, platform == "douban", metadata.douban != null)
            assertEquals(platform, platform == "tieba", metadata.tieba != null)
            assertEquals(platform, platform == "reddit", metadata.reddit != null)
            assertEquals(platform, if (platform == "tieba") "帖子" else "", metadata.tieba?.threadTitle.orEmpty())
            assertEquals(platform, if (platform == "tieba") "吧名" else "", metadata.tieba?.forumName.orEmpty())
            assertEquals(platform, if (platform == "tieba") 3L else null, metadata.tieba?.floorNumber)
            assertEquals(platform, if (platform == "zhihu") "摘要" else "", metadata.zhihu?.excerpt.orEmpty())
            assertEquals(platform, if (platform == "zhihu") "签名" else "", metadata.zhihu?.authorHeadline.orEmpty())
            assertEquals(platform, if (platform == "douban") "简介" else "", metadata.douban?.intro.orEmpty())
            assertEquals(platform, if (platform == "douban") "副标题" else "", metadata.douban?.cardSubtitle.orEmpty())
            assertEquals(platform, if (platform == "douban") "42" else "", metadata.douban?.subjectId.orEmpty())
            assertEquals(platform, if (platform == "douban") "条目" else "", metadata.douban?.subjectTitle.orEmpty())
            assertEquals(platform, if (platform in setOf("zhihu", "douban")) "封面" else "", metadata.zhihu?.titleImage ?: metadata.douban?.titleImage.orEmpty())
            assertEquals(platform, if (platform == "douban") 3.0 else 0.0, metadata.douban?.starCount ?: 0.0, 0.0)
            assertEquals(platform, when (platform) {
                "zhihu", "douban" -> "北京"
                "tieba" -> "上海"
                else -> null
            }, metadata.zhihu?.ipInfo ?: metadata.douban?.ipInfo ?: metadata.tieba?.ipAddress)
        }
        // The same spelling must not grant a foreign field meaning on another platform.
        assertEquals("", PlatformMetadataAdapters("""{"ip_info":"北京"}""", "tieba").tieba?.ipAddress)
        assertNull(PlatformMetadataAdapters("""{"ip_address":"上海"}""", "zhihu").zhihu?.ipInfo)
        assertNull(PlatformMetadataAdapters("""{"ip_address":"上海"}""", "douban").douban?.ipInfo)
    }

    @Test fun adaptersAcceptOneParsedObjectAndMaterializeReadOnlyValues() {
        val values = parseMetadataObject("""{"score":-23,"comment_count":0,"subreddit":"android","thread_title":"帖子","floor":3,"excerpt":"摘要","star_count":3,"subject_id":"42"}""")
        val reddit = RedditMetadata(values)
        val zhihu = ZhihuRecordMetadata(values)
        val douban = DoubanRecordMetadata(values)
        val tieba = com.melonhu.indexrecord.data.model.TiebaMetadata(values)
        val adapters = PlatformMetadataAdapters(values, "reddit")
        values.addProperty("score", 999)
        values.addProperty("subreddit", "changed")
        values.addProperty("excerpt", "changed")
        values.addProperty("star_count", 5)
        values.addProperty("thread_title", "changed")
        assertEquals(-23L, reddit.score)
        assertEquals("android", reddit.subreddit)
        assertEquals(-23L, adapters.reddit?.score)
        assertEquals("摘要", zhihu.excerpt)
        assertEquals(3.0, douban.starCount, 0.0)
        assertEquals("帖子", tieba.threadTitle)
        assertEquals(3L, tieba.floorNumber)
        assertNull(adapters.zhihu)
        assertNull(adapters.douban)
        assertNull(adapters.tieba)
    }

    @Test fun malformedNonInteractionFieldsStayEmptyInTheirOwnAdapters() {
        for (json in listOf("invalid", "[]", "null", "true", """{"excerpt":{},"author_headline":12,"title_image":false,"intro":[],"subject_id":true,"subject_title":12,"star_count":"NaN","rating_value":"Infinity"}""")) {
            val zhihu = requireNotNull(PlatformMetadataAdapters(json, "zhihu").zhihu)
            assertEquals("", zhihu.excerpt)
            assertEquals("", zhihu.authorHeadline)
            assertEquals("", zhihu.titleImage)
            val douban = requireNotNull(PlatformMetadataAdapters(json, "douban").douban)
            assertEquals("", douban.intro)
            assertEquals("", douban.subjectId)
            assertEquals("", douban.subjectTitle)
            assertEquals("", douban.titleImage)
            assertEquals(0.0, douban.starCount, 0.0)
        }
    }

    @Test fun domainMetadataIsPlatformScopedAndDefensive() {
        val json = """{"thread_title":"帖子","floor":3,"intro":"简介","excerpt":"摘要","subject_id":"42","star_count":3,"rating_value":4,"ip_info":"北京"}"""
        assertNull(PlatformMetadataAdapters(json, "threads").tieba)
        assertNull(PlatformMetadataAdapters(json, "tieba").douban)
        assertNull(PlatformMetadataAdapters(json, "douban").zhihu)
        assertNull(PlatformMetadataAdapters(json, "zhihu").douban)
        assertNull(PlatformMetadataAdapters(json, "twitter").douban)
        assertEquals(3.0, requireNotNull(PlatformMetadataAdapters(json, "douban").douban).starCount, 0.0)
        assertNull(PlatformMetadataAdapters("""{"floor":1.5}""", "tieba").tieba?.floorNumber)
        assertEquals("", PlatformMetadataAdapters("""{"intro":{},"subject_title":12}""", "douban").douban?.intro)
    }
}
