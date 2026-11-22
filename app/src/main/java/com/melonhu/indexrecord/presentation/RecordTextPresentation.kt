package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.RecordContent
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.bodyPlainText

internal fun recordDisplayTitle(
    record: RecordContent,
    metadata: PlatformMetadataAdapters = PlatformMetadataAdapters(record.extraMetadata, record.platform)
): String = record.title.ifBlank {
    val threadTitle = if (record.platform == "tieba") metadata.tieba?.threadTitle.orEmpty() else ""
    threadTitle.ifBlank {
        when (record.platform) {
            "weibo" -> if (record.recordType == "comment") "微博评论" else "${record.authorName.ifBlank { "微博用户" }}的微博"
            "threads" -> if (record.recordType == "comment") "Threads 回复" else "${record.authorName.ifBlank { "Threads 用户" }}的帖子"
            "bluesky" -> if (record.recordType == "comment") "Bluesky 回复" else "${record.authorName.ifBlank { "Bluesky 用户" }}的帖子"
            "reddit" -> if (record.recordType == "comment") "Reddit 回复" else "${record.authorName.ifBlank { "Reddit 用户" }}的帖子"
            "coolapk" -> "${record.authorName.ifBlank { "酷安用户" }}的动态"
            "douban" -> if (record.recordType == "feed" ||
                (record.recordType == "topic" && record.platformId.startsWith("douban_group_topic_") && metadata.douban?.groupName.isNullOrBlank())
            ) {
                "${record.authorName.ifBlank { "豆瓣用户" }}的动态"
            } else {
                "无标题"
            }
            else -> "无标题"
        }
    }
}

internal fun recordContext(
    record: RecordContent,
    metadata: PlatformMetadataAdapters = PlatformMetadataAdapters(record.extraMetadata, record.platform)
): String = when (record.platform) {
    "tieba" -> listOfNotNull(
        metadata.tieba?.forumName?.takeIf { it.isNotBlank() }?.let { "${it}吧" },
        metadata.tieba?.floorNumber?.takeIf { it > 0 }?.let { "$it 楼" }
    ).joinToString(" · ")
    "reddit" -> listOfNotNull(
        metadata.reddit?.subreddit?.takeIf { it.isNotBlank() }?.let { "r/$it" },
        metadata.reddit?.flair?.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
    "coolapk" -> listOfNotNull(
        metadata.coolapk?.ttitle?.takeIf { it.isNotBlank() }?.let { "#$it#" },
        metadata.coolapk?.deviceTitle?.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
    else -> ""
}

internal fun recordPreviewText(
    record: RecordContent,
    metadata: PlatformMetadataAdapters = PlatformMetadataAdapters(record.extraMetadata, record.platform)
): String {
    val raw = when (record.recordType) {
        "question" -> record.content.ifBlank { metadata.zhihu?.excerpt.orEmpty() }
        "subject" -> if (record.content.isNotBlank() && record.content != record.title) record.content
            else metadata.douban?.intro.orEmpty().ifBlank { metadata.douban?.cardSubtitle.orEmpty() }
        else -> record.content
    }
    return if (raw == record.content) record.bodyPlainText()
    else {
        // Question excerpts are HTML from the API; subject intro/card subtitle are plain text.
        val format = if (record.recordType == "question") RecordBodyFormat.HTML else RecordBodyFormat.PLAIN_TEXT
        bodyPlainText(raw, format)
    }
}

internal fun contentSnippet(content: String, query: String): String {
    val index = content.indexOf(query, ignoreCase = true)
    if (index == -1) return content.take(100)
    val start = (index - 40).coerceAtLeast(0)
    val end = (index + 80).coerceAtMost(content.length)
    return (if (start > 0) "..." else "") + content.substring(start, end) +
        (if (end < content.length) "..." else "")
}
