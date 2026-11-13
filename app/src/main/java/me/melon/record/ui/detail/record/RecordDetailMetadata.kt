package me.melon.record.ui.detail.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.presentation.RecordMetadata
import me.melon.record.presentation.StatisticsSurface
import me.melon.record.presentation.recordStatistics
import me.melon.record.ui.components.statisticIcon

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordDetailMetadata(record: UniversalRecord) {
    val metadata = RecordMetadata(record.extraMetadata, record.platform)
    val statistics = recordStatistics(record, StatisticsSurface.DETAIL, metadata)
    if (metadata.hasValues) {
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            statistics.forEach { MetadataBadge(statisticIcon(it.kind), it.text) }
        }
    }
    Spacer(modifier = Modifier.height(32.dp))
}
