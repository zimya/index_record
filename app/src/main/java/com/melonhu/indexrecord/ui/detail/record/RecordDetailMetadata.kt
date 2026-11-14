package com.melonhu.indexrecord.ui.detail.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.presentation.RecordStatistic
import com.melonhu.indexrecord.ui.components.statisticIcon

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordDetailMetadata(statistics: List<RecordStatistic>, hasMetadata: Boolean) {
    if (hasMetadata) {
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            statistics.forEach { com.melonhu.indexrecord.ui.components.StatisticCount(it) }
        }
    }
    Spacer(modifier = Modifier.height(32.dp))
}
