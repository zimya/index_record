package com.melonhu.indexrecord.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.logic.DataProcessor

/**
 * 同步状态文字区，铺满屏宽。空闲时仅提示保留数据待重试，
 * 活动会话的横向进度条由 [HomePullRefreshBar] 统一绘制在其上边缘。
 */
@Composable
internal fun HomeSyncProgress(
    syncProgress: DataProcessor.SyncProgress,
    rawPendingCount: Long,
    sessionLoadedCount: Int,
    syncSessionActive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RectangleShape
    ) {
        val syncLabel = when {
            !syncSessionActive && rawPendingCount > 0L ->
                stringResource(R.string.sync_retry_pending, rawPendingCount)
            !syncSessionActive ->
                stringResource(R.string.sync_completed, sessionLoadedCount)
            syncProgress.isRunning ->
                stringResource(R.string.sync_in_progress_with_pending, rawPendingCount, sessionLoadedCount)
            rawPendingCount > 0L ->
                stringResource(R.string.sync_waiting_process, rawPendingCount, sessionLoadedCount)
            else ->
                stringResource(R.string.sync_in_progress_waiting_more, sessionLoadedCount)
        }
        Text(
            text = syncLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
