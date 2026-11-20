package com.melonhu.indexrecord.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.repository.HomeRecordRepository
import com.melonhu.indexrecord.data.repository.RecordHistoryRepository
import com.melonhu.indexrecord.logic.ComputedStats
import com.melonhu.indexrecord.logic.DistributionRange
import com.melonhu.indexrecord.logic.StatsCalculator
import com.melonhu.indexrecord.settings.RecordSettings
import com.melonhu.indexrecord.sync.HomeSyncCoordinator
import java.time.ZoneId

data class StatsUiState(
    val selectedRange: DistributionRange = DistributionRange.LAST_30_DAYS,
    val stats: ComputedStats = ComputedStats(),
    val includeRepliesAndComments: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val hasStats: Boolean = false
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {
    var hasAnimatedNumbers: Boolean = false

    private val historyRepository = RecordHistoryRepository()
    private val homeRecordRepository = HomeRecordRepository()
    private val syncCoordinator = HomeSyncCoordinator(application, viewModelScope)
    private val zone = ZoneId.systemDefault()

    private val selectedRangeFlow = MutableStateFlow(DistributionRange.LAST_30_DAYS)

    private val reads = DetailRetry { historyRepository.observeAllResult() }
    val isRetrying = reads.isRetrying
    val rawPendingCountFlow = homeRecordRepository.rawPendingCount
    val syncProgress = syncCoordinator.syncProgress
    fun retry() = reads.retry()

    suspend fun refreshData(): Int {
        if (state.value.errorMessage != null) {
            retry()
        }
        return syncCoordinator.refresh()
    }

    private val computedStatsFlow = statsStates(
        reads.snapshots,
        RecordSettings.includeRepliesAndCommentsInStats,
        zone
    ).flowOn(Dispatchers.Default)

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

/** Keep the last successful value and its filter together across failed reads and retries. */
internal fun statsStates(
    snapshots: Flow<Result<List<UniversalRecord>>>,
    includeRepliesAndComments: Flow<Boolean>,
    zone: ZoneId
): Flow<StatsUiState> = flow {
    var latest = StatsUiState()
    emit(latest)
    combine(snapshots, includeRepliesAndComments) { result, includeReplies ->
        result to includeReplies
    }.collect { (result, includeReplies) ->
        latest = try {
            val records = result.getOrThrow()
            StatsUiState(
                stats = StatsCalculator.computeState(
                    records, weeks = 52, zone = zone, includeRepliesAndComments = includeReplies
                ),
                includeRepliesAndComments = includeReplies,
                isLoading = false,
                hasStats = true
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            latest.copy(isLoading = false, errorMessage = "无法读取或计算本地统计，请重试")
        }
        emit(latest)
    }
}
