package me.melon.record.ui.home

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.melon.record.presentation.displayTime
import kotlin.math.max
import kotlin.math.roundToInt

private fun homeItemTimeMillis(item: HomeListItem): Long {
    return when (item) {
        is RecordListItem -> item.record.captureTime
        is AnswerGroupListItem -> item.answers.firstOrNull()?.captureTime ?: 0L
        is InterestGroupListItem -> item.interests.firstOrNull()?.captureTime ?: 0L
    }
}

@Composable
internal fun DateFastScroller(
    items: List<HomeListItem>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    val density = LocalDensity.current

    var heightPx by remember { mutableStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragYPx by remember { mutableStateOf(0f) }
    var hoverIndex by remember { mutableStateOf(0) }

    val currentIndex = listState.firstVisibleItemIndex.coerceIn(0, items.lastIndex)
    val thumbFraction = if (items.size <= 1) 0f else currentIndex.toFloat() / (items.size - 1).toFloat()
    val thumbYPx = if (isDragging) dragYPx else thumbFraction * heightPx.toFloat()

    val trackWidth = 26.dp
    val edgePadding = 0.dp          // 想更贴边就用 0.dp
    val bubbleGap = 8.dp            // 气泡离滚动条的距离

    fun scrollToY(y: Float) {
        val safeY = y.coerceIn(0f, heightPx.toFloat())
        val fraction = if (heightPx <= 1) 0f else safeY / heightPx.toFloat()
        val idx = ((items.size - 1) * fraction).roundToInt().coerceIn(0, items.lastIndex)
        hoverIndex = idx
        dragYPx = safeY
        scope.launch {
            listState.scrollToItem(idx)
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = edgePadding)
                .fillMaxHeight()
                .width(trackWidth)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(items.size, heightPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            scrollToY(it.y)
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            scrollToY(change.position.y)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .width(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(3.dp)
                    )
            )

            val thumbRadiusPx = with(density) { 6.dp.toPx() }
            val thumbMaxY = max(0f, heightPx.toFloat() - 2 * thumbRadiusPx)
            val thumbY = (thumbYPx - thumbRadiusPx).coerceIn(0f, thumbMaxY)

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, thumbY.roundToInt()) }
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }

        if (isDragging) {
            val ts = homeItemTimeMillis(items[hoverIndex])
            val label = displayTime(ts, "yyyy年M月d日")

            if (label.isNotBlank()) {
                val y = (thumbYPx - with(density) { 18.dp.toPx() })
                    .coerceIn(0f, max(0f, heightPx.toFloat() - with(density) { 40.dp.toPx() }))
                    .roundToInt()

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(
                                x = -with(density) { (trackWidth + bubbleGap).toPx() }.roundToInt(),
                                y = y
                            )
                        }
                        .widthIn(min = 128.dp)
                        .onSizeChanged { /* 不一定需要测量宽高了 */ },
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 3.dp
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
