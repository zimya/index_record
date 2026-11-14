package me.melon.record.ui.detail.record


import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding


import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.RecordContent
import me.melon.record.ui.components.DetailTopAppBar
import me.melon.record.ui.components.ImageViewerDialog
import me.melon.record.presentation.replySectionPresentation
import me.melon.record.ui.detail.DetailLoadContent
import androidx.compose.runtime.collectAsState
import me.melon.record.data.PlatformCatalog
import me.melon.record.ui.components.recordTypeLabel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue


internal fun recordDetailTitle(record: RecordContent): String {
    val platform = PlatformCatalog.displayName(record.platform)
    val type = recordTypeLabel(record)
    return "$platform · $type"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenRecord: (UniversalRecord) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    var commentSort by rememberSaveable { mutableStateOf(RecordDetailCommentSort.DEFAULT) }
    var viewerUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    val viewModel = rememberRecordDetailViewModel(record)
    val data by viewModel.state.collectAsState()
    val isRetrying by viewModel.isRetrying.collectAsState()
    val detailRecord = data.snapshot.record
    val comments = data.snapshot.comments

    val commentRows = rememberRecordDetailCommentRows(comments, commentSort, detailRecord)
    val replySection = replySectionPresentation(detailRecord, commentRows.size)
    val onImageClick: (List<String>, Int) -> Unit = { urls, index ->
        viewerUrls = urls
        viewerIndex = index
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(
                title = recordDetailTitle(detailRecord),
                onBack = onBack
            )
        }
    ) { padding ->
        DetailLoadContent(data.loadState, isRetrying, viewModel::retry, Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                state = listState
            ) {
                item { RecordDetailHeader(detailRecord, onImageClick) }
                item { RecordDetailBody(detailRecord, onImageClick) }
                item { RecordDetailMetadata(data.statistics, data.hasMetadata) }
                item { RecordDetailRelatedRecord(data.snapshot.record, data.snapshot.related, onOpenRecord) }

                item {
                    RecordDetailCommentsHeader(
                        count = commentRows.size,
                        commentSort = commentSort,
                        platform = detailRecord.platform,
                        hasNextPage = replySection.hasMore
                    ) { commentSort = it }
                }
                item {
                    replySection.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(
                    count = commentRows.size,
                    key = { index -> commentRows[index].stableKey }
                ) { index ->
                    RecordDetailCommentItem(commentRows[index], onOpenRecord, onImageClick)
                }
                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }

    if (viewerUrls.isNotEmpty()) {
        ImageViewerDialog(
            urls = viewerUrls,
            initialIndex = viewerIndex,
            onDismiss = { viewerUrls = emptyList() }
        )
    }
}
