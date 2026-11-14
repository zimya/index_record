package com.melonhu.indexrecord.ui.detail.question

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember


import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.data.model.UniversalRecord

import com.melonhu.indexrecord.data.repository.AnswerSort
import com.melonhu.indexrecord.viewmodel.QuestionDetailViewModel
import com.melonhu.indexrecord.ui.detail.DetailLoadContent
import com.melonhu.indexrecord.ui.components.DetailTopAppBar
import com.melonhu.indexrecord.ui.detail.history.RecordHistoryAction
import com.melonhu.indexrecord.markdown.RenderMarkdownText


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuestionDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenAnswer: (UniversalRecord) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val viewModel: QuestionDetailViewModel = viewModel(
        key = "question-detail:${record.platform}:${record.platformId}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                QuestionDetailViewModel(record) as T
        }
    )
    val state by viewModel.state.collectAsState()
    val isRetrying by viewModel.isRetrying.collectAsState()
    val current = state.snapshot.record
    val excerpt = state.snapshot.excerpt
    val topics = state.snapshot.topics
    val sortedAnswerRefs = state.sortedAnswerRefs
    val existingAnswers = state.snapshot.existingAnswers
    val sort = state.sort




    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(title = "问题详情", onBack = onBack, actions = { RecordHistoryAction(record) })
        }
    ) { padding ->
        DetailLoadContent(state.loadState, isRetrying, viewModel::retry, Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                state = listState
            ) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = current.title.ifBlank { "（未缓存题目）" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (current.content.isBlank() && excerpt.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = excerpt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    // Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                }

                item {
                    if (current.content.isNotBlank()) {
                        val body = com.melonhu.indexrecord.presentation.recordBodyPresentation(current)
                        RenderMarkdownText(
                            markdown = body.content,
                            contentFormat = body.contentFormat,
                            enableMath = body.enableMath,
                            modifier = Modifier.fillMaxWidth(),
                            enableScroll = false,
                            selectable = true,
                            onImageClick = { _, _ -> }
                        )
                    } else {
                        val hint = excerpt.ifBlank { "（问题详情未缓存）" }
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    if (topics.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            // verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            for (t in topics) {
                                FilterChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(t.name) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "回答 (${sortedAnswerRefs.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        FilterChip(
                            selected = sort == AnswerSort.VOTEUP,
                            onClick = { viewModel.setSort(AnswerSort.VOTEUP) },
                            label = { Text("按赞同") }
                        )
                        FilterChip(
                            selected = sort == AnswerSort.LATEST,
                            onClick = { viewModel.setSort(AnswerSort.LATEST) },
                            label = { Text("按时间") }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (sortedAnswerRefs.isEmpty()) {
                    item {
                        Text(
                            text = "暂无回答列表缓存（需要抓到 /questions/<id>/feeds 接口）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                } else {
                    items(sortedAnswerRefs, key = { it.id }) { ref ->
                        QuestionAnswerCard(ref, existingAnswers[ref.id], onOpenAnswer)
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
