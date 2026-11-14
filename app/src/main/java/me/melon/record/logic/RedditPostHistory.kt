package me.melon.record.logic

import com.google.gson.JsonParser
import me.melon.record.data.model.PendingRecordSnapshot
import me.melon.record.data.model.RecordIdentity
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.encodeSnapshotRecord
import me.melon.record.data.model.recordSnapshot
import me.melon.record.data.model.toRecord
import me.melon.record.data.model.toSnapshot
import me.melon.record.parser.reddit.RedditReadVisit
import java.security.MessageDigest
import kotlin.math.abs

internal const val REDDIT_VISIT_KEY_PREFIX = "reddit-visit:"
internal const val REDDIT_LEGACY_VISIT_WINDOW = 30_000L
private const val CONTENT_BEFORE_VISIT = 30_000L
private const val CONTENT_AFTER_VISIT = 120_000L

/** A body-less GraphQL parent/configuration object is not an empty version of the post. */
internal fun hasRedditPostBody(record: UniversalRecord): Boolean = runCatching {
    val meta = JsonParser.parseString(record.extraMetadata).asJsonObject
    meta.get("body_complete")?.asBoolean == true || meta.get("media_complete")?.asBoolean == true
}.getOrDefault(false)

/** Transaction-owned visit markers and source bodies share the bounded pending-history store. */
internal class RedditPostHistory(
    private val history: RecordSnapshotStorage,
    private val beforeAppend: (UniversalRecord) -> Unit
) {
    fun accept(rawId: Long, records: List<UniversalRecord>, visits: List<RedditReadVisit>, now: Long) {
        prunePendingHistory(history, now)
        val posts = records.filter { it.platform == "reddit" && it.recordType == "post" }
        for ((index, record) in posts.withIndex()) {
            if (!hasRedditPostBody(record) || record.captureTime < now - PENDING_HISTORY_TTL_MILLIS) continue
            val key = "reddit-content:$rawId:${record.platformId}:$index"
            if (!history.hasRedditPostSource(RecordIdentity.of("reddit", record.platformId), key) &&
                history.findPending(key) == null) history.putPending(PendingRecordSnapshot(
                key = key, platformId = record.platformId, captureTime = record.captureTime,
                recordJson = encodeSnapshotRecord(record), createdAt = minOf(now, record.captureTime)
            ))
        }
        for (visit in visits) {
            // Retained Partial raws may be retried after pending eviction. Anchor this
            // deadline to capture time so deletion cannot reset their retention clock.
            if (visit.captureTime < now - PENDING_HISTORY_TTL_MILLIS) continue
            val key = visitKey(visit)
            if (history.findRedditVisit(key) == null && history.findPending(key) == null) {
                history.putPending(PendingRecordSnapshot(
                    key = key, platformId = visit.postId, captureTime = visit.captureTime,
                    createdAt = minOf(now, visit.captureTime)
                ))
            }
        }
        for (postId in (posts.map { it.platformId } + visits.map { it.postId }).distinct()) {
            release(postId)
        }
        prunePendingHistory(history, now)
    }

    private fun visitKey(visit: RedditReadVisit): String {
        val prefix = "$REDDIT_VISIT_KEY_PREFIX${visit.postId}:"
        visit.token?.let { token ->
            // Persist a stable identity, not Reddit's opaque request token.
            val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return "${prefix}token:$digest"
        }
        // Legacy captures have no navigation token. Coalesce only a bounded burst, not all
        // future reads of this post; do not depend on sync time or an in-memory singleton.
        val identity = RecordIdentity.of("reddit", visit.postId)
        val previous = history.pendingFor(identity).filter { it.key.startsWith(prefix) }
            .map { it.key to it.captureTime } + history.redditVisitsNear(identity, visit.captureTime)
            .map { it.key.substringBefore(":source:") to it.captureTime }
        return previous.filter { abs(visit.captureTime - it.second) <= REDDIT_LEGACY_VISIT_WINDOW }
            .minByOrNull { abs(visit.captureTime - it.second) }?.first
            ?: "${prefix}legacy:${visit.captureTime}"
    }

    private fun release(postId: String) {
        val pending = history.pendingFor(RecordIdentity.of("reddit", postId))
        val visits = pending.filter { it.key.startsWith(REDDIT_VISIT_KEY_PREFIX) }.sortedBy { it.captureTime }
        val sources = pending.filterNot { it.key.startsWith(REDDIT_VISIT_KEY_PREFIX) }.toMutableList()
        // Pre-fix pending queues may also contain sparse raw captures. Never promote those.
        sources.filterNot { hasRedditPostBody(it.toSnapshot().toRecord()) }.forEach {
            history.removePending(it)
            sources.remove(it)
        }
        // Associate the nearest source/visit pair first. An older unfinished visit must
        // not steal a later visit's hydration merely because it sorts first.
        val remaining = visits.toMutableList()
        while (remaining.isNotEmpty() && sources.isNotEmpty()) {
            val pair = remaining.asSequence().flatMap { visit -> sources.asSequence().map { source -> visit to source } }
                .filter { (visit, source) ->
                    source.captureTime >= visit.captureTime - CONTENT_BEFORE_VISIT &&
                        source.captureTime <= visit.captureTime + CONTENT_AFTER_VISIT
                }.minByOrNull { (visit, source) -> abs(source.captureTime - visit.captureTime) } ?: break
            val (visit, source) = pair
            val captured = source.toSnapshot().toRecord().copy(captureTime = visit.captureTime)
            if (history.findRedditVisit(visit.key) == null) {
                beforeAppend(captured)
                // Source provenance in the durable key makes a retained Partial raw's
                // replay idempotent even after its pending source has been consumed.
                history.append(recordSnapshot("${visit.key}:source:${source.key}", captured))
            }
            history.removePending(visit)
            history.removePending(source)
            remaining.remove(visit)
            sources.remove(source)
        }
    }
}
