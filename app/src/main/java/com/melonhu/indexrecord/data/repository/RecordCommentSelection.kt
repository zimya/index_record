package com.melonhu.indexrecord.data.repository

import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.RecordIdentity
import com.melonhu.indexrecord.data.model.distinctRecordIdentities
import com.melonhu.indexrecord.data.model.identity
import com.melonhu.indexrecord.data.model.replyTarget
import com.melonhu.indexrecord.presentation.RedditMetadata
import java.util.ArrayDeque

/** Descendant selection operates on content identity, including Twitter's two capture roles. */
internal fun collectRecordComments(
    record: UniversalRecord,
    children: (Set<RecordIdentity>) -> List<UniversalRecord>
): List<UniversalRecord> {
    val seen = mutableSetOf(record.identity)
    var frontier = setOf(record.identity)
    val selected = mutableListOf<UniversalRecord>()
    while (frontier.isNotEmpty()) {
        val candidates = children(frontier).filter {
            it.platform == record.platform && it.recordType == "comment" &&
                it.platformId.isNotBlank() && it.replyTarget() in frontier
        }
        val added = distinctRecordIdentities(candidates).filter { seen.add(it.identity) }
        selected.addAll(added)
        frontier = added.map { it.identity }.toSet()
    }
    return selected
}

/** Keep real descendants and, on post pages only, captured branches identified by post_id. */
internal fun selectRedditComments(record: UniversalRecord, candidates: List<UniversalRecord>): List<UniversalRecord> {
    val comments = candidates.filter {
        it.platform == "reddit" && it.recordType == "comment" &&
            it.platformId.isNotBlank() && it.identity != record.identity
    }.distinctBy { it.identity }
    val byId = comments.associateBy { it.identity }
    val children = comments.groupBy { it.replyTarget() }
    val selected = linkedSetOf(record.identity)
    if (record.recordType == "post") {
        val ancestors = mutableSetOf(record.identity)
        for (comment in comments) {
            if (RedditMetadata(comment.extraMetadata).postId != record.platformId) continue
            var current: UniversalRecord? = comment
            while (current != null && ancestors.add(current.identity)) {
                selected.add(current.identity)
                current = byId[current.replyTarget()]
            }
        }
    }
    val frontier = ArrayDeque<RecordIdentity>().apply { addAll(selected) }
    while (frontier.isNotEmpty()) {
        for (child in children[frontier.removeFirst()].orEmpty()) {
            if (selected.add(child.identity)) frontier.addLast(child.identity)
        }
    }
    return comments.filter { it.identity in selected }
}
