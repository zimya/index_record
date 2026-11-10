package me.melon.record.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUpOffAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.markdown.RenderMarkdownText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.components.CoverImage
import me.melon.record.ui.components.ImageViewerDialog
import me.melon.record.ui.components.RatingStars
import me.melon.record.parser.douban.DoubanUtils.safeDouble
import me.melon.record.ui.theme.getPlatformThemeColor
import me.melon.record.ui.theme.isAppInDarkTheme
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord_
import org.jsoup.Jsoup
import java.util.Locale

private enum class CommentSort { DEFAULT, LATEST }

@Composable
private fun MetadataBadge(icon: ImageVector, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenRecord: (UniversalRecord) -> Unit = {}
) {
    var commentSort by remember { mutableStateOf(CommentSort.DEFAULT) }
    var allComments by remember { mutableStateOf<List<UniversalRecord>>(emptyList()) }
    var viewerUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    var relatedRecord by remember { mutableStateOf<UniversalRecord?>(null) }

    LaunchedEffect(record.platformId) {
        withContext(Dispatchers.IO) {
            val box = ObjectBox.store.boxFor(UniversalRecord::class.java)
            val rootComments = box.query()
                .equal(UniversalRecord_.parentId, record.platformId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                .equal(UniversalRecord_.recordType, "comment", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                .build()
                .find()

            val all = rootComments.toMutableList()
            val seen = rootComments.map { it.platformId }.toMutableSet()
            var frontier = rootComments.map { it.platformId }

            while (frontier.isNotEmpty()) {
                val parentIds = frontier.toTypedArray()
                val children = box.query()
                    .`in`(UniversalRecord_.parentId, parentIds, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .equal(UniversalRecord_.recordType, "comment", io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()
                    .find()

                val newChildren = children.filter { seen.add(it.platformId) }
                if (newChildren.isEmpty()) break
                all.addAll(newChildren)
                frontier = newChildren.map { it.platformId }
            }

            allComments = all
        }
    }

    LaunchedEffect(record.platformId, record.parentId) {
        withContext(Dispatchers.IO) {
            val box = ObjectBox.store.boxFor(UniversalRecord::class.java)

            fun queryRelated() {
                if (record.platform == "zhihu" && record.recordType == "answer" && record.parentId.startsWith("zhihu_question_")) {
                    relatedRecord = box.query()
                        .equal(UniversalRecord_.platformId, record.parentId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .build()
                        .findFirst()
                } else if (record.platform == "douban" && record.parentId.startsWith("douban_")) {
                    val found = box.query()
                        .equal(UniversalRecord_.platformId, record.parentId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .build()
                        .findFirst()
                    if (found != null) {
                        relatedRecord = found
                    } else {
                        // 若尚未捕获条目本体，根据记录中的 subject 元数据构造一个条目引用，点击后可查看聚合短评/长评
                        try {
                            val meta = JsonParser.parseString(record.extraMetadata).asJsonObject
                            val sId = if (meta.has("subject_id") && !meta.get("subject_id").isJsonNull) meta.get("subject_id").asString else ""
                            val sTitle = if (meta.has("subject_title") && !meta.get("subject_title").isJsonNull) meta.get("subject_title").asString else ""
                            val sType = if (meta.has("subject_type") && !meta.get("subject_type").isJsonNull) meta.get("subject_type").asString else "movie"
                            if (sId.isNotBlank() && sTitle.isNotBlank()) {
                                relatedRecord = UniversalRecord(
                                    platform = "douban",
                                    recordType = "subject",
                                    sourceApp = record.sourceApp,
                                    platformId = record.parentId,
                                    title = sTitle,
                                    content = sTitle,
                                    extraMetadata = record.extraMetadata
                                )
                            } else {
                                relatedRecord = null
                            }
                        } catch (_: Exception) {
                            relatedRecord = null
                        }
                    }
                } else {
                    relatedRecord = null
                }
            }

            queryRelated()

            if (record.parentId.isNotBlank()) {
                val sub = box.query()
                    .equal(UniversalRecord_.platformId, record.parentId, io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()
                    .subscribe()
                    .observer {
                        queryRelated()
                    }
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    sub.cancel()
                }
            }
        }
    }

    data class CommentRow(val record: UniversalRecord, val depth: Int)

    val commentRows = remember(allComments, commentSort, record.platformId) {
        val byId = allComments.associateBy { it.platformId }
        val childrenByParent = allComments.groupBy { it.parentId }
        val roots = childrenByParent[record.platformId].orEmpty()

        fun likeCount(r: UniversalRecord): Long {
            return try {
                val obj = JsonParser.parseString(r.extraMetadata).asJsonObject
                if (obj.has("like_count") && !obj.get("like_count").isJsonNull) obj.get("like_count").asLong else 0L
            } catch (_: Exception) {
                0L
            }
        }

        val sortedRoots = when (commentSort) {
            CommentSort.DEFAULT -> roots.sortedWith(compareByDescending<UniversalRecord> { likeCount(it) }.thenByDescending { it.publishTime })
            CommentSort.LATEST -> roots.sortedByDescending { it.publishTime }
        }

        val result = mutableListOf<CommentRow>()
        fun visit(node: UniversalRecord, depth: Int) {
            result.add(CommentRow(node, depth))
            val children = childrenByParent[node.platformId].orEmpty().sortedBy { it.publishTime }
            for (c in children) visit(c, depth + 1)
        }
        for (root in sortedRoots) {
            if (!byId.containsKey(root.platformId)) continue
            visit(root, 0)
        }
        result
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("内容详情", style = MaterialTheme.typography.titleMedium) },
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
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                val parsedMetadata = remember(record.extraMetadata) {
                    try {
                        JsonParser.parseString(record.extraMetadata).asJsonObject
                    } catch (_: Exception) {
                        JsonObject()
                    }
                }
                val titleImage = parsedMetadata.get("title_image")?.asString ?: ""
                if (titleImage.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clickable {
                                viewerUrls = listOf(titleImage)
                                viewerIndex = 0
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        CoverImage(url = titleImage, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (record.title.isNotEmpty()) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                val authorHeadline = parsedMetadata.get("author_headline")?.asString ?: ""
                val starCount = remember(parsedMetadata) {
                    parsedMetadata.safeDouble("star_count").let {
                        if (it > 0) it else parsedMetadata.safeDouble("rating_value")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(url = record.authorAvatar, size = 40.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = record.authorName.ifBlank { if (record.platform == "douban") "豆瓣用户" else "" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (starCount > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                RatingStars(starCount)
                            }
                        }
                        if (authorHeadline.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = authorHeadline,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    Text(
                        text = sdf.format(Date(record.publishTime)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            item {
                RenderMarkdownText(
                    markdown = record.content,
                    modifier = Modifier.fillMaxWidth(),
                    enableScroll = false,
                    selectable = true,
                    onImageClick = { urls, index ->
                        viewerUrls = urls
                        viewerIndex = index
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                val metadata = remember(record.extraMetadata) {
                    try {
                        JsonParser.parseString(record.extraMetadata).asJsonObject
                    } catch (_: Exception) {
                        JsonObject()
                    }
                }

                if (metadata.size() > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        metadata.get("voteup_count")?.let {
                            MetadataBadge(Icons.Outlined.ThumbUp, it.asString)
                        }
                        metadata.get("comment_count")?.let {
                            MetadataBadge(Icons.Outlined.ChatBubbleOutline, it.asString)
                        }
                        metadata.get("ip_info")?.let {
                            MetadataBadge(Icons.Outlined.Place, it.asString)
                        }
                        metadata.get("favlists_count")?.let {
                            MetadataBadge(Icons.Outlined.StarOutline, it.asString)
                        }
                    }
                }

                // Text(
                //     text = "元数据 (Ext Data):",
                //     style = MaterialTheme.typography.labelLarge,
                //     color = MaterialTheme.colorScheme.secondary,
                //     modifier = Modifier.padding(top = 16.dp)
                // )
                // Spacer(modifier = Modifier.height(8.dp))
                // Surface(
                //     modifier = Modifier.fillMaxWidth(),
                //     color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                //     shape = MaterialTheme.shapes.small
                // ) {
                //     Text(
                //         text = record.extraMetadata,
                //         style = MaterialTheme.typography.bodySmall,
                //         fontFamily = FontFamily.Monospace,
                //         modifier = Modifier.padding(8.dp)
                //     )
                // }
                Spacer(modifier = Modifier.height(32.dp))
            }

            item {
                val target = relatedRecord
                if (target != null) {
                    val platform = target.platform.ifBlank {
                        if (target.recordType == "subject") "douban" else if (target.recordType == "question") "zhihu" else record.platform
                    }
                    val isDark = isAppInDarkTheme()
                    val platformColor = getPlatformThemeColor(platform, isDark)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRecord(target) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = platformColor.container
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val tagText = when (target.recordType) {
                                    "subject" -> "所属豆瓣条目"
                                    "question" -> "对应知乎问题"
                                    else -> "关联内容"
                                }
                                Text(
                                    text = tagText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = platformColor.text,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = target.title,
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
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (commentRows.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "评论 (${commentRows.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = commentSort == CommentSort.DEFAULT,
                                onClick = { commentSort = CommentSort.DEFAULT },
                                label = { Text("默认") }
                            )
                            FilterChip(
                                selected = commentSort == CommentSort.LATEST,
                                onClick = { commentSort = CommentSort.LATEST },
                                label = { Text("最新") }
                            )
                        }
                    }
                }

                items(commentRows.size) { index ->
                    val row = commentRows[index]
                    val comment = row.record
                    val depth = row.depth
                    val commentMeta = remember(comment.extraMetadata) {
                        try {
                            JsonParser.parseString(comment.extraMetadata).asJsonObject
                        } catch (_: Exception) {
                            JsonObject()
                        }
                    }
                    val likeCount = if (commentMeta.has("like_count") && !commentMeta.get("like_count").isJsonNull) commentMeta.get("like_count").asLong else 0L
                    val dislikeCount = if (commentMeta.has("dislike_count") && !commentMeta.get("dislike_count").isJsonNull) commentMeta.get("dislike_count").asLong else 0L

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRecord(comment) }
                            .padding(
                                start = (depth * 24).dp,
                                bottom = 12.dp
                            ),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (depth > 0) 0.2f else 0.4f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AvatarImage(url = comment.authorAvatar, size = 28.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = comment.authorName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                Text(
                                    text = sdf.format(java.util.Date(comment.publishTime)),
                                    style = MaterialTheme.typography.labelSmall,
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
                            Spacer(modifier = Modifier.height(4.dp))
                            val cleanContent = Jsoup.parse(comment.content).text()
                            Text(
                                text = cleanContent,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    if (viewerUrls.isNotEmpty()) {
        ImageViewerDialog(
            urls = viewerUrls,
            initialIndex = viewerIndex,
            onDismiss = { viewerUrls = emptyList() }
        )
    }
}
