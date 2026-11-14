package com.melonhu.indexrecord.ui.detail.question


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.model.toEntity
import com.melonhu.indexrecord.data.repository.ZhihuAnswerRef
import com.melonhu.indexrecord.ui.components.AvatarImage
import com.melonhu.indexrecord.ui.components.StatisticCount
import com.melonhu.indexrecord.presentation.questionAnswerPresentation
import com.melonhu.indexrecord.presentation.displayPublishTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionAnswerCard(
    ref: ZhihuAnswerRef,
    stored: DetailRecord?,

    onOpenAnswer: (UniversalRecord) -> Unit
) {
    val enabled = stored != null
    val presentation = questionAnswerPresentation(ref, stored)

    Surface(
        onClick = { stored?.let { onOpenAnswer(it.toEntity()) } },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.35f else 0.18f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(url = presentation.authorAvatar, size = 32.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = presentation.author,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

            }
            if (presentation.preview.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = presentation.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (presentation.statistics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presentation.statistics.forEach { StatisticCount(it) }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = displayPublishTime(presentation.publishTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (!enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "未缓存该回答，无法展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
