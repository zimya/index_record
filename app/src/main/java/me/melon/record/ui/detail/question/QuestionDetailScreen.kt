package me.melon.record.ui.detail.question

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.DetailTopAppBar
import me.melon.record.markdown.RenderMarkdownText
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuestionDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenAnswer: (UniversalRecord) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    var sort by remember { mutableStateOf(AnswerSort.VOTEUP) }
    val (excerpt, topics, sortedAnswerRefs, existingAnswers) = rememberQuestionDetailData(record, sort)

    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }


    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(title = "问题详情", onBack = onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            state = listState
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = record.title.ifBlank { "（未缓存题目）" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (record.content.isBlank() &&excerpt.isNotBlank()) {
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
                if (record.content.isNotBlank()) {
                    RenderMarkdownText(
                        markdown = record.content,
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
                        onClick = { sort = AnswerSort.VOTEUP },
                        label = { Text("按赞同") }
                    )
                    FilterChip(
                        selected = sort == AnswerSort.LATEST,
                        onClick = { sort = AnswerSort.LATEST },
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
                    QuestionAnswerCard(ref, existingAnswers[ref.id], sdf, onOpenAnswer)
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
