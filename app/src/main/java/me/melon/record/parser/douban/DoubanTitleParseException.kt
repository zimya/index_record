package me.melon.record.parser.douban

import me.melon.record.data.model.UniversalRecord

internal data class DoubanTitleLookup(val subjectId: String, val subjectType: String)

/** Lookup is reached even when a valid interests array contains no recordable items. */
internal data class DoubanInterestsResult(
    val records: List<UniversalRecord>,
    val titleLookup: DoubanTitleLookup? = null
)

/**
 * Immutable evidence from a failed parse that crossed a former title-cache boundary.
 * A non-null title means subject registration was reached; null means interest title lookup
 * was reached. The caller completes that evidence without admitting any failed record.
 */
internal class DoubanTitleParseException(
    val subjectId: String,
    val subjectType: String,
    val subjectTitle: String?,
    cause: Exception
) : RuntimeException(cause.message, cause)
