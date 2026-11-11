package me.melon.record.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
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
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.viewmodel.TiebaMetadata
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
        "comment" -> if (record.platform == "tieba") "楼中楼" else "评论"
        "thread" -> "主帖"
        "floor" -> "楼层"
        "topic" -> "帖子"
        "review" -> "长评"
        "interest" -> "短评"
        "subject" -> "条目"
        "tweet" -> "推文"
        else -> record.recordType
    }

    val tiebaMeta = remember(record.platform, record.extraMetadata) {
        if (record.platform == "tieba") TiebaMetadata(record) else null
    }
    val displayTitle = record.title.ifBlank {
        tiebaMeta?.text("thread_title").orEmpty().ifBlank { "无标题" }
    }

    HomeRecordCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isDark = isAppInDarkTheme()
                val platformColor = getPlatformThemeColor(record.platform, isDark)
                val isTweetCard = record.platform == "twitter" && record.recordType == "tweet"
                Surface(
                    color = if (isTweetCard) Color.Black else platformColor.container,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (isTweetCard) "X · tweet" else "$platformLabel · $typeLabel",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = if (isTweetCard) Color.White else platformColor.text,
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
                text = highlightText(displayTitle, searchQuery),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (tiebaMeta != null) {
                val contextLabel = listOfNotNull(
                    tiebaMeta.text("forum_name").takeIf { it.isNotBlank() }?.let { "${it}吧" },
                    tiebaMeta.number("floor")?.takeIf { it > 0 }?.let { "$it 楼" }
                ).joinToString(" · ")
                if (contextLabel.isNotBlank()) {
                    Text(
                        text = highlightText(contextLabel, searchQuery),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

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

            if (record.platform == "twitter") {
                val twitterMeta = remember(record.extraMetadata) {
                    try {
                        JsonParser.parseString(record.extraMetadata).asJsonObject
                    } catch (_: Exception) {
                        null
                    }
                }
                if (twitterMeta != null) {
                    val lk = if (twitterMeta.has("like_count") && !twitterMeta.get("like_count").isJsonNull) {
                        twitterMeta.get("like_count").asLong
                    } else if (twitterMeta.has("voteup_count") && !twitterMeta.get("voteup_count").isJsonNull) {
                        twitterMeta.get("voteup_count").asLong
                    } else 0L

                    val rt = if (twitterMeta.has("retweet_count") && !twitterMeta.get("retweet_count").isJsonNull) {
                        twitterMeta.get("retweet_count").asLong
                    } else 0L

                    val rp = if (twitterMeta.has("reply_count") && !twitterMeta.get("reply_count").isJsonNull) {
                        twitterMeta.get("reply_count").asLong
                    } else if (twitterMeta.has("comment_count") && !twitterMeta.get("comment_count").isJsonNull) {
                        twitterMeta.get("comment_count").asLong
                    } else 0L

                    val vw = if (twitterMeta.has("views_count") && !twitterMeta.get("views_count").isJsonNull) {
                        twitterMeta.get("views_count").asLong
                    } else 0L

                    if (lk > 0L || rt > 0L || rp > 0L || vw > 0L) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (rp > 0L) {
                                StatItem(Icons.Outlined.ChatBubbleOutline, me.melon.record.parser.x.XUtils.formatCount(rp))
                            }
                            if (rt > 0L) {
                                StatItem(Icons.Outlined.Repeat, me.melon.record.parser.x.XUtils.formatCount(rt))
                            }
                            if (lk > 0L) {
                                StatItem(Icons.Outlined.ThumbUp, me.melon.record.parser.x.XUtils.formatCount(lk))
                            }
                            if (vw > 0L) {
                                StatItem(Icons.Outlined.RemoveRedEye, me.melon.record.parser.x.XUtils.formatCount(vw))
                            }
                        }
                    }
                }
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
        "subject" -> {
            if (record.content.isNotBlank() && record.content != record.title) {
                record.content
            } else {
                try {
                    val obj = JsonParser.parseString(record.extraMetadata).asJsonObject
                    val intro = if (obj.has("intro") && !obj.get("intro").isJsonNull) obj.get("intro").asString else ""
                    val cardSub = if (obj.has("card_subtitle") && !obj.get("card_subtitle").isJsonNull) obj.get("card_subtitle").asString else ""
                    intro.ifBlank { cardSub }
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

@Composable
private fun StatItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
