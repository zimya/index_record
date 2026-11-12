package me.melon.record.ui.detail.record


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.ThumbUpOffAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.components.EngagementCount
import java.text.SimpleDateFormat
import java.util.Date
import org.jsoup.Jsoup
import java.util.Locale

@Composable
internal fun RecordDetailCommentsHeader(
    count: Int,
    commentSort: RecordDetailCommentSort,
    onSortChange: (RecordDetailCommentSort) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "评论 (${count})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = commentSort == RecordDetailCommentSort.DEFAULT,
                onClick = { onSortChange(RecordDetailCommentSort.DEFAULT) },
                label = { Text("默认") }
            )
            FilterChip(
                selected = commentSort == RecordDetailCommentSort.LATEST,
                onClick = { onSortChange(RecordDetailCommentSort.LATEST) },
                label = { Text("最新") }
            )
        }
    }
}

@Composable
internal fun RecordDetailCommentItem(row: RecordDetailCommentRow, onOpenRecord: (UniversalRecord) -> Unit) {
    val comment = row.record
    val depth = row.depth
    val commentMeta = remember(comment.extraMetadata) {
        try {
            JsonParser.parseString(comment.extraMetadata).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }
    val likeCount = if (commentMeta.has("like_count") && !commentMeta.get("like_count").isJsonNull) commentMeta.get("like_count").asLong else 0L
    val dislikeCount = if (commentMeta.has("dislike_count") && !commentMeta.get("dislike_count").isJsonNull) commentMeta.get("dislike_count").asLong else 0L

    Surface(
        onClick = { onOpenRecord(comment) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = (depth.coerceAtMost(4) * 24).dp,
                bottom = 12.dp
            ),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (depth > 0) 0.2f else 0.4f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(url = comment.authorAvatar, size = 28.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = comment.authorName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (likeCount > 0) {
                    EngagementCount(count = likeCount, icon = Icons.Outlined.ThumbUpOffAlt)
                }
                if (dislikeCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    EngagementCount(count = dislikeCount, icon = Icons.Outlined.ThumbDownOffAlt)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            val cleanContent = Jsoup.parse(comment.content).text()
            Text(
                text = cleanContent,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
            Text(
                text = sdf.format(Date(comment.publishTime)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
