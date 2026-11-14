package com.melonhu.indexrecord.data.model

/** Read-only view shared by stored entities and immutable detail snapshots.
 * Implementations need not be immutable; consumers must never mutate them.
 */
interface RecordContent {
    val id: Long
    val platform: String
    val recordType: String
    val sourceApp: String?
    val platformId: String
    val title: String
    val content: String
    val authorId: String
    val authorName: String
    val authorAvatar: String
    val sourceUrl: String
    val parentId: String
    val publishTime: Long
    val captureTime: Long
    val extraMetadata: String
    val contentFormat: String
}
