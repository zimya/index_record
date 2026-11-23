package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.UniversalRecord

/** Presentation identities only: never write these keys back to recordType. */
object RecordContentPolicy {
    private val primaryTypesByPlatform: Map<String, List<String>> = mapOf(
        "zhihu" to listOf("question", "answer", "article", "pin"),
        "douban" to listOf("topic", "review", "interest", "subject"),
        "tieba" to listOf("lou"),
        "twitter" to listOf("tweet"),
        "weibo" to listOf("status"),
        "threads" to listOf("thread"),
        "reddit" to listOf("post"),
        "coolapk" to listOf("feed", "rating", "feedArticle", "question", "answer"),
        "hupu" to listOf("post"),
        "xiaoheihe" to listOf("post"),
        "bluesky" to listOf("post"),
        "nga" to listOf("post"),
        "github" to listOf("issue", "pr", "discussion", "release", "readme"),
        "mastodon" to listOf("post"),
        "bilibili" to listOf("article", "opus", "video"),
        "medium" to listOf("article"),
        "goodreads" to listOf("review", "book"),
        "lofter" to listOf("post"),
        "dcard" to listOf("post"),
        "weread" to listOf("book", "note", "review"),
        "netease" to listOf("song")
    )

    private val secondaryTypesByPlatform: Map<String, List<String>> = mapOf(
        "zhihu" to listOf("comment"),
        "douban" to listOf("comment"),
        "tieba" to listOf("floor", "comment"),
        "twitter" to listOf("comment"),
        "weibo" to listOf("comment"),
        "threads" to listOf("comment"),
        "reddit" to listOf("comment"),
        "coolapk" to listOf("comment"),
        "hupu" to listOf("comment"),
        "xiaoheihe" to listOf("comment"),
        "bluesky" to listOf("comment"),
        "nga" to listOf("comment"),
        "github" to listOf("comment"),
        "mastodon" to listOf("comment"),
        "bilibili" to listOf("comment"),
        "medium" to listOf("comment"),
        "goodreads" to listOf("comment"),
        "lofter" to listOf("comment"),
        "dcard" to listOf("comment"),
        "weread" to listOf("comment"),
        "netease" to listOf("comment")
    )

    fun isPrimaryContent(record: UniversalRecord): Boolean =
        record.recordType in primaryTypesByPlatform[record.platform].orEmpty()

    /** Returns the platform entry types included in statistics for the current scope setting. */
    fun statsRecordTypes(platform: String, includeRepliesAndComments: Boolean): List<String> {
        val primary = primaryTypesByPlatform[platform].orEmpty()
        if (!includeRepliesAndComments) return primary
        return primary + secondaryTypesByPlatform[platform].orEmpty()
    }

    fun isEligibleForHome(record: UniversalRecord, includeSecondary: Boolean): Boolean =
        includeSecondary || isPrimaryContent(record)

    fun filterTypeKey(record: UniversalRecord): String = filterTypeKey(record.platform, record.recordType)

    fun filterTypeKey(platform: String, recordType: String): String = "$platform:$recordType"

    fun platformFromFilterTypeKey(key: String): String {
        if (':' in key) return key.substringBefore(':')
        return when (key) {
            "question", "answer", "article", "pin" -> "zhihu"
            "topic", "review", "interest", "subject", "note" -> "douban"
            "post" -> "reddit"
            "lou", "floor" -> "tieba"
            "thread" -> "threads"
            "tweet" -> "twitter"
            "status" -> "weibo"
            "feed", "rating", "feedArticle" -> "coolapk"
            else -> key
        }
    }

    fun filterTypeLabel(record: UniversalRecord): String = filterTypeLabel(filterTypeKey(record))

    fun filterTypeLabel(key: String): String {
        // Preserve labels for legacy callers; new homepage options always use qualified keys.
        if (':' !in key) return PlatformCatalog.filterTypeLabel(key)
        val platform = key.substringBefore(':')
        val type = key.substringAfter(':')
        val name = if (platform == "twitter") "X" else PlatformCatalog.displayName(platform)
        val label = when {
            platform == "threads" && type == "thread" -> "帖子"
            platform == "threads" && type == "comment" -> "回复"
            else -> PlatformCatalog.typeLabel(platform, type)
        }
        return "$name$label"
    }

    fun sourceApp(record: UniversalRecord): String = record.sourceApp?.takeIf { it.isNotBlank() }
        ?: PlatformCatalog.defaultPackage(record.platform)
}
