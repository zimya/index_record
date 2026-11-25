package com.melonhu.indexrecord.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.melonhu.indexrecord.presentation.RecordStatistic
import com.melonhu.indexrecord.presentation.StatisticKind

/** One accessible node per statistic; decorative icons and rounded text are not read separately. */
@Composable
internal fun StatisticCount(
    statistic: RecordStatistic,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outline,
    iconSize: Dp = 13.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall
) {
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = statistic.accessibilityText },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(statisticIcon(statistic.kind), contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
        Text(statistic.text, style = textStyle, color = color)
    }
}

internal fun statisticIcon(kind: StatisticKind): ImageVector = when (kind) {
    StatisticKind.LIKE -> Icons.Outlined.ThumbUp
    StatisticKind.DISLIKE -> Icons.Outlined.ThumbDown
    StatisticKind.REPLY -> Icons.Outlined.ChatBubbleOutline
    StatisticKind.REPOST -> Icons.Outlined.Repeat
    StatisticKind.VIEW -> Icons.Outlined.RemoveRedEye
    StatisticKind.SHARE -> Icons.Outlined.Share
    StatisticKind.QUOTE -> Icons.Outlined.FormatQuote
    StatisticKind.BOOKMARK -> Icons.Outlined.BookmarkBorder
    StatisticKind.LOCATION -> Icons.Outlined.Place
    StatisticKind.FAVORITE -> Icons.Outlined.StarOutline
    StatisticKind.DANMAKU -> Icons.Outlined.Subtitles
    StatisticKind.COIN -> Icons.Outlined.MonetizationOn
}
