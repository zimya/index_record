package me.melon.record.ui.detail.record

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.RelatedRecordCard

internal fun redditMissingParentNotice(parentId: String): String {
    if (parentId.isBlank()) return "父节点关系未记录"
    val label = when {
        parentId.startsWith("reddit_comment_") -> "父回复未捕获"
        parentId.startsWith("reddit_post_") -> "父帖子未捕获"
        else -> "父节点未捕获"
    }
    return "$label：$parentId"
}

@Composable
internal fun RecordDetailRelatedRecord(
    record: UniversalRecord,
    relatedRecord: UniversalRecord?,
    onOpenRecord: (UniversalRecord) -> Unit
) {
    val target = relatedRecord
    if (target != null) {
        RelatedRecordCard(
            record = target,
            fallbackPlatform = record.platform,
            onClick = { onOpenRecord(target) }
        )
        Spacer(modifier = Modifier.height(16.dp))
    } else if (record.platform == "reddit" && (record.recordType == "comment" || record.parentId.isNotBlank())) {
        Text(
            text = redditMissingParentNotice(record.parentId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
