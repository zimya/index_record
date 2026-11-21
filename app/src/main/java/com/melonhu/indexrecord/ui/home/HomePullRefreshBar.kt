package com.melonhu.indexrecord.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.melonhu.indexrecord.ui.util.rememberAppHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

// 与 Material3 LinearProgressIndicator 不确定态一致的两段式曲线：头尾速度不同，视觉上有快慢节奏。
private const val LoopCycleMillis = 1800
private val Line1HeadEasing = CubicBezierEasing(0.2f, 0f, 0.8f, 1f)
private val Line1TailEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
private val Line2HeadEasing = CubicBezierEasing(0f, 0f, 0.65f, 1f)
private val Line2TailEasing = CubicBezierEasing(0.1f, 0f, 0.45f, 1f)
private val HandOffEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

@Composable
private fun InfiniteTransition.loopValue(delayMillis: Int, durationMillis: Int, easing: Easing) =
    animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                this.durationMillis = LoopCycleMillis
                0f at delayMillis using easing
                1f at delayMillis + durationMillis
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "loopValue"
    )

/**
 * 首页顶部唯一的横向进度条，贯穿「下拉 -> 松手 -> 加载 -> 结束」：
 * - 下拉中：条长度/透明度跟随下拉幅度；越过触发阈值时轻震动一次。
 * - 半途松手：跟随回弹动画缩回并淡出。
 * - 开始加载（[loading]）：下拉出来的条整体向右滑出屏幕，同时新的加载条从左侧滑入并进入循环，不会收缩或突变。
 * - 有明确进度时（[syncFraction] 非空）：显示确定进度。
 * - 加载结束：淡出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomePullRefreshBar(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    loading: Boolean,
    syncFraction: Float?,
    modifier: Modifier = Modifier
) {
    val pullProgress = state.distanceFraction.coerceIn(0f, 1f)

    val loadingAlpha by animateFloatAsState(
        targetValue = if (loading) 1f else 0f,
        animationSpec = if (loading) snap() else tween(durationMillis = 250),
        label = "homeTopBarAlpha"
    )

    val appHaptics = rememberAppHapticFeedback()
    val currentIsRefreshing by rememberUpdatedState(isRefreshing)
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }
            .distinctUntilChanged()
            .collect { reachedThreshold ->
                if (reachedThreshold && !currentIsRefreshing) {
                    appHaptics.refreshThreshold()
                }
            }
    }

    val barModifier = modifier
        .fillMaxWidth()
        .height(3.dp)
    val color = MaterialTheme.colorScheme.primary

    when {
        loading || loadingAlpha > 0.01f -> LoadingBar(
            startLength = pullProgress,
            syncFraction = syncFraction,
            color = color,
            alpha = { loadingAlpha },
            modifier = barModifier
        )
        pullProgress > 0.001f -> Canvas(barModifier) {
            drawBar(color, 0f, pullProgress, pullProgress)
        }
    }
}

@Composable
private fun LoadingBar(
    startLength: Float,
    syncFraction: Float?,
    color: Color,
    alpha: () -> Float,
    modifier: Modifier
) {
    if (syncFraction != null) {
        val fraction by animateFloatAsState(
            targetValue = syncFraction.coerceIn(0f, 1f),
            animationSpec = tween(300),
            label = "syncFraction"
        )
        Canvas(modifier) { drawBar(color, 0f, fraction, alpha()) }
        return
    }

    // 进入加载时，原下拉条 [0, startLength] 的尾部先向右划走，头部同步划到右边缘。
    val handOffFrom = remember { startLength.coerceIn(0f, 1f) }
    val handOff = remember { Animatable(if (handOffFrom > 0.05f) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (handOff.value < 1f) handOff.animateTo(1f, tween(durationMillis = 550, easing = HandOffEasing))
    }

    // 同时，新的加载条立即从左侧开始循环（相位从 0 起，和下拉条的左端衔接）。
    val transition = rememberInfiniteTransition(label = "loadingBar")
    val line1Head by transition.loopValue(0, 750, Line1HeadEasing)
    val line1Tail by transition.loopValue(333, 850, Line1TailEasing)
    val line2Head by transition.loopValue(1000, 567, Line2HeadEasing)
    val line2Tail by transition.loopValue(1267, 533, Line2TailEasing)

    Canvas(modifier) {
        val a = alpha()
        if (handOff.value < 1f) {
            val tail = handOff.value
            val head = handOffFrom + (1f - handOffFrom) * handOff.value
            drawBar(color, tail, head, a)
        }
        drawBar(color, line1Tail, line1Head, a)
        drawBar(color, line2Tail, line2Head, a)
    }
}

private fun DrawScope.drawBar(color: Color, from: Float, to: Float, alpha: Float) {
    if (to <= from) return
    drawRect(
        color = color,
        topLeft = Offset(from * size.width, 0f),
        size = Size((to - from) * size.width, size.height),
        alpha = alpha
    )
}
