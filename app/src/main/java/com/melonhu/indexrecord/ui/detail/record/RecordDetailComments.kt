package com.melonhu.indexrecord.ui.detail.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.RecordContent
import com.melonhu.indexrecord.data.model.toEntity
import com.melonhu.indexrecord.markdown.RenderMarkdownText
import com.melonhu.indexrecord.presentation.StatisticsSurface
import com.melonhu.indexrecord.presentation.displayAuthor
import com.melonhu.indexrecord.presentation.displayPublishTime
import com.melonhu.indexrecord.presentation.recordBodyPresentation
import com.melonhu.indexrecord.presentation.recordStatistics
import com.melonhu.indexrecord.ui.components.AvatarImage
import com.melonhu.indexrecord.ui.components.StatisticCount
import com.melonhu.indexrecord.ui.detail.localRecordedCountLabel

@Suppress("UNUSED_PARAMETER")
internal fun recordDetailCommentCountLabel(platform: String, count: Int): String = localRecordedCountLabel(count)

@Composable
internal fun RecordDetailCommentsHeader(
    count: Int,
    commentSort: RecordDetailCommentSort,
    platform: String = "",
    hasNextPage: Boolean? = null,
    onSortChange: (RecordDetailCommentSort) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = recordDetailCommentCountLabel(platform, count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        if (hasNextPage == true) {
            Text("平台仍有更多回复未采集", style = MaterialTheme.typography.bodySmall)
        }
        if (count > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = commentSort == RecordDetailCommentSort.DEFAULT,
                    onClick = { onSortChange(RecordDetailCommentSort.DEFAULT) },
                    label = { Text("按互动排序") }
                )
                FilterChip(
                    selected = commentSort == RecordDetailCommentSort.LATEST,
                    onClick = { onSortChange(RecordDetailCommentSort.LATEST) },
                    label = { Text("最新") }
                )
            }
        }
    }
}

@Composable
internal fun RecordDetailCommentItem(
    row: RecordDetailCommentRow,
    onOpenRecord: (UniversalRecord) -> Unit,
    onImageClick: ((List<String>, Int) -> Unit)? = null
) {
    val comment = row.record
    Surface(
        onClick = { onOpenRecord(comment.toEntity()) },
        modifier = Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(4) * 24).dp, bottom = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (row.depth > 0) 0.2f else 0.4f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            row.missingParentId?.let {
                Text(
                    text = if (it.isBlank()) "孤立分支 · 父节点关系未记录" else "孤立分支 · 回复对象本地缺失：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (row.hasInvalidParentChain) {
                Text("孤立分支 · 父回复关系异常", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (row.depth >= 4) {
                Text("本分支第 ${row.depth + 1} 级回复", style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(url = comment.authorAvatar, size = 28.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(displayAuthor(comment.authorName), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(4.dp))
            val body = recordBodyPresentation(comment)
            MaterialTheme(
                typography = MaterialTheme.typography.copy(bodyLarge = MaterialTheme.typography.bodyMedium)
            ) {
                RenderMarkdownText(
                    markdown = body.content,
                    modifier = Modifier.fillMaxWidth(),
                    enableScroll = false,
                    selectable = false,
                    onImageClick = onImageClick,
                    contentFormat = body.contentFormat,
                    enableMath = body.enableMath
                )
            }
            RecordAttachments(body.images, onImageClick)
            RecordCommentStatistics(comment)
            Spacer(modifier = Modifier.height(8.dp))
            Text(displayPublishTime(comment.publishTime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RecordCommentStatistics(record: RecordContent) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        recordStatistics(record, StatisticsSurface.DETAIL).forEach { StatisticCount(it) }
    }
}
