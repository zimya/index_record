package me.melon.record.logic

import me.melon.record.data.model.UniversalRecord
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class DistributionRange(val label: String) {
    LAST_30_DAYS("近30天"),
    FULL_YEAR("全年"),
    ALL_TIME("全部时间")
}

data class BarData(
    val key: String,
    val label: String,
    val fullLabel: String,
    val appCounts: Map<String, Int>,
    val totalCount: Int
)

data class DistributionChartData(
    val range: DistributionRange,
    val bars: List<BarData>,
    val maxCount: Int
)

data class DayCell(
    val date: LocalDate,
    val appCounts: Map<String, Int>
)

data class ComputedStats(
    val todayWordCount: Long = 0,
    val todayRecordCount: Int = 0,
    val totalRecordCount: Long = 0,
    val totalWordCount: Long = 0,
    val currentStreakDays: Int = 0,
    val maxStreakDays: Int = 0,

    val distribution30Days: DistributionChartData = DistributionChartData(DistributionRange.LAST_30_DAYS, emptyList(), 0),
    val distributionFullYear: DistributionChartData = DistributionChartData(DistributionRange.FULL_YEAR, emptyList(), 0),
    val distributionAllTime: DistributionChartData = DistributionChartData(DistributionRange.ALL_TIME, emptyList(), 0),

    val hourlyDistribution: List<BarData> = emptyList(),
    val maxHourlyCount: Int = 0,
    val peakHourRange: String = "",

    val weeks: Int = 52,
    val columns: List<List<DayCell>> = emptyList(),
    val maxTotalPerDay: Int = 0,
    val topApps: List<Pair<String, Int>> = emptyList()
)

object WordCountUtils {
    private val MARKDOWN_IMAGE_REGEX = Regex("""!\[.*?\]\(.*?\)""")
    private val MARKDOWN_LINK_REGEX = Regex("""\[(.*?)\]\(.*?\)""")
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val LATIN_WORD_REGEX = Regex("""[a-zA-Z0-9_-]+""")

    fun countWords(title: String?, content: String?): Long {
        var count = 0L
        if (!title.isNullOrBlank()) {
            count += countText(title)
        }
        if (!content.isNullOrBlank()) {
            count += countText(content)
        }
        return count
    }

    fun countText(raw: String): Long {
        if (raw.isBlank()) return 0L
        // 1. Remove markdown images
        var text = MARKDOWN_IMAGE_REGEX.replace(raw, " ")
        // 2. Keep markdown link text, remove link URL
        text = MARKDOWN_LINK_REGEX.replace(text, "$1")
        // 3. Remove HTML tags
        text = HTML_TAG_REGEX.replace(text, " ")

        var cjkCount = 0L
        val nonCjkBuilder = StringBuilder(text.length)

        for (ch in text) {
            val code = ch.code
            val isCjk = (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF) ||
                    (code in 0x3040..0x309F) || (code in 0x30A0..0x30FF) ||
                    (code in 0xAC00..0xD7AF)
            if (isCjk) {
                cjkCount++
                nonCjkBuilder.append(' ')
            } else {
                nonCjkBuilder.append(ch)
            }
        }

        val latinWordCount = LATIN_WORD_REGEX.findAll(nonCjkBuilder.toString()).count().toLong()
        return cjkCount + latinWordCount
    }
}

object StatsCalculator {

    fun resolveSourceApp(record: UniversalRecord): String {
        val direct = record.sourceApp?.trim() ?: ""
        if (direct.isNotEmpty()) return direct
        return when (record.platform.trim().lowercase()) {
            "zhihu" -> "com.zhihu.android"
            "douban" -> "com.douban.frodo"
            "tieba" -> "com.baidu.tieba"
            "xiaohongshu", "xhs" -> "com.xingin.xhs"
            "twitter", "x" -> "com.twitter.android"
            "weibo" -> "com.sina.weibo"
            else -> if (record.platform.isNotBlank()) record.platform else "unknown"
        }
    }

    fun computeState(
        records: List<UniversalRecord>,
        weeks: Int = 52,
        zone: ZoneId = ZoneId.systemDefault()
    ): ComputedStats {
        val today = LocalDate.now(zone)
        val currentMonth = YearMonth.now(zone)

        var todayWordCount = 0L
        var todayRecordCount = 0
        var totalWordCount = 0L
        val activeDates = HashSet<LocalDate>()

        val hourlyMap = Array(24) { HashMap<String, Int>() }
        val dailyMap = HashMap<LocalDate, HashMap<String, Int>>()
        val monthlyMap = HashMap<YearMonth, HashMap<String, Int>>()
        val perAppTotalAll = HashMap<String, Int>()

        var earliestDate: LocalDate? = null
        var latestDate: LocalDate? = null

        for (r in records) {
            val pkg = resolveSourceApp(r)
            val captureInstant = Instant.ofEpochMilli(r.captureTime)
            val dateTime = captureInstant.atZone(zone)
            val date = dateTime.toLocalDate()
            val hour = dateTime.hour.coerceIn(0, 23)
            val ym = YearMonth.from(date)

            val words = WordCountUtils.countWords(r.title, r.content)
            totalWordCount += words

            if (date == today) {
                todayWordCount += words
                todayRecordCount++
            }

            activeDates.add(date)
            if (earliestDate == null || date.isBefore(earliestDate)) earliestDate = date
            if (latestDate == null || date.isAfter(latestDate)) latestDate = date

            perAppTotalAll[pkg] = (perAppTotalAll[pkg] ?: 0) + 1

            val hMap = hourlyMap[hour]
            hMap[pkg] = (hMap[pkg] ?: 0) + 1

            val dMap = dailyMap.getOrPut(date) { HashMap() }
            dMap[pkg] = (dMap[pkg] ?: 0) + 1

            val mMap = monthlyMap.getOrPut(ym) { HashMap() }
            mMap[pkg] = (mMap[pkg] ?: 0) + 1
        }

        // 1. Current streak and Max streak
        val currentStreak = if (activeDates.contains(today)) {
            var count = 0
            var d = today
            while (activeDates.contains(d)) {
                count++
                d = d.minusDays(1)
            }
            count
        } else if (activeDates.contains(today.minusDays(1))) {
            var count = 0
            var d = today.minusDays(1)
            while (activeDates.contains(d)) {
                count++
                d = d.minusDays(1)
            }
            count
        } else {
            0
        }

        val sortedDates = activeDates.sorted()
        var maxStreak = 0
        var currentRun = 0
        var prevDate: LocalDate? = null
        for (d in sortedDates) {
            if (prevDate == null || d == prevDate.plusDays(1)) {
                currentRun++
            } else {
                currentRun = 1
            }
            if (currentRun > maxStreak) {
                maxStreak = currentRun
            }
            prevDate = d
        }

        // 2. 条目统计分布图: 近30天
        val start30 = today.minusDays(29)
        val bars30 = ArrayList<BarData>(30)
        var maxCount30 = 0
        for (i in 0 until 30) {
            val d = start30.plusDays(i.toLong())
            val appCounts = dailyMap[d] ?: emptyMap()
            val total = appCounts.values.sum()
            if (total > maxCount30) maxCount30 = total
            bars30.add(
                BarData(
                    key = d.toString(),
                    label = d.format(DateTimeFormatter.ofPattern("MM-dd")),
                    fullLabel = d.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")),
                    appCounts = appCounts,
                    totalCount = total
                )
            )
        }
        val distribution30Days = DistributionChartData(DistributionRange.LAST_30_DAYS, bars30, maxCount30)

        // 2. 条目统计分布图: 全年 (近12个月)
        val startFullYear = currentMonth.minusMonths(11)
        val barsFullYear = ArrayList<BarData>(12)
        var maxCountFullYear = 0
        for (i in 0 until 12) {
            val ym = startFullYear.plusMonths(i.toLong())
            val appCounts = monthlyMap[ym] ?: emptyMap()
            val total = appCounts.values.sum()
            if (total > maxCountFullYear) maxCountFullYear = total
            barsFullYear.add(
                BarData(
                    key = ym.toString(),
                    label = "${ym.monthValue}月",
                    fullLabel = "${ym.year}年${ym.monthValue}月",
                    appCounts = appCounts,
                    totalCount = total
                )
            )
        }
        val distributionFullYear = DistributionChartData(DistributionRange.FULL_YEAR, barsFullYear, maxCountFullYear)

        // 2. 条目统计分布图: 全部时间 (按月)
        val startAllTime = if (earliestDate != null) {
            YearMonth.from(earliestDate).let {
                if (ChronoUnit.MONTHS.between(it, currentMonth) < 5) currentMonth.minusMonths(5) else it
            }
        } else {
            currentMonth.minusMonths(5)
        }
        val totalMonthsAllTime = (ChronoUnit.MONTHS.between(startAllTime, currentMonth) + 1).toInt().coerceAtLeast(1)
        val barsAllTime = ArrayList<BarData>(totalMonthsAllTime)
        var maxCountAllTime = 0
        for (i in 0 until totalMonthsAllTime) {
            val ym = startAllTime.plusMonths(i.toLong())
            val appCounts = monthlyMap[ym] ?: emptyMap()
            val total = appCounts.values.sum()
            if (total > maxCountAllTime) maxCountAllTime = total
            val showYear = ym.monthValue == 1 || i == 0
            val label = if (showYear) "${ym.year}\n${ym.monthValue}月" else "${ym.monthValue}月"
            barsAllTime.add(
                BarData(
                    key = ym.toString(),
                    label = label,
                    fullLabel = "${ym.year}年${ym.monthValue}月",
                    appCounts = appCounts,
                    totalCount = total
                )
            )
        }
        val distributionAllTime = DistributionChartData(DistributionRange.ALL_TIME, barsAllTime, maxCountAllTime)

        // 3. 时段分布 (0-24点)
        val hourlyBars = ArrayList<BarData>(24)
        var maxHourlyCount = 0
        var peakHour = -1
        for (h in 0 until 24) {
            val appCounts = hourlyMap[h]
            val total = appCounts.values.sum()
            if (total > maxHourlyCount) {
                maxHourlyCount = total
                peakHour = h
            }
            hourlyBars.add(
                BarData(
                    key = h.toString(),
                    label = "$h",
                    fullLabel = String.format("%02d:00 - %02d:59", h, h),
                    appCounts = appCounts,
                    totalCount = total
                )
            )
        }
        val peakHourRange = if (peakHour >= 0 && maxHourlyCount > 0) {
            String.format("%02d:00 - %02d:59 (高峰 %d 条)", peakHour, peakHour, maxHourlyCount)
        } else {
            "暂无时段高峰"
        }

        // 4. 热力图 (最近 52 周)
        val end = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        val totalDays = weeks * 7
        val alignedStart = end.minusDays((totalDays - 1).toLong())

        var maxTotalPerDay = 0
        for (m in dailyMap.values) {
            val total = m.values.sum()
            if (total > maxTotalPerDay) maxTotalPerDay = total
        }

        val columns = ArrayList<List<DayCell>>(weeks)
        for (w in (weeks - 1) downTo 0) {
            val col = ArrayList<DayCell>(7)
            for (d in 0 until 7) {
                val date = alignedStart.plusDays((w * 7 + d).toLong())
                col.add(DayCell(date, dailyMap[date] ?: emptyMap()))
            }
            columns.add(col)
        }

        val topApps = perAppTotalAll.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key to it.value }

        return ComputedStats(
            todayWordCount = todayWordCount,
            todayRecordCount = todayRecordCount,
            totalRecordCount = records.size.toLong(),
            totalWordCount = totalWordCount,
            currentStreakDays = currentStreak,
            maxStreakDays = maxStreak,
            distribution30Days = distribution30Days,
            distributionFullYear = distributionFullYear,
            distributionAllTime = distributionAllTime,
            hourlyDistribution = hourlyBars,
            maxHourlyCount = maxHourlyCount,
            peakHourRange = peakHourRange,
            weeks = weeks,
            columns = columns,
            maxTotalPerDay = maxTotalPerDay,
            topApps = topApps
        )
    }
}
