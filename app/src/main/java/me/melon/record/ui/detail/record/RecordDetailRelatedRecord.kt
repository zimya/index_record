package me.melon.record.ui.detail.record

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toEntity
import me.melon.record.data.repository.RelatedContent
import me.melon.record.ui.components.RelatedRecordCard

@Composable
internal fun RecordDetailRelatedRecord(
    record: DetailRecord,
    related: RelatedContent?,
    onOpenRecord: (UniversalRecord) -> Unit
) {
    if (related == null) return
    val target = related.record
    RelatedRecordCard(
        record = target?.toEntity(),
        relation = related.relation,
        targetState = related.targetState,
        fallbackPlatform = record.platform,
        onClick = { target?.let { onOpenRecord(it.toEntity()) } }
    )
    Spacer(modifier = Modifier.height(16.dp))
}
