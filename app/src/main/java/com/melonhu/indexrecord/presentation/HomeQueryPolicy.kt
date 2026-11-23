package com.melonhu.indexrecord.presentation

import com.melonhu.indexrecord.data.model.UniversalRecord

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
        filter: HomeQueryFilter = HomeQueryFilter()
    ): List<UniversalRecord> {
        val includeSecondary = filter.isActive
        return records.filter { record ->
            RecordContentPolicy.isEligibleForHome(record, includeSecondary) &&
                (filter.startMillis == null || record.captureTime >= filter.startMillis) &&
                (filter.endMillis == null || record.captureTime <= filter.endMillis) &&
                (filter.apps.isEmpty() || RecordContentPolicy.sourceApp(record) in filter.apps) &&
                (filter.types.isEmpty() || RecordContentPolicy.filterTypeKey(record) in filter.types)
        }
    }
}
