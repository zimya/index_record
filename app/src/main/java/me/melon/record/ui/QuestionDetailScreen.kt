package me.melon.record.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ThumbUp
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.components.HtmlBodyWebView
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AnswerSort { LATEST, VOTEUP }

private data class ZhihuAnswerRef(
    val id: String,
    val excerpt: String,
    val createdTimeSec: Long,
    val updatedTimeSec: Long,
    val voteupCount: Long,
    val commentCount: Long,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String
)

private data class ZhihuTopicRef(
    val id: String,
    val name: String
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuestionDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenAnswer: (UniversalRecord) -> Unit
) {
    var sort by remember { mutableStateOf(AnswerSort.VOTEUP) }
    val meta = remember(record.extraMetadata) {
        try {
            JsonParser.parseString(record.extraMetadata).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }
    val excerpt = remember(meta) { meta.safeString("excerpt") }

    val topics = remember(meta) {
        val arr = meta.getAsJsonArray("topics") ?: JsonArray()
        val out = ArrayList<ZhihuTopicRef>(arr.size())
        for (e in arr) {
            if (!e.isJsonObject) continue
            val obj = e.asJsonObject
            val id = obj.safeString("id")
            val name = obj.safeString("name")
            if (id.isNotBlank() && name.isNotBlank()) out.add(ZhihuTopicRef(id, name))
        }
        out
    }

    val answerRefs = remember(meta) {
        val arr = meta.getAsJsonArray("answer_refs") ?: JsonArray()
        val out = ArrayList<ZhihuAnswerRef>(arr.size())
        for (e in arr) {
            if (!e.isJsonObject) continue
            val obj = e.asJsonObject
            val id = obj.safeString("id")
            if (id.isBlank()) continue
            val excerpt = obj.safeString("excerpt")
            val created = obj.safeLong("created_time")
            val updated = obj.safeLong("updated_time")
            val voteup = obj.safeLong("voteup_count")
            val comment = obj.safeLong("comment_count")
            val authorObj = obj.getAsJsonObject("author")
            out.add(
                ZhihuAnswerRef(
                    id = id,
                    excerpt = excerpt,
                    createdTimeSec = created,
                    updatedTimeSec = updated,
                    voteupCount = voteup,
                    commentCount = comment,
                    authorId = authorObj?.safeString("id") ?: "",
                    authorName = authorObj?.safeString("name") ?: "知乎用户",
                    authorAvatar = authorObj?.safeString("avatar_url") ?: ""
                )
            )
        }
        out
    }

    var existingAnswers by remember { mutableStateOf<Map<String, UniversalRecord>>(emptyMap()) }
    LaunchedEffect(record.platformId, answerRefs.size) {
        withContext(Dispatchers.IO) {
            val ids = answerRefs.map { "zhihu_answer_${it.id}" }.distinct()
            if (ids.isEmpty()) {
                existingAnswers = emptyMap()
                return@withContext
            }
            val box = ObjectBox.store.boxFor(UniversalRecord::class.java)
            val found = box.query()
                .`in`(UniversalRecord_.platformId, ids.toTypedArray(), io.objectbox.query.QueryBuilder.StringOrder.CASE_SENSITIVE)
                .build()
                .find()
            existingAnswers = found.associateBy { it.platformId.removePrefix("zhihu_answer_") }
        }
    }

    val sortedAnswerRefs = remember(answerRefs, sort) {
        when (sort) {
            AnswerSort.LATEST -> answerRefs.sortedByDescending { maxOf(it.updatedTimeSec, it.createdTimeSec) }
            AnswerSort.VOTEUP -> answerRefs.sortedWith(compareByDescending<ZhihuAnswerRef> { it.voteupCount }.thenByDescending { maxOf(it.updatedTimeSec, it.createdTimeSec) })
        }
    }

    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("问题详情", style = MaterialTheme.typography.titleMedium) },
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
                Text(
                    text = record.title.ifBlank { "（未缓存题目）" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (record.content.isBlank() &&excerpt.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                // Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (record.content.isNotBlank()) {
                        HtmlBodyWebView(
                            htmlBody = record.content,
                            modifier = Modifier.padding(16.dp),
                            onImageClick = { _, _ -> }
                        )
                    } else {
                        val hint = excerpt.ifBlank { "（问题详情未缓存）" }
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                if (topics.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        // verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        for (t in topics) {
                            FilterChip(
                                selected = false,
                                onClick = {},
                                label = { Text(t.name) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "回答 (${sortedAnswerRefs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    FilterChip(
                        selected = sort == AnswerSort.VOTEUP,
                        onClick = { sort = AnswerSort.VOTEUP },
                        label = { Text("按赞同") }
                    )
                    FilterChip(
                        selected = sort == AnswerSort.LATEST,
                        onClick = { sort = AnswerSort.LATEST },
                        label = { Text("按时间") }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (sortedAnswerRefs.isEmpty()) {
                item {
                    Text(
                        text = "暂无回答列表缓存（需要抓到 /questions/<id>/feeds 接口）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(sortedAnswerRefs, key = { it.id }) { ref ->
                    val stored = existingAnswers[ref.id]
                    val enabled = stored != null
                    val excerpt = ref.excerpt.ifBlank {
                        stored?.let { Jsoup.parse(it.content).text().take(140) } ?: ""
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { onOpenAnswer(stored!!) }
                            .padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.35f else 0.18f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AvatarImage(url = ref.authorAvatar, size = 32.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ref.authorName.ifBlank { "知乎用户" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    val ts = (if (ref.updatedTimeSec > 0) ref.updatedTimeSec else ref.createdTimeSec) * 1000
                                    if (ts > 0) {
                                        Text(
                                            text = sdf.format(Date(ts)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                if (ref.voteupCount > 0) {
                                    Icon(
                                        imageVector = Icons.Outlined.ThumbUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = ref.voteupCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            if (excerpt.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = excerpt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (!enabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "未缓存该回答，无法展开",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

private fun JsonObject.safeString(key: String): String {
    return if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asString else ""
}

private fun JsonObject.safeLong(key: String): Long {
    return try {
        if (this.has(key) && !this.get(key).isJsonNull) this.get(key).asLong else 0L
    } catch (_: Exception) {
        0L
    }
}
