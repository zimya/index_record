package com.melonhu.indexrecord.logic

import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.x.XRelations

internal object TwitterPersistence : PlatformPersistence {
    override fun mergeExtra(existing: UniversalRecord, incoming: UniversalRecord) {
        incoming.extraMetadata = XRelations.mergeTweetExtra(existing.extraMetadata, incoming.extraMetadata)
    }

    // Link writes belong to the caller's transaction. Failure must abort it, not commit a partial graph.
    override fun afterPut(record: UniversalRecord, repository: RecordRepository) {
        if (record.recordType == "comment") {
            val parentId = record.parentId
            if (parentId.isBlank() || repository.find(parentId) != null) return
            val alternative = when {
                parentId.startsWith("twitter_comment_") -> parentId.removePrefix("twitter_comment_")
                    .takeIf { it.isNotBlank() }?.let { "twitter_tweet_$it" }
                parentId.startsWith("twitter_tweet_") -> parentId.removePrefix("twitter_tweet_")
                    .takeIf { it.isNotBlank() }?.let { "twitter_comment_$it" }
                else -> null
            } ?: return
            val parent = repository.find(alternative) ?: return
            record.parentId = parent.platformId
            repository.put(record)
        } else if (record.recordType == "tweet") {
            val id = record.platformId.removePrefix("twitter_tweet_")
            if (id.isBlank()) return
            for (comment in repository.children("twitter_comment_$id")) {
                if (comment.platform == "twitter" && comment.recordType == "comment") {
                    comment.parentId = record.platformId
                    repository.put(comment)
                }
            }
        }
    }
}
