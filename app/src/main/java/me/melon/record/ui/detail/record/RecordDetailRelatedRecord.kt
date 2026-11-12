package me.melon.record.ui.detail.record

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.RelatedRecordCard

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
    }
}
