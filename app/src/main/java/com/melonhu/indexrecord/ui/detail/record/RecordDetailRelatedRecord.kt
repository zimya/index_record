package com.melonhu.indexrecord.ui.detail.record

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.toEntity
import com.melonhu.indexrecord.data.repository.RelatedContent
import com.melonhu.indexrecord.ui.components.RelatedRecordCard

@Composable
internal fun RecordDetailRelatedRecord(
    record: DetailRecord,
    related: RelatedContent?,
    onOpenRecord: (UniversalRecord) -> Unit
) {
    if (related == null) return
    val target = related.record
    RelatedRecordCard(
        record = target,
        relation = related.relation,
        targetState = related.targetState,
        fallbackPlatform = record.platform,
        onClick = { target?.let { onOpenRecord(it.toEntity()) } }
    )
    Spacer(modifier = Modifier.height(16.dp))
}
