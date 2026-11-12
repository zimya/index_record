package me.melon.record.ui.detail.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord

internal enum class RecordDetailCommentSort { DEFAULT, LATEST }

internal data class RecordDetailCommentRow(val record: UniversalRecord, val depth: Int)

@Composable
internal fun rememberRecordDetailCommentRows(
    allComments: List<UniversalRecord>,
    commentSort: RecordDetailCommentSort,
    record: UniversalRecord
): List<RecordDetailCommentRow> {
    return remember(allComments, commentSort, record.platformId) {
    val byId = allComments.associateBy { it.platformId }
    val childrenByParent = allComments.groupBy { it.parentId }

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
            if (obj.has("like_count") && !obj.get("like_count").isJsonNull) obj.get("like_count").asLong else 0L
        } catch (_: Exception) {
            0L
        }
    }

    val sortedRoots = when (commentSort) {
        RecordDetailCommentSort.DEFAULT -> roots.sortedWith(compareByDescending<UniversalRecord> { likeCount(it) }.thenByDescending { it.publishTime })
        RecordDetailCommentSort.LATEST -> roots.sortedByDescending { it.publishTime }
    }

    val result = mutableListOf<RecordDetailCommentRow>()
    val visited = mutableSetOf(record.platformId)
    fun visit(node: UniversalRecord, depth: Int) {
        if (!visited.add(node.platformId)) return
        result.add(RecordDetailCommentRow(node, depth))
        val children = childrenByParent[node.platformId].orEmpty().sortedBy { it.publishTime }
        for (c in children) visit(c, depth + 1)
    }
    for (root in sortedRoots) {
        if (!byId.containsKey(root.platformId)) continue
        visit(root, 0)
    }
    result
}
}
