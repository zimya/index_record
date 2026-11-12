package me.melon.record.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.melon.record.logic.DataProcessor

@Composable
internal fun HomeSyncProgress(
    syncProgress: DataProcessor.SyncProgress,
    rawPendingCount: Long,
    sessionLoadedCount: Int
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            val determinate = syncProgress.total > 0
            val progress = if (determinate) {
                (syncProgress.processed.toFloat() / syncProgress.total.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            if (determinate) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(8.dp))
            val syncLabel = when {
                syncProgress.isRunning ->
                    "同步中：剩余 ${rawPendingCount} 条 · 本轮已加载 ${sessionLoadedCount} 条"
                rawPendingCount > 0L ->
                    "等待处理：剩余 ${rawPendingCount} 条 · 本轮已加载 ${sessionLoadedCount} 条"
                else ->
                    "同步中：本轮已加载 ${sessionLoadedCount} 条 · 等待后续数据…"
            }
            Text(
                text = syncLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
