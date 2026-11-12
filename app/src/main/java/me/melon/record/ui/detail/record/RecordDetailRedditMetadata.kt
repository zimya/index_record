package me.melon.record.ui.detail.record

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.Composable
import me.melon.record.parser.x.XUtils
import me.melon.record.ui.components.RedditMetadata

@Composable
internal fun RecordDetailRedditMetadata(metadata: RedditMetadata) {
    metadata.score?.let { MetadataBadge(Icons.Outlined.ThumbUp, XUtils.formatCount(it)) }
    metadata.commentCount?.let { MetadataBadge(Icons.Outlined.ChatBubbleOutline, XUtils.formatCount(it)) }
    metadata.shareCount?.let { MetadataBadge(Icons.Outlined.Share, XUtils.formatCount(it)) }
}
