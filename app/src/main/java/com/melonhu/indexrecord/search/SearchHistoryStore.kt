package com.melonhu.indexrecord.search

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

internal class SearchHistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<String> {
        return decodeJson(prefs.getString(KEY_ENTRIES, null))
    }

    fun add(query: String): List<String> {
        val updated = addEntry(load(), query)
        save(updated)
        return updated
    }

    fun remove(query: String): List<String> {
        val updated = removeEntry(load(), query)
        save(updated)
        return updated
    }

    fun clear(): List<String> {
        prefs.edit().remove(KEY_ENTRIES).apply()
        return emptyList()
    }

    private fun save(entries: List<String>) {
        prefs.edit().putString(KEY_ENTRIES, encodeJson(entries)).apply()
    }

    companion object {
        private const val PREFS_NAME = "search_history"
        private const val KEY_ENTRIES = "entries"
        internal const val MAX_ENTRIES = 20

        internal fun addEntry(current: List<String>, rawQuery: String): List<String> {
            val trimmed = rawQuery.trim()
            if (trimmed.isEmpty()) return current
            val next = ArrayList<String>(minOf(current.size + 1, MAX_ENTRIES))
            next.add(trimmed)
            for (item in current) {
                if (item != trimmed && next.size < MAX_ENTRIES) {
                    next.add(item)
                }
            }
            return next
        }

        internal fun removeEntry(current: List<String>, query: String): List<String> {
            return current.filterNot { it == query }
        }

        internal fun encodeJson(entries: List<String>): String {
            val array = JSONArray()
            entries.forEach { array.put(it) }
            return array.toString()
        }

        internal fun decodeJson(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList(array.length()) {
                    for (i in 0 until array.length()) {
                        val value = array.optString(i).trim()
                        if (value.isNotEmpty() && value !in this) {
                            add(value)
                        }
                    }
                }.take(MAX_ENTRIES)
            }.getOrDefault(emptyList())
        }
    }
}
