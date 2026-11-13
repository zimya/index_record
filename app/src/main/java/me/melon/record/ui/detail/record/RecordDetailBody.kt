package me.melon.record.ui.detail.record

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.presentation.recordBodyPresentation
import me.melon.record.markdown.RenderMarkdownText
import me.melon.record.presentation.displayPublishTime

@Composable
internal fun RecordDetailBody(record: UniversalRecord, onImageClick: (List<String>, Int) -> Unit) {
    val presentation = recordBodyPresentation(record)
    RenderMarkdownText(
        markdown = presentation.content,
        modifier = Modifier.fillMaxWidth(),
        enableScroll = false,
        selectable = true,
        onImageClick = onImageClick,
        contentFormat = presentation.contentFormat,
        enableMath = presentation.enableMath
    )
    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = displayPublishTime(record.publishTime),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Spacer(modifier = Modifier.height(16.dp))
}
