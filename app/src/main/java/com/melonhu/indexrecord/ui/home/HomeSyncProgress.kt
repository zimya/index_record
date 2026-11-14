package com.melonhu.indexrecord.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
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
                "待重试：剩余 ${rawPendingCount} 条原始数据已保留"
            !syncSessionActive ->
                "同步完成：本轮已加载 ${sessionLoadedCount} 条"
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
