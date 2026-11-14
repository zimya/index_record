package me.melon.record.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.melon.record.data.PlatformCatalog
import me.melon.record.presentation.historyBodyFormat
import me.melon.record.presentation.historyMetadataChanges
import me.melon.record.presentation.historyPublishTime
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.repository.HistoricalRecord
import me.melon.record.data.repository.RecordHistoryRepository
import me.melon.record.logic.RecordDiff
import me.melon.record.logic.recordDiff

internal data class RecordHistoryState(
    val entries: List<HistoricalRecord> = emptyList(),
    val loadState: DetailLoadState = DetailLoadState.Loading
)

internal data class HistoryDiffSection(val title: String, val diff: RecordDiff) {
    val hasChanges: Boolean get() = diff.parts.any { it.kind != me.melon.record.logic.DiffKind.UNCHANGED }
}
internal data class HistoryDiffState(
    val old: HistoricalRecord,
    val new: HistoricalRecord,
    val sections: List<HistoryDiffSection> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false
)

internal class RecordHistoryViewModel(
    record: UniversalRecord,
    repository: RecordHistoryRepository = RecordHistoryRepository()
) : ViewModel() {
    private val initial = record.copy()
    private val reads = DetailRetry { repository.observeResult(initial) }
    val isRetrying = reads.isRetrying
    fun retry() = reads.retry()
    val state: StateFlow<RecordHistoryState> = recordHistoryStates(reads.snapshots).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordHistoryState()
    )

    private val selected = MutableStateFlow<HistoricalRecord?>(null)
    val snapshot = selected.asStateFlow()
    fun openSnapshot(entry: HistoricalRecord) { selected.value = entry }
    fun closeSnapshot() { selected.value = null }

    private val comparison = MutableStateFlow<HistoryDiffState?>(null)
    val diff = comparison.asStateFlow()
    private var diffJob: Job? = null

    fun compare(first: HistoricalRecord, second: HistoricalRecord) {
        diffJob?.cancel()
        val (old, new) = chronologicalPair(first, second)
        comparison.value = HistoryDiffState(old, new)
        diffJob = viewModelScope.launch {
            try {
                val sections = withContext(Dispatchers.Default) { historyDiffSections(old.record, new.record, includeUnchanged = true) }
                comparison.value = HistoryDiffState(old, new, sections, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                comparison.value = HistoryDiffState(old, new, loading = false, error = true)
            }
        }
    }

    fun closeDiff() { diffJob?.cancel(); comparison.value = null }
    fun closePages() { closeDiff(); closeSnapshot() }
}

internal fun chronologicalPair(first: HistoricalRecord, second: HistoricalRecord): Pair<HistoricalRecord, HistoricalRecord> =
    if (first.record.captureTime <= second.record.captureTime) first to second else second to first

internal fun recordHistoryStates(snapshots: Flow<Result<List<HistoricalRecord>>>): Flow<RecordHistoryState> = flow {
    var latest = emptyList<HistoricalRecord>()
    snapshots.collect { result ->
        result.fold(
            onSuccess = { latest = it.toList(); emit(RecordHistoryState(latest, DetailLoadState.Ready)) },
            onFailure = {
                if (it is CancellationException) throw it
                emit(RecordHistoryState(latest, DetailLoadState.Failed("无法读取本地历史，请重试")))
            }
        )
    }
}

internal fun historyDiffSections(
    old: DetailRecord,
    new: DetailRecord,
    includeUnchanged: Boolean = false
): List<HistoryDiffSection> {
    val fields = listOf(
        Triple("标题", old.title, new.title),
        Triple("正文", old.content, new.content),
        Triple("作者", old.authorName, new.authorName),
        Triple("作者标识", old.authorId, new.authorId),
        Triple("作者头像", old.authorAvatar, new.authorAvatar),
        Triple("记录类型", PlatformCatalog.typeLabel(old.platform, old.recordType), PlatformCatalog.typeLabel(new.platform, new.recordType)),
        Triple("来源应用", old.sourceApp.orEmpty(), new.sourceApp.orEmpty()),
        Triple("父级标识", old.parentId, new.parentId),
        Triple("正文格式", historyBodyFormat(old.contentFormat), historyBodyFormat(new.contentFormat)),
        Triple("来源链接", old.sourceUrl, new.sourceUrl),
        Triple("发布时间", historyPublishTime(old.publishTime), historyPublishTime(new.publishTime))
    )
    return fields.filter { it.second != it.third || (includeUnchanged && it.second.isNotBlank()) }.map { (title, before, after) ->
        HistoryDiffSection(title, recordDiff(before, after))
    } + historyMetadataChanges(
            old.extraMetadata, new.extraMetadata, old.platform, new.platform,
            includeUnchanged = includeUnchanged
        ).map { field ->
        HistoryDiffSection(field.title, recordDiff(field.before, field.after))
    }
}
