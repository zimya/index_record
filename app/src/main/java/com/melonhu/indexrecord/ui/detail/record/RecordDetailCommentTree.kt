package com.melonhu.indexrecord.ui.detail.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.melonhu.indexrecord.presentation.commentStatistics
import com.melonhu.indexrecord.data.model.RecordContent
import com.melonhu.indexrecord.data.model.identity
import com.melonhu.indexrecord.data.model.replyTarget
import java.util.ArrayDeque

internal enum class RecordDetailCommentSort { DEFAULT, LATEST }

internal data class RecordDetailCommentRow(
    val record: RecordContent,
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
    allComments: List<RecordContent>,
    commentSort: RecordDetailCommentSort,
    record: RecordContent
): List<RecordDetailCommentRow> {
    return remember(allComments, commentSort, record.platformId, record.platform, record.recordType) {
        buildRecordDetailCommentRows(allComments, commentSort, record)
    }
}

internal fun buildRecordDetailCommentRows(
    allComments: List<RecordContent>,
    commentSort: RecordDetailCommentSort,
    record: RecordContent
): List<RecordDetailCommentRow> {
    val comments = allComments.distinctBy { it.identity }
    val byId = comments.associateBy { it.identity }
    val childrenByParent = comments.groupBy { it.replyTarget() }
    val scores = comments.associate { it.identity to commentStatistics(it).likeCount }

    val roots = childrenByParent[record.identity].orEmpty()

    fun likeCount(r: RecordContent): Long? = scores[r.identity]

    fun sortedSiblings(nodes: List<RecordContent>): List<RecordContent> = when (commentSort) {
        // Unknown sorts after every captured value, including negative scores.
        RecordDetailCommentSort.DEFAULT -> nodes.sortedWith(
            compareByDescending<RecordContent> { likeCount(it) }
                .thenByDescending { it.publishTime }.thenBy { it.platformId }
        )
        RecordDetailCommentSort.LATEST -> nodes.sortedByDescending { it.publishTime }
    }

    val result = mutableListOf<RecordDetailCommentRow>()
    val visited = mutableSetOf(record.identity)
    fun visit(root: RecordContent, missingParentId: String? = null, invalidParentChain: Boolean = false) {
        val stack = ArrayDeque<RecordDetailCommentRow>()
        stack.addLast(RecordDetailCommentRow(root, 0, missingParentId, invalidParentChain))
        while (stack.isNotEmpty()) {
            val row = stack.removeLast()
            if (!visited.add(row.record.identity)) continue
            result.add(row)
            val children = sortedSiblings(childrenByParent[row.record.identity].orEmpty())
            for (child in children.asReversed()) {
                stack.addLast(RecordDetailCommentRow(child, row.depth + 1))
            }
        }
    }

    for (root in sortedSiblings(roots)) visit(root)

    run {
        val orphanRoots = comments.filter { it.identity !in visited && it.replyTarget() !in byId }
        for (root in sortedSiblings(orphanRoots)) visit(root, missingParentId = root.replyTarget()?.storageIds()?.firstOrNull().orEmpty())
        // Preserve real records with malformed cyclic ancestry too, without claiming a parent is missing.
        for (root in sortedSiblings(comments.filter { it.identity !in visited })) {
            visit(root, invalidParentChain = true)
        }
    }
    return result
}
