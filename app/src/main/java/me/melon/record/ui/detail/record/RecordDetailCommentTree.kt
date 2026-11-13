package me.melon.record.ui.detail.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.melon.record.presentation.RecordMetadata
import me.melon.record.data.model.UniversalRecord
import me.melon.record.presentation.RedditMetadata
import me.melon.record.data.model.identity
import me.melon.record.data.model.replyTarget
import me.melon.record.data.repository.selectRedditComments
import java.util.ArrayDeque

internal enum class RecordDetailCommentSort { DEFAULT, LATEST }

internal data class RecordDetailCommentRow(
    val record: UniversalRecord,
    val depth: Int,
    val missingParentId: String? = null,
    val hasInvalidParentChain: Boolean = false
) {
    val stableKey: String get() = record.identity.let {
        "comment:${it.platform.length}:${it.platform}:${it.contentId}"
    }
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
    val comments = if (isReddit) selectRedditComments(record, allComments) else allComments.distinctBy { it.identity }
    val byId = comments.associateBy { it.identity }
    val childrenByParent = comments.groupBy { it.replyTarget() }
    val scores = if (isReddit) comments.associate { it.platformId to RedditMetadata(it.extraMetadata).score } else emptyMap()

    val roots = childrenByParent[record.identity].orEmpty()

    fun likeCount(r: UniversalRecord): Long {
        return RecordMetadata(r.extraMetadata, r.platform).commentLikeCount
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
    val visited = mutableSetOf(record.identity)
    fun visit(root: UniversalRecord, missingParentId: String? = null, invalidParentChain: Boolean = false) {
        val stack = ArrayDeque<RecordDetailCommentRow>()
        stack.addLast(RecordDetailCommentRow(root, 0, missingParentId, invalidParentChain))
        while (stack.isNotEmpty()) {
            val row = stack.removeLast()
            if (!visited.add(row.record.identity)) continue
            result.add(row)
            val children = sortedSiblings(childrenByParent[row.record.identity].orEmpty(), isRoot = false)
            for (child in children.asReversed()) {
                stack.addLast(RecordDetailCommentRow(child, row.depth + 1))
            }
        }
    }

    for (root in sortedSiblings(roots, isRoot = true)) visit(root)

    if (isReddit && record.recordType == "post") {
        val orphanRoots = comments.filter { it.identity !in visited && it.replyTarget() !in byId }
        for (root in sortedSiblings(orphanRoots, isRoot = true)) visit(root, missingParentId = root.replyTarget()?.storageIds()?.firstOrNull().orEmpty())
        // Preserve real records with malformed cyclic ancestry too, without claiming a parent is missing.
        for (root in sortedSiblings(comments.filter { it.identity !in visited }, isRoot = true)) {
            visit(root, invalidParentChain = true)
        }
    }
    return result
}
