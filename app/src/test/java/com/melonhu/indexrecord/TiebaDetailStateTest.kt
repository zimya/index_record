package com.melonhu.indexrecord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import com.melonhu.indexrecord.data.model.DetailRecord
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.data.model.toDetailRecord
import com.melonhu.indexrecord.data.repository.TiebaDetailSnapshot
import com.melonhu.indexrecord.viewmodel.DetailLoadState
import com.melonhu.indexrecord.viewmodel.TiebaDetailData
import com.melonhu.indexrecord.viewmodel.tiebaDetailStates
import org.junit.Assert.*
import org.junit.Test

class TiebaDetailStateTest {
    private fun record() = UniversalRecord(platform = "tieba", platformId = "reply", recordType = "comment").toDetailRecord()

    @Test
    fun initialStateIsLoading() {
        assertEquals(DetailLoadState.Loading, TiebaDetailData(record()).loadState)
    }

    @Test
    fun failedSnapshotRetainsCompleteSnapshotAndLaterSuccessReplacesIt() = runBlocking {
        val initial = record()
        val current = initial.copy(content = "loaded")
        val floor = initial.copy(platformId = "floor", recordType = "floor")
        val thread = initial.copy(platformId = "thread", recordType = "lou")
        val updated = current.copy(content = "updated")
        val states = tiebaDetailStates(initial, flowOf(
            Result.success(TiebaDetailSnapshot(current, thread, floor, listOf(floor), mapOf("floor" to listOf(current)), listOf(current))),
            Result.failure(IllegalStateException("read failed")),
            Result.success(TiebaDetailSnapshot(updated))
        )).toList()
        assertEquals(3, states.size)
        assertEquals(DetailLoadState.Ready, states[0].loadState)
        assertTrue(states[1].loadState is DetailLoadState.Failed)
        assertEquals("无法读取本地记录，请重试", (states[1].loadState as DetailLoadState.Failed).message)
        assertEquals(current, states[1].current)
        assertEquals(thread, states[1].thread)
        assertEquals(floor, states[1].floor)
        assertEquals(listOf(floor), states[1].floors)
        assertEquals(mapOf("floor" to listOf(current)), states[1].repliesByParent)
        assertEquals(listOf(current), states[1].replies)
        assertEquals(DetailLoadState.Ready, states[2].loadState)
        assertEquals("updated", states[2].current.content)
        assertNull(states[2].thread)
        assertNull(states[2].floor)
        assertTrue(states[2].repliesByParent.isEmpty())
        assertEquals("loaded", states[0].current.content)
    }

    @Test
    fun failureBeforeFirstSuccessUsesImmutableInitialRecord() = runBlocking {
        val initial = record()
        val states = tiebaDetailStates(initial, flowOf(Result.failure<TiebaDetailSnapshot>(IllegalStateException()))).toList()
        assertEquals(initial, states.single().current)
        assertTrue(states.single().loadState is DetailLoadState.Failed)
    }

    @Test
    fun unexpectedUpstreamFailureIsPresentedAsFailed() = runBlocking {
        val states = tiebaDetailStates(record(), flow { throw IllegalStateException("subscription failed") }).toList()
        assertTrue(states.single().loadState is DetailLoadState.Failed)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotPresentedAsReadFailure() {
        runBlocking {
            tiebaDetailStates(record(), flow { throw CancellationException("cancelled") }).toList()
        }
    }

    @Test
    fun stateConstructorDefensivelyCopiesAndFreezesNestedCollections() {
        val current = record()
        val floors = mutableListOf(current)
        val replies = mutableListOf(current)
        val grouped = mutableMapOf("floor" to replies as List<DetailRecord>)
        val state = TiebaDetailData(current, floors = floors, repliesByParent = grouped, replies = replies)
        floors.clear()
        replies.clear()
        grouped.clear()
        assertEquals(listOf(current), state.floors)
        assertEquals(listOf(current), state.replies)
        assertEquals(listOf(current), state.repliesByParent["floor"])
        assertThrows(UnsupportedOperationException::class.java) { (state.floors as MutableList<DetailRecord>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (state.replies as MutableList<DetailRecord>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (state.repliesByParent as MutableMap<String, List<DetailRecord>>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (state.repliesByParent.getValue("floor") as MutableList<DetailRecord>).clear() }
    }
}
