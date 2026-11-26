package com.melonhu.indexrecord.logic

import com.melonhu.indexrecord.data.model.UniversalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordStorageEventsTest {

    @Test
    fun notifiesRegisteredObserversAndIsolatesFailures() {
        val received = mutableListOf<String>()
        var clearedCount = 0

        val failing = object : RecordStorageObserver {
            override fun onRecordsPersisted(records: List<UniversalRecord>) {
                throw IllegalStateException("boom")
            }

            override fun onDatabaseCleared() {
                throw IllegalStateException("clear boom")
            }
        }
        val healthy = object : RecordStorageObserver {
            override fun onRecordsPersisted(records: List<UniversalRecord>) {
                received.addAll(records.map { it.platformId })
            }

            override fun onDatabaseCleared() {
                clearedCount++
            }
        }

        RecordStorageEvents.registerObserver(failing)
        RecordStorageEvents.registerObserver(healthy)
        try {
            RecordStorageEvents.notifyRecordsPersisted(emptyList())
            assertTrue(received.isEmpty())

            RecordStorageEvents.notifyRecordsPersisted(
                listOf(UniversalRecord(id = 1L, platform = "zhihu", platformId = "zhihu_answer_1"))
            )
            assertEquals(listOf("zhihu_answer_1"), received)

            RecordStorageEvents.notifyDatabaseCleared()
            assertEquals(1, clearedCount)
        } finally {
            RecordStorageEvents.unregisterObserver(failing)
            RecordStorageEvents.unregisterObserver(healthy)
        }
    }
}
