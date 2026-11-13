package me.melon.record

import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.logic.StatsCalculator
import me.melon.record.presentation.engagementCount
import me.melon.record.presentation.viewEngagementCount
import me.melon.record.ui.stats.appLabel
import me.melon.record.ui.stats.colorFromPackage
import me.melon.record.ui.theme.getPlatformThemeColor
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadsUiIntegrationTest {
    @Test
    fun threadsPreviewDoesNotExposeMarkdownEscapes() {
        val method = Class.forName("me.melon.record.ui.components.RecordItemKt")
            .getDeclaredMethod("getPreviewText", UniversalRecord::class.java)
        method.isAccessible = true
        val record = UniversalRecord(platform = "threads", content = "Hello\\! \\~world\\~ ![图片](https://example.com/image.jpg)")
        assertEquals("Hello! ~world~ [图片]", method.invoke(null, record))
    }

    @Test
    fun threadsRecordsResolveToHostPackageWithoutSourceApp() {
        for (type in listOf("thread", "comment")) {
            val record = UniversalRecord(platform = "threads", recordType = type, platformId = "threads_post_123")
            assertEquals("com.instagram.barcelona", StatsCalculator.resolveSourceApp(record))
        }
    }

    @Test
    fun platformAndPackageShareLabelAndTheme() {
        assertEquals("Threads", appLabel("threads"))
        assertEquals("Threads", appLabel("com.instagram.barcelona"))
        assertEquals(colorFromPackage("threads"), colorFromPackage("com.instagram.barcelona"))
        for (isDark in listOf(false, true)) {
            assertEquals(
                getPlatformThemeColor("threads", isDark),
                getPlatformThemeColor("com.instagram.barcelona", isDark)
            )
        }
    }

    @Test
    fun genericCountsAcceptCamelCaseAndExistingSchema() {
        val camel = JsonParser.parseString("""{"likeCount":12,"commentCount":34,"repostCount":56}""").asJsonObject
        val snake = JsonParser.parseString("""{"like_count":12,"comment_count":34,"repost_count":56}""").asJsonObject
        for (meta in listOf(camel, snake)) {
            assertEquals(12L, meta.engagementCount("like_count", "likeCount", "voteup_count"))
            assertEquals(34L, meta.engagementCount("reply_count", "comment_count", "commentCount"))
            assertEquals(56L, meta.engagementCount("retweet_count", "repost_count", "repostCount"))
        }
    }

    @Test
    fun threadsParserCountsIncludeViewsQuotesAndShares() {
        val meta = JsonParser.parseString("""{"like_count":11,"reply_count":22,"repost_count":33,"quote_count":44,"reshare_count":55,"view_count":66}""").asJsonObject
        assertEquals(11L, meta.engagementCount("like_count", "likeCount", "voteup_count"))
        assertEquals(22L, meta.engagementCount("reply_count", "comment_count", "commentCount"))
        assertEquals(33L, meta.engagementCount("retweet_count", "repost_count", "repostCount"))
        assertEquals(44L, meta.engagementCount("quote_count"))
        assertEquals(55L, meta.engagementCount("reshare_count"))
        assertEquals(66L, meta.viewEngagementCount("threads"))
    }

    @Test
    fun viewCountAliasesAreThreadsOnlyAndPreferParserKey() {
        val meta = JsonParser.parseString("""{"view_count":10,"views_count":20,"viewCount":30}""").asJsonObject
        assertEquals(10L, meta.viewEngagementCount("threads"))
        for (platform in listOf("twitter", "weibo", "zhihu", "douban")) {
            assertEquals(20L, meta.viewEngagementCount(platform))
            val parserOnly = JsonParser.parseString("""{"view_count":10}""").asJsonObject
            assertEquals(0L, parserOnly.viewEngagementCount(platform))
        }
        assertEquals(20L, JsonParser.parseString("""{"views_count":20}""").asJsonObject.viewEngagementCount("threads"))
        assertEquals(30L, JsonParser.parseString("""{"viewCount":30}""").asJsonObject.viewEngagementCount("threads"))
    }

    @Test
    fun threadsOptionalCountsHandleInvalidMissingAndZeroValues() {
        val meta = JsonParser.parseString("""{"view_count":null,"views_count":"bad","viewCount":"42","quote_count":{},"reshare_count":-1}""").asJsonObject
        assertEquals(42L, meta.viewEngagementCount("threads"))
        assertEquals(0L, meta.engagementCount("quote_count"))
        assertEquals(0L, meta.engagementCount("reshare_count"))
        val empty = JsonParser.parseString("{}").asJsonObject
        assertEquals(0L, empty.viewEngagementCount("threads"))
        assertEquals(0L, empty.engagementCount("quote_count"))
        assertEquals(0L, empty.engagementCount("reshare_count"))
        val zero = JsonParser.parseString("""{"view_count":0,"views_count":99,"quote_count":0,"reshare_count":0}""").asJsonObject
        assertEquals(0L, zero.viewEngagementCount("threads"))
        assertEquals(0L, zero.engagementCount("quote_count"))
        assertEquals(0L, zero.engagementCount("reshare_count"))
    }

    @Test
    fun invalidAndMissingCountsAreSafeAndLegacyKeysKeepPriority() {
        val meta = JsonParser.parseString("""{"like_count":null,"likeCount":"42","comment_count":{},"commentCount":7,"repostCount":-1,"reply_count":3}""").asJsonObject
        assertEquals(42L, meta.engagementCount("like_count", "likeCount"))
        assertEquals(7L, meta.engagementCount("comment_count", "commentCount"))
        assertEquals(3L, meta.engagementCount("reply_count", "comment_count", "commentCount"))
        assertEquals(0L, meta.engagementCount("repostCount"))
        assertEquals(0L, meta.engagementCount("views_count"))
    }
}
