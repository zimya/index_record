package me.melon.record.ui.detail.comment


import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.ThumbUpOffAlt

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

import androidx.compose.runtime.remember

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import me.melon.record.markdown.RenderMarkdownText

import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.DetailTopAppBar
import me.melon.record.data.model.toEntity
import me.melon.record.viewmodel.CommentDetailViewModel
import me.melon.record.viewmodel.DetailLoadState
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.components.EngagementCount
import me.melon.record.ui.components.RatingStars
import me.melon.record.ui.components.RelatedRecordCard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenParent: (UniversalRecord) -> Unit
) {
    val viewModel: CommentDetailViewModel = viewModel(
        key = "comment-detail:${record.platform}:${record.platformId}",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CommentDetailViewModel(record) as T
        }
    )
    val state by viewModel.state.collectAsState()
    val snapshot = state.snapshot
    val current = snapshot.record
    val likeCount = snapshot.likeCount
    val dislikeCount = snapshot.dislikeCount
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(title = "评论详情", onBack = onBack)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            (state.loadState as? DetailLoadState.Failed)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error)
            }
            val starCount = snapshot.starCount
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(url = current.authorAvatar, size = 40.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = current.authorName.ifBlank { if (current.platform == "douban") "豆瓣用户" else "" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (starCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    RatingStars(starCount)
                }
                Spacer(modifier = Modifier.weight(1f))
                if (likeCount > 0) {
                    EngagementCount(count = likeCount, icon = Icons.Outlined.ThumbUpOffAlt)
                }
                if (dislikeCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    EngagementCount(count = dislikeCount, icon = Icons.Outlined.ThumbDownOffAlt)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            RenderMarkdownText(
                markdown = current.content,
                modifier = Modifier.fillMaxWidth(),
                enableScroll = false,
                selectable = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = sdf.format(Date(current.publishTime)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(24.dp))

            val target = snapshot.related
            if (target != null) {
                RelatedRecordCard(
                    record = target.toEntity(),
                    fallbackPlatform = current.platform,
                    onClick = { onOpenParent(target.toEntity()) }
                )
            }
        }
    }
}
