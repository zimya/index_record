package com.melonhu.indexrecord

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.logic.StatsCalculator
import com.melonhu.indexrecord.presentation.PlatformMetadataAdapters
import com.melonhu.indexrecord.presentation.recordPreviewText
import org.junit.Assert.assertNull
import com.melonhu.indexrecord.ui.stats.appLabel
import com.melonhu.indexrecord.ui.stats.colorFromPackage
import com.melonhu.indexrecord.ui.theme.getPlatformThemeColor
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadsUiIntegrationTest {
    @Test
    fun threadsPreviewDoesNotExposeMarkdownEscapes() {
        val record = UniversalRecord(
                    platform = "threads", content = "Hello\\! \\~world\\~ ![图片](https://example.com/image.jpg)",
                    contentFormat = RecordBodyFormat.MARKDOWN.storageValue
                )
        assertEquals("Hello! ~world~ [图片]", recordPreviewText(record))
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
    fun threadsAdapterAcceptsCapturedAliases() {
        val camel = JsonParser.parseString("""{"likeCount":12,"commentCount":34,"repostCount":56}""").asJsonObject
        val snake = JsonParser.parseString("""{"like_count":12,"comment_count":34,"repost_count":56}""").asJsonObject
        for (meta in listOf(camel, snake)) {
            val counts = PlatformMetadataAdapters(meta.toString(), "threads").engagement
            assertEquals(12L, counts.likeCount)
            assertEquals(34L, counts.replyCount)
            assertEquals(56L, counts.repostCount)
        }
    }

    @Test
    fun threadsParserCountsIncludeViewsQuotesAndShares() {
        val meta = JsonParser.parseString("""{"like_count":11,"reply_count":22,"repost_count":33,"quote_count":44,"reshare_count":55,"view_count":66}""").asJsonObject
        val counts = PlatformMetadataAdapters(meta.toString(), "threads").engagement
        assertEquals(11L, counts.likeCount)
        assertEquals(22L, counts.replyCount)
        assertEquals(33L, counts.repostCount)
        assertEquals(44L, counts.quoteCount)
        assertEquals(55L, counts.shareCount)
        assertEquals(66L, counts.viewCount)
    }

    @Test
    fun viewCountAliasesAreThreadsOnlyAndPreferParserKey() {
        val meta = JsonParser.parseString("""{"view_count":10,"views_count":20,"viewCount":30}""").asJsonObject
        assertEquals(10L, PlatformMetadataAdapters(meta.toString(), "threads").engagement.viewCount)
        for (platform in listOf("twitter", "weibo")) {
            assertEquals(20L, PlatformMetadataAdapters(meta.toString(), platform).engagement.viewCount)
            assertNull(PlatformMetadataAdapters("""{"view_count":10}""", platform).engagement.viewCount)
        }
        for (platform in listOf("zhihu", "douban")) {
            assertNull(PlatformMetadataAdapters(meta.toString(), platform).engagement.viewCount)
        }
        assertEquals(20L, PlatformMetadataAdapters("""{"views_count":20}""", "threads").engagement.viewCount)
        assertEquals(30L, PlatformMetadataAdapters("""{"viewCount":30}""", "threads").engagement.viewCount)
    }

    @Test
    fun threadsOptionalCountsHandleInvalidMissingAndZeroValues() {
        val meta = JsonParser.parseString("""{"view_count":null,"views_count":"bad","viewCount":"42","quote_count":{},"reshare_count":-1}""").asJsonObject
        val counts = PlatformMetadataAdapters(meta.toString(), "threads").engagement
        assertEquals(42L, counts.viewCount)
        assertNull(counts.quoteCount)
        assertNull(counts.shareCount)
        val empty = PlatformMetadataAdapters("{}", "threads").engagement
        assertNull(empty.viewCount)
        assertNull(empty.quoteCount)
        assertNull(empty.shareCount)
        val zero = PlatformMetadataAdapters("""{"view_count":0,"views_count":99,"quote_count":0,"reshare_count":0}""", "threads").engagement
        assertEquals(0L, zero.viewCount)
        assertEquals(0L, zero.quoteCount)
        assertEquals(0L, zero.shareCount)
    }

    @Test
    fun invalidAndMissingCountsAreSafeAndLegacyKeysKeepPriority() {
        val meta = JsonParser.parseString("""{"like_count":null,"likeCount":"42","comment_count":{},"commentCount":7,"repostCount":-1,"reply_count":3}""").asJsonObject
        val counts = PlatformMetadataAdapters(meta.toString(), "threads").engagement
        assertEquals(42L, counts.likeCount)
        assertEquals(3L, counts.replyCount)
        assertNull(counts.repostCount)
        assertNull(counts.viewCount)
    }
}
