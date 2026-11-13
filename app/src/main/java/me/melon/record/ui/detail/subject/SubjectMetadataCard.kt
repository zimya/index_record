package me.melon.record.ui.detail.subject

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.DoubanSubjectMetadata

@Composable
internal fun SubjectMetadataCard(subject: DoubanSubjectMetadata, isDark: Boolean) {
    with(subject) {
        val cardBg = if (isDark) MaterialTheme.colorScheme.surface.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = cardBg
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "条目信息",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Movie & TV
                if (directors.isNotEmpty()) MetaInfoRow("导演", directors.joinToString(" / "))
                if (actors.isNotEmpty()) MetaInfoRow("主演", actors.take(8).joinToString(" / "))
                if (writers.isNotEmpty()) MetaInfoRow("编剧", writers.take(4).joinToString(" / "))
                if (countries.isNotEmpty()) MetaInfoRow("制片地区", countries.joinToString(" / "))
                if (languages.isNotEmpty()) MetaInfoRow("语言", languages.joinToString(" / "))
                if (pubdate.isNotEmpty()) MetaInfoRow("上映/首播", pubdate.joinToString(" / "))
                if (durations.isNotEmpty()) MetaInfoRow("片长", durations.joinToString(" / "))
                if (episodesCount > 0) MetaInfoRow("集数", "${episodesCount}集")

                // Book
                if (author.isNotEmpty()) MetaInfoRow("作者", author.joinToString(" / "))
                if (translator.isNotEmpty()) MetaInfoRow("译者", translator.joinToString(" / "))
                if (press.isNotEmpty()) MetaInfoRow("出版社", press.joinToString(" / "))
                if (pages.isNotEmpty()) MetaInfoRow("页数", pages.joinToString(" / ") + "页")
                if (price.isNotEmpty()) MetaInfoRow("定价", price.joinToString(" / "))
                if (binding.isNotBlank()) MetaInfoRow("装帧", binding)
                if (isbn13.isNotBlank()) MetaInfoRow("ISBN", isbn13)

                // Game
                if (developers.isNotEmpty()) MetaInfoRow("开发商", developers.joinToString(" / "))
                if (publishers.isNotEmpty()) MetaInfoRow("发行商", publishers.joinToString(" / "))
                if (platforms.isNotEmpty()) MetaInfoRow("平台", platforms.joinToString(" / "))
                if (releaseDate.isNotBlank()) MetaInfoRow("发售日期", releaseDate)

                // Music
                if (singer.isNotEmpty()) MetaInfoRow("表演者", singer.joinToString(" / "))
                if (discs.isNotEmpty()) MetaInfoRow("唱片数", discs.joinToString(" / "))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}


@Composable
private fun MetaInfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(68.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
