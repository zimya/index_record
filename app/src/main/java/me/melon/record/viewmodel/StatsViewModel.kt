package me.melon.record.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class StatsViewModel(application: Application) : AndroidViewModel(application) {
    data class DayCell(
        val date: LocalDate,
        val appCounts: Map<String, Int>
    )

    data class StatsState(
        val weeks: Int = 52,
        val columns: List<List<DayCell>> = emptyList(),
        val maxTotalPerDay: Int = 0,
        val topApps: List<Pair<String, Int>> = emptyList()
    )

    private val recordBox = ObjectBox.store.boxFor(UniversalRecord::class.java)
    private val zone = ZoneId.systemDefault()

    private val updates: Flow<StatsState> = callbackFlow {
        val query = recordBox.query().orderDesc(UniversalRecord_.captureTime).build()
        val subscription = query.subscribe()
            .observer { data ->
                trySend(computeState(data, 52))
            }

        trySend(computeState(query.find(), 52))

        awaitClose {
            subscription.cancel()
            query.close()
        }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<StatsState> = updates.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = StatsState()
    )

    private fun computeState(records: List<UniversalRecord>, weeks: Int): StatsState {
        val today = LocalDate.now(zone)
        val end = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        val totalDays = weeks * 7
        val alignedStart = end.minusDays((totalDays - 1).toLong())

        val perDay = HashMap<LocalDate, HashMap<String, Int>>(totalDays)
        val perAppTotalAll = HashMap<String, Int>()

        for (r in records) {
            val pkg = resolveSourceApp(r)
            val date = Instant.ofEpochMilli(r.captureTime).atZone(zone).toLocalDate()
            if (date.isBefore(alignedStart) || date.isAfter(end)) continue

            perAppTotalAll[pkg] = (perAppTotalAll[pkg] ?: 0) + 1
            val m = perDay.getOrPut(date) { HashMap() }
            m[pkg] = (m[pkg] ?: 0) + 1
        }

        var maxTotal = 0
        for (m in perDay.values) {
            val total = m.values.sum()
            if (total > maxTotal) maxTotal = total
        }

        val columns = ArrayList<List<DayCell>>(weeks)
        for (w in (weeks - 1) downTo 0) {
            val col = ArrayList<DayCell>(7)
            for (d in 0 until 7) {
                val date = alignedStart.plusDays((w * 7 + d).toLong())
                col.add(DayCell(date, perDay[date] ?: emptyMap()))
            }
            columns.add(col)
        }

        val topApps = perAppTotalAll.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key to it.value }

        return StatsState(
            weeks = weeks,
            columns = columns,
            maxTotalPerDay = maxTotal,
            topApps = topApps
        )
    }

    private fun resolveSourceApp(record: UniversalRecord): String {
        val direct = record.sourceApp ?: ""
        if (direct.isNotBlank()) return direct
        return when (record.platform) {
            "zhihu" -> "com.zhihu.android"
            "douban" -> "com.douban.frodo"
            "tieba" -> "com.baidu.tieba"
            "xiaohongshu" -> "com.xingin.xhs"
            "twitter" -> "com.twitter.android"
            else -> ""
        }
    }
}
