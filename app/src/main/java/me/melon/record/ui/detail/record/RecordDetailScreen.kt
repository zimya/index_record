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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.DetailTopAppBar
import me.melon.record.ui.components.ImageViewerDialog
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenRecord: (UniversalRecord) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    var commentSort by remember { mutableStateOf(RecordDetailCommentSort.DEFAULT) }
    var viewerUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    val data = rememberRecordDetailData(record)

    // 等列表项完整后再挂载列表，避免临时显示顶部以及钳制保存的滚动位置。
    val contentReady = data.commentsLoaded && data.relatedLoaded
    val commentRows = rememberRecordDetailCommentRows(data.allComments, commentSort, record)
    val onImageClick: (List<String>, Int) -> Unit = { urls, index ->
        viewerUrls = urls
        viewerIndex = index
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(
                title = if (record.platform == "zhihu" && record.recordType == "pin") "知乎 · 想法" else "内容详情",
                onBack = onBack
            )
        }
    ) { padding ->
        if (!contentReady) return@Scaffold

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            state = listState
        ) {
            item { RecordDetailHeader(record, onImageClick) }
            item { RecordDetailBody(record, onImageClick) }
            item { RecordDetailMetadata(record) }
            item { RecordDetailRelatedRecord(record, data.relatedRecord, onOpenRecord) }

            if (commentRows.isNotEmpty()) {
                item {
                    RecordDetailCommentsHeader(commentRows.size, commentSort) { commentSort = it }
                }
                items(commentRows.size) { index ->
                    RecordDetailCommentItem(commentRows[index], onOpenRecord)
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
