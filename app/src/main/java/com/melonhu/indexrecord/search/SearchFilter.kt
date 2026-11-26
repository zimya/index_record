package com.melonhu.indexrecord.search

internal data class SearchFilter(
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val platforms: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val sources: Set<SearchMatchSource> = SearchMatchSource.entries.toSet()
) {
    val hasMetadataFilter: Boolean
        get() = startMillis != null || endMillis != null || platforms.isNotEmpty() || types.isNotEmpty()

    val hasSemanticSource: Boolean
        get() = SearchMatchSource.TEXT_SEMANTIC in sources || SearchMatchSource.IMAGE_VISUAL in sources
}
