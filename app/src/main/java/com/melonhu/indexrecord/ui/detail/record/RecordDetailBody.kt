package com.melonhu.indexrecord.ui.detail.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.data.model.RecordContent
import com.melonhu.indexrecord.presentation.recordBodyPresentation
import com.melonhu.indexrecord.markdown.RenderMarkdownText
import com.melonhu.indexrecord.presentation.displayPublishTime
import com.melonhu.indexrecord.ui.components.CoverImage

@Composable
internal fun RecordDetailBody(record: RecordContent, onImageClick: (List<String>, Int) -> Unit) {
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
    RecordAttachments(presentation.images, onImageClick)
    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = displayPublishTime(record.publishTime),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordAttachments(images: List<String>, onImageClick: ((List<String>, Int) -> Unit)?) {
    if (images.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEachIndexed { index, url ->
            val thumbnail = Modifier.size(88.dp)
            CoverImage(url, if (onImageClick == null) thumbnail else thumbnail.clickable { onImageClick(images, index) })
        }
    }
}
