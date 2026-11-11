package me.melon.record.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.theme.getPlatformThemeColor
import me.melon.record.ui.theme.isAppInDarkTheme
import org.jsoup.Jsoup

@Composable
fun RelatedRecordCard(
    record: UniversalRecord,
    fallbackPlatform: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platform = record.platform.ifBlank {
        when (record.recordType) {
            "subject" -> "douban"
            "question" -> "zhihu"
            else -> fallbackPlatform
        }
    }
    val platformColor = getPlatformThemeColor(platform, isAppInDarkTheme())
    val label = when {
        record.recordType == "subject" -> "所属豆瓣条目"
        record.recordType == "question" -> "对应知乎问题"
        platform == "twitter" -> "关联 X 推文"
        platform == "tieba" -> "所属贴吧主帖"
        else -> "关联内容"
    }
    val title = remember(record.title, record.content, record.authorName) {
        record.title.ifBlank {
            Jsoup.parse(record.content).text().ifBlank { record.authorName }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = platformColor.container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = platformColor.text,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = platformColor.text.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
