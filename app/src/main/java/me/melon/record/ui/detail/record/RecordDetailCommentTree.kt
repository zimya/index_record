package me.melon.record.ui.detail.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.RedditMetadata
import me.melon.record.ui.components.engagementCount
import java.util.ArrayDeque

internal enum class RecordDetailCommentSort { DEFAULT, LATEST }

internal data class RecordDetailCommentRow(
    val record: UniversalRecord,
    val depth: Int,
    val missingParentId: String? = null,
    val hasInvalidParentChain: Boolean = false
)

/** Keep real descendants and, on post pages only, captured branches identified by canonical post_id. */
internal fun selectRedditComments(
    record: UniversalRecord,
    candidates: List<UniversalRecord>
): List<UniversalRecord> {
    val comments = candidates.filter {
        it.platform == "reddit" && it.recordType == "comment" &&
            it.platformId.isNotBlank() && it.platformId != record.platformId
    }.distinctBy { it.platformId }
    val byId = comments.associateBy { it.platformId }
    val childrenByParent = comments.groupBy { it.parentId }
    val selected = linkedSetOf(record.platformId)

    if (record.recordType == "post") {
        val ancestorSeen = mutableSetOf(record.platformId)
        for (comment in comments) {
            if (RedditMetadata(comment.extraMetadata).postId != record.platformId) continue
            var current: UniversalRecord? = comment
            // A partial payload may supply post_id on a child but omit it on its captured ancestors.
            while (current != null && ancestorSeen.add(current.platformId)) {
                selected.add(current.platformId)
                current = byId[current.parentId]
            }
        }
    }

    val frontier = ArrayDeque<String>().apply { addAll(selected) }
    while (frontier.isNotEmpty()) {
        for (child in childrenByParent[frontier.removeFirst()].orEmpty()) {
            if (selected.add(child.platformId)) frontier.addLast(child.platformId)
        }
    }
    return comments.filter { it.platformId in selected }
}

@Composable
internal fun rememberRecordDetailCommentRows(
    allComments: List<UniversalRecord>,
    commentSort: RecordDetailCommentSort,
    record: UniversalRecord
): List<RecordDetailCommentRow> {
    return remember(allComments, commentSort, record.platformId, record.platform, record.recordType) {
        buildRecordDetailCommentRows(allComments, commentSort, record)
    }
}

internal fun buildRecordDetailCommentRows(
    allComments: List<UniversalRecord>,
    commentSort: RecordDetailCommentSort,
    record: UniversalRecord
): List<RecordDetailCommentRow> {
    val isReddit = record.platform == "reddit"
    val comments = if (isReddit) selectRedditComments(record, allComments) else allComments.distinctBy { it.platformId }
    val byId = comments.associateBy { it.platformId }
    val childrenByParent = comments.groupBy { it.parentId }
    val scores = if (isReddit) comments.associate { it.platformId to RedditMetadata(it.extraMetadata).score } else emptyMap()

    val altId = if (record.platform == "twitter") {
        if (record.platformId.startsWith("twitter_tweet_")) {
            "twitter_comment_" + record.platformId.removePrefix("twitter_tweet_")
        } else if (record.platformId.startsWith("twitter_comment_")) {
            "twitter_tweet_" + record.platformId.removePrefix("twitter_comment_")
        } else ""
    } else ""

    val roots = (childrenByParent[record.platformId].orEmpty() + (if (altId.isNotBlank()) childrenByParent[altId].orEmpty() else emptyList())).distinctBy { it.platformId }

    fun likeCount(r: UniversalRecord): Long {
        return try {
            val obj = JsonParser.parseString(r.extraMetadata).asJsonObject
            obj.engagementCount("like_count", "likeCount")
        } catch (_: Exception) {
            0L
        }
    }

    fun sortedSiblings(nodes: List<UniversalRecord>, isRoot: Boolean): List<UniversalRecord> {
        return if (isReddit) {
            when (commentSort) {
                // Nullable scores sort after all real scores, including zero and Long.MIN_VALUE.
                RecordDetailCommentSort.DEFAULT -> nodes.sortedWith(
                    compareByDescending<UniversalRecord> { scores[it.platformId] }
                        .thenByDescending { it.publishTime }
                        .thenBy { it.platformId }
                )
                RecordDetailCommentSort.LATEST -> nodes.sortedByDescending { it.publishTime }
            }
        } else if (!isRoot) {
            nodes.sortedBy { it.publishTime }
        } else {
            when (commentSort) {
                RecordDetailCommentSort.DEFAULT -> nodes.sortedWith(compareByDescending<UniversalRecord> { likeCount(it) }.thenByDescending { it.publishTime })
                RecordDetailCommentSort.LATEST -> nodes.sortedByDescending { it.publishTime }
            }
        }
    }

    val result = mutableListOf<RecordDetailCommentRow>()
    val visited = mutableSetOf(record.platformId)
    fun visit(root: UniversalRecord, missingParentId: String? = null, invalidParentChain: Boolean = false) {
        val stack = ArrayDeque<RecordDetailCommentRow>()
        stack.addLast(RecordDetailCommentRow(root, 0, missingParentId, invalidParentChain))
        while (stack.isNotEmpty()) {
            val row = stack.removeLast()
            if (!visited.add(row.record.platformId)) continue
            result.add(row)
            val children = sortedSiblings(childrenByParent[row.record.platformId].orEmpty(), isRoot = false)
            for (child in children.asReversed()) {
                stack.addLast(RecordDetailCommentRow(child, row.depth + 1))
            }
        }
    }

    for (root in sortedSiblings(roots, isRoot = true)) visit(root)

    if (isReddit && record.recordType == "post") {
        val orphanRoots = comments.filter { it.platformId !in visited && it.parentId !in byId }
        for (root in sortedSiblings(orphanRoots, isRoot = true)) visit(root, missingParentId = root.parentId)
        // Preserve real records with malformed cyclic ancestry too, without claiming a parent is missing.
        for (root in sortedSiblings(comments.filter { it.platformId !in visited }, isRoot = true)) {
            visit(root, invalidParentChain = true)
        }
    }
    return result
}
