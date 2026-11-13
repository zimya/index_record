package me.melon.record.ui.detail.record

import androidx.compose.runtime.Composable
import me.melon.record.presentation.RedditMetadata
import me.melon.record.presentation.redditStatistics
import me.melon.record.ui.components.statisticIcon

@Composable
internal fun RecordDetailRedditMetadata(metadata: RedditMetadata) {
    redditStatistics(metadata).forEach { MetadataBadge(statisticIcon(it.kind), it.text) }
}
