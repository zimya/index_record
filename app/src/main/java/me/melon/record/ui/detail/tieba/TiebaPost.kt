package me.melon.record.ui.detail.tieba

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.TiebaMetadata
import me.melon.record.data.model.UniversalRecord
import me.melon.record.markdown.RenderMarkdownText
import me.melon.record.presentation.displayAuthor
import me.melon.record.presentation.displayPublishTime
import me.melon.record.presentation.recordBodyPresentation
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.detail.record.RecordCommentStatistics

@Composable
internal fun TiebaPost(
    record: UniversalRecord,
    label: String,
    onImageClick: (List<String>, Int) -> Unit,
    isMainPost: Boolean = false
) {
    val meta = remember(record.extraMetadata) { TiebaMetadata(record.extraMetadata) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarImage(url = record.authorAvatar, size = if (isMainPost) 40.dp else 28.dp)
        Spacer(Modifier.width(if (isMainPost) 12.dp else 8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayAuthor(record.authorName),
                style = if (isMainPost) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge,
                fontWeight = if (isMainPost) FontWeight.Medium else FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    if (isMainPost) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    } else {
        Spacer(Modifier.height(4.dp))
    }
    val target = meta.replyToName.ifBlank {
        meta.replyToId.takeIf { it.isNotBlank() }?.let { "用户 ID $it" }.orEmpty()
    }
    if (target.isNotBlank()) {
        Text("回复 $target", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
    val body = recordBodyPresentation(record)
    if (body.content.isBlank()) {
        TiebaLocalNotice("正文尚未记录")
    } else {
        RenderMarkdownText(
            markdown = body.content,
            modifier = Modifier.fillMaxWidth(),
            enableScroll = false,
            selectable = true,
            onImageClick = onImageClick,
            contentFormat = body.contentFormat,
            enableMath = body.enableMath
        )
    }
    RecordCommentStatistics(record)
    Spacer(Modifier.height(8.dp))
    Text(
        displayPublishTime(record.publishTime),
        style = if (isMainPost) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
internal fun TiebaLocalNotice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}
