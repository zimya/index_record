package me.melon.record.ui.detail.subject

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.DetailTopAppBar
import me.melon.record.parser.douban.DoubanUtils.safeString
import me.melon.record.ui.components.ImageViewerDialog
import me.melon.record.ui.theme.isAppInDarkTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenRecord: (UniversalRecord) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val data = rememberSubjectDetailData(record)
    val subject = rememberSubjectMetadata(data.record)
    val relatedRecords = data.relatedRecords
    val contentReady = data.contentReady
    var viewerUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    var isIntroExpanded by remember { mutableStateOf(false) }
    var relatedFilter by remember { mutableStateOf(SubjectRelatedFilter.ALL) }
    val interestCount = remember(relatedRecords) { relatedRecords.count { it.recordType == "interest" } }
    val reviewCount = remember(relatedRecords) { relatedRecords.count { it.recordType == "review" } }
    val topicCount = remember(relatedRecords) { relatedRecords.count { it.recordType == "topic" } }

    val filteredList = remember(relatedRecords, relatedFilter) {
        when (relatedFilter) {
            SubjectRelatedFilter.ALL -> relatedRecords
            SubjectRelatedFilter.INTEREST -> relatedRecords.filter { it.recordType == "interest" }
            SubjectRelatedFilter.REVIEW -> relatedRecords.filter { it.recordType == "review" }
            SubjectRelatedFilter.TOPIC -> relatedRecords.filter { it.recordType == "topic" }
        }
    }
    val isDark = isAppInDarkTheme()
    val animatedBgColor = rememberSubjectBackgroundColor(
        subject.coverUrl, subject.meta.safeString("header_bg_color")
    )
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = animatedBgColor,
        topBar = {
            DetailTopAppBar(
                title = "豆瓣 · ${subject.subtypeLabel}",
                onBack = onBack,
                containerColor = animatedBgColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    ) { innerPadding ->
        if (!contentReady) return@Scaffold

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(animatedBgColor)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            state = listState
        ) {
            item {
                SubjectHeader(subject, onCoverClick = { url ->
                    viewerUrls = listOf(url)
                    viewerIndex = 0
                })
            }
            item { SubjectRatingCard(subject, isDark) }
            item { SubjectMetadataCard(subject, isDark) }
            if (subject.intro.isNotBlank()) {
                item {
                    SubjectIntroduction(
                        intro = subject.intro,
                        isExpanded = isIntroExpanded,
                        onToggle = { isIntroExpanded = !isIntroExpanded },
                        onImageClick = { urls, idx ->
                            viewerUrls = urls
                            viewerIndex = idx
                        }
                    )
                }
            }
            item {
                SubjectRelatedFilter(
                    relatedRecords, relatedFilter, { relatedFilter = it },
                    interestCount, reviewCount, topicCount
                )
            }
            if (filteredList.isEmpty()) {
                item { SubjectRelatedEmptyState() }
            } else {
                items(filteredList, key = { it.id }) { item ->
                    SubjectRelatedRecordItem(record = item, onClick = { onOpenRecord(item) })
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (viewerUrls.isNotEmpty()) {
        ImageViewerDialog(
            urls = viewerUrls,
            initialIndex = viewerIndex,
            onDismiss = { viewerUrls = emptyList() }
        )
    }
}
