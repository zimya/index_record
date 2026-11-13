package me.melon.record.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.RecordRelation
import me.melon.record.data.model.RelatedTargetState
import me.melon.record.data.model.UniversalRecord
import me.melon.record.presentation.relatedRecordLabel
import me.melon.record.presentation.relatedTargetNotice
import me.melon.record.presentation.relatedTargetStateLabel
import me.melon.record.presentation.recordPreviewText
import me.melon.record.ui.theme.getPlatformThemeColor
import me.melon.record.ui.theme.isAppInDarkTheme


@Composable
internal fun RelatedRecordCard(
    record: UniversalRecord?,
    relation: RecordRelation,
    targetState: RelatedTargetState,
    fallbackPlatform: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platform = record?.platform?.takeIf { it.isNotBlank() }
        ?: relation.target.platform.ifBlank { fallbackPlatform }
    val platformColor = getPlatformThemeColor(platform, isAppInDarkTheme())
    val label = relatedRecordLabel(relation)
    val notice = relatedTargetNotice(targetState)
    val canOpen = record != null && targetState != RelatedTargetState.MISSING
    val title = remember(
        platform,
        record?.platform,
        record?.title,
        record?.content,
        record?.contentFormat,
        record?.recordType,
        record?.extraMetadata,
        record?.authorName,
        targetState
    ) {
        if (targetState == RelatedTargetState.MISSING || record == null) {
            relatedTargetNotice(RelatedTargetState.MISSING).orEmpty()
        } else {
            record.title.ifBlank {
                recordPreviewText(record).ifBlank { record.authorName }
            }.ifBlank { "未记录标题" }
        }
    }

    Card(
        onClick = onClick,
        enabled = canOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = platformColor.container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$label · ${relatedTargetStateLabel(targetState)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = platformColor.text,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (notice != null && targetState != RelatedTargetState.MISSING) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (canOpen) {
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = platformColor.text.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
