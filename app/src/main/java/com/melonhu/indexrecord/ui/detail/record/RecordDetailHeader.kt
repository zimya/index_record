package com.melonhu.indexrecord.ui.detail.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.presentation.recordHeaderPresentation
import com.melonhu.indexrecord.data.model.RecordContent
import androidx.compose.ui.text.style.TextOverflow
import com.melonhu.indexrecord.ui.components.AvatarImage
import com.melonhu.indexrecord.ui.components.CoverImage
import com.melonhu.indexrecord.ui.components.RatingStars


@Composable
internal fun RecordDetailHeader(record: RecordContent, onImageClick: (List<String>, Int) -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    val presentation = recordHeaderPresentation(record)
    val titleImage = presentation.titleImage
    if (titleImage.isNotBlank()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clickable {
                    onImageClick(listOf(titleImage), 0)
                },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = MaterialTheme.shapes.medium
        ) {
            CoverImage(url = titleImage, modifier = Modifier.fillMaxSize())
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
    if (com.melonhu.indexrecord.presentation.contentStructure(record).hasTitle) {
        Text(
            text = presentation.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
    val authorHeadline = presentation.authorHeadline
    val starCount = presentation.starCount
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarImage(url = record.authorAvatar, size = 40.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = presentation.author,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (starCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    RatingStars(starCount)
                }
            }
            if (authorHeadline.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = authorHeadline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
}
