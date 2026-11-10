package me.melon.record.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.theme.getPlatformThemeColor
import me.melon.record.ui.theme.isAppInDarkTheme
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordItem(record: UniversalRecord, searchQuery: String, onClick: () -> Unit) {
    val platformLabel = when (record.platform) {
        "zhihu" -> "知乎"
        "douban" -> "豆瓣"
        "tieba" -> "贴吧"
        "twitter" -> "Twitter(X)"
        "xiaohongshu" -> "小红书"
        else -> record.platform.replaceFirstChar { it.uppercase() }
    }
    val typeLabel = when (record.recordType) {
        "question" -> "问题"
        "answer" -> "回答"
        "article" -> "文章"
        "comment" -> "评论"
        "topic" -> "帖子"
        "review" -> "长评"
        "interest" -> "短评"
        "subject" -> "条目"
        else -> record.recordType
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isDark = isAppInDarkTheme()
                val platformColor = getPlatformThemeColor(record.platform, isDark)
                Surface(
                    color = platformColor.container,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "$platformLabel · $typeLabel",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = platformColor.text,
                        fontWeight = FontWeight.Medium
                    )
                }

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                Text(
                    text = "浏览于 ${sdf.format(Date(record.captureTime))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = highlightText(record.title.ifEmpty { "无标题" }, searchQuery),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = buildAnnotatedString {
                    append("作者: ")
                    append(highlightText(record.authorName, searchQuery))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val previewText = getPreviewText(record)
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

            if (searchQuery.isNotEmpty() && record.content.contains(searchQuery, ignoreCase = true)) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = getContentSnippet(record.content, searchQuery),
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

private fun getPreviewText(record: UniversalRecord): String {
    val raw = when (record.recordType) {
        "question" -> {
            if (record.content.isNotBlank()) {
                record.content
            } else {
                try {
                    val obj = JsonParser.parseString(record.extraMetadata).asJsonObject
                    if (obj.has("excerpt") && !obj.get("excerpt").isJsonNull) obj.get("excerpt").asString else ""
                } catch (_: Exception) {
                    ""
                }
            }
        }
        else -> record.content
    }

    val text = try {
        Jsoup.parse(raw).text()
    } catch (_: Exception) {
        raw
    }
    return text.replace('\n', ' ').replace('\r', ' ').trim()
}

@Composable
private fun getContentSnippet(content: String, query: String): AnnotatedString {
    val index = content.indexOf(query, ignoreCase = true)
    if (index == -1) return AnnotatedString(content.take(100))

    val start = (index - 40).coerceAtLeast(0)
    val end = (index + 80).coerceAtMost(content.length)
    val snippet = (if (start > 0) "..." else "") +
            content.substring(start, end) +
            (if (end < content.length) "..." else "")

    return highlightText(snippet, query)
}

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
