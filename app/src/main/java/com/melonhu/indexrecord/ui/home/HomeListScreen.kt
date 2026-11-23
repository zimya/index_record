package com.melonhu.indexrecord.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.logic.DataProcessor
import com.melonhu.indexrecord.ui.components.RecordItem
import com.melonhu.indexrecord.ui.components.SyncPullRefreshContainer
import com.melonhu.indexrecord.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshListScreen(
    records: List<UniversalRecord>,
    recordsLoaded: Boolean,
    filter: HomeViewModel.HomeFilter,
    onRefresh: suspend () -> Int,
    onOpenFilter: () -> Unit,
    listState: LazyListState,
    rawPendingCount: Long,
    syncProgress: DataProcessor.SyncProgress,
    onItemClick: (UniversalRecord) -> Unit
) {
    val listItems = remember(records) { groupRecordsForHome(records) }
    val recordIds = remember(records) { records.map { it.id } }
    val scope = rememberCoroutineScope()
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            HomeTopBar(
                filter = filter,
                onOpenFilter = onOpenFilter
            )
        }
    ) { padding ->
        SyncPullRefreshContainer(
            rawPendingCount = rawPendingCount,
            syncProgress = syncProgress,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding),
            suppressSyncUi = false,
            observedItemIds = recordIds,
            observedItemsLoaded = recordsLoaded,
            isAtTop = isAtTop,
            onScrollToTop = {
                scope.launch {
                    if (listState.firstVisibleItemIndex > 15) {
                        listState.scrollToItem(15)
                    }
                    listState.animateScrollToItem(0)
                }
            },
            overlayContent = {
                if (listItems.size >= 2) {
                    DateFastScroller(
                        items = listItems,
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(end = 0.dp, top = 10.dp, bottom = 10.dp)
                    )
                }
            }
        ) {
            if (records.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val emptyText = if (!recordsLoaded) {
                        ""
                    } else {
                        stringResource(R.string.home_empty_prompt)
                    }
                    Text(
                        text = emptyText,
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState
                ) {
                    items(listItems, key = { it.key }) { item ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            when (item) {
                                is RecordListItem -> {
                                    RecordItem(item.record, "") {
                                        onItemClick(item.record)
                                    }
                                }
                                is AnswerGroupListItem -> {
                                    AnswerGroupItem(
                                        questionTitle = item.questionTitle,
                                        answers = item.answers,
                                        searchQuery = "",
                                        onOpenAnswer = onItemClick
                                    )
                                }
                                is InterestGroupListItem -> {
                                    InterestGroupItem(
                                        subjectTitle = item.subjectTitle,
                                        typeLabel = item.typeLabel,
                                        interests = item.interests,
                                        searchQuery = "",
                                        onOpenInterest = onItemClick
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
