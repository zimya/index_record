package me.melon.record.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.HtmlBodyWebView
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
private fun MetadataBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
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
                if (record.title.isNotEmpty()) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.authorName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    Text(
                        text = "发布于: " + sdf.format(Date(record.publishTime)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    HtmlBodyWebView(
                        htmlBody = record.content,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                val metadata = remember(record.extraMetadata) {
                    try {
                        JsonParser.parseString(record.extraMetadata).asJsonObject
                    } catch (e: Exception) {
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
                            MetadataBadge("👍 ${it.asString}")
                        }
                        metadata.get("comment_count")?.let {
                            MetadataBadge("💬 ${it.asString}")
                        }
                        metadata.get("ip_info")?.let {
                            MetadataBadge("📍 ${it.asString}")
                        }
                        metadata.get("favlists_count")?.let {
                            MetadataBadge("⭐ ${it.asString}")
                        }
                    }
                }

                Text(
                    text = "元数据 (Ext Data):",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = record.extraMetadata,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
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
}
