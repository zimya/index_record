package me.melon.record.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUpOffAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.markdown.RenderMarkdownText
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.components.ImageViewerDialog
import me.melon.record.ui.components.RelatedRecordCard
import me.melon.record.viewmodel.TiebaDetailData
import me.melon.record.viewmodel.TiebaMetadata
import me.melon.record.viewmodel.observeTiebaDetail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TiebaDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenRecord: (UniversalRecord) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    firstFloorListState: LazyListState = rememberLazyListState()
) {
    val flow = remember(record.platformId) { observeTiebaDetail(record) }
    val data by flow.collectAsState(initial = TiebaDetailData(record))
    var showFirstFloorReplies by rememberSaveable(record.platformId) { mutableStateOf(false) }
    var viewerUrls by remember { mutableStateOf(emptyList<String>()) }
    var viewerIndex by remember { mutableStateOf(0) }
    val onImageClick: (List<String>, Int) -> Unit = { urls, index ->
        viewerUrls = urls
        viewerIndex = index
    }
    val current = data.current
    val isThread = current.recordType == "thread" && !showFirstFloorReplies
    val floor = data.floor
    val parentId = floor?.platformId ?: current.parentId
    val replies = data.repliesByParent[parentId].orEmpty()
    val activeListState = if (showFirstFloorReplies) firstFloorListState else listState
    val meta = remember(current.extraMetadata) { TiebaMetadata(current) }
    val threadTitle = data.thread?.title?.ifBlank { null }
        ?: meta.text("thread_title").ifBlank { current.title.ifBlank { "未记录标题的帖子" } }
    val forumName = meta.text("forum_name").ifBlank {
        data.thread?.let { TiebaMetadata(it).text("forum_name") }.orEmpty()
    }
    val back: () -> Unit = {
        if (showFirstFloorReplies) showFirstFloorReplies = false else onBack()
    }
    BackHandler(enabled = showFirstFloorReplies) { showFirstFloorReplies = false }

    // A search result for a sub-reply opens its owning floor and locates that reply.
    LaunchedEffect(record.platformId, data.loaded) {
        if (data.loaded && current.recordType == "comment" &&
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        ) {
            val index = replies.indexOfFirst { it.platformId == current.platformId }
            if (index >= 0) listState.scrollToItem(index + 1)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isThread -> "贴吧 · 主帖"
                            current.recordType == "comment" -> "贴吧 · 楼中楼回复"
                            else -> "贴吧 · 楼层详情"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TiebaSourceLink(
                        floor?.sourceUrl?.takeIf { showFirstFloorReplies && it.isNotBlank() }
                            ?: current.sourceUrl.ifBlank { data.thread?.sourceUrl.orEmpty() }
                    )
                }
            )
        }
    ) { padding ->
        // Do not clamp a retained scroll position while the database query is pending.
        if (!data.loaded) {
            Column(Modifier.padding(padding).padding(16.dp)) {
                CircularProgressIndicator()
                Text("正在读取本地记录…", modifier = Modifier.padding(top = 12.dp))
            }
        } else if (data.error != null) {
            Text(data.error.orEmpty(), modifier = Modifier.padding(padding).padding(16.dp))
        } else {
            LazyColumn(
                state = activeListState,
                modifier = Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        if (forumName.isNotBlank()) {
                            Surface(
                                // modifier = Modifier.align(Alignment.CenterHorizontally),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "${forumName}吧",
                                        color = Color.Black,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        Text(
                            threadTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        if (!isThread && data.thread == null) {
                            LocalNotice("主帖尚未记录，暂时无法回主帖。")
                        }
                        // LocalNotice("仅展示本地已记录内容，不会联网补齐；平台数量为采集时记录，可能与当前数量不同。")
                        Spacer(Modifier.height(12.dp))
                        if (isThread) {
                            TiebaPost(
                                record = current,
                                label = "1 楼 · 首楼",
                                onImageClick = onImageClick,
                                isMainPost = true
                            )
                            TiebaReplyPreview(
                                owner = current,
                                replies = data.repliesByParent[current.platformId].orEmpty(),
                                onOpenFloor = { showFirstFloorReplies = true },
                                onOpenRecord = onOpenRecord
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "楼层 · 已记录 ${data.floors.size} 层",
                                style = MaterialTheme.typography.titleMedium
                            )

                            if (data.floors.isEmpty()) LocalNotice("本地尚未记录其他楼层。")
                        } else {
                            if (floor != null) {
                                TiebaPost(floor, floorLabel(floor), onImageClick)
                            } else {
                                LocalNotice("所属楼层尚未记录；下面仅展示本地已缓存的楼中楼，不补造层主与正文。")
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "本楼楼中楼 · 已记录 ${replies.size} 条",
                                style = MaterialTheme.typography.titleMedium
                            )

                            if (replies.isEmpty()) LocalNotice("本地暂无楼中楼记录。请在贴吧浏览后同步记录。")
                        }
                    }
                }
                if (isThread) {
                    items(data.floors, key = { it.platformId }) { post ->
                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                                .clickable { onOpenRecord(post) },
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                TiebaPost(post, floorLabel(post), onImageClick)
                                TiebaReplyPreview(
                                    owner = post,
                                    replies = data.repliesByParent[post.platformId].orEmpty(),
                                    onOpenFloor = { onOpenRecord(post) },
                                    onOpenRecord = onOpenRecord
                                )
                            }
                        }
                    }
                } else {
                    items(replies, key = { it.platformId }) { reply ->
                        val selected = current.recordType == "comment" && reply.platformId == current.platformId
                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                TiebaPost(reply, replyAuthorLabel(reply, floor), onImageClick)
                            }
                        }
                    }
                }
                if (!isThread) {
                    data.thread?.let { thread ->
                        item(key = "related_thread") {
                            RelatedRecordCard(
                                record = thread,
                                fallbackPlatform = "tieba",
                                onClick = {
                                    if (showFirstFloorReplies) showFirstFloorReplies = false
                                    else onOpenRecord(thread)
                                },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    if (viewerUrls.isNotEmpty()) {
        ImageViewerDialog(viewerUrls, viewerIndex, onDismiss = { viewerUrls = emptyList() })
    }
}

@Composable
private fun TiebaPost(
    record: UniversalRecord,
    label: String,
    onImageClick: (List<String>, Int) -> Unit,
    isMainPost: Boolean = false
) {
    val meta = remember(record.extraMetadata) { TiebaMetadata(record) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarImage(url = record.authorAvatar, size = if (isMainPost) 40.dp else 28.dp)
        Spacer(Modifier.width(if (isMainPost) 12.dp else 8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                record.authorName.ifBlank { "昵称未记录" },
                style = if (isMainPost) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge,
                fontWeight = if (isMainPost) FontWeight.Medium else FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (!isMainPost) {
            meta.number("agree_count")?.takeIf { it > 0 }?.let { likes ->
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.ThumbUpOffAlt,
                    contentDescription = "点赞数",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(likes.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    if (isMainPost) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    } else {
        Spacer(Modifier.height(4.dp))
    }
    val target = meta.text("reply_to_name").ifBlank {
        meta.text("reply_to_id").takeIf { it.isNotBlank() }?.let { "用户 ID $it" }.orEmpty()
    }
    if (target.isNotBlank()) {
        Text("回复 $target", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
    if (record.content.isBlank()) {
        LocalNotice("正文尚未记录")
    } else {
        RenderMarkdownText(
            markdown = record.content,
            modifier = Modifier.fillMaxWidth(),
            enableScroll = false,
            selectable = true,
            onImageClick = onImageClick
        )
    }
    if (record.publishTime > 0) {
        val time = remember(record.publishTime) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(record.publishTime))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            time,
            style = if (isMainPost) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
    if (isMainPost) {
        Spacer(Modifier.height(16.dp))
        TiebaPostStats(record)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TiebaPostStats(record: UniversalRecord) {
    val meta = remember(record.extraMetadata) { TiebaMetadata(record) }
    val likes = meta.number("agree_count")
    val replies = meta.number("reply_count").takeUnless { record.recordType == "comment" }
    val favorites = meta.number("favlists_count") ?: meta.number("bookmark_count")
    if (likes == null && replies == null && favorites == null) return

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        likes?.let { MetadataBadge(Icons.Outlined.ThumbUp, it.toString()) }
        replies?.let { MetadataBadge(Icons.Outlined.ChatBubbleOutline, it.toString()) }
        favorites?.let { MetadataBadge(Icons.Outlined.StarOutline, it.toString()) }
    }
}

@Composable
private fun TiebaReplyPreview(
    owner: UniversalRecord,
    replies: List<UniversalRecord>,
    onOpenFloor: () -> Unit,
    onOpenRecord: (UniversalRecord) -> Unit
) {
    if (replies.isEmpty() && (owner.recordType == "thread" || TiebaMetadata(owner).number("reply_count") == 0L)) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(10.dp)) {
            // Text(
            //     "层主：${owner.authorName.ifBlank { "昵称未记录" }}",
            //     style = MaterialTheme.typography.labelMedium,
            //     color = MaterialTheme.colorScheme.primary
            // )
            replies.take(3).forEach { reply ->
                val preview = remember(reply.content) {
                    reply.content.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "[图片]")
                        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                        .replace('\n', ' ').trim().ifBlank { "正文尚未记录" }
                }
                val meta = remember(reply.extraMetadata) { TiebaMetadata(reply) }
                val target = meta.text("reply_to_name").ifBlank { meta.text("reply_to_id") }
                Text(
                    "${reply.authorName.ifBlank { "昵称未记录" }}${if (target.isBlank() || preview.startsWith("回复")) "" else " 回复 $target"}：$preview",
                    modifier = Modifier.fillMaxWidth().clickable { onOpenRecord(reply) }.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TiebaSourceLink(url: String) {
    if (url.isBlank()) return

    val context = LocalContext.current
    IconButton(onClick = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // Resolve a generic web URL so Android uses the default browser, not a Tieba app link.
                selector = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            })
        }.onFailure {
            Toast.makeText(context, "无法打开来源链接", Toast.LENGTH_SHORT).show()
        }
    }) {
        Icon(Icons.Default.OpenInBrowser, contentDescription = "在默认浏览器中打开原帖")
    }
}

@Composable
private fun LocalNotice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

private fun floorLabel(record: UniversalRecord): String {
    if (record.recordType == "thread") return "1 楼 · 首楼"
    val floor = TiebaMetadata(record).number("floor")?.takeIf { it > 0 }
    return floor?.let { "$it 楼" } ?: "楼号未记录"
}

private fun replyAuthorLabel(reply: UniversalRecord, owner: UniversalRecord?): String =
    if (owner != null && reply.authorId.isNotBlank() && reply.authorId == owner.authorId) "楼中楼 · 层主"
    else "楼中楼回复"
