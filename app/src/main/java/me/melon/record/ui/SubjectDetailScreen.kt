package me.melon.record.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ThumbUp
import me.melon.record.ui.components.RatingStars
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.markdown.RenderMarkdownText
import me.melon.record.parser.douban.DoubanUtils.safeDouble
import me.melon.record.parser.douban.DoubanUtils.safeLong
import me.melon.record.parser.douban.DoubanUtils.safeString
import me.melon.record.parser.douban.DoubanUtils.safeStringList
import me.melon.record.ui.components.AvatarImage
import me.melon.record.ui.components.CoverImage
import me.melon.record.ui.components.ImageViewerDialog
import me.melon.record.ui.theme.DynamicColorExtractor
import me.melon.record.ui.theme.DynamicColors
import me.melon.record.ui.theme.getPlatformThemeColor
import me.melon.record.ui.theme.isAppInDarkTheme
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class RelatedFilter { ALL, INTEREST, REVIEW, TOPIC }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubjectDetailScreen(
    record: UniversalRecord,
    onBack: () -> Unit,
    onOpenRecord: (UniversalRecord) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val context = LocalContext.current
    var currentRecord by remember(record) { mutableStateOf(record) }
    var viewerUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    var isIntroExpanded by remember { mutableStateOf(false) }
    var relatedFilter by remember { mutableStateOf(RelatedFilter.ALL) }
    var relatedRecords by remember { mutableStateOf<List<UniversalRecord>>(emptyList()) }
    var contentReady by remember { mutableStateOf(false) }
    // 数据未加载完成前不使用缓存的滚动状态，否则列表项不足时位置会被钳制并写回缓存
    val pendingListState = remember { LazyListState() }

    // 监听与主动查询条目本体最新数据以及关联短评长评
    LaunchedEffect(record.platformId) {
        withContext(Dispatchers.IO) {
            if (ObjectBox.isInitialized) {
                val box = ObjectBox.store.boxFor(UniversalRecord::class.java)

                fun refreshData() {
                    val realSubject = box.query()
                        .equal(UniversalRecord_.platformId, record.platformId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .equal(UniversalRecord_.recordType, "subject", QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .build()
                        .findFirst()
                    if (realSubject != null) {
                        currentRecord = realSubject
                    }

                    val list = box.query()
                        .equal(UniversalRecord_.parentId, record.platformId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .orderDesc(UniversalRecord_.publishTime)
                        .build()
                        .find()
                    relatedRecords = list
                }

                refreshData()
                contentReady = true

                // 实时订阅更新，保证后台无论何时解析入库条目均可瞬时响应刷新
                val sub = box.query()
                    .equal(UniversalRecord_.platformId, record.platformId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .or()
                    .equal(UniversalRecord_.parentId, record.platformId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .build()
                    .subscribe()
                    .observer {
                        refreshData()
                    }

                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    sub.cancel()
                }
            }
        }
    }

    val meta = remember(currentRecord.extraMetadata) {
        try {
            JsonParser.parseString(currentRecord.extraMetadata).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }

    val subjectTitle = remember(currentRecord.title, meta) {
        meta.safeString("subject_title").ifBlank { currentRecord.title }
    }
    val originalTitle = remember(meta) { meta.safeString("original_title") }
    val subtype = remember(meta) { meta.safeString("subject_type").ifBlank { "movie" } }
    val year = remember(meta) { meta.safeString("year") }
    val coverUrl = remember(meta) { meta.safeString("cover_url").ifBlank { meta.safeString("title_image") } }
    val intro = remember(currentRecord.content, meta) {
        meta.safeString("intro").ifBlank { currentRecord.content }
    }
    val cardSubtitle = remember(meta) { meta.safeString("card_subtitle") }

    val ratingVal = remember(meta) { meta.safeDouble("rating_value") }
    val ratingStars = remember(meta) { meta.safeDouble("rating_stars") }
    val ratingCount = remember(meta) { meta.safeLong("rating_count") }

    val genres = remember(meta) { meta.safeStringList("genres") }
    val countries = remember(meta) { meta.safeStringList("countries") }
    val directors = remember(meta) { meta.safeStringList("directors") }
    val actors = remember(meta) { meta.safeStringList("actors") }
    val writers = remember(meta) { meta.safeStringList("writers") }
    val pubdate = remember(meta) { meta.safeStringList("pubdate") }
    val durations = remember(meta) { meta.safeStringList("durations") }
    val languages = remember(meta) { meta.safeStringList("languages") }
    val episodesCount = remember(meta) { meta.safeLong("episodes_count") }

    val author = remember(meta) { meta.safeStringList("author") }
    val translator = remember(meta) { meta.safeStringList("translator") }
    val press = remember(meta) { meta.safeStringList("press") }
    val pages = remember(meta) { meta.safeStringList("pages") }
    val price = remember(meta) { meta.safeStringList("price") }
    val binding = remember(meta) { meta.safeString("binding") }
    val isbn13 = remember(meta) { meta.safeString("isbn13") }

    val developers = remember(meta) { meta.safeStringList("developers") }
    val publishers = remember(meta) { meta.safeStringList("publishers") }
    val platforms = remember(meta) { meta.safeStringList("platforms") }
    val releaseDate = remember(meta) { meta.safeString("release_date") }

    val singer = remember(meta) { meta.safeStringList("singer") }
    val discs = remember(meta) { meta.safeStringList("discs") }

    val honors = remember(meta) { meta.safeStringList("honor_infos") }
    val tags = remember(meta) { meta.safeStringList("tags") }

    val subtypeLabel = when (subtype) {
        "movie" -> "电影"
        "book" -> "图书"
        "music" -> "音乐"
        "game" -> "游戏"
        "tv" -> "电视剧"
        "drama" -> "舞台剧"
        else -> "条目"
    }

    val interestCount = remember(relatedRecords) { relatedRecords.count { it.recordType == "interest" } }
    val reviewCount = remember(relatedRecords) { relatedRecords.count { it.recordType == "review" } }
    val topicCount = remember(relatedRecords) { relatedRecords.count { it.recordType == "topic" } }

    val filteredList = remember(relatedRecords, relatedFilter) {
        when (relatedFilter) {
            RelatedFilter.ALL -> relatedRecords
            RelatedFilter.INTEREST -> relatedRecords.filter { it.recordType == "interest" }
            RelatedFilter.REVIEW -> relatedRecords.filter { it.recordType == "review" }
            RelatedFilter.TOPIC -> relatedRecords.filter { it.recordType == "topic" }
        }
    }

    // 根据图片动态拾取代表色 (暗色和亮色模式下分别作为暗色和亮色背景)
    val headerBgColorHex = remember(meta) { meta.safeString("header_bg_color") }
    val initialColors = remember(headerBgColorHex) {
        DynamicColorExtractor.parseHexToRepresentativeColors(headerBgColorHex)
    }
    var dynamicColors by remember(coverUrl) { mutableStateOf<DynamicColors?>(null) }

    LaunchedEffect(coverUrl) {
        if (coverUrl.isBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val imageLoader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .allowHardware(false)
                    .crossfade(false)
                    .setHeader("Referer", "https://frodo.douban.com/")
                    .build()
                val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap ?: result?.toBitmap()
                if (bitmap != null) {
                    val colors = DynamicColorExtractor.extractRepresentativeColors(bitmap)
                    withContext(Dispatchers.Main) {
                        dynamicColors = colors
                    }
                }
            } catch (_: Exception) {}
        }
    }

    val isDark = isAppInDarkTheme()
    val targetBgColor = remember(dynamicColors, initialColors, isDark) {
        val colors = dynamicColors ?: initialColors
        if (colors != null) {
            if (isDark) colors.dark else colors.light
        } else {
            if (isDark) Color(0xFF141414) else Color(0xFFF7F7F7)
        }
    }

    val animatedBgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 350),
        label = "SubjectBgColor"
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = animatedBgColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "豆瓣 · $subtypeLabel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = animatedBgColor,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(animatedBgColor)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            state = if (contentReady) listState else pendingListState
        ) {
            // --- 顶部头部：海报 + 标题 + 原名 + 年份 + 基础标签 ---
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    if (coverUrl.isNotBlank()) {
                        Card(
                            modifier = Modifier
                                .width(110.dp)
                                .height(155.dp)
                                .clickable {
                                    viewerUrls = listOf(coverUrl)
                                    viewerIndex = 0
                                },
                            shape = RoundedCornerShape(10.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            CoverImage(url = coverUrl, modifier = Modifier.fillMaxSize())
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 155.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = subjectTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (originalTitle.isNotBlank() && originalTitle != subjectTitle) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (year.isNotBlank()) "$originalTitle ($year)" else originalTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else if (year.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = year,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            if (cardSubtitle.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = cardSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // 榜单荣誉勋章与分类标签
                        Column(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 榜单荣誉勋章 (置于分类标签上方)
                            val topHonor = honors.firstOrNull()
                            if (!topHonor.isNullOrBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.EmojiEvents,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = topHonor,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // 分类标签 Chips
                            if (genres.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    for (g in genres.take(4)) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = g,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // --- 豆瓣评分卡片 ---
            item {
                val cardBg = if (isDark) MaterialTheme.colorScheme.surface.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = cardBg
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "豆瓣评分",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = " TM",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    fontSize = 8.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            if (ratingVal > 0) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = String.format(Locale.US, "%.1f", ratingVal),
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        RatingStars(ratingStars)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val countText = if (ratingCount > 10000) {
                                            String.format(Locale.CHINA, "%.1f万人评价", ratingCount / 10000f)
                                        } else {
                                            "${ratingCount}人评价"
                                        }
                                        Text(
                                            text = countText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "暂无评分",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // --- 详细元信息表 (演职员/作者/出版/发售等) ---
            item {
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

            // --- 剧情简介 / 图书内容简介 ---
            if (intro.isNotBlank()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                    ) {
                        Text(
                            text = "内容简介",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isIntroExpanded) {
                            RenderMarkdownText(
                                markdown = intro,
                                modifier = Modifier.fillMaxWidth(),
                                enableScroll = false,
                                selectable = true,
                                onImageClick = { urls, idx ->
                                    viewerUrls = urls
                                    viewerIndex = idx
                                }
                            )
                        } else {
                            val cleanText = try { Jsoup.parse(intro).text() } catch (_: Exception) { intro }
                            Text(
                                text = cleanText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isIntroExpanded = !isIntroExpanded }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isIntroExpanded) "收起简介" else "展开完整简介",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (isIntroExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                }
            }

            // --- 关联内容标题与筛选 (全部 / 短评 / 长评 / 讨论) ---
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "关联内容 (${relatedRecords.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = relatedFilter == RelatedFilter.ALL,
                            onClick = { relatedFilter = RelatedFilter.ALL },
                            label = { Text("全部 (${relatedRecords.size})") }
                        )
                        if (interestCount > 0) {
                            FilterChip(
                                selected = relatedFilter == RelatedFilter.INTEREST,
                                onClick = { relatedFilter = RelatedFilter.INTEREST },
                                label = { Text("短评 ($interestCount)") }
                            )
                        }
                        if (reviewCount > 0) {
                            FilterChip(
                                selected = relatedFilter == RelatedFilter.REVIEW,
                                onClick = { relatedFilter = RelatedFilter.REVIEW },
                                label = { Text("长评 ($reviewCount)") }
                            )
                        }
                        if (topicCount > 0) {
                            FilterChip(
                                selected = relatedFilter == RelatedFilter.TOPIC,
                                onClick = { relatedFilter = RelatedFilter.TOPIC },
                                label = { Text("讨论 ($topicCount)") }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            // --- 关联内容列表 ---
            if (filteredList.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "暂无相关短评或讨论",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "在豆瓣 App 中浏览该条目的短评、长评或讨论帖子即可自动收录归档",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else {
                items(filteredList, key = { it.id }) { item ->
                    RelatedRecordItem(record = item, onClick = { onOpenRecord(item) })
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
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

@Composable
private fun RelatedRecordItem(record: UniversalRecord, onClick: () -> Unit) {
    val meta = remember(record.extraMetadata) {
        try {
            JsonParser.parseString(record.extraMetadata).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }

    val typeBadge = when (record.recordType) {
        "interest" -> "短评"
        "review" -> "长评"
        "topic" -> "讨论"
        else -> record.recordType
    }

    val starCount = meta.safeDouble("star_count")
    val voteCount = meta.safeLong("voteup_count")
    val isDark = isAppInDarkTheme()

    Card(
        modifier = Modifier
            .fillMaxWidth()
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
                        text = record.authorName.ifBlank { "豆瓣用户" },
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

            val snippet = remember(record.content) {
                val t = try { Jsoup.parse(record.content).text() } catch (_: Exception) { record.content }
                t.replace('\n', ' ').trim()
            }
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
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                Text(
                    text = sdf.format(Date(record.publishTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                if (voteCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.ThumbUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$voteCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}
