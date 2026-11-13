package me.melon.record

import me.melon.record.data.model.DetailRecord
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.model.toEntity
import me.melon.record.data.repository.TiebaDetailSnapshot
import me.melon.record.data.repository.assembleTiebaDetailSnapshot
import org.junit.Assert.*
import org.junit.Test

class TiebaDetailRepositoryTest {
    private fun record(id: String, type: String, parent: String = "", metadata: String = "{}", time: Long = 0) =
        UniversalRecord(platform = "tieba", platformId = id, recordType = type, parentId = parent,
            extraMetadata = metadata, publishTime = time)

    private fun snapshot(current: UniversalRecord, records: List<UniversalRecord>) =
        assembleTiebaDetailSnapshot(current,
            find = { id -> records.firstOrNull { it.platformId == id } },
            children = { ids -> records.filter { it.parentId in ids } })

    @Test
    fun threadIncludesFirstFloorRepliesAndSortsOtherFloorsAndReplies() {
        val thread = record("tieba_thread_1", "lou")
        val second = record("floor_2", "floor", thread.platformId, """{"floor":2}""")
        val third = record("floor_3", "floor", thread.platformId, """{"floor":3}""")
        val unknown = record("floor_unknown", "floor", thread.platformId, """{"floor":0}""")
        val firstReply = record("reply_first", "comment", thread.platformId)
        val a = record("a", "comment", second.platformId, """{"sub_floor":2}""", 10)
        val b = record("b", "comment", second.platformId, """{"sub_floor":1}""", 20)
        val c = record("c", "comment", second.platformId, time = 30)
        val d = record("d", "comment", second.platformId, time = 0)
        val result = snapshot(thread, listOf(thread, unknown, third, second, firstReply, d, c, a, b))
        assertEquals(listOf(second, third, unknown).map { it.toDetailRecord() }, result.floors)
        assertEquals(listOf(firstReply.toDetailRecord()), result.repliesByParent[thread.platformId])
        assertEquals(listOf(b, a, c, d).map { it.toDetailRecord() }, result.repliesByParent[second.platformId])
        assertEquals(thread.toDetailRecord(), result.thread)
        assertEquals(thread.toDetailRecord(), result.floor)
    }

    @Test
    fun missingParentUsesMetadataAndRetainsCurrentReplyWithoutDuplication() {
        val thread = record("tieba_thread_1", "lou")
        val reply = record("reply", "comment", "missing_floor", """{"thread_id":"1"}""")
        val other = record("other", "comment", "missing_floor", """{"sub_floor":1}""")
        val result = snapshot(reply, listOf(thread, reply, other))
        assertEquals(thread.toDetailRecord(), result.thread)
        assertNull(result.floor)
        assertEquals(listOf(other, reply).map { it.toDetailRecord() }, result.repliesByParent["missing_floor"])
        assertEquals(listOf(other, reply).map { it.toDetailRecord() }, result.replies)
    }

    @Test
    fun everySnapshotRefreshesCurrentAndAllRelationsIncludingDeletion() {
        val stale = record("reply", "comment", "floor")
        val updated = stale.copy(content = "新正文")
        val floor = record("floor", "floor", "tieba_thread_1")
        val thread = record("tieba_thread_1", "lou")
        val initial = snapshot(stale, emptyList())
        assertEquals(stale.toDetailRecord(), initial.current)
        assertNull(initial.floor)
        assertNull(initial.thread)
        val refreshed = snapshot(stale, listOf(updated, floor, thread))
        assertEquals(updated.toDetailRecord(), refreshed.current)
        assertEquals(floor.toDetailRecord(), refreshed.floor)
        assertEquals(thread.toDetailRecord(), refreshed.thread)
        assertEquals(listOf(updated.toDetailRecord()), refreshed.repliesByParent["floor"])
        val deleted = snapshot(stale, listOf(updated))
        assertNull(deleted.floor)
        assertNull(deleted.thread)
        assertTrue(deleted.floors.isEmpty())
    }

    @Test
    fun replyToFirstFloorResolvesThreadAsOwner() {
        val thread = record("tieba_thread_1", "lou")
        val reply = record("reply", "comment", thread.platformId)
        val result = snapshot(reply, listOf(thread, reply))
        assertEquals(thread.toDetailRecord(), result.thread)
        assertEquals(thread.toDetailRecord(), result.floor)
        assertEquals(listOf(reply.toDetailRecord()), result.repliesByParent[thread.platformId])
        assertEquals(listOf(reply.toDetailRecord()), result.replies)
    }

    @Test
    fun snapshotsDetachEveryEntityAndFallbackFromTheirSources() {
        val thread = record("tieba_thread_1", "lou")
        val floor = record("floor", "floor", thread.platformId)
        val reply = record("reply", "comment", floor.platformId)
        val records = listOf(thread, floor, reply)
        val threadSnapshot = snapshot(thread, records)
        val replySnapshot = snapshot(reply, records)
        val fallbackSnapshot = snapshot(reply, emptyList())
        thread.content = "changed thread"
        floor.content = "changed floor"
        reply.content = "changed reply"
        assertEquals("", threadSnapshot.current.content)
        assertEquals("", threadSnapshot.thread!!.content)
        assertEquals("", threadSnapshot.floor!!.content)
        assertEquals("", threadSnapshot.floors.single().content)
        assertEquals("", threadSnapshot.repliesByParent["floor"]!!.single().content)
        assertEquals("", replySnapshot.current.content)
        assertEquals("", replySnapshot.thread!!.content)
        assertEquals("", replySnapshot.floor!!.content)
        assertEquals("", replySnapshot.replies.single().content)
        assertEquals("", fallbackSnapshot.current.content)
        assertEquals("", fallbackSnapshot.replies.single().content)
        val refreshed = snapshot(reply, records)
        assertEquals("changed reply", refreshed.current.content)
        assertEquals("changed floor", refreshed.floor!!.content)
        val navigationEntity = replySnapshot.current.toEntity()
        navigationEntity.content = "navigation mutation"
        assertEquals("", replySnapshot.current.content)
    }

    @Test
    fun typedFloorRelationFindsThreadWithoutUiInterpretingStorageIds() {
        val thread = record("thread_without_prefix", "lou")
        val floor = record("floor", "floor", thread.platformId)
        val reply = record("reply", "comment", floor.platformId)
        val records = listOf(thread, floor, reply)
        assertEquals(thread.toDetailRecord(), snapshot(floor, records).thread)
        val result = snapshot(reply, records)
        assertEquals(thread.toDetailRecord(), result.thread)
        assertEquals(floor.toDetailRecord(), result.floor)
        assertEquals(listOf(reply.toDetailRecord()), result.replies)
    }

    @Test
    fun snapshotConstructorDefensivelyCopiesAndFreezesNestedCollections() {
        val current = record("thread", "lou").toDetailRecord()
        val child = record("floor", "floor", "thread").toDetailRecord()
        val floors = mutableListOf(child)
        val replies = mutableListOf(child)
        val grouped = mutableMapOf("thread" to replies as List<DetailRecord>)
        val result = TiebaDetailSnapshot(current, floors = floors, repliesByParent = grouped, replies = replies)
        floors.clear()
        replies.clear()
        grouped.clear()
        assertEquals(listOf(child), result.floors)
        assertEquals(listOf(child), result.replies)
        assertEquals(listOf(child), result.repliesByParent["thread"])
        assertThrows(UnsupportedOperationException::class.java) { (result.floors as MutableList<DetailRecord>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.replies as MutableList<DetailRecord>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.repliesByParent as MutableMap<String, List<DetailRecord>>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.repliesByParent.getValue("thread") as MutableList<DetailRecord>).clear() }
    }
}
