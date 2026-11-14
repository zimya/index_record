package com.melonhu.indexrecord.parser.reddit

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RedditPendingRecord
import com.melonhu.indexrecord.data.model.UniversalRecord

/** Persistent READ staging. Only explicit thread visits establish proof, never saved record payloads. */
class RedditReadBuffer(private val storage: Storage) {
    interface Storage {
        fun find(platformId: String): RedditPendingRecord?
        fun forPost(postId: String): List<RedditPendingRecord>
        fun put(row: RedditPendingRecord)
        fun remove(row: RedditPendingRecord)

        /** All evictable rows, including payload-free comment mappings; confirmed roots may be omitted. */
        fun pending(): List<RedditPendingRecord>
    }

    private val gson = Gson()

    fun accept(
        records: List<UniversalRecord>,
        confirmedPostIds: Set<String>,
        now: Long
    ): List<UniversalRecord> {
        val touched = linkedSetOf<String>()
        for (record in records) {
            if (record.platform != "reddit" || record.platformId.isBlank()) continue
            val previous = storage.find(record.platformId)
            val merged = record.copy()
            previous?.recordJson?.takeIf { it.isNotEmpty() }?.let {
                RedditRelations.mergeRecord(gson.fromJson(it, UniversalRecord::class.java), merged)
            }
            val postId = when (merged.recordType) {
                "post" -> merged.platformId
                "comment" -> commentPostId(merged).ifBlank { previous?.postId.orEmpty() }
                else -> ""
            }
            if (merged.recordType == "comment" && postId.isNotBlank() && commentPostId(merged).isBlank()) {
                val meta = runCatching { JsonParser.parseString(merged.extraMetadata).asJsonObject }
                    .getOrElse { com.google.gson.JsonObject() }
                meta.addProperty("post_id", postId)
                merged.extraMetadata = meta.toString()
            }
            val row = previous?.copy() ?: RedditPendingRecord(platformId = merged.platformId)
            row.postId = postId
            row.recordJson = gson.toJson(merged)
            row.updatedAt = now
            storage.put(row)
            if (postId.isNotBlank()) touched.add(postId)
        }

        for (postId in confirmedPostIds.filter { it.isNotBlank() }) {
            val root = storage.find(postId)?.copy() ?: RedditPendingRecord(platformId = postId)
            root.postId = postId
            root.confirmed = true
            root.updatedAt = now
            storage.put(root)
            touched.add(postId)
        }

        val emitted = mutableListOf<UniversalRecord>()
        for (postId in touched) {
            val root = storage.find(postId) ?: continue
            if (!isProof(root)) continue
            for (row in storage.forPost(postId)) {
                if (row.recordJson.isEmpty()) continue
                emitted.add(gson.fromJson(row.recordJson, UniversalRecord::class.java))
                // Retain root proof and lightweight comment -> post mappings, not duplicate DB bodies.
                storage.put(row.copy(recordJson = "", updatedAt = now))
            }
        }
        prune(now)
        return emitted
    }

    private fun commentPostId(record: UniversalRecord): String = runCatching {
        JsonParser.parseString(record.extraMetadata).asJsonObject["post_id"]
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
    }.getOrDefault("")

    private fun isProof(row: RedditPendingRecord): Boolean =
        row.confirmed && row.postId.isNotBlank() && row.platformId == row.postId

    private fun prune(now: Long) {
        val pending = storage.pending().filterNot(::isProof)
        val (expired, retained) = pending.partition { it.updatedAt < now - PENDING_TTL_MILLIS }
        expired.forEach(storage::remove)
        retained.sortedWith(compareBy<RedditPendingRecord> { it.updatedAt }.thenBy { it.platformId })
            .take((retained.size - MAX_PENDING).coerceAtLeast(0))
            .forEach(storage::remove)
    }

    private companion object {
        const val PENDING_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val MAX_PENDING = 5000
    }
}
