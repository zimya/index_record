package me.melon.record.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.objectbox.BoxStore
import io.objectbox.query.QueryBuilder.StringOrder
import kotlinx.coroutines.flow.Flow
import me.melon.record.ObjectBox
import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.RecordIdentity
import me.melon.record.data.model.RecordRelation
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_
import me.melon.record.data.model.directRelation
import me.melon.record.data.model.immutableList
import me.melon.record.data.model.inCaptureRole
import me.melon.record.data.model.resolveRecordIdentity
import me.melon.record.data.model.toDetailRecord


internal class CommentDetailSnapshot(
    val record: DetailRecord,
    ancestors: List<DetailRecord> = emptyList(),
    val related: DetailRecord? = null,
    val likeCount: Long = 0,
    val dislikeCount: Long = 0,
    val starCount: Double = 0.0
) {
    val ancestors: List<DetailRecord> = immutableList(ancestors)
}

internal class CommentDetailRepository(private val store: BoxStore = ObjectBox.store) {
    fun observe(seed: DetailRecord): Flow<Result<CommentDetailSnapshot>> = observeRecordSnapshots(store) {
        assembleCommentDetailSnapshot(seed) { identity ->
            val query = store.boxFor(UniversalRecord::class.java).query()
                .equal(UniversalRecord_.platform, identity.platform, StringOrder.CASE_SENSITIVE)
                .`in`(UniversalRecord_.platformId, identity.storageIds().toTypedArray(), StringOrder.CASE_SENSITIVE)
                .build()
            try {
                val captures = query.find()
                                val latest = resolveRecordIdentity(captures)
                                val replyRole = captures.firstOrNull { it.recordType == "comment" }
                                val resolved = if (identity.platform == "twitter" && latest != null && replyRole != null) {
                                    latest.inCaptureRole(replyRole)
                                } else latest
                                resolved?.toDetailRecord()
            } finally { query.close() }
        }
    }
}

internal fun assembleCommentDetailSnapshot(
    seed: DetailRecord,
    find: (RecordIdentity) -> DetailRecord?
): CommentDetailSnapshot {
    val latest = find(seed.identity) ?: seed
    val current = if (seed.platform == "twitter") {
        latest.copy(id = seed.id, platformId = seed.platformId, recordType = seed.recordType)
    } else latest
    val ancestors = mutableListOf<DetailRecord>()
    val visited = mutableSetOf(RecordIdentity.of(current.platform, current.platformId))
    var cursor = current
    var related: DetailRecord? = null
    // Bound ancestor traversal and stop cycles by logical identity, including Twitter aliases.
    for (depth in 0..30) {
        val relation = cursor.directRelation() as? RecordRelation.ReplyTo ?: break
        if (!visited.add(relation.target)) break
        val parent = find(relation.target) ?: break
        if (parent.recordType != "comment" || depth == 30) {
            related = parent
            break
        }
        ancestors.add(parent)
        cursor = parent
    }
    val meta = runCatching { JsonParser.parseString(current.extraMetadata).asJsonObject }.getOrDefault(JsonObject())
    fun number(key: String) = runCatching { meta.get(key)?.asDouble ?: 0.0 }.getOrDefault(0.0)
    return CommentDetailSnapshot(
        record = current,
        ancestors = ancestors,
        related = related,
        likeCount = runCatching { meta.get("like_count")?.asLong ?: 0L }.getOrDefault(0L),
        dislikeCount = runCatching { meta.get("dislike_count")?.asLong ?: 0L }.getOrDefault(0L),
        starCount = number("star_count").takeIf { it > 0 } ?: number("rating_value")
    )
}
