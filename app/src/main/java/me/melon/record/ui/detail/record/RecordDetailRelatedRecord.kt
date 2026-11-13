package me.melon.record.ui.detail.record

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toEntity
import me.melon.record.data.repository.RelatedContent
import me.melon.record.presentation.redditMissingParentNotice
import me.melon.record.ui.components.RelatedRecordCard

@Composable
internal fun RecordDetailRelatedRecord(
    record: DetailRecord,
    related: RelatedContent?,
    onOpenRecord: (UniversalRecord) -> Unit
) {
    val target = related?.record
    if (target != null) {
        RelatedRecordCard(
            record = target.toEntity(),
            fallbackPlatform = record.platform,
            onClick = { onOpenRecord(target.toEntity()) }
        )
        Spacer(modifier = Modifier.height(16.dp))
    } else if (record.platform == "reddit" && (record.recordType == "comment" || related != null)) {
        Text(
            text = redditMissingParentNotice(related?.relation?.target?.storageIds()?.firstOrNull().orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
