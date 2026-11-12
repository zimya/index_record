package me.melon.record.ui.detail.tieba

import me.melon.record.ui.detail.record.MetadataBadge


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUpOffAlt
import androidx.compose.material3.Icon
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
import me.melon.record.data.model.UniversalRecord
import me.melon.record.markdown.RenderMarkdownText
import me.melon.record.ui.components.AvatarImage
import me.melon.record.viewmodel.TiebaMetadata
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TiebaPost(
    record: UniversalRecord,
    label: String,
    onImageClick: (List<String>, Int) -> Unit,
    isMainPost: Boolean = false
) {
    val meta = remember(record.extraMetadata) { TiebaMetadata(record) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarImage(url = record.authorAvatar, size = if (isMainPost) 40.dp else 28.dp)
        Spacer(Modifier.width(if (isMainPost) 12.dp else 8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                record.authorName.ifBlank { "昵称未记录" },
                style = if (isMainPost) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge,
                fontWeight = if (isMainPost) FontWeight.Medium else FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (!isMainPost) {
            meta.number("agree_count")?.takeIf { it > 0 }?.let { likes ->
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.ThumbUpOffAlt,
                    contentDescription = "点赞数",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(likes.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    if (isMainPost) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    } else {
        Spacer(Modifier.height(4.dp))
    }
    val target = meta.text("reply_to_name").ifBlank {
        meta.text("reply_to_id").takeIf { it.isNotBlank() }?.let { "用户 ID $it" }.orEmpty()
    }
    if (target.isNotBlank()) {
        Text("回复 $target", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
    if (record.content.isBlank()) {
        TiebaLocalNotice("正文尚未记录")
    } else {
        RenderMarkdownText(
            markdown = record.content,
            modifier = Modifier.fillMaxWidth(),
            enableScroll = false,
            selectable = true,
            onImageClick = onImageClick
        )
    }
    if (record.publishTime > 0) {
        val time = remember(record.publishTime) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(record.publishTime))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            time,
            style = if (isMainPost) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
    if (isMainPost) {
        Spacer(Modifier.height(16.dp))
        TiebaPostStats(record)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TiebaPostStats(record: UniversalRecord) {
    val meta = remember(record.extraMetadata) { TiebaMetadata(record) }
    val likes = meta.number("agree_count")
    val replies = meta.number("reply_count").takeUnless { record.recordType == "comment" }
    val favorites = meta.number("favlists_count") ?: meta.number("bookmark_count")
    if (likes == null && replies == null && favorites == null) return

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        likes?.let { MetadataBadge(Icons.Outlined.ThumbUp, it.toString()) }
        replies?.let { MetadataBadge(Icons.Outlined.ChatBubbleOutline, it.toString()) }
        favorites?.let { MetadataBadge(Icons.Outlined.StarOutline, it.toString()) }
    }
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
