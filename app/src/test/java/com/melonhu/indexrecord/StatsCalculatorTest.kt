package com.melonhu.indexrecord

import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.logic.DistributionRange
import com.melonhu.indexrecord.logic.StatsCalculator
import com.melonhu.indexrecord.logic.WordCountUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsCalculatorTest {

    @Test
    fun repeatedCapturesKeepYesterdayBucketAndCountEverySnapshot() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.now(zone)
        fun noon(day: LocalDate) = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val yesterday = UniversalRecord(
            id = 7, platform = "zhihu", recordType = "article", platformId = "zhihu_article_1",
            captureTime = noon(today.minusDays(1)), content = "昨天正文"
        )
        val updated = yesterday.copy(captureTime = noon(today), content = "今天的新正文")
        val snapshots = listOf(
            com.melonhu.indexrecord.data.model.recordSnapshot("legacy:7", yesterday, legacy = true),
            com.melonhu.indexrecord.data.model.recordSnapshot("raw:1", updated),
            com.melonhu.indexrecord.data.model.recordSnapshot("raw:2", updated)
        )
        val records = com.melonhu.indexrecord.data.repository.assembleAllHistoricalRecords(snapshots, listOf(updated))
        val stats = StatsCalculator.computeState(records, zone = zone)
        val before = StatsCalculator.computeState(listOf(yesterday), zone = zone)
        assertEquals(3L, stats.totalRecordCount)
        assertEquals(2, stats.todayRecordCount)
        assertEquals(2, stats.currentStreakDays)
        assertEquals(2 * WordCountUtils.countText(updated.content), stats.todayWordCount)
        assertEquals(before.totalWordCount + stats.todayWordCount, stats.totalWordCount)
        val dayKey = today.minusDays(1).toString()
        assertEquals(
            before.distribution30Days.bars.single { it.key == dayKey },
            stats.distribution30Days.bars.single { it.key == dayKey }
        )
        assertEquals(2, stats.distribution30Days.bars.single { it.key == today.toString() }.totalCount)
    }

    @Test
    fun testWeiboSourceApps() {
        val fallback = UniversalRecord(platform = "weibo", recordType = "status")
        val official = UniversalRecord(platform = "weibo", recordType = "status", sourceApp = "com.sina.weibo")
        val international = UniversalRecord(platform = "weibo", recordType = "comment", sourceApp = "com.weico.international")
        assertEquals("com.sina.weibo", StatsCalculator.resolveSourceApp(fallback))
        assertEquals("com.sina.weibo", StatsCalculator.resolveSourceApp(official))
        assertEquals("com.weico.international", StatsCalculator.resolveSourceApp(international))
        val stats = StatsCalculator.computeState(listOf(fallback, official, international))
        assertEquals(mapOf("com.sina.weibo" to 2), stats.topApps.toMap())
    }

    @Test
    fun repliesDoNotAffectAnyStatisticsForPostOnlyPlatforms() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.now(zone)
        val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val postTypes = mapOf("reddit" to "post", "tieba" to "lou", "twitter" to "tweet", "weibo" to "status", "threads" to "thread", "zhihu" to "answer", "douban" to "topic")
        val posts = postTypes.map { (platform, type) ->
            UniversalRecord(platform = platform, recordType = type, captureTime = now, content = "main post")
        }
        val replies = postTypes.keys.flatMap { platform ->
            listOf(0L, 1L, 2L).map { daysAgo ->
                UniversalRecord(platform = platform, recordType = "comment", captureTime = now - daysAgo * 86400_000L, content = "reply text")
            }
        } + UniversalRecord(platform = "tieba", recordType = "floor", captureTime = now, content = "floor reply")
        val expected = StatsCalculator.computeState(posts, zone = zone)
        assertEquals(7L, expected.totalRecordCount)
        assertEquals(7, expected.todayRecordCount)
        assertEquals(14L, expected.totalWordCount)
        assertEquals(expected, StatsCalculator.computeState(posts + replies, zone = zone))
        assertEquals(StatsCalculator.computeState(emptyList(), zone = zone), StatsCalculator.computeState(replies, zone = zone))
    }

    @Test
    fun optInIncludesRepliesInEveryMetricAndCanBeTurnedOffAgain() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.now(zone)
        val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val post = UniversalRecord(platform = "threads", recordType = "thread", captureTime = now, content = "main post")
        val replies = listOf("zhihu", "douban", "threads", "reddit", "twitter", "weibo", "tieba").mapIndexed { index, platform ->
            UniversalRecord(
                platform = platform, recordType = if (platform == "tieba") "floor" else "comment",
                captureTime = now - index * 86400_000L, content = "reply text",
                extraMetadata = """{"reply_count":99999,"like_count":88888}"""
            )
        }
        val all = listOf(post) + replies
        val primaryOnly = StatsCalculator.computeState(all, zone = zone)
        val included = StatsCalculator.computeState(all, zone = zone, includeRepliesAndComments = true)
        assertEquals(1L, primaryOnly.totalRecordCount)
        assertEquals(8L, included.totalRecordCount)
        assertEquals(16L, included.totalWordCount)
        assertEquals(2, included.todayRecordCount)
        assertEquals(4L, included.todayWordCount)
        assertEquals(7, included.currentStreakDays)
        assertEquals(7, included.maxStreakDays)
        assertEquals(8, included.distribution30Days.bars.sumOf { it.totalCount })
        assertEquals(8, included.distributionFullYear.bars.sumOf { it.totalCount })
        assertEquals(8, included.distributionAllTime.bars.sumOf { it.totalCount })
        assertEquals(8, included.hourlyDistribution.sumOf { it.totalCount })
        assertEquals(8, included.columns.flatten().sumOf { it.appCounts.values.sum() })
        assertEquals(8, included.topApps.sumOf { it.second })
        assertEquals(primaryOnly, StatsCalculator.computeState(all, zone = zone, includeRepliesAndComments = false))
        assertEquals("""{"reply_count":99999,"like_count":88888}""", replies.first().extraMetadata)
    }

    @Test
    fun primaryEligibilityIncludesAllSupportedPrimaryTypesButNotUnknownTypes() {
        val types = mapOf(
            "zhihu" to listOf("question", "answer", "article", "pin"),
            "douban" to listOf("topic", "review", "interest", "subject"),
            "threads" to listOf("thread"), "reddit" to listOf("post"),
            "twitter" to listOf("tweet"), "weibo" to listOf("status"), "tieba" to listOf("lou")
        )
        val primary = types.flatMap { (platform, recordTypes) ->
            recordTypes.map { UniversalRecord(platform = platform, recordType = it) }
        }
        val unknown = listOf(UniversalRecord(platform = "unknown", recordType = "post"), UniversalRecord(platform = "zhihu", recordType = "unknown"))
        assertEquals(primary.size.toLong(), StatsCalculator.computeState(primary + unknown).totalRecordCount)
    }

    @Test
    fun testWordCountUtils() {
        val count1 = WordCountUtils.countWords("标题", "这是正文内容。")
        // 标(1) 题(1) 这(1) 是(1) 正(1) 文(1) 内(1) 容(1) -> 8
        assertEquals(8L, count1)

        val countEnglish = WordCountUtils.countWords("Title", "Hello world this is a test")
        // Title(1) + Hello world this is a test(6) = 7
        assertEquals(7L, countEnglish)

        val countMarkdown = WordCountUtils.countWords(
            "测试",
            "这是正文 ![图片说明](https://pic.com/a.jpg) 带有链接 [点击查看](https://url.com) <p>段落</p>"
        )
        // 测(1) 试(1) 这(1) 是(1) 正(1) 文(1) 带(1) 有(1) 链(1) 接(1) 点(1) 击(1) 查(1) 看(1) 段(1) 落(1) = 16
        assertEquals(16L, countMarkdown)
    }

    @Test
    fun testStreakCalculation() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayMilli = today.atStartOfDay(zone).toInstant().toEpochMilli() + 3600_000L

        // Day 0 (today), Day -1 (yesterday), Day -2 (2 days ago), Day -4 (4 days ago), Day -5 (5 days ago)
        val records = listOf(
            UniversalRecord(id = 1, captureTime = todayMilli, title = "T1", content = "C1", platform = "zhihu", recordType = "answer"),
            UniversalRecord(id = 2, captureTime = todayMilli - 86400_000L, title = "T2", content = "C2", platform = "zhihu", recordType = "answer"),
            UniversalRecord(id = 3, captureTime = todayMilli - 86400_000L * 2, title = "T3", content = "C3", platform = "douban", recordType = "topic"),
            UniversalRecord(id = 4, captureTime = todayMilli - 86400_000L * 4, title = "T4", content = "C4", platform = "douban", recordType = "topic"),
            UniversalRecord(id = 5, captureTime = todayMilli - 86400_000L * 5, title = "T5", content = "C5", platform = "tieba", recordType = "lou")
        )

        val stats = StatsCalculator.computeState(records, weeks = 52, zone = zone)
        assertEquals(5L, stats.totalRecordCount)
        assertEquals(1, stats.todayRecordCount)
        assertEquals(3, stats.currentStreakDays) // today, yesterday, 2 days ago -> 3
        assertEquals(3, stats.maxStreakDays) // streak 3 vs streak 2 -> max is 3
    }

    @Test
    fun testHourlyDistribution() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // Hour 10 and Hour 15
        val time10 = today.atTime(10, 30).atZone(zone).toInstant().toEpochMilli()
        val time15_1 = today.atTime(15, 10).atZone(zone).toInstant().toEpochMilli()
        val time15_2 = today.atTime(15, 45).atZone(zone).toInstant().toEpochMilli()

        val records = listOf(
            UniversalRecord(id = 1, captureTime = time10, platform = "zhihu", recordType = "answer"),
            UniversalRecord(id = 2, captureTime = time15_1, platform = "zhihu", recordType = "answer"),
            UniversalRecord(id = 3, captureTime = time15_2, platform = "douban", recordType = "topic")
        )

        val stats = StatsCalculator.computeState(records, weeks = 52, zone = zone)
        assertEquals(24, stats.hourlyDistribution.size)
        assertEquals(1, stats.hourlyDistribution[10].totalCount)
        assertEquals(2, stats.hourlyDistribution[15].totalCount)
        assertEquals(1, stats.hourlyDistribution[15].appCounts["com.zhihu.android"])
        assertEquals(1, stats.hourlyDistribution[15].appCounts["com.douban.frodo"])
        assertTrue(stats.peakHourRange.contains("15:00"))
    }

    @Test
    fun test30DaysDistribution() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayMilli = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        val records = listOf(
            UniversalRecord(id = 1, captureTime = todayMilli, platform = "zhihu", recordType = "answer"),
            UniversalRecord(id = 2, captureTime = todayMilli - 86400_000L * 5, platform = "douban", recordType = "topic")
        )

        val stats = StatsCalculator.computeState(records, weeks = 52, zone = zone)
        assertEquals(30, stats.distribution30Days.bars.size)
        // Last bar should be today
        val lastBar = stats.distribution30Days.bars.last()
        assertEquals(1, lastBar.totalCount)
        assertEquals(1, lastBar.appCounts["com.zhihu.android"])
    }
}
