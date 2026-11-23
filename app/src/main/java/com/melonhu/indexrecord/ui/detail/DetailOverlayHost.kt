package com.melonhu.indexrecord.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.ui.detail.question.QuestionDetailScreen
import com.melonhu.indexrecord.ui.detail.record.RecordDetailScreen
import com.melonhu.indexrecord.ui.detail.subject.SubjectDetailScreen
import com.melonhu.indexrecord.ui.detail.tieba.TiebaDetailScreen
import com.melonhu.indexrecord.viewmodel.DetailNavigationEntry
import com.melonhu.indexrecord.viewmodel.DetailNavigationViewModel

@Composable
internal fun DetailOverlayHost(
    navigation: DetailNavigationViewModel,
    content: @Composable () -> Unit
) {
    val readingStates = rememberSaveableStateHolder()
    val currentEntry = navigation.entries.lastOrNull()
    val lastNavAction = navigation.lastAction
    val hasDetail = currentEntry != null
    var lastNonNullEntry by remember { mutableStateOf<DetailNavigationEntry?>(null) }
    if (currentEntry != null) {
        lastNonNullEntry = currentEntry
    }

    val transition = updateTransition(targetState = hasDetail, label = "DetailOverlayTransition")
    val offsetFraction by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 300, easing = FastOutSlowInEasing) },
        label = "DetailOverlayOffsetFraction"
    ) { showDetail ->
        if (showDetail) -0.35f else 0f
    }
    val alpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 300, easing = FastOutSlowInEasing) },
        label = "DetailOverlayAlpha"
    ) { showDetail ->
        if (showDetail) 0.85f else 1f
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = size.width * offsetFraction
                    this.alpha = alpha
                }
        ) {
            content()
        }

        AnimatedVisibility(
            visible = hasDetail,
            enter = slideInHorizontally(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                initialOffsetX = { it }
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutHorizontally(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                targetOffsetX = { it }
            ) + fadeOut(animationSpec = tween(300))
        ) {
            val entryToShow = currentEntry ?: lastNonNullEntry
            if (entryToShow != null) {
                BackHandler(enabled = hasDetail) {
                    navigation.pop()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    shadowElevation = 8.dp
                ) {
                    AnimatedContent(
                        targetState = entryToShow,
                        contentKey = { it.key },
                        transitionSpec = {
                            if (lastNavAction == DetailNavigationViewModel.Action.PUSH) {
                                (slideInHorizontally(
                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                    initialOffsetX = { it }
                                ) + fadeIn(animationSpec = tween(300)))
                                    .togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                                            targetOffsetX = { -it / 2 }
                                        ) + fadeOut(animationSpec = tween(300))
                                    )
                            } else {
                                (slideInHorizontally(
                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                    initialOffsetX = { -it / 2 }
                                ) + fadeIn(animationSpec = tween(300)))
                                    .togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                                            targetOffsetX = { it }
                                        ) + fadeOut(animationSpec = tween(300))
                                    )
                            }.using(SizeTransform(clip = false))
                        },
                        label = "DetailStackTransition"
                    ) { entry ->
                        DisposableEffect(entry) {
                            navigation.attach(entry)
                            onDispose {
                                navigation.detach(entry)
                                if (entry !in navigation.entries) readingStates.removeState(entry.key)
                            }
                        }
                        readingStates.SaveableStateProvider(entry.key) {
                            DetailViewModelScope(entry) {
                                val targetRecord = entry.record
                                if (targetRecord.platform == "tieba" && targetRecord.recordType in setOf("lou", "floor", "comment")) {
                                    TiebaDetailScreen(
                                        record = targetRecord,
                                        onBack = { navigation.pop() },
                                        onOpenRecord = { navigation.push(it) },
                                        listState = rememberLazyListState(),
                                        firstFloorListState = rememberLazyListState()
                                    )
                                } else if (targetRecord.recordType == "question") {
                                    QuestionDetailScreen(
                                        record = targetRecord,
                                        onBack = { navigation.pop() },
                                        onOpenAnswer = { navigation.push(it) },
                                        listState = rememberLazyListState()
                                    )
                                } else if (targetRecord.recordType == "subject") {
                                    SubjectDetailScreen(
                                        record = targetRecord,
                                        onBack = { navigation.pop() },
                                        onOpenRecord = { navigation.push(it) },
                                        listState = rememberLazyListState()
                                    )
                                } else {
                                    RecordDetailScreen(
                                        record = targetRecord,
                                        onBack = { navigation.pop() },
                                        onOpenRecord = { navigation.push(it) },
                                        listState = rememberLazyListState()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
