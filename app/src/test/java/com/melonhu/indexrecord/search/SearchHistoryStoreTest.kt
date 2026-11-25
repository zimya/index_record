package com.melonhu.indexrecord.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryStoreTest {

    @Test
    fun `addEntry trims, deduplicates, and moves to front`() {
        val initial = listOf("kotlin", "compose", "android")
        val updated = SearchHistoryStore.addEntry(initial, "  compose  ")
        assertEquals(listOf("compose", "kotlin", "android"), updated)
    }

    @Test
    fun `addEntry ignores blank queries`() {
        val initial = listOf("kotlin")
        assertEquals(initial, SearchHistoryStore.addEntry(initial, "   "))
    }

    @Test
    fun `addEntry caps list at max entries`() {
        var list = emptyList<String>()
        for (i in 1..(SearchHistoryStore.MAX_ENTRIES + 5)) {
            list = SearchHistoryStore.addEntry(list, "query-$i")
        }
        assertEquals(SearchHistoryStore.MAX_ENTRIES, list.size)
        assertEquals("query-${SearchHistoryStore.MAX_ENTRIES + 5}", list.first())
    }

    @Test
    fun `removeEntry removes matching item`() {
        val initial = listOf("kotlin", "compose", "android")
        assertEquals(
            listOf("kotlin", "android"),
            SearchHistoryStore.removeEntry(initial, "compose")
        )
    }

    @Test
    fun `encodeJson and decodeJson roundtrip`() {
        val entries = listOf("知乎", "compose \"test\"", "android")
        val encoded = SearchHistoryStore.encodeJson(entries)
        assertEquals(entries, SearchHistoryStore.decodeJson(encoded))
    }
}
