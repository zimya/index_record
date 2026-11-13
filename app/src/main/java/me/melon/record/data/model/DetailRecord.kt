package me.melon.record.data.model

import java.util.Collections

/** Read-only content value. ObjectBox entities never escape into detail snapshots. */
internal data class DetailRecord(
    val id: Long = 0,
    val platform: String = "",
    val recordType: String = "",
    val sourceApp: String? = null,
    val platformId: String = "",
    val title: String = "",
    val content: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatar: String = "",
    val sourceUrl: String = "",
    val parentId: String = "",
    val publishTime: Long = 0,
    val captureTime: Long = 0,
    val extraMetadata: String = "{}",
    val contentFormat: String = RecordBodyFormat.PLAIN_TEXT.storageValue
) {
    val identity: RecordIdentity get() = RecordIdentity.of(platform, platformId)
}

internal fun UniversalRecord.toDetailRecord(): DetailRecord = DetailRecord(
    id, platform, recordType, sourceApp, platformId, title, content, authorId, authorName,
    authorAvatar, sourceUrl, parentId, publishTime, captureTime, extraMetadata, contentFormat
)

/** Navigation and existing rendering components receive a fresh entity, never the snapshot's storage. */
internal fun DetailRecord.toEntity(): UniversalRecord = UniversalRecord(
    id, platform, recordType, sourceApp, platformId, title, content, authorId, authorName,
    authorAvatar, sourceUrl, parentId, publishTime, captureTime, extraMetadata, contentFormat
)

internal fun <T> immutableList(values: Iterable<T>): List<T> =
    Collections.unmodifiableList(values.toMutableList())

internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
