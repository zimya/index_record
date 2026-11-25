package com.melonhu.indexrecord.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.data.model.bodyPlainText
import com.melonhu.indexrecord.presentation.contentSnippet
import com.melonhu.indexrecord.presentation.recordListPresentation
import com.melonhu.indexrecord.search.SearchMatchSource
import com.melonhu.indexrecord.search.SearchResultItem
import com.melonhu.indexrecord.ui.components.HomeRecordCard
import com.melonhu.indexrecord.ui.components.StatisticCount
import com.melonhu.indexrecord.ui.theme.getPlatformThemeColor
import com.melonhu.indexrecord.ui.theme.isAppInDarkTheme
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchResultCard(
    item: SearchResultItem,
    highlightTerms: List<String>,
    onClick: () -> Unit,
    onImageClick: (urls: List<String>, initialIndex: Int) -> Unit = { _, _ -> }
) {
    val record = item.record
    val primaryTerm = highlightTerms.firstOrNull().orEmpty()
    val presentation = remember(
        record.id,
        record.platform,
        record.recordType,
        record.title,
        record.content,
        record.authorName,
        record.extraMetadata,
        record.captureTime,
        highlightTerms,
        Locale.getDefault(),
        TimeZone.getDefault()
    ) {
        val base = recordListPresentation(record, primaryTerm)
        val bodyPlain = record.bodyPlainText().ifBlank { base.preview }
        val bestTerm = highlightTerms.firstOrNull { term -> bodyPlain.contains(term, ignoreCase = true) }
        val snippet = if (bestTerm != null) contentSnippet(bodyPlain, bestTerm) else base.snippet
        base.copy(snippet = snippet)
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

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = highlightKeywords(presentation.title, highlightTerms),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (presentation.context.isNotBlank()) {
                    Text(
                        text = highlightKeywords(presentation.context, highlightTerms),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = buildAnnotatedString {
                        append(stringResource(R.string.common_author_prefix))
                        append(highlightKeywords(presentation.author, highlightTerms))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
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

            val isDark = isAppInDarkTheme()

            // 1. 文本匹配 (TEXT_EXACT)
            if (SearchMatchSource.TEXT_EXACT in item.sources) {
                val textExactSnippet = item.textExactSnippet
                    ?: presentation.snippet
                    ?: record.bodyPlainText().take(120).ifBlank { presentation.preview }
                if (textExactSnippet.isNotBlank()) {
                    val theme = getSourceColorTheme(SearchMatchSource.TEXT_EXACT, isDark)
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = theme.container,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = theme.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = theme.accent
                                )
                                Text(
                                    text = stringResource(R.string.search_source_text_exact),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.accent
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = highlightKeywords(textExactSnippet, highlightTerms),
                                style = MaterialTheme.typography.bodySmall,
                                color = theme.content,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 2. 文本语义 (TEXT_SEMANTIC)
            if (SearchMatchSource.TEXT_SEMANTIC in item.sources) {
                val textSemanticSnippet = item.textSemanticSnippet
                    ?: presentation.snippet
                    ?: record.bodyPlainText().take(120).ifBlank { presentation.preview }
                val theme = getSourceColorTheme(SearchMatchSource.TEXT_SEMANTIC, isDark)
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = theme.container,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = theme.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = theme.accent
                                )
                                Text(
                                    text = stringResource(R.string.search_source_text_semantic),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.accent
                                )
                                val sim = item.textSimilarity
                                if (sim != null && sim > 0f) {
                                    val pct = (sim * 100).toInt().coerceIn(1, 100)
                                    Text(
                                        text = stringResource(R.string.search_visual_similarity_label, pct),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.accent.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        if (textSemanticSnippet.isNotBlank()) {
                            Text(
                                text = highlightKeywords(textSemanticSnippet, highlightTerms),
                                style = MaterialTheme.typography.bodySmall,
                                color = theme.content,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = stringResource(R.string.search_text_semantic_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.content.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            // 3. 图内文字 OCR (OCR_EXACT)
            if (SearchMatchSource.OCR_EXACT in item.sources && item.matchedOcrItems.isNotEmpty()) {
                val theme = getSourceColorTheme(SearchMatchSource.OCR_EXACT, isDark)
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = theme.container,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val ocrPagerState = rememberPagerState { item.matchedOcrItems.size }
                        val ocrUrls = remember(item.matchedOcrItems) {
                            item.matchedOcrItems.map { it.imageUrl }.filter { it.isNotBlank() }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = theme.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = theme.accent
                                )
                                Text(
                                    text = stringResource(R.string.search_ocr_snippet_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.accent
                                )
                            }

                            if (item.matchedOcrItems.size > 1) {
                                Text(
                                    text = "${ocrPagerState.currentPage + 1}/${item.matchedOcrItems.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = theme.accent
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (item.matchedOcrItems.size == 1) {
                            val ocr = item.matchedOcrItems.first()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                if (ocr.imageUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = ocr.imageUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                onImageClick(ocrUrls.ifEmpty { listOf(ocr.imageUrl) }, 0)
                                            },
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Text(
                                    text = highlightKeywords(ocr.snippet, highlightTerms),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = theme.content,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            HorizontalPager(
                                state = ocrPagerState,
                                modifier = Modifier.fillMaxWidth()
                            ) { page ->
                                val ocr = item.matchedOcrItems[page]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    if (ocr.imageUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = ocr.imageUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    onImageClick(ocrUrls.ifEmpty { listOf(ocr.imageUrl) }, page)
                                                },
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                    }
                                    Text(
                                        text = highlightKeywords(ocr.snippet, highlightTerms),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = theme.content,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. 图片画面 (IMAGE_VISUAL)
            if (SearchMatchSource.IMAGE_VISUAL in item.sources && item.matchedVisualItems.isNotEmpty()) {
                val theme = getSourceColorTheme(SearchMatchSource.IMAGE_VISUAL, isDark)
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = theme.container,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val visualPagerState = rememberPagerState { item.matchedVisualItems.size }
                        val visualUrls = remember(item.matchedVisualItems) {
                            item.matchedVisualItems.map { it.imageUrl }.filter { it.isNotBlank() }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = theme.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = theme.accent
                                )
                                Text(
                                    text = stringResource(R.string.search_source_image_visual),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.accent
                                )
                                if (item.matchedVisualItems.size == 1) {
                                    val sim = item.matchedVisualItems.first().similarity
                                    if (sim > 0f) {
                                        val pct = (sim * 100).toInt().coerceIn(1, 100)
                                        Text(
                                            text = stringResource(R.string.search_visual_similarity_label, pct),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = theme.accent.copy(alpha = 0.85f)
                                        )
                                    }
                                }
                            }

                            if (item.matchedVisualItems.size > 1) {
                                Text(
                                    text = "${visualPagerState.currentPage + 1}/${item.matchedVisualItems.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = theme.accent
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (item.matchedVisualItems.size == 1) {
                            val vis = item.matchedVisualItems.first()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = vis.imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            onImageClick(visualUrls.ifEmpty { listOf(vis.imageUrl) }, 0)
                                        },
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.search_visual_matched_image_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = theme.content.copy(alpha = 0.85f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            HorizontalPager(
                                state = visualPagerState,
                                modifier = Modifier.fillMaxWidth()
                            ) { page ->
                                val vis = item.matchedVisualItems[page]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = vis.imageUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                onImageClick(visualUrls.ifEmpty { listOf(vis.imageUrl) }, page)
                                            },
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        if (vis.similarity > 0f) {
                                            val pct = (vis.similarity * 100).toInt().coerceIn(1, 100)
                                            Text(
                                                text = stringResource(R.string.search_visual_similarity_label, pct).removePrefix("· ").trim(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = theme.accent,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                        }
                                        Text(
                                            text = stringResource(R.string.search_visual_matched_image_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = theme.content.copy(alpha = 0.85f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class SourceColorTheme(
    val container: Color,
    val accent: Color,
    val content: Color,
    val icon: ImageVector
)

private fun getSourceColorTheme(source: SearchMatchSource, isDark: Boolean): SourceColorTheme {
    val contentColor = if (isDark) Color(0xFFE5E7EB) else Color.Black
    return when (source) {
        SearchMatchSource.TEXT_EXACT -> SourceColorTheme(
            container = if (isDark) Color(0xFF162436) else Color(0xFFEFF6FF),
            accent = if (isDark) Color(0xFF93C5FD) else Color.Black,
            content = contentColor,
            icon = Icons.Outlined.TextFields
        )
        SearchMatchSource.OCR_EXACT -> SourceColorTheme(
            container = if (isDark) Color(0xFF2D2414) else Color(0xFFFFFBEB),
            accent = if (isDark) Color(0xFFFCD34D) else Color.Black,
            content = contentColor,
            icon = Icons.AutoMirrored.Outlined.TextSnippet
        )
        SearchMatchSource.TEXT_SEMANTIC -> SourceColorTheme(
            container = if (isDark) Color(0xFF241C38) else Color(0xFFF5F3FF),
            accent = if (isDark) Color(0xFFC4B5FD) else Color.Black,
            content = contentColor,
            icon = Icons.Outlined.Psychology
        )
        SearchMatchSource.IMAGE_VISUAL -> SourceColorTheme(
            container = if (isDark) Color(0xFF331B26) else Color(0xFFFFF1F5),
            accent = if (isDark) Color(0xFFF9A8D4) else Color.Black,
            content = contentColor,
            icon = Icons.Outlined.ImageSearch
        )
    }
}

@Composable
internal fun highlightKeywords(text: String, terms: List<String>): AnnotatedString {
    if (text.isEmpty() || terms.isEmpty()) return AnnotatedString(text)
    val validTerms = terms.filter { it.isNotBlank() }
    if (validTerms.isEmpty()) return AnnotatedString(text)

    val ranges = ArrayList<IntRange>()
    for (term in validTerms) {
        var start = 0
        while (start < text.length) {
            val idx = text.indexOf(term, start, ignoreCase = true)
            if (idx < 0) break
            ranges.add(idx until (idx + term.length))
            start = idx + term.length
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(text)

    ranges.sortWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
    val merged = ArrayList<IntRange>(ranges.size)
    for (r in ranges) {
        val last = merged.lastOrNull()
        if (last == null || r.first > last.last + 1) {
            merged.add(r)
        } else if (r.last > last.last) {
            merged[merged.lastIndex] = last.first..r.last
        }
    }

    val highlightStyle = SpanStyle(
        background = Color(0xFFFDE047),
        color = Color.Black,
        fontWeight = FontWeight.Bold
    )

    return buildAnnotatedString {
        var cursor = 0
        for (r in merged) {
            if (r.first > cursor) {
                append(text.substring(cursor, r.first))
            }
            withStyle(highlightStyle) {
                append(text.substring(r.first, r.last + 1))
            }
            cursor = r.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
