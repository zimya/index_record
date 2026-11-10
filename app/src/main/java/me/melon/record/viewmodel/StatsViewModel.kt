package me.melon.record.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.logic.ComputedStats
import me.melon.record.logic.DistributionRange
import me.melon.record.logic.StatsCalculator
import java.time.ZoneId

data class StatsUiState(
    val selectedRange: DistributionRange = DistributionRange.LAST_30_DAYS,
    val stats: ComputedStats = ComputedStats()
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {
    var hasAnimatedNumbers: Boolean = false

    private val recordBox = ObjectBox.store.boxFor(UniversalRecord::class.java)
    private val zone = ZoneId.systemDefault()

    private val selectedRangeFlow = MutableStateFlow(DistributionRange.LAST_30_DAYS)

    private val computedStatsFlow: Flow<ComputedStats> = callbackFlow {
        val query = recordBox.query().orderDesc(UniversalRecord_.captureTime).build()
        val subscription = query.subscribe()
            .observer { data ->
                trySend(StatsCalculator.computeState(data, weeks = 52, zone = zone))
            }

        trySend(StatsCalculator.computeState(query.find(), weeks = 52, zone = zone))

        awaitClose {
            subscription.cancel()
            query.close()
        }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<StatsUiState> = combine(
        selectedRangeFlow,
        computedStatsFlow
    ) { range, stats ->
        StatsUiState(selectedRange = range, stats = stats)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = StatsUiState()
    )

    fun selectRange(range: DistributionRange) {
        selectedRangeFlow.value = range
    }
}
