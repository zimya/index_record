package me.melon.record.ui.detail.subject

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.DetailRecord

internal enum class SubjectRelatedFilter { ALL, INTEREST, REVIEW, TOPIC }

@Composable
internal fun SubjectRelatedFilter(relatedRecords: List<DetailRecord>, relatedFilter: SubjectRelatedFilter, onFilterChange: (SubjectRelatedFilter) -> Unit, interestCount: Int, reviewCount: Int, topicCount: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "关联内容 (${relatedRecords.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = relatedFilter == SubjectRelatedFilter.ALL,
                onClick = { onFilterChange(SubjectRelatedFilter.ALL) },
                label = { Text("全部 (${relatedRecords.size})") }
            )
            if (interestCount > 0) {
                FilterChip(
                    selected = relatedFilter == SubjectRelatedFilter.INTEREST,
                    onClick = { onFilterChange(SubjectRelatedFilter.INTEREST) },
                    label = { Text("短评 ($interestCount)") }
                )
            }
            if (reviewCount > 0) {
                FilterChip(
                    selected = relatedFilter == SubjectRelatedFilter.REVIEW,
                    onClick = { onFilterChange(SubjectRelatedFilter.REVIEW) },
                    label = { Text("长评 ($reviewCount)") }
                )
            }
            if (topicCount > 0) {
                FilterChip(
                    selected = relatedFilter == SubjectRelatedFilter.TOPIC,
                    onClick = { onFilterChange(SubjectRelatedFilter.TOPIC) },
                    label = { Text("讨论 ($topicCount)") }
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}
