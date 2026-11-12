package me.melon.record.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.HomeRecordCard
import me.melon.record.ui.theme.getPlatformThemeColor
import me.melon.record.ui.theme.isAppInDarkTheme
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
internal fun AnswerGroupItem(
    questionTitle: String,
    answers: List<UniversalRecord>,
    searchQuery: String,
    onOpenAnswer: (UniversalRecord) -> Unit
) {
    val isDark = isAppInDarkTheme()
    val platformColor = getPlatformThemeColor("zhihu", isDark)
    val collapseByDefault by me.melon.record.settings.RecordSettings.collapseMultipleItems.collectAsState()
    var expanded by rememberSaveable(collapseByDefault) { mutableStateOf(!collapseByDefault) }
    val collapsible = answers.size > 1

    HomeRecordCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = if (collapsible) ({ expanded = !expanded }) else null
    ) {
        Column(
            modifier = Modifier.padding(
                top = 16.dp,
                bottom = if (!collapsible || expanded) 0.dp else 16.dp
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Surface(
                    color = platformColor.container,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "知乎 · 回答",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = platformColor.text,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                GroupTitle(
                    title = questionTitle,
                    searchQuery = searchQuery,
                    collapsible = collapsible,
                    expanded = expanded
                )

            }

            AnimatedVisibility(
                visible = !collapsible || expanded,
                enter = expandVertically(tween(250)) + fadeIn(tween(250)),
                exit = shrinkVertically(tween(250)) + fadeOut(tween(250))
            ) {
                Column {
                    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                    answers.forEachIndexed { index, answer ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenAnswer(answer) }
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 10.dp,
                                    bottom = if (index == answers.lastIndex) 16.dp else 10.dp
                                )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = highlightText(answer.authorName.ifBlank { "未知作者" }, searchQuery),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "浏览于 ${sdf.format(Date(answer.captureTime))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            val previewText = previewFromRecordContent(answer.content)
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
                        }
                }            }
            }
        }
    }
}

@Composable
internal fun InterestGroupItem(
    subjectTitle: String,
    typeLabel: String,
    interests: List<UniversalRecord>,
    searchQuery: String,
    onOpenInterest: (UniversalRecord) -> Unit
) {
    val isDark = isAppInDarkTheme()
    val platformColor = getPlatformThemeColor("douban", isDark)
    val collapseByDefault by me.melon.record.settings.RecordSettings.collapseMultipleItems.collectAsState()
    var expanded by rememberSaveable(collapseByDefault) { mutableStateOf(!collapseByDefault) }
    val collapsible = interests.size > 1

    HomeRecordCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = if (collapsible) ({ expanded = !expanded }) else null
    ) {
        Column(
            modifier = Modifier.padding(
                top = 16.dp,
                bottom = if (!collapsible || expanded) 0.dp else 16.dp
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Surface(
                    color = platformColor.container,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "豆瓣 · $typeLabel",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = platformColor.text,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                GroupTitle(
                    title = subjectTitle,
                    searchQuery = searchQuery,
                    collapsible = collapsible,
                    expanded = expanded
                )

            }

            AnimatedVisibility(
                visible = !collapsible || expanded,
                enter = expandVertically(tween(250)) + fadeIn(tween(250)),
                exit = shrinkVertically(tween(250)) + fadeOut(tween(250))
            ) {
                Column {
                    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                    interests.forEachIndexed { index, interest ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenInterest(interest) }
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 10.dp,
                                    bottom = if (index == interests.lastIndex) 16.dp else 10.dp
                                )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = highlightText(interest.authorName.ifBlank { "豆瓣用户" }, searchQuery),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "浏览于 ${sdf.format(Date(interest.captureTime))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            val previewText = previewFromRecordContent(interest.content)
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
                        }
                }            }
            }
        }
    }
}

@Composable
private fun GroupTitle(
    title: String,
    searchQuery: String,
    collapsible: Boolean,
    expanded: Boolean
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(250),
        label = "groupArrowRotation"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = highlightText(title.ifBlank { "无标题" }, searchQuery),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (collapsible) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun previewFromRecordContent(content: String): String {
    if (content.isBlank()) return ""
    val text = try {
        Jsoup.parse(content).text()
    } catch (_: Exception) {
        content
    }
    return text.replace('\n', ' ').replace('\r', ' ').trim()
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
