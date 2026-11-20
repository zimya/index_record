package com.melonhu.indexrecord.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.ui.components.RecordItem
import com.melonhu.indexrecord.ui.components.SyncPullRefreshContainer
import com.melonhu.indexrecord.viewmodel.HomeViewModel
import androidx.compose.runtime.saveable.rememberSaveable
import com.melonhu.indexrecord.logic.DataProcessor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshListScreen(
    records: List<UniversalRecord>,
    recordsLoaded: Boolean,
    searchQuery: String,
    filter: HomeViewModel.HomeFilter,
    availableApps: List<String>,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
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

    val keyboardController = LocalSoftwareKeyboardController.current
    val isSearching = isSearchActive || searchQuery.isNotBlank()

    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(searchQuery, TextRange(searchQuery.length)))
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery != textFieldValue.text) {
            textFieldValue = TextFieldValue(
                text = searchQuery,
                selection = TextRange(searchQuery.length)
            )
        }
    }

    BackHandler(enabled = isSearching) {
        keyboardController?.hide()
        if (searchQuery.isNotBlank()) {
            onSearchQueryChange("")
        }
        onSearchActiveChange(false)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            HomeTopBar(
                isSearchActive = isSearchActive,
                textFieldValue = textFieldValue,
                onTextFieldValueChange = { textFieldValue = it },
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                onSearchActiveChange = onSearchActiveChange,
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
            suppressSyncUi = isSearching,
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
                    val emptyText = if (searchQuery.isNotEmpty()) {
                        stringResource(R.string.home_empty_search)
                    } else if (!recordsLoaded) {
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
                                    RecordItem(item.record, searchQuery) {
                                        onItemClick(item.record)
                                    }
                                }
                                is AnswerGroupListItem -> {
                                    AnswerGroupItem(
                                        questionTitle = item.questionTitle,
                                        answers = item.answers,
                                        searchQuery = searchQuery,
                                        onOpenAnswer = onItemClick
                                    )
                                }
                                is InterestGroupListItem -> {
                                    InterestGroupItem(
                                        subjectTitle = item.subjectTitle,
                                        typeLabel = item.typeLabel,
                                        interests = item.interests,
                                        searchQuery = searchQuery,
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
