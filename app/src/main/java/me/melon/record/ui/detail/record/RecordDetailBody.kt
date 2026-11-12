package me.melon.record.ui.detail.record

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.markdown.RenderMarkdownText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RecordDetailBody(record: UniversalRecord, onImageClick: (List<String>, Int) -> Unit) {
    RenderMarkdownText(
        markdown = record.content,
        modifier = Modifier.fillMaxWidth(),
        enableScroll = false,
        selectable = true,
        onImageClick = onImageClick
    )
    Spacer(modifier = Modifier.height(8.dp))
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Text(
        text = sdf.format(Date(record.publishTime)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Spacer(modifier = Modifier.height(16.dp))
}
