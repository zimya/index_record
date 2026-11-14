package com.melonhu.indexrecord.ui.home

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
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.ui.components.HomeRecordCard
import com.melonhu.indexrecord.ui.theme.getPlatformThemeColor
import com.melonhu.indexrecord.ui.theme.isAppInDarkTheme

import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.presentation.displayAuthor
import com.melonhu.indexrecord.presentation.displayCaptureTime
import com.melonhu.indexrecord.presentation.StatisticsSurface
import com.melonhu.indexrecord.presentation.recordPreviewText
import com.melonhu.indexrecord.presentation.recordStatistics
import com.melonhu.indexrecord.ui.components.StatisticCount
import androidx.compose.runtime.saveable.rememberSaveable

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnswerGroupItem(
    questionTitle: String,
    answers: List<UniversalRecord>,
    searchQuery: String,
    onOpenAnswer: (UniversalRecord) -> Unit
) {
    val isDark = isAppInDarkTheme()
    val platformColor = getPlatformThemeColor("zhihu", isDark)
    val collapseByDefault by com.melonhu.indexrecord.settings.RecordSettings.collapseMultipleItems.collectAsState()
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
                        text = PlatformCatalog.recordLabel("zhihu", "answer"),
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
                                    text = highlightText(displayAuthor(answer.authorName), searchQuery),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = displayCaptureTime(answer.captureTime),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            val previewText = recordPreviewText(answer)
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
                            val statistics = recordStatistics(answer, StatisticsSurface.LIST)
                            if (statistics.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    statistics.forEach { StatisticCount(it) }
                                }
                            }
                        }
                    }
                }
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
    val collapseByDefault by com.melonhu.indexrecord.settings.RecordSettings.collapseMultipleItems.collectAsState()
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
                        text = "${PlatformCatalog.displayName("douban")} · $typeLabel",
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
                                    text = highlightText(displayAuthor(interest.authorName), searchQuery),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = displayCaptureTime(interest.captureTime),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            val previewText = recordPreviewText(interest)
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
