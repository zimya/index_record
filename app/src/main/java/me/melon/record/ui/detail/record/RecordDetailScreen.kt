package me.melon.record.ui.detail.record

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toEntity
import me.melon.record.ui.components.DetailTopAppBar
import me.melon.record.ui.components.ImageViewerDialog
import me.melon.record.presentation.RedditMetadata
import me.melon.record.ui.detail.DetailLoadContent
import androidx.compose.runtime.collectAsState
import me.melon.record.data.PlatformCatalog
import me.melon.record.ui.components.recordTypeLabel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

@Composable
internal fun MetadataBadge(icon: ImageVector, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

internal fun recordDetailTitle(record: UniversalRecord): String {
    val platform = PlatformCatalog.displayName(record.platform)
    val type = when {
        record.platform == "threads" && record.recordType == "thread" -> "主帖"
        record.platform == "threads" && record.recordType == "comment" -> "回复"
        else -> recordTypeLabel(record)
    }
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
    val detailRecord = remember(data.snapshot) { data.snapshot.record.toEntity() }
    val comments = remember(data.snapshot) { data.snapshot.comments.map { it.toEntity() } }

    val redditMetadata = remember(detailRecord.platform, detailRecord.extraMetadata) {
        if (detailRecord.platform == "reddit") RedditMetadata(detailRecord.extraMetadata) else null
    }


    val commentRows = rememberRecordDetailCommentRows(comments, commentSort, detailRecord)
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

                if (commentRows.isNotEmpty() || detailRecord.platform == "reddit") {
                    item {
                        RecordDetailCommentsHeader(
                            count = commentRows.size,
                            commentSort = commentSort,
                            platform = detailRecord.platform,
                            hasNextPage = redditMetadata?.commentsHasNextPage
                        ) { commentSort = it }
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
    }

    if (viewerUrls.isNotEmpty()) {
        ImageViewerDialog(
            urls = viewerUrls,
            initialIndex = viewerIndex,
            onDismiss = { viewerUrls = emptyList() }
        )
    }
}
