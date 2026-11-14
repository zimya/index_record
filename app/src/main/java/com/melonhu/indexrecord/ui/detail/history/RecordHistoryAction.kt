package com.melonhu.indexrecord.ui.detail.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.repository.HistoricalRecord
import com.melonhu.indexrecord.logic.DiffKind
import com.melonhu.indexrecord.ui.components.DetailTopAppBar
import com.melonhu.indexrecord.ui.components.ImageViewerDialog
import com.melonhu.indexrecord.ui.detail.record.RecordDetailBody
import com.melonhu.indexrecord.ui.detail.record.RecordDetailHeader
import com.melonhu.indexrecord.viewmodel.DetailLoadState
import com.melonhu.indexrecord.viewmodel.HistoryDiffState
import com.melonhu.indexrecord.viewmodel.RecordHistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RecordHistoryAction(record: UniversalRecord) {
    var open by rememberSaveable(record.platform, record.platformId) { mutableStateOf(false) }
    TextButton(
        onClick = { open = true },
        modifier = Modifier.semantics { contentDescription = "查看此记录的历史快照" }
    ) { Text("历史") }
    if (!open) return

    val model: RecordHistoryViewModel = viewModel(
        key = "record-history:${record.platform}:${record.platformId}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordHistoryViewModel(record) as T
        }
    )
    RecordHistoryDialog(model) { model.closePages(); open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordHistoryDialog(model: RecordHistoryViewModel, onClose: () -> Unit) {
    val history by model.state.collectAsState()
    val retrying by model.isRetrying.collectAsState()
    val snapshot by model.snapshot.collectAsState()
    val diff by model.diff.collectAsState()
    var showUnchanged by rememberSaveable(diff?.old?.key, diff?.new?.key) { mutableStateOf(false) }
    var first by remember { mutableStateOf<HistoricalRecord?>(null) }
    var second by remember { mutableStateOf<HistoricalRecord?>(null) }
    var viewerUrls by remember { mutableStateOf(emptyList<String>()) }
    var viewerIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val back: () -> Unit = {
        when {
            diff != null -> model.closeDiff()
            snapshot != null -> model.closeSnapshot()
            else -> onClose()
        }
    }
    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                topBar = {
                    DetailTopAppBar(
                        title = when { diff != null -> "快照差异 · 旧 → 新"; snapshot != null -> "历史快照"; else -> "历史快照" },
                        onBack = back,
                        actions = {
                            if (diff != null) {
                                TextButton(onClick = { showUnchanged = !showUnchanged }) {
                                    Text(if (showUnchanged) "只看差异" else "显示全部")
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                val currentSnapshot = snapshot
                val currentDiff = diff
                when {
                    currentDiff != null -> HistoryDiff(currentDiff, showUnchanged, Modifier.padding(padding)) {
                        model.compare(currentDiff.old, currentDiff.new)
                    }
                    currentSnapshot != null -> {
                        // Render the captured value, never navigate through a live detail repository.
                        androidx.compose.runtime.key(currentSnapshot.key) {
                            LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp)) {
                                item { SnapshotLabel(currentSnapshot) }
                                item { RecordDetailHeader(currentSnapshot.record) { urls, index -> viewerUrls = urls; viewerIndex = index } }
                                item {
                                    if (currentSnapshot.record.content.isBlank()) Text("此快照未缓存正文")
                                    RecordDetailBody(currentSnapshot.record) { urls, index -> viewerUrls = urls; viewerIndex = index }
                                }
                            }
                        }
                    }
                    else -> Column(Modifier.padding(padding).fillMaxSize()) {
                        Text(
                            "按阅读时间倒序排列，选择两个快照进行比较。",
                            modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall
                        )
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = first != null && second != null, onClick = { model.compare(first!!, second!!) }) { Text("比较所选快照") }
                            TextButton(enabled = first != null, onClick = { first = null; second = null }) { Text("清空选择") }
                        }
                        if (retrying || history.loadState == DetailLoadState.Loading) {
                            CircularProgressIndicator(Modifier.padding(16.dp).semantics { contentDescription = "正在读取历史快照" })
                        }
                        if (history.loadState is DetailLoadState.Failed) {
                            Text("无法读取本地历史，已加载的快照仍可查看。", Modifier.padding(16.dp))
                            TextButton(enabled = !retrying, onClick = model::retry) { Text("重试") }
                        }
                        if (history.loadState == DetailLoadState.Ready && history.entries.isEmpty()) {
                            Text("暂无历史快照", Modifier.padding(16.dp))
                        }
                        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                            items(history.entries, key = { it.key }) { entry ->
                                val selected = first?.key == entry.key || second?.key == entry.key
                                Surface(
                                    onClick = { model.openSnapshot(entry) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            SnapshotLabel(entry)
                                            Text(entry.record.title.ifBlank { "无标题快照" }, maxLines = 2)
                                            Text("作者：${entry.record.authorName.ifBlank { "未记录" }}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Checkbox(
                                            checked = selected,
                                            enabled = selected || second == null,
                                            onCheckedChange = {
                                                when (entry.key) {
                                                    first?.key -> { first = second; second = null }
                                                    second?.key -> second = null
                                                    else -> if (first == null) first = entry else second = entry
                                                }
                                            },
                                            modifier = Modifier.semantics {
                                                contentDescription = "选择快照，${readingTimeLabel(entry)}，${entry.record.title}"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (viewerUrls.isNotEmpty()) {
            ImageViewerDialog(viewerUrls, viewerIndex, onDismiss = { viewerUrls = emptyList() })
        }
    }
}

private fun readingTimeLabel(entry: HistoricalRecord): String = if (entry.record.captureTime > 0) {
    SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss.SSS", Locale.CHINA).format(Date(entry.record.captureTime))
} else "时间未知"

@Composable
private fun SnapshotLabel(entry: HistoricalRecord) {
    Text("阅读时间：${readingTimeLabel(entry)}", style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun HistoryDiff(state: HistoryDiffState, showUnchanged: Boolean, modifier: Modifier, retry: () -> Unit) {
    androidx.compose.runtime.key(state.old.key, state.new.key) {

        LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("旧 → 新", style = MaterialTheme.typography.titleLarge)
                Text("旧快照", style = MaterialTheme.typography.titleSmall)
                SnapshotLabel(state.old)
                Text("新快照", style = MaterialTheme.typography.titleSmall)
                SnapshotLabel(state.new)


                if (state.loading) CircularProgressIndicator(Modifier.semantics { contentDescription = "正在计算快照差异" })
                if (state.error) {
                    Text("差异计算失败")
                    TextButton(onClick = retry) { Text("重试") }
                }
                if (!state.loading && !state.error && state.sections.none { it.hasChanges }) Text("快照内容相同")
            }
            state.sections.filter { showUnchanged || it.hasChanges }.forEach { section ->
                item {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text(section.title, style = MaterialTheme.typography.titleMedium)

                }
                items(section.diff.parts.filter { showUnchanged || it.kind != DiffKind.UNCHANGED }) { part ->

                    val color = when (part.kind) {
                        DiffKind.UNCHANGED -> MaterialTheme.colorScheme.surface
                        DiffKind.REMOVED -> MaterialTheme.colorScheme.errorContainer
                        DiffKind.ADDED -> MaterialTheme.colorScheme.primaryContainer
                    }
                    val foreground = when (part.kind) {
                        DiffKind.UNCHANGED -> MaterialTheme.colorScheme.onSurface
                        DiffKind.REMOVED -> MaterialTheme.colorScheme.onErrorContainer
                        DiffKind.ADDED -> MaterialTheme.colorScheme.onPrimaryContainer
                    }
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth().background(color).padding(8.dp)) {

                            Text(part.text, color = foreground, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
