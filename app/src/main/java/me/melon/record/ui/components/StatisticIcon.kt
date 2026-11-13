package me.melon.record.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.ui.graphics.vector.ImageVector
import me.melon.record.presentation.StatisticKind

internal fun statisticIcon(kind: StatisticKind): ImageVector = when (kind) {
    StatisticKind.LIKE -> Icons.Outlined.ThumbUp
    StatisticKind.REPLY -> Icons.Outlined.ChatBubbleOutline
    StatisticKind.REPOST -> Icons.Outlined.Repeat
    StatisticKind.VIEW -> Icons.Outlined.RemoveRedEye
    StatisticKind.SHARE -> Icons.Outlined.Share
    StatisticKind.QUOTE -> Icons.Outlined.FormatQuote
    StatisticKind.BOOKMARK -> Icons.Outlined.BookmarkBorder
    StatisticKind.LOCATION -> Icons.Outlined.Place
    StatisticKind.FAVORITE -> Icons.Outlined.StarOutline
}
