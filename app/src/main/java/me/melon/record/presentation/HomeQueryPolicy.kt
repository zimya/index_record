package me.melon.record.presentation

import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.bodyPlainText

/** Immutable query input, independent of Android and storage. */
data class HomeQueryFilter(
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val apps: Set<String> = emptySet(),
    val types: Set<String> = emptySet()
) {
    val isActive: Boolean
        get() = startMillis != null || endMillis != null || apps.isNotEmpty() || types.isNotEmpty()
}

object HomeQueryPolicy {
    fun apply(
        records: List<UniversalRecord>,
        queryText: String,
        filter: HomeQueryFilter = HomeQueryFilter()
    ): List<UniversalRecord> {
        val query = queryText.trim()
        val includeSecondary = query.isNotEmpty() || filter.isActive
        return records.filter { record ->
            RecordContentPolicy.isEligibleForHome(record, includeSecondary) &&
                (filter.startMillis == null || record.captureTime >= filter.startMillis) &&
                (filter.endMillis == null || record.captureTime <= filter.endMillis) &&
                (filter.apps.isEmpty() || RecordContentPolicy.sourceApp(record) in filter.apps) &&
                (filter.types.isEmpty() || RecordContentPolicy.filterTypeKey(record) in filter.types) &&
                (query.isEmpty() || sequenceOf(
                    record.title, record.bodyPlainText(), record.authorName, record.platform,
                    record.recordType, record.extraMetadata, record.sourceUrl,
                    RecordContentPolicy.sourceApp(record), RecordContentPolicy.filterTypeLabel(record)
                ).any { it.contains(query, ignoreCase = true) })
        }
    }
}
