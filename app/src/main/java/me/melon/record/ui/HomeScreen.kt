package me.melon.record.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.melon.record.data.model.Article
import me.melon.record.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val articles by viewModel.articlesFlow.collectAsState(initial = emptyList())
    // 监听 ViewModel 中的刷新状态 (用来控制转圈圈什么时候结束)
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    var selectedArticle by remember { mutableStateOf<Article?>(null) }

    // === 核心逻辑 1: 解决侧滑退出问题 ===
    // 逻辑：如果当前 selectedArticle 不为空（说明在详情页），
    // 拦截系统返回键/侧滑手势，执行 onBack 操作（设为 null，即回列表）
    if (selectedArticle != null) {
        BackHandler {
            selectedArticle = null
        }

        // 显示详情页
        ArticleDetailScreen(
            article = selectedArticle!!,
            onBack = { selectedArticle = null }
        )
    } else {
        // === 核心逻辑 2: 下拉刷新列表页 ===
        PullToRefreshListScreen(
            articles = articles,
            isViewModelRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshData() },
            onItemClick = { selectedArticle = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshListScreen(
    articles: List<Article>,
    isViewModelRefreshing: Boolean,
    onRefresh: () -> Unit,
    onItemClick: (Article) -> Unit
) {
    // Material3 的下拉刷新状态
    val pullState = rememberPullToRefreshState()

    // 逻辑 A: 当 ViewModel 告诉我们不在刷新了，停止下拉组件的转圈动画
    if (pullState.isRefreshing) {
        LaunchedEffect(isViewModelRefreshing) {
            if (!isViewModelRefreshing) {
                pullState.endRefresh()
            }
        }
    }

    // 逻辑 B: 当用户手动下拉触发刷新时，通知 ViewModel 开始干活
    if (pullState.isRefreshing) {
        LaunchedEffect(true) {
            onRefresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Zhihu Recorder") })
        }
    ) { padding ->
        // Box 是容器，用来层叠列表和刷新指示器
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // 关键：绑定嵌套滚动，让下拉手势生效
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            if (articles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No Articles. Pull down to process.", color = Color.Gray)
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(articles) { article ->
                    ArticleItem(article) {
                        onItemClick(article)
                    }
                }
            }

            // 下拉刷新的指示器 (圆圈箭头)
            // 必须放在 Box 的最后，这样才会浮在列表上面
            PullToRefreshContainer(
                state = pullState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
fun ArticleItem(article: Article, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = article.author,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                Text(
                    text = sdf.format(Date(article.createTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(article: Article, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        article.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // 使用 AutoMirrored 图标，适应阿拉伯语等右向左语言
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            item {
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Author: ${article.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                // 这里用 Divider，因为你之前可能因为版本问题找不到 HorizontalDivider
                Divider(modifier = Modifier.padding(vertical = 12.dp))
            }

            item {
                Text(
                    text = "Content Preview:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (article.contentMarkdown.length > 500)
                           article.contentMarkdown.take(500) + "..."
                           else article.contentMarkdown,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Divider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    text = "Extra Data:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = article.extraData,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
