package com.melonhu.indexrecord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.logic.DataProcessor
import com.melonhu.indexrecord.ui.home.HomePullRefreshBar
import com.melonhu.indexrecord.ui.home.HomeSyncProgress
import kotlinx.coroutines.delay

/**
 * 统一的下拉刷新与同步状态容器，供首页、统计页与设置页共用：
 * 承载顶部下拉进度条、同步状态栏与刷新结果提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPullRefreshContainer(
    rawPendingCount: Long,
    syncProgress: DataProcessor.SyncProgress,
    onRefresh: suspend () -> Int,
    modifier: Modifier = Modifier,
    showSyncStatusBanner: Boolean = true,
    showResultHint: Boolean = true,
    suppressSyncUi: Boolean = false,
    observedItemIds: List<Long>? = null,
    observedItemsLoaded: Boolean = true,
    isAtTop: Boolean = true,
    onScrollToTop: (() -> Unit)? = null,
    overlayContent: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    val pullState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(false) }
    var hintMessage by remember { mutableStateOf("") }
    var hintShowArrow by remember { mutableStateOf(false) }
    var hintNoNewToken by remember { mutableLongStateOf(0L) }

    val context = LocalContext.current

    var syncSessionActive by remember { mutableStateOf(false) }
    val syncInProgress = syncProgress.isRunning || isRefreshing
    val showSync = (syncSessionActive || syncInProgress) && !suppressSyncUi
    val showSyncStatus = (showSync || rawPendingCount > 0L) && !suppressSyncUi
    var sessionLoadedCount by remember { mutableIntStateOf(0) }
    var acknowledgedLoadedCount by remember { mutableIntStateOf(0) }
    var previousRecordIds by remember { mutableStateOf<Set<Long>?>(null) }

    if (observedItemIds != null) {
        LaunchedEffect(observedItemIds, observedItemsLoaded) {
            if (!observedItemsLoaded) return@LaunchedEffect
            val ids = observedItemIds.toHashSet()
            val previous = previousRecordIds
            previousRecordIds = ids
            if (previous == null) return@LaunchedEffect
            // 只有在真实的同步或刷新会话中、且非抑制状态下，列表新增项才计入本轮加载数
            if (syncSessionActive && !suppressSyncUi) {
                val addedCount = ids.count { it !in previous }
                if (addedCount > 0) {
                    sessionLoadedCount += addedCount
                }
            }
        }
    }

    LaunchedEffect(suppressSyncUi) {
        if (suppressSyncUi) {
            hintVisible = false
            sessionLoadedCount = 0
            acknowledgedLoadedCount = 0
        }
    }

    // 只由真实处理/刷新维持会话；失败保留的 RawData 不代表仍在加载。
    // 持续空闲 2.5 秒后结束展示，衔接宿主逐条转存的批次间隙。
    LaunchedEffect(syncInProgress) {
        if (syncInProgress) {
            if (!syncSessionActive) {
                sessionLoadedCount = 0
                acknowledgedLoadedCount = 0
            }
            syncSessionActive = true
        } else if (syncSessionActive) {
            delay(2500)
            syncSessionActive = false
        }
    }

    LaunchedEffect(showSync, sessionLoadedCount, isAtTop, suppressSyncUi, showResultHint) {
        if (!showResultHint || suppressSyncUi || showSync) {
            hintVisible = false
            return@LaunchedEffect
        }
        if (sessionLoadedCount == 0 || sessionLoadedCount <= acknowledgedLoadedCount) {
            hintVisible = false
            return@LaunchedEffect
        }
        if (isAtTop && hintShowArrow) {
            acknowledgedLoadedCount = sessionLoadedCount
            hintVisible = false
            hintShowArrow = false
            return@LaunchedEffect
        }
        hintMessage = context.getString(R.string.sync_hint_loaded, sessionLoadedCount)
        hintShowArrow = !isAtTop && onScrollToTop != null
        hintVisible = true
        if (isAtTop || onScrollToTop == null) {
            acknowledgedLoadedCount = sessionLoadedCount
            hintNoNewToken = System.currentTimeMillis()
        } else {
            hintNoNewToken = 0L
        }
    }

    LaunchedEffect(hintNoNewToken, showSync) {
        if (hintNoNewToken == 0L || showSync) return@LaunchedEffect
        delay(1800)
        hintVisible = false
    }

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) return@LaunchedEffect
        try {
            val loadedCount = onRefresh()
            if (showResultHint && loadedCount == 0 && sessionLoadedCount == 0 && !showSync) {
                hintMessage = context.getString(R.string.sync_hint_empty)
                hintShowArrow = false
                hintVisible = true
                hintNoNewToken = System.currentTimeMillis()
            } else if (loadedCount > 0 && sessionLoadedCount == 0) {
                sessionLoadedCount = loadedCount
            }
        } finally {
            isRefreshing = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = pullState,
                onRefresh = { isRefreshing = true }
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = showSyncStatusBanner && showSyncStatus,
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300)),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            ) {
                HomeSyncProgress(
                    syncProgress = syncProgress,
                    rawPendingCount = rawPendingCount,
                    sessionLoadedCount = sessionLoadedCount,
                    syncSessionActive = showSync
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                content()
            }
        }

        overlayContent()

        RefreshResultHint(
            visible = hintVisible,
            message = hintMessage,
            showArrow = hintShowArrow,
            onClick = if (hintShowArrow && onScrollToTop != null) {
                {
                    acknowledgedLoadedCount = sessionLoadedCount
                    hintVisible = false
                    hintShowArrow = false
                    onScrollToTop()
                }
            } else {
                null
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
        )

        HomePullRefreshBar(
            state = pullState,
            isRefreshing = isRefreshing,
            loading = if (showSyncStatusBanner) isRefreshing || showSync else isRefreshing,
            syncFraction = if (showSyncStatusBanner && showSync && syncProgress.total > 0) {
                syncProgress.processed.toFloat() / syncProgress.total.toFloat()
            } else {
                null
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}
