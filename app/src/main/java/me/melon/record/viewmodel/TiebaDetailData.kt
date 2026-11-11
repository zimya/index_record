package me.melon.record.viewmodel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.objectbox.query.QueryBuilder.StringOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import me.melon.record.ObjectBox
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.UniversalRecord_

/** UI metadata only; keep this independent of the capture/parser implementation. */
internal class TiebaMetadata(record: UniversalRecord) {
    private val json = runCatching {
        JsonParser.parseString(record.extraMetadata).asJsonObject
    }.getOrDefault(JsonObject())

    fun text(key: String): String = runCatching {
        json.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    }.getOrDefault("")

    fun number(key: String): Long? = text(key).toLongOrNull()?.takeIf { it >= 0 }
}

internal data class TiebaDetailData(
    val current: UniversalRecord,
    val thread: UniversalRecord? = null,
    val floor: UniversalRecord? = null,
    val floors: List<UniversalRecord> = emptyList(),
    val repliesByParent: Map<String, List<UniversalRecord>> = emptyMap(),
    val loaded: Boolean = false,
    val error: String? = null
)

internal fun observeTiebaDetail(record: UniversalRecord): Flow<TiebaDetailData> = callbackFlow {
    val subscription = ObjectBox.store.subscribe(UniversalRecord::class.java)
        .observer { trySend(Unit) }
    trySend(Unit)
    awaitClose { subscription.cancel() }
}.conflate().map {
    runCatching { queryTiebaDetail(record) }.getOrElse {
        TiebaDetailData(record, loaded = true, error = "无法读取本地记录，请返回后重试")
    }
}.flowOn(Dispatchers.IO)

private fun queryTiebaDetail(record: UniversalRecord): TiebaDetailData {
    val box = ObjectBox.store.boxFor(UniversalRecord::class.java)

    fun find(id: String): UniversalRecord? {
        if (id.isBlank()) return null
        val query = box.query()
            .equal(UniversalRecord_.platform, "tieba", StringOrder.CASE_SENSITIVE)
            .equal(UniversalRecord_.platformId, id, StringOrder.CASE_SENSITIVE)
            .build()
        return try { query.findFirst() } finally { query.close() }
    }

    fun children(ids: List<String>): List<UniversalRecord> {
        if (ids.isEmpty()) return emptyList()
        val query = box.query()
            .equal(UniversalRecord_.platform, "tieba", StringOrder.CASE_SENSITIVE)
            .`in`(UniversalRecord_.parentId, ids.distinct().toTypedArray(), StringOrder.CASE_SENSITIVE)
            .build()
        return try { query.find() } finally { query.close() }
    }

    val current = find(record.platformId) ?: record
    val parent = if (current.recordType == "comment") find(current.parentId) else null
    val threadId = when {
        current.recordType == "thread" -> current.platformId
        current.parentId.startsWith("tieba_thread_") -> current.parentId
        parent?.recordType == "thread" -> parent.platformId
        parent?.parentId?.startsWith("tieba_thread_") == true -> parent.parentId
        else -> TiebaMetadata(current).text("thread_id").takeIf { it.isNotBlank() }
            ?.let { "tieba_thread_$it" }.orEmpty()
    }
    val thread = if (current.recordType == "thread") current else find(threadId)
        ?.takeIf { it.recordType == "thread" }
    val floor = when (current.recordType) {
        "thread", "floor" -> current
        else -> parent?.takeIf { it.recordType == "floor" || it.recordType == "thread" }
    }
    val roots = if (current.recordType == "thread") children(listOf(current.platformId)) else emptyList()
    val floors = roots.filter { it.recordType == "floor" }
        .sortedWith(compareBy<UniversalRecord> {
            TiebaMetadata(it).number("floor")?.takeIf { n -> n > 0 } ?: Long.MAX_VALUE
        }.thenBy { it.platformId })
    val replyParents = buildList {
        addAll(floors.map { it.platformId })
        if (current.recordType != "thread") floor?.let { add(it.platformId) }
        if (current.recordType == "comment" && current.parentId.isNotBlank()) add(current.parentId)
    }
    val replies = (roots.filter { it.recordType == "comment" } +
        children(replyParents).filter { it.recordType == "comment" } +
        listOfNotNull(current.takeIf { it.recordType == "comment" }))
        .distinctBy { it.platformId }
        .sortedWith(compareBy<UniversalRecord> {
            TiebaMetadata(it).number("sub_floor")?.takeIf { n -> n > 0 } ?: Long.MAX_VALUE
        }.thenBy { it.publishTime.takeIf { time -> time > 0 } ?: Long.MAX_VALUE }
            .thenBy { it.platformId })
    return TiebaDetailData(
        current = current,
        thread = thread,
        floor = floor,
        floors = floors,
        repliesByParent = replies.groupBy { it.parentId },
        loaded = true
    )
}
