package me.melon.record

import me.melon.record.data.model.UniversalRecord
import me.melon.record.logic.DistributionRange
import me.melon.record.logic.StatsCalculator
import me.melon.record.logic.WordCountUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsCalculatorTest {

    @Test
    fun testWeiboSourceApps() {
        val fallback = UniversalRecord(platform = "weibo", recordType = "status")
        val official = UniversalRecord(platform = "weibo", recordType = "status", sourceApp = "com.sina.weibo")
        val international = UniversalRecord(platform = "weibo", recordType = "comment", sourceApp = "com.weico.international")
        assertEquals("com.sina.weibo", StatsCalculator.resolveSourceApp(fallback))
        assertEquals("com.sina.weibo", StatsCalculator.resolveSourceApp(official))
        assertEquals("com.weico.international", StatsCalculator.resolveSourceApp(international))
        val stats = StatsCalculator.computeState(listOf(fallback, official, international))
        assertEquals(mapOf("com.sina.weibo" to 2, "com.weico.international" to 1), stats.topApps.toMap())
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
            UniversalRecord(id = 1, captureTime = todayMilli, title = "T1", content = "C1", platform = "zhihu"),
            UniversalRecord(id = 2, captureTime = todayMilli - 86400_000L, title = "T2", content = "C2", platform = "zhihu"),
            UniversalRecord(id = 3, captureTime = todayMilli - 86400_000L * 2, title = "T3", content = "C3", platform = "douban"),
            UniversalRecord(id = 4, captureTime = todayMilli - 86400_000L * 4, title = "T4", content = "C4", platform = "douban"),
            UniversalRecord(id = 5, captureTime = todayMilli - 86400_000L * 5, title = "T5", content = "C5", platform = "tieba")
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
            UniversalRecord(id = 1, captureTime = time10, platform = "zhihu"),
            UniversalRecord(id = 2, captureTime = time15_1, platform = "zhihu"),
            UniversalRecord(id = 3, captureTime = time15_2, platform = "douban")
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
            UniversalRecord(id = 1, captureTime = todayMilli, platform = "zhihu"),
            UniversalRecord(id = 2, captureTime = todayMilli - 86400_000L * 5, platform = "douban")
        )

        val stats = StatsCalculator.computeState(records, weeks = 52, zone = zone)
        assertEquals(30, stats.distribution30Days.bars.size)
        // Last bar should be today
        val lastBar = stats.distribution30Days.bars.last()
        assertEquals(1, lastBar.totalCount)
        assertEquals(1, lastBar.appCounts["com.zhihu.android"])
    }
}
