package me.melon.record.data.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/** Append-only capture payload. The current index's IDs and completion writes do not own this row. */
@Entity
data class RecordSnapshot(
    @Id var id: Long = 0,
    @Unique var key: String = "",
    @Index var platform: String = "",
    @Index var identityId: String = "",
    var captureTime: Long = 0,
    var legacy: Boolean = false,
    var recordJson: String = ""
)

internal fun recordSnapshot(key: String, record: UniversalRecord, legacy: Boolean = false): RecordSnapshot =
    RecordSnapshot(
        key = key, platform = record.identity.platform, identityId = record.identity.contentId,
        captureTime = record.captureTime, legacy = legacy, recordJson = encodeSnapshotRecord(record)
    )

/** Explicit wire names: never reflect on UniversalRecord fields, which R8 may rename. */
internal fun encodeSnapshotRecord(record: UniversalRecord): String = JsonObject().apply {
    addProperty("version", 1)
    addProperty("id", record.id)
    addProperty("platform", record.platform)
    addProperty("recordType", record.recordType)
    addProperty("sourceApp", record.sourceApp)
    addProperty("platformId", record.platformId)
    addProperty("title", record.title)
    addProperty("content", record.content)
    addProperty("authorId", record.authorId)
    addProperty("authorName", record.authorName)
    addProperty("authorAvatar", record.authorAvatar)
    addProperty("sourceUrl", record.sourceUrl)
    addProperty("parentId", record.parentId)
    addProperty("publishTime", record.publishTime)
    addProperty("captureTime", record.captureTime)
    addProperty("extraMetadata", record.extraMetadata)
    addProperty("contentFormat", record.contentFormat)
}.toString()

internal fun RecordSnapshot.toRecord(): UniversalRecord {
    val json = JsonParser.parseString(recordJson).asJsonObject
    require(json.get("version")?.asInt == 1) { "Unsupported record snapshot version" }
    fun text(name: String): String = json.get(name).asString
    return UniversalRecord(
        id = json.get("id").asLong,
        platform = text("platform"), recordType = text("recordType"),
        sourceApp = json.get("sourceApp")?.takeUnless { it.isJsonNull }?.asString,
        platformId = text("platformId"), title = text("title"), content = text("content"),
        authorId = text("authorId"), authorName = text("authorName"), authorAvatar = text("authorAvatar"),
        sourceUrl = text("sourceUrl"), parentId = text("parentId"),
        publishTime = json.get("publishTime").asLong, captureTime = json.get("captureTime").asLong,
        extraMetadata = text("extraMetadata"), contentFormat = text("contentFormat")
    )
}

/** Remains identical when an on-demand legacy backup replaces the live-index fallback. */
internal fun legacySnapshotKey(record: UniversalRecord): String = "legacy:${record.id}:${record.platform}:${record.platformId}"
