package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.UniversalRecord

/** Presentation identities only: never write these keys back to recordType. */
object RecordContentPolicy {
    fun isPrimaryContent(record: UniversalRecord): Boolean = when (record.platform) {
        "zhihu" -> record.recordType in setOf("question", "answer", "article", "pin")
        "tieba" -> record.recordType == "lou"
        "reddit" -> record.recordType == "post"
        "twitter" -> record.recordType == "tweet"
        "weibo" -> record.recordType == "status"
        "threads" -> record.recordType == "thread"
        "douban" -> record.recordType in setOf("topic", "review", "interest", "subject")
        "coolapk" -> record.recordType in setOf("feed", "rating", "feedArticle", "question", "answer")
        "hupu" -> record.recordType == "post"
        "xiaoheihe" -> record.recordType == "post"
        else -> false
    }

    fun isEligibleForHome(record: UniversalRecord, includeSecondary: Boolean): Boolean =
        includeSecondary || isPrimaryContent(record)

    fun filterTypeKey(record: UniversalRecord): String = filterTypeKey(record.platform, record.recordType)

    fun filterTypeKey(platform: String, recordType: String): String = "$platform:$recordType"

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
