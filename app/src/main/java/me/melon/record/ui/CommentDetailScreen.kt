package me.melon.record.ui


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.ThumbUpOffAlt

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import me.melon.record.markdown.RenderMarkdownText
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.components.RatingStars
import me.melon.record.ui.components.RelatedRecordCard
import me.melon.record.parser.douban.DoubanUtils.safeDouble
import org.jsoup.Jsoup
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
    var related by remember { mutableStateOf<UniversalRecord?>(null) }

    LaunchedEffect(record.platformId) {
        withContext(Dispatchers.IO) {
            val box = ObjectBox.store.boxFor(UniversalRecord::class.java)

            var current = record
            var guard = 0
            while ((current.parentId.startsWith("zhihu_comment_") || current.parentId.startsWith("douban_comment_") || current.parentId.startsWith("twitter_comment_")) && guard < 30) {
                val parent = box.query()
                    .equal(UniversalRecord_.platformId, current.parentId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()
                    .findFirst() ?: break
                current = parent
                guard++
            }

            val targetId = current.parentId
            related = if (targetId.isNotBlank()) {
                val found = box.query()
                    .equal(UniversalRecord_.platformId, targetId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()
                    .findFirst()
                if (found == null && (targetId.startsWith("twitter_tweet_") || targetId.startsWith("twitter_comment_"))) {
                    val altTargetId = if (targetId.startsWith("twitter_tweet_")) {
                        "twitter_comment_" + targetId.removePrefix("twitter_tweet_")
                    } else {
                        "twitter_tweet_" + targetId.removePrefix("twitter_comment_")
                    }
                    box.query()
                        .equal(UniversalRecord_.platformId, altTargetId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .build()
                        .findFirst()
                } else {
                    found
                }
            } else {
                null
            }
        }
    }

    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val cleanContent = remember(record.content) { Jsoup.parse(record.content).text() }
    val meta = remember(record.extraMetadata) {
        try {
            JsonParser.parseString(record.extraMetadata).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }
    val likeCount = if (meta.has("like_count") && !meta.get("like_count").isJsonNull) meta.get("like_count").asLong else 0L
    val dislikeCount = if (meta.has("dislike_count") && !meta.get("dislike_count").isJsonNull) meta.get("dislike_count").asLong else 0L

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("评论详情", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            val starCount = remember(meta) {
                meta.safeDouble("star_count").let {
                    if (it > 0) it else meta.safeDouble("rating_value")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(url = record.authorAvatar, size = 40.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = record.authorName.ifBlank { if (record.platform == "douban") "豆瓣用户" else "" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (starCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    RatingStars(starCount)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = sdf.format(Date(record.publishTime)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.weight(1f))
                if (likeCount > 0) {
                    Icon(
                        imageVector = Icons.Outlined.ThumbUpOffAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = likeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (dislikeCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Outlined.ThumbDownOffAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = dislikeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            RenderMarkdownText(
                markdown = record.content,
                modifier = Modifier.fillMaxWidth(),
                enableScroll = false,
                selectable = true,
            )
            Spacer(modifier = Modifier.height(24.dp))

            val target = related
            if (target != null) {
                RelatedRecordCard(
                    record = target,
                    fallbackPlatform = record.platform,
                    onClick = { onOpenParent(target) }
                )
            }
        }
    }
}
