package me.melon.record.ui.detail.subject

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import me.melon.record.data.PlatformCatalog
import me.melon.record.data.model.toEntity
import me.melon.record.presentation.displayAuthor
import me.melon.record.presentation.displayPublishTime
import me.melon.record.presentation.recordPreviewText
import me.melon.record.presentation.recordStatistics
import me.melon.record.presentation.StatisticsSurface
import me.melon.record.ui.components.StatisticCount
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.DoubanSubjectRelatedMetadata
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.components.RatingStars
import me.melon.record.ui.theme.getPlatformThemeColor
import me.melon.record.ui.theme.isAppInDarkTheme


@Composable
internal fun SubjectRelatedRecordItem(record: DetailRecord, onClick: () -> Unit) {
    val meta = remember(record.extraMetadata) { DoubanSubjectRelatedMetadata(record) }

    val typeBadge = PlatformCatalog.typeLabel(record.platform, record.recordType)

    val starCount = meta.starCount

    val isDark = isAppInDarkTheme()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) MaterialTheme.colorScheme.surface.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (record.authorAvatar.isNotBlank()) {
                        AvatarImage(url = record.authorAvatar, size = 26.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = displayAuthor(record.authorName),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (starCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        RatingStars(starCount)
                    }
                }

                val platformColor = getPlatformThemeColor("douban", isDark)
                Surface(
                    color = platformColor.container,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = typeBadge,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = platformColor.text
                    )
                }
            }

            if (record.recordType != "interest" && record.title.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val snippet = remember(record) { recordPreviewText(record.toEntity()) }
            if (snippet.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = displayPublishTime(record.publishTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recordStatistics(record.toEntity(), StatisticsSurface.LIST).forEach { StatisticCount(it) }
                }
            }
        }
    }
}
