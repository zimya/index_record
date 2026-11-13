package me.melon.record.ui.detail.record


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.ThumbUpOffAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.melon.record.presentation.RecordMetadata
import me.melon.record.presentation.redditMissingParentNotice
import me.melon.record.presentation.commentStatistics
import me.melon.record.data.model.UniversalRecord

import me.melon.record.markdown.RenderMarkdownText
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.components.EngagementCount

import java.text.SimpleDateFormat
import java.util.Date
import me.melon.record.presentation.commentPlainText
import me.melon.record.presentation.recordBodyPresentation
import java.util.Locale

internal fun recordDetailCommentCountLabel(platform: String, count: Int): String =
    if (platform == "reddit") "已记录回复 ($count)" else "评论 ($count)"

@Composable
internal fun RecordDetailCommentsHeader(
    count: Int,
    commentSort: RecordDetailCommentSort,
    platform: String = "",
    hasNextPage: Boolean? = null,
    onSortChange: (RecordDetailCommentSort) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = recordDetailCommentCountLabel(platform, count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = commentSort == RecordDetailCommentSort.DEFAULT,
                onClick = { onSortChange(RecordDetailCommentSort.DEFAULT) },
                label = { Text(if (platform == "reddit") "净票数" else "默认") }
            )
            FilterChip(
                selected = commentSort == RecordDetailCommentSort.LATEST,
                onClick = { onSortChange(RecordDetailCommentSort.LATEST) },
                label = { Text("最新") }
            )
        }
    }
}

@Composable
internal fun RecordDetailCommentItem(
    row: RecordDetailCommentRow,
    onOpenRecord: (UniversalRecord) -> Unit,
    onImageClick: ((List<String>, Int) -> Unit)? = null
) {
    val comment = row.record
    val depth = row.depth
    val metadata = remember(comment.platform, comment.extraMetadata) {
        RecordMetadata(comment.extraMetadata, comment.platform)
    }
    val redditMetadata = metadata.reddit
    val statistics = commentStatistics(comment, metadata)
    val likeCount = statistics.likeCount
    val dislikeCount = statistics.dislikeCount

    Surface(
        onClick = { onOpenRecord(comment) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = (depth.coerceAtMost(4) * 24).dp,
                bottom = 12.dp
            ),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (depth > 0) 0.2f else 0.4f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (comment.platform == "reddit") {
                row.missingParentId?.let { parentId ->
                    Text(
                        text = "孤立分支 · ${redditMissingParentNotice(parentId)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                if (row.hasInvalidParentChain) {
                    Text(
                        text = "孤立分支 · 父回复关系异常",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                if (depth >= 4) {
                    Text(
                        text = "本分支第 ${depth + 1} 级回复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(url = comment.authorAvatar, size = 28.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = comment.authorName.ifBlank { if (comment.platform == "reddit") "Reddit 用户" else "" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = if (redditMetadata != null) Modifier.weight(1f) else Modifier,
                    maxLines = if (redditMetadata != null) 1 else Int.MAX_VALUE,
                    overflow = if (redditMetadata != null) TextOverflow.Ellipsis else TextOverflow.Clip
                )
                Spacer(modifier = if (redditMetadata != null) Modifier.width(8.dp) else Modifier.weight(1f))

                likeCount?.let {
                    EngagementCount(count = it, icon = Icons.Outlined.ThumbUpOffAlt)
                }
                statistics.replyCount?.let {
                    Spacer(modifier = Modifier.width(8.dp))
                    EngagementCount(count = it, icon = Icons.Outlined.ChatBubbleOutline)
                }
                statistics.shareCount?.let {
                    Spacer(modifier = Modifier.width(8.dp))
                    EngagementCount(count = it, icon = Icons.Outlined.Share)
                }
                if (dislikeCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    EngagementCount(count = dislikeCount, icon = Icons.Outlined.ThumbDownOffAlt)
                }
            }

            if (comment.platform == "threads") {
                val viewsCount = statistics.viewCount
                if (viewsCount > 0L) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EngagementCount(count = viewsCount, icon = Icons.Outlined.RemoveRedEye)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (comment.platform == "reddit") {
                val body = recordBodyPresentation(comment)
                RenderMarkdownText(
                    markdown = body.content,
                    modifier = Modifier.fillMaxWidth(),
                    enableScroll = false,
                    selectable = false,
                    onImageClick = onImageClick,
                    contentFormat = body.contentFormat,
                    enableMath = body.enableMath
                )
            } else {
                val cleanContent = commentPlainText(comment)
                Text(
                    text = cleanContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (comment.platform == "reddit" && comment.publishTime <= 0L) "发布时间未知" else sdf.format(Date(comment.publishTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

            }
        }
    }
}
