package com.melonhu.indexrecord.logic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.douban.DoubanInterestsResult
import com.melonhu.indexrecord.parser.douban.DoubanRelations
import com.melonhu.indexrecord.parser.douban.DoubanTitleLookup
import com.melonhu.indexrecord.parser.douban.DoubanTitleParseException

private data class DoubanInterestsEvidence(val result: DoubanInterestsResult) : CompletionEvidence {
    override val platform = "douban"
}

internal fun DoubanInterestsResult.completionEvidence(): CompletionEvidence = DoubanInterestsEvidence(this)

private fun metadata(record: UniversalRecord): JsonObject = JsonParser.parseString(record.extraMetadata).asJsonObject
private fun JsonObject.text(key: String): String = get(key)?.asString ?: ""

internal class DoubanPersistence : PlatformPersistence {
    // This collaborator survives processing runs through RecordPersistenceContext:
    // a review can teach later runs a title even when the first stored child predates that title.
    // Keys intentionally remain subjectId-only (not type+ID), and context updates intentionally
    // survive transaction rollback. Both are legacy behaviors, not new persistence guarantees.

    private val titles = mutableMapOf<String, String>()

    private fun titleFor(subjectId: String): String =
        titles[subjectId] ?: DoubanRelations.sampleSubjectTitle(subjectId)

    private fun rememberTitle(subjectId: String, title: String) {
        if (subjectId.isNotBlank() && title.isNotBlank()) titles[subjectId] = title
    }

    override fun completeEvidence(evidence: CompletionEvidence, repository: RecordRepository) {
        if (evidence is DoubanInterestsEvidence) completeInterests(evidence.result, repository)
    }

    override fun completeChunk(records: List<UniversalRecord>, repository: RecordRepository) {
        // Former subject parsing registered a title immediately, before any chunk writes.
        // Apply capture-order title evidence to the injected persistence context before writes;
        // no parser object owns or consults implicit mutable title state.
        for (record in records) {
            if (record.recordType == "subject") {
                runCatching {
                    val id = metadata(record).text("subject_id")
                    rememberTitle(id, record.title)
                }
            }
            complete(record, repository)
        }
    }

    override fun completeFailure(error: Exception, repository: RecordRepository) {
        if (error !is DoubanTitleParseException) return
        if (error.subjectTitle != null) {
            rememberTitle(error.subjectId, error.subjectTitle)
        } else {
            completeTitleLookup(DoubanTitleLookup(error.subjectId, error.subjectType), repository)
        }
    }

    private fun completeTitleLookup(lookup: DoubanTitleLookup, repository: RecordRepository): String {
        val cached = titleFor(lookup.subjectId)
        if (cached.isNotBlank()) return cached
        return try {
            val parentId = "douban_${lookup.subjectType}_${lookup.subjectId}"
            // 从该条目下的长评或讨论帖元数据中查找 subject_title
            // Keep the original lookup order (subject, first child), not a search over all children.
            val title = repository.find(parentId)?.title.orEmpty().ifBlank {
                repository.firstChild(parentId)?.let { metadata(it).text("subject_title") }.orEmpty()
            }
            // Database hits used to warm the cache during parsing, before subsequent items
            // in this same chunk. Preserve that precedence over later database lookups.
            rememberTitle(lookup.subjectId, title)
            title
        } catch (_: Exception) { "" }
    }

    private fun completeInterests(result: DoubanInterestsResult, repository: RecordRepository) {
        val lookup = result.titleLookup ?: return
        // One lookup per capture, including empty lists. Never repeat it for every item or write.
        val title = completeTitleLookup(lookup, repository)
        for (record in result.records) applyTitle(record, title)
    }

    private fun applyTitle(record: UniversalRecord, title: String) {
        if (title.isBlank()) return
        val meta = metadata(record)
        record.title = "${interestLabel(meta.text("subject_type"))}：$title"
        meta.addProperty("subject_title", title)
        record.extraMetadata = meta.toString()
    }

    override fun complete(record: UniversalRecord, repository: RecordRepository) {
        if (record.recordType != "interest") return
        try {
            val meta = metadata(record)
            val id = meta.text("subject_id")
            val title = completeTitleLookup(DoubanTitleLookup(id, meta.text("subject_type")), repository)
                .ifBlank { meta.text("subject_title") }
            rememberTitle(id, title)
            applyTitle(record, title)
        } catch (_: Exception) {}
    }
    override fun mergeExtra(existing: UniversalRecord, incoming: UniversalRecord) {
        if (incoming.recordType == "subject") {
            incoming.extraMetadata = DoubanRelations.mergeSubjectExtra(existing.extraMetadata, incoming.extraMetadata)
        } else if (incoming.recordType in setOf("topic", "feed") && incoming.platformId.startsWith("douban_group_topic_")) {
            val merged = runCatching { metadata(existing) }.getOrElse { JsonObject() }
            metadata(incoming).entrySet().forEach { (key, value) -> merged.add(key, value) }
            incoming.extraMetadata = merged.toString()
        }
    }
    override fun afterPut(record: UniversalRecord, repository: RecordRepository) {
        try {
            val meta = metadata(record)
            val id = meta.text("subject_id")
            val title = meta.text("subject_title")
            val type = meta.text("subject_type").ifBlank { "movie" }
            if (id.isBlank() || title.isBlank()) return
            rememberTitle(id, title)
            val expectedTitle = "${interestLabel(type)}：$title"
            // Backfill only titles, after the source record is written. Do not synthesize metadata
            // on earlier interests: their original capture/merge order must remain observable.
            for (interest in repository.children("douban_${type}_${id}")) {
                if (interest.platform == "douban" && interest.recordType == "interest" && interest.title != expectedTitle) {
                    interest.title = expectedTitle
                    repository.put(interest)
                }
            }
        } catch (_: Exception) {}
    }
}

private fun interestLabel(type: String): String = when (type) {
    "movie" -> "电影短评"
    "book" -> "图书短评"
    "music" -> "音乐短评"
    "game" -> "游戏短评"
    "tv" -> "剧集短评"
    "drama" -> "舞台剧短评"
    else -> "豆瓣短评"
}
