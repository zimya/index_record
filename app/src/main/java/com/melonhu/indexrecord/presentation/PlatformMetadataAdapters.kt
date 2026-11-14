package com.melonhu.indexrecord.presentation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.TiebaMetadata
import com.melonhu.indexrecord.data.model.RecordIdentity

/** Decode once at the boundary; platform adapters only receive the captured object. */
internal fun parseMetadataObject(extraMetadata: String): JsonObject =
    runCatching { JsonParser.parseString(extraMetadata).asJsonObject }.getOrElse { JsonObject() }

internal class ZhihuRecordMetadata(values: JsonObject) {
    val questionTarget: RecordIdentity? = values.metadataText("question_id").trim()
        .takeIf { it.isNotBlank() }?.let { RecordIdentity.of("zhihu", "zhihu_question_$it") }
    val excerpt: String = values.metadataText("excerpt")
    val titleImage: String = values.metadataText("title_image")
    val authorHeadline: String = values.metadataText("author_headline")
    val ipInfo: String? = values.metadataText("ip_info").takeIf { it.isNotBlank() }
}

/** Record-level fields, not the subject page's full-record fallbacks or aggregate rating. */
internal class DoubanRecordMetadata(values: JsonObject) {
    val intro: String = values.metadataText("intro")
    val cardSubtitle: String = values.metadataText("card_subtitle")
    val titleImage: String = values.metadataText("title_image")
    val subjectId: String = values.metadataText("subject_id")
    val subjectType: String = values.metadataText("subject_type").trim()
    val subjectTarget: RecordIdentity? = subjectId.trim().takeIf { it.isNotBlank() }?.let {
        RecordIdentity.of("douban", if (subjectType.isBlank()) "douban_$it" else "douban_${subjectType}_$it")
    }
    val subjectTitle: String = values.metadataText("subject_title")
    val ipInfo: String? = values.metadataText("ip_info").takeIf { it.isNotBlank() }
    val starCount: Double = values.metadataDecimal("star_count")?.takeIf { it > 0 && it <= 5 }
        ?: values.metadataDecimal("rating_value")?.takeIf { it in 0.0..5.0 } ?: 0.0
}

/** Only the selected platform gets an adapter, even if the payload contains foreign keys. */
internal class PlatformMetadataAdapters(values: JsonObject, platform: String) {
    constructor(extraMetadata: String, platform: String) : this(parseMetadataObject(extraMetadata), platform)

    val hasValues: Boolean = values.size() > 0
    val engagement: EngagementMetadata = platformEngagement(platform, values)
    val zhihu: ZhihuRecordMetadata? = if (platform == "zhihu") ZhihuRecordMetadata(values) else null
    val douban: DoubanRecordMetadata? = if (platform == "douban") DoubanRecordMetadata(values) else null
    val tieba: TiebaMetadata? = if (platform == "tieba") TiebaMetadata(values) else null
    val reddit: RedditMetadata? = if (platform == "reddit") RedditMetadata(values) else null
}

private fun JsonObject.metadataText(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

private fun JsonObject.metadataDecimal(key: String): Double? {
    val value = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!value.isNumber && !value.isString) return null
    return value.asString.toDoubleOrNull()?.takeIf { it.isFinite() }
}
