package me.melon.record

import me.melon.record.data.model.UniversalRecord
import me.melon.record.ui.components.typeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordFilterTypeTest {
    @Test
    fun storedTypesAndFilterLabelsAreDistinct() {
        val records = listOf(
            UniversalRecord(platform = "tieba", recordType = "lou"),
            UniversalRecord(platform = "threads", recordType = "thread")
        )
        assertEquals(listOf("tieba"), records.filter { it.recordType == "lou" }.map { it.platform })
        assertEquals(listOf("threads"), records.filter { it.recordType == "thread" }.map { it.platform })
        assertEquals("贴吧主帖", typeLabel("lou"))
        assertEquals("thread", typeLabel("thread"))
    }
}
