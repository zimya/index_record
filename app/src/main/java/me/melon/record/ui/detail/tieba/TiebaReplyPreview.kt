package me.melon.record.ui.detail.tieba


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import me.melon.record.ui.detail.localRecordedCountLabel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.TiebaMetadata
import me.melon.record.presentation.displayAuthor
import me.melon.record.presentation.recordPreviewText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TiebaReplyPreview(
    owner: UniversalRecord,
    replies: List<UniversalRecord>,
    onOpenFloor: () -> Unit,
    onOpenRecord: (UniversalRecord) -> Unit
) {
    if (replies.isEmpty() && (owner.recordType == "lou" || TiebaMetadata(owner.extraMetadata).replyCount == 0L)) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(top = 10.dp, bottom = 0.dp)) {
            Text(
                localRecordedCountLabel(replies.size),
                modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.labelMedium
            )
            replies.take(3).forEach { reply ->
                val preview = remember(reply.content, reply.contentFormat, reply.extraMetadata) {
                    recordPreviewText(reply).ifBlank { "正文尚未记录" }
                }
                val meta = remember(reply.extraMetadata) { TiebaMetadata(reply.extraMetadata) }
                val target = meta.replyToName.ifBlank { meta.replyToId }
                Text(
                    "${displayAuthor(reply.authorName)}${if (target.isBlank() || preview.startsWith("回复")) "" else " 回复 $target"}：$preview",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecord(reply) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentEnforcement provides false
            ) {
                TextButton(
                    onClick = onOpenFloor,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(
                        "查看全部已记录回复",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
