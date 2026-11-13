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
import me.melon.record.settings.RecordSettings
import java.time.ZoneId

data class StatsUiState(
    val selectedRange: DistributionRange = DistributionRange.LAST_30_DAYS,
    val stats: ComputedStats = ComputedStats(),
    val includeRepliesAndComments: Boolean = false
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {
    var hasAnimatedNumbers: Boolean = false

    private val recordBox = ObjectBox.store.boxFor(UniversalRecord::class.java)
    private val zone = ZoneId.systemDefault()

    private val selectedRangeFlow = MutableStateFlow(DistributionRange.LAST_30_DAYS)

    private val recordsFlow: Flow<List<UniversalRecord>> = callbackFlow {
        val query = recordBox.query().orderDesc(UniversalRecord_.captureTime).build()
        val subscription = query.subscribe()
            .observer { data ->
                trySend(data)
            }

        trySend(query.find())

        awaitClose {
            subscription.cancel()
            query.close()
        }
    }.flowOn(Dispatchers.IO)

    private val computedStatsFlow = combine(
        recordsFlow,
        RecordSettings.includeRepliesAndCommentsInStats
    ) { records, includeReplies ->
        StatsUiState(
            stats = StatsCalculator.computeState(
                records, weeks = 52, zone = zone, includeRepliesAndComments = includeReplies
            ),
            includeRepliesAndComments = includeReplies
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<StatsUiState> = combine(
        selectedRangeFlow,
        computedStatsFlow
    ) { range, computed ->
        computed.copy(selectedRange = range)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = StatsUiState()
    )

    fun selectRange(range: DistributionRange) {
        selectedRangeFlow.value = range
    }
}
