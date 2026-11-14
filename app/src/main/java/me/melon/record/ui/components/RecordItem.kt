package me.melon.record.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding



import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.melon.record.data.PlatformCatalog
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.RecordContent
import me.melon.record.presentation.recordListPresentation

import me.melon.record.ui.theme.getPlatformThemeColor
import me.melon.record.ui.theme.isAppInDarkTheme
import java.util.Locale
import java.util.TimeZone


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordItem(record: UniversalRecord, searchQuery: String, onClick: () -> Unit) {
    val presentation = remember(
        record.platform, record.recordType, record.title, record.content, record.authorName,
        record.extraMetadata, record.captureTime, searchQuery, Locale.getDefault(), TimeZone.getDefault()
    ) {
        recordListPresentation(record, searchQuery)
    }

    HomeRecordCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isDark = isAppInDarkTheme()
                val platformColor = getPlatformThemeColor(record.platform, isDark)
                val isTweetCard = presentation.useBlackBadge
                Surface(
                    color = if (isTweetCard) Color.Black else platformColor.container,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = presentation.badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = if (isTweetCard) Color.White else platformColor.text,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = presentation.capturedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = highlightText(presentation.title, searchQuery),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (presentation.context.isNotBlank()) {
                Text(
                    text = highlightText(presentation.context, searchQuery),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = buildAnnotatedString {
                    append("作者: ")
                    append(highlightText(presentation.author, searchQuery))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val previewText = presentation.preview
            if (previewText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = highlightText(previewText, searchQuery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (presentation.statistics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presentation.statistics.forEach { StatisticCount(it) }
                }
            }

            presentation.snippet?.let { snippet ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = highlightText(snippet, searchQuery),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

internal fun recordTypeLabel(record: RecordContent): String =
    PlatformCatalog.typeLabel(record.platform, record.recordType)


@Composable
private fun highlightText(text: String, query: String): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(query, start, ignoreCase = true)
            if (index == -1) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            withStyle(
                style = SpanStyle(
                    background = MaterialTheme.colorScheme.primaryContainer,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(text.substring(index, index + query.length))
            }
            start = index + query.length
        }
    }
}
