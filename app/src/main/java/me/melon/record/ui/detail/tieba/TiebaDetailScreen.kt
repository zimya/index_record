package me.melon.record.ui.detail.tieba

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.DetailTopAppBar
import me.melon.record.ui.components.ImageViewerDialog
import me.melon.record.ui.components.RelatedRecordCard
import me.melon.record.data.model.TiebaMetadata
import me.melon.record.data.model.toEntity
import me.melon.record.viewmodel.DetailLoadState
import me.melon.record.ui.detail.DetailLoadContent
import me.melon.record.ui.detail.localRecordedCountLabel
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TiebaDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenRecord: (UniversalRecord) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    firstFloorListState: LazyListState = rememberLazyListState()
) {
    val viewModel = rememberTiebaDetailViewModel(record)
    val data by viewModel.state.collectAsState()
    val isRetrying by viewModel.isRetrying.collectAsState()
    var showFirstFloorReplies by rememberSaveable(record.platformId) { mutableStateOf(false) }
    var viewerUrls by remember { mutableStateOf(emptyList<String>()) }
    var viewerIndex by remember { mutableStateOf(0) }
    val onImageClick: (List<String>, Int) -> Unit = { urls, index ->
        viewerUrls = urls
        viewerIndex = index
    }
    var highlightedReplyId by remember(record.platformId) { mutableStateOf<String?>(null) }
    val current = data.current.toEntity()
    val thread = data.thread?.toEntity()
    val floors = data.floors.map { it.toEntity() }
    val repliesByParent = data.repliesByParent.mapValues { (_, values) -> values.map { it.toEntity() } }
    val isThread = current.recordType == "lou" && !showFirstFloorReplies
    val floor = data.floor?.toEntity()
    val replies = data.replies.map { it.toEntity() }
    val activeListState = if (showFirstFloorReplies) firstFloorListState else listState
    val meta = remember(current.extraMetadata) { TiebaMetadata(current.extraMetadata) }
    val threadTitle = thread?.title?.ifBlank { null }
        ?: meta.threadTitle.ifBlank { current.title.ifBlank { "未记录标题的帖子" } }
    val forumName = meta.forumName.ifBlank {
        thread?.let { TiebaMetadata(it.extraMetadata).forumName }.orEmpty()
    }
    val back: () -> Unit = {
        if (showFirstFloorReplies) showFirstFloorReplies = false else onBack()
    }
    BackHandler(enabled = showFirstFloorReplies) { showFirstFloorReplies = false }

    // A search result for a sub-reply opens its owning floor and locates that reply.
    LaunchedEffect(record.platformId, data.loadState) {
        if (data.loadState == DetailLoadState.Ready && current.recordType == "comment") {
            val index = replies.indexOfFirst { it.platformId == current.platformId }
            if (index >= 0) {
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                    listState.scrollToItem(index + 1)
                }
                highlightedReplyId = current.platformId
                delay(1_000)
                highlightedReplyId = null
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(
                title = when {
                    isThread -> "贴吧 · 主帖"
                    current.recordType == "comment" -> "贴吧 · 楼中楼回复"
                    else -> "贴吧 · 楼层详情"
                },
                onBack = back,
                containerColor = TopAppBarDefaults.topAppBarColors().containerColor,
                titleContentColor = TopAppBarDefaults.topAppBarColors().titleContentColor,
                backContentDescription = "返回",
                actions = {
                    TiebaSourceLink(
                        floor?.sourceUrl?.takeIf { showFirstFloorReplies && it.isNotBlank() }
                            ?: current.sourceUrl.ifBlank { thread?.sourceUrl.orEmpty() }
                    )
                }
            )
        }
    ) { padding ->
        DetailLoadContent(data.loadState, isRetrying, viewModel::retry, Modifier.padding(padding)) {
            LazyColumn(
                state = activeListState,
                modifier = Modifier.fillMaxSize(),
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
                        if (!isThread && thread == null) {
                            TiebaLocalNotice("主帖尚未记录，暂时无法回主帖。")
                        }
                        // TiebaLocalNotice("仅展示本地已记录内容，不会联网补齐；平台数量为采集时记录，可能与当前数量不同。")
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
                                replies = repliesByParent[current.platformId].orEmpty(),
                                onOpenFloor = { showFirstFloorReplies = true },
                                onOpenRecord = onOpenRecord
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "楼层 · ${localRecordedCountLabel(floors.size)}",
                                style = MaterialTheme.typography.titleMedium
                            )

                            if (floors.isEmpty()) TiebaLocalNotice("本地尚未记录其他楼层。")
                        } else {
                            if (floor != null) {
                                TiebaPost(floor, tiebaFloorLabel(floor), onImageClick)
                            } else {
                                TiebaLocalNotice("所属楼层尚未记录；下面仅展示本地已缓存的楼中楼，不补造层主与正文。")
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "本楼楼中楼 · ${localRecordedCountLabel(replies.size)}",
                                style = MaterialTheme.typography.titleMedium
                            )

                            if (replies.isEmpty()) TiebaLocalNotice("本地暂无楼中楼记录。请在贴吧浏览后同步记录。")
                        }
                    }
                }
                if (isThread) {
                    items(floors, key = { it.platformId }) { post ->
                        Surface(
                            onClick = { onOpenRecord(post) },
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                TiebaPost(post, tiebaFloorLabel(post), onImageClick)
                                TiebaReplyPreview(
                                    owner = post,
                                    replies = repliesByParent[post.platformId].orEmpty(),
                                    onOpenFloor = { onOpenRecord(post) },
                                    onOpenRecord = onOpenRecord
                                )
                            }
                        }
                    }
                } else {
                    items(replies, key = { it.platformId }) { reply ->
                        val selected = reply.platformId == highlightedReplyId
                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                TiebaPost(reply, tiebaReplyAuthorLabel(reply, floor), onImageClick)
                            }
                        }
                    }
                }
                if (!isThread) {
                    thread?.let { thread ->
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
