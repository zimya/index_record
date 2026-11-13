package me.melon.record.presentation

import me.melon.record.data.model.UniversalRecord
import org.jsoup.Jsoup

internal fun recordDisplayTitle(
    record: UniversalRecord,
    metadata: RecordMetadata = RecordMetadata(record.extraMetadata, record.platform)
): String = record.title.ifBlank {
    val threadTitle = if (record.platform == "tieba") metadata.threadTitle else ""
    threadTitle.ifBlank {
        when (record.platform) {
            "weibo" -> if (record.recordType == "comment") "微博评论" else "${record.authorName.ifBlank { "微博用户" }}的微博"
            "threads" -> if (record.recordType == "comment") "Threads 回复" else "${record.authorName.ifBlank { "Threads 用户" }}的帖子"
            "reddit" -> if (record.recordType == "comment") "Reddit 回复" else "${record.authorName.ifBlank { "Reddit 用户" }}的帖子"
            else -> "无标题"
        }
    }
}

internal fun recordContext(
    record: UniversalRecord,
    metadata: RecordMetadata = RecordMetadata(record.extraMetadata, record.platform)
): String = when (record.platform) {
    "tieba" -> listOfNotNull(
        metadata.forumName.takeIf { it.isNotBlank() }?.let { "${it}吧" },
        metadata.floor?.takeIf { it > 0 }?.let { "$it 楼" }
    ).joinToString(" · ")
    "reddit" -> listOfNotNull(
        metadata.reddit?.subreddit?.takeIf { it.isNotBlank() }?.let { "r/$it" },
        metadata.reddit?.flair?.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
    else -> ""
}

internal fun recordPreviewText(
    record: UniversalRecord,
    metadata: RecordMetadata = RecordMetadata(record.extraMetadata, record.platform)
): String {
    if (record.platform == "reddit") return redditPreviewText(record.content)
    val raw = when (record.recordType) {
        "question" -> record.content.ifBlank { metadata.excerpt }
        "subject" -> if (record.content.isNotBlank() && record.content != record.title) record.content
            else metadata.intro.ifBlank { metadata.cardSubtitle }
        else -> record.content
    }
    val preview = if (record.platform in setOf("weibo", "threads")) {
        raw.replace(Regex("!\\[[^\\]]*\\]\\([^\\n]*\\)"), "[图片]")
    } else raw
    val text = try { Jsoup.parse(preview).text() } catch (_: Exception) { preview }
    val plainText = if (record.platform == "threads") {
        text.replace(Regex("""\\([\\`*_{}\[\]<>#!|~])"""), "$1")
    } else text
    return plainText.replace('\n', ' ').replace('\r', ' ').trim()
}

internal fun contentSnippet(content: String, query: String): String {
    val index = content.indexOf(query, ignoreCase = true)
    if (index == -1) return content.take(100)
    val start = (index - 40).coerceAtLeast(0)
    val end = (index + 80).coerceAtMost(content.length)
    return (if (start > 0) "..." else "") + content.substring(start, end) +
        (if (end < content.length) "..." else "")
}
