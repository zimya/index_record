package com.melonhu.indexrecord.presentation

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class HistoryMetadataChange(val title: String, val before: String, val after: String)

/** Compare captured fields, not JSON serialization order or adapter defaults for missing values. */
internal fun historyMetadataChanges(
    old: String,
    new: String,
    oldPlatform: String,
    newPlatform: String = oldPlatform,
    zone: ZoneId = ZoneId.systemDefault(),
    includeUnchanged: Boolean = false
): List<HistoryMetadataChange> {
    val before = historyMetadataFields(old, oldPlatform, zone)
    val after = historyMetadataFields(new, newPlatform, zone)
    return (before.keys + after.keys).distinct().sortedWith(compareBy { it.joinToString("\u0000") })
        .filter { includeUnchanged || before[it] != after[it] }
        .map { path ->
            HistoryMetadataChange(
                "附加信息 · " + path.joinToString(" / ") { historyMetadataLabel(it) },
                before[path] ?: "未记录",
                after[path] ?: "未记录"
            )
        }
}

private fun historyMetadataFields(raw: String, platform: String, zone: ZoneId): Map<List<String>, String> {
    if (raw.isBlank()) return emptyMap()
    val root = runCatching {
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            val value = Gson().getAdapter(JsonElement::class.java).read(reader)
            require(reader.peek() == JsonToken.END_DOCUMENT && value.isJsonObject)
            value
        }
    }.getOrNull() ?: return mapOf(listOf("解析状态") to "无法解析")
    val fields = linkedMapOf<List<String>, String>()
    fun flatten(value: JsonElement, path: List<String>, fieldKey: String) {
        when {
            value.isJsonObject -> {
                if (value.asJsonObject.size() == 0 && path.isNotEmpty()) fields[path] = "空对象"
                value.asJsonObject.entrySet().sortedBy { it.key }.forEach { (key, child) ->
                    flatten(child, path + key, key)
                }
            }
            value.isJsonArray -> {
                if (value.asJsonArray.size() == 0) fields[path] = "空列表"
                value.asJsonArray.forEachIndexed { index, child ->
                    flatten(child, path + "第 ${index + 1} 项", fieldKey)
                }
            }
            value.isJsonNull -> fields[path] = "空值"
            else -> {
                val primitive = value.asJsonPrimitive
                fields[path] = when {
                    primitive.isBoolean -> if (primitive.asBoolean) "是" else "否"
                    primitive.asString.isEmpty() -> "空文本"
                    else -> historyMetadataValue(fieldKey, primitive.asString, platform, zone)
                }
            }
        }
    }
    flatten(root, emptyList(), "")
    return fields
}

internal fun historyPublishTime(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (timestamp <= 0) "发布时间未知" else historyInstant(Instant.ofEpochMilli(timestamp), zone)

internal fun historyBodyFormat(value: String): String = when (value) {
    "plain_text" -> "纯文本"
    "html" -> "HTML 富文本"
    "markdown" -> "Markdown"
    "markdown_math" -> "Markdown（含公式）"
    else -> value
}

private val secondTimeKeys = setOf("created", "updated", "created_time", "updated_time", "createdTime", "updatedTime")
private val isoTimeKeys = setOf("created_at", "updated_at", "edited_at", "createdAt", "updatedAt", "editedAt")

private fun historyMetadataValue(key: String, value: String, platform: String, zone: ZoneId): String {
    if (key == "content_format") return historyBodyFormat(value)
    // Zhihu copies API seconds unchanged; Reddit normalizes editedAt to epoch milliseconds.
    val seconds = platform == "zhihu" && key in secondTimeKeys
    val milliseconds = (platform == "reddit" && key == "edited_at") || key.endsWith("_at_ms")
    if (seconds || milliseconds || key in isoTimeKeys) {
        val instant = runCatching {
            when {
                seconds -> Instant.ofEpochSecond(value.toLong())
                milliseconds && value.toLongOrNull() != null -> Instant.ofEpochMilli(value.toLong())
                else -> Instant.parse(value)
            }
        }.getOrNull()
        if (instant != null) return historyInstant(instant, zone)
    }
    return value
}

private fun historyInstant(instant: Instant, zone: ZoneId): String {
    val pattern = if (instant.nano == 0) "yyyy-MM-dd HH:mm:ss XXX" else "yyyy-MM-dd HH:mm:ss.SSSSSSSSS XXX"
    return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withZone(zone).format(instant)
}

// Keep unknown keys verbatim so new parser fields remain identifiable and are never dropped.
private fun historyMetadataLabel(key: String): String = historyMetadataLabels[key] ?: key

private val historyMetadataLabels = mapOf(
    "title" to "标题", "name" to "名称", "id" to "标识", "url" to "链接", "text" to "文本",
    "created" to "创建时间", "created_time" to "创建时间", "createdTime" to "创建时间",
    "created_at" to "创建时间", "createdAt" to "创建时间", "created_at_ms" to "创建时间",
    "updated" to "编辑时间", "updated_time" to "编辑时间", "updatedTime" to "编辑时间",
    "updated_at" to "编辑时间", "updatedAt" to "编辑时间", "updated_at_ms" to "编辑时间",
    "edited_at" to "编辑时间", "editedAt" to "编辑时间", "edited_at_ms" to "编辑时间",
    "create_time_text" to "发布时间", "update_time_text" to "编辑时间",
    "excerpt" to "摘要", "intro" to "简介", "card_subtitle" to "副标题",
    "author" to "作者", "author_headline" to "作者简介", "author_intro" to "作者介绍",
    "author_level" to "作者等级", "author_flair" to "作者标签", "username" to "用户名",
    "screen_name" to "用户名", "portrait" to "头像标识", "avatar_url" to "头像链接",
    "headline" to "简介", "ip_info" to "IP 属地", "ip_address" to "IP 属地", "region_name" to "地区",
    "title_image" to "标题图片", "cover_url" to "封面链接", "source" to "发布来源",
    "voteup_count" to "赞同数", "like_count" to "点赞数", "likeCount" to "点赞数",
    "agree_count" to "赞同数", "vote_count" to "投票数", "useful_count" to "有用数",
    "comment_count" to "评论数", "commentCount" to "评论数", "reply_count" to "回复数",
    "child_comment_count" to "子评论数", "child_count" to "子回复数", "thread_reply_count" to "帖子回复数",
    "favlists_count" to "收藏数", "bookmark_count" to "书签数", "bookmarkCount" to "书签数",
    "retweet_count" to "转发数", "repost_count" to "转发数", "repostCount" to "转发数", "reshares_count" to "转发数",
    "views_count" to "浏览数", "view_count" to "浏览数", "viewCount" to "浏览数",
    "quote_count" to "引用数", "reshare_count" to "分享数", "share_count" to "分享数",
    "dislike_count" to "反对数", "follower_count" to "关注者数", "answer_count" to "回答数",
    "collapsed_answer_count" to "折叠回答数", "score" to "净投票分", "is_score_hidden" to "隐藏分数",
    "question_id" to "问题标识", "subject_id" to "条目标识", "subject_title" to "条目标题",
    "subject_type" to "条目类型", "topic_id" to "话题标识", "review_id" to "长评标识", "status_id" to "动态标识",
    "group_name" to "小组名称", "group_id" to "小组标识", "topics" to "话题", "tags" to "标签",
    "thread_id" to "帖子标识", "thread_title" to "帖子标题", "post_id" to "帖子标识",
    "forum_name" to "贴吧名称", "forum_id" to "贴吧标识", "floor" to "楼层", "sub_floor" to "楼中楼层",
    "floor_post_id" to "所属楼层标识", "reply_to_name" to "回复对象", "reply_to_id" to "回复对象标识",
    "reply_to_post_id" to "回复帖子标识", "parent_comment_id" to "父评论标识", "ref_comment_id" to "引用评论标识",
    "tweet_id" to "推文标识", "conversation_id" to "会话标识", "in_reply_to_status_id" to "回复推文标识",
    "in_reply_to_screen_name" to "回复用户名", "is_quote" to "引用推文", "quoted_status_id" to "引用推文标识",
    "is_retweet" to "转发推文", "retweeted_status_id" to "原推文标识", "root_post_id" to "根帖子标识",
    "quoted_post_id" to "引用帖子标识", "reposted_post_id" to "转发帖子标识", "reply_level" to "回复层级",
    "subreddit" to "社区", "subreddit_id" to "社区标识", "flair" to "帖子标签", "fullname" to "完整标识",
    "parent_fullname" to "父级完整标识", "crosspost_count" to "跨社区转发数", "crosspost_root_id" to "跨社区原帖标识",
    "is_nsfw" to "成人内容", "is_spoiler" to "剧透内容", "is_archived" to "已归档",
    "is_locked" to "已锁定", "is_stickied" to "已置顶", "is_self_post" to "文字帖子",
    "is_removed" to "已移除", "is_admin_takedown" to "管理员移除", "is_op" to "原帖作者",
    "comments_has_next_page" to "评论还有下一页", "comment_sort" to "评论排序", "depth" to "层级",
    "poll" to "投票", "awardings" to "奖励", "richtext_media" to "富文本媒体", "tombstone" to "移除说明",
    "distinguished_as" to "身份标记", "removed_by_category" to "移除类别", "comment_more" to "更多评论",
    "content_format" to "正文格式", "body_source" to "正文来源", "body_complete" to "正文完整",
    "body_length" to "正文长度", "media_complete" to "媒体完整", "media_markdown" to "媒体内容",
    "comment_forest_count" to "评论树数量", "capture_url" to "捕获链接", "is_preview" to "预览内容",
    "page" to "页码", "parse_mode" to "解析方式", "full_text" to "全文", "status" to "状态",
    "original_title" to "原名", "year" to "年份", "header_bg_color" to "背景色",
    "rating_value" to "评分", "rating_stars" to "评分星数", "star_count" to "星数",
    "rating_count" to "评分人数", "rating_max" to "评分满分", "genres" to "类型", "countries" to "国家或地区",
    "languages" to "语言", "durations" to "时长", "pubdate" to "发行日期", "episodes_count" to "集数",
    "directors" to "导演", "actors" to "演员", "writers" to "编剧", "translator" to "译者",
    "press" to "出版社", "pages" to "页数", "price" to "定价", "binding" to "装帧",
    "isbn13" to "ISBN", "catalog" to "目录", "developers" to "开发商", "publishers" to "发行商",
    "platforms" to "平台", "release_date" to "发行日期", "singer" to "歌手", "discs" to "唱片数",
    "honor_infos" to "获奖信息"
)
