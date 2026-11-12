package me.melon.record.ui.detail.tieba


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.viewmodel.TiebaMetadata

@Composable
internal fun TiebaReplyPreview(
    owner: UniversalRecord,
    replies: List<UniversalRecord>,
    onOpenFloor: () -> Unit,
    onOpenRecord: (UniversalRecord) -> Unit
) {
    if (replies.isEmpty() && (owner.recordType == "thread" || TiebaMetadata(owner).number("reply_count") == 0L)) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(10.dp)) {
            // Text(
            //     "层主：${owner.authorName.ifBlank { "昵称未记录" }}",
            //     style = MaterialTheme.typography.labelMedium,
            //     color = MaterialTheme.colorScheme.primary
            // )
            replies.take(3).forEach { reply ->
                val preview = remember(reply.content) {
                    reply.content.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "[图片]")
                        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                        .replace('\n', ' ').trim().ifBlank { "正文尚未记录" }
                }
                val meta = remember(reply.extraMetadata) { TiebaMetadata(reply) }
                val target = meta.text("reply_to_name").ifBlank { meta.text("reply_to_id") }
                Text(
                    "${reply.authorName.ifBlank { "昵称未记录" }}${if (target.isBlank() || preview.startsWith("回复")) "" else " 回复 $target"}：$preview",
                    modifier = Modifier.fillMaxWidth().clickable { onOpenRecord(reply) }.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
