package me.melon.record.ui.detail.question


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.toEntity
import me.melon.record.data.repository.ZhihuAnswerRef
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.components.EngagementCount
import org.jsoup.Jsoup
import me.melon.record.presentation.displayAuthor
import me.melon.record.presentation.displayPublishTime

@Composable
internal fun QuestionAnswerCard(
    ref: ZhihuAnswerRef,
    stored: DetailRecord?,

    onOpenAnswer: (UniversalRecord) -> Unit
) {
    val enabled = stored != null
    val excerpt = ref.excerpt.ifBlank {
        stored?.let { Jsoup.parse(it.content).text().take(140) } ?: ""
    }

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
                AvatarImage(url = ref.authorAvatar, size = 32.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayAuthor(ref.authorName),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (ref.voteupCount > 0) {
                    EngagementCount(count = ref.voteupCount, icon = Icons.Outlined.ThumbUp, label = "赞同数")
                }
            }
            if (excerpt.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val ts = (if (ref.updatedTimeSec > 0) ref.updatedTimeSec else ref.createdTimeSec) * 1000
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = displayPublishTime(ts),
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
