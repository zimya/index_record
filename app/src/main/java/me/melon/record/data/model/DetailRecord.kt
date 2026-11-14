package me.melon.record.data.model

import java.util.Collections

/** Read-only content value. ObjectBox entities never escape into detail snapshots. */
internal data class DetailRecord(
    override val id: Long = 0,
    override val platform: String = "",
    override val recordType: String = "",
    override val sourceApp: String? = null,
    override val platformId: String = "",
    override val title: String = "",
    override val content: String = "",
    override val authorId: String = "",
    override val authorName: String = "",
    override val authorAvatar: String = "",
    override val sourceUrl: String = "",
    override val parentId: String = "",
    override val publishTime: Long = 0,
    override val captureTime: Long = 0,
    override val extraMetadata: String = "{}",
    override val contentFormat: String = RecordBodyFormat.PLAIN_TEXT.storageValue
) : RecordContent {
    val identity: RecordIdentity get() = RecordIdentity.of(platform, platformId)
}

internal fun UniversalRecord.toDetailRecord(): DetailRecord = DetailRecord(
    id, platform, recordType, sourceApp, platformId, title, content, authorId, authorName,
    authorAvatar, sourceUrl, parentId, publishTime, captureTime, extraMetadata, contentFormat
)

/** Convert only at navigation or storage boundaries, never to render content. */
internal fun RecordContent.toEntity(): UniversalRecord = UniversalRecord(
    id, platform, recordType, sourceApp, platformId, title, content, authorId, authorName,
    authorAvatar, sourceUrl, parentId, publishTime, captureTime, extraMetadata, contentFormat
)

internal fun <T> immutableList(values: Iterable<T>): List<T> =
    Collections.unmodifiableList(values.toMutableList())

internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
