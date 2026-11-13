package me.melon.record

import me.melon.record.data.model.RecordIdentity
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.repository.assembleQuestionDetailSnapshot
import me.melon.record.data.repository.QuestionDetailSnapshot
import me.melon.record.data.repository.ZhihuAnswerRef
import me.melon.record.data.repository.ZhihuTopicRef
import org.junit.Assert.*
import org.junit.Test

class QuestionDetailRepositoryTest {
    private fun question(ids: String = "1") = UniversalRecord(platform = "zhihu", platformId = "zhihu_question_1",
        recordType = "question", extraMetadata = """{"excerpt":"summary","topics":[{"id":"t","name":"topic"}],"answer_refs":[{"id":"$ids","voteup_count":7,"author":{"name":"author"}}]}""")
    private fun answer(id: String) = UniversalRecord(platform = "zhihu", platformId = "zhihu_answer_$id",
        recordType = "answer", parentId = "zhihu_question_1", content = "body $id")
    private fun snapshot(seed: UniversalRecord, records: List<UniversalRecord>) =
        assembleQuestionDetailSnapshot(seed.toDetailRecord()) { identity ->
            records.firstOrNull { RecordIdentity.of(it.platform, it.platformId) == identity }?.toDetailRecord()
        }

    @Test fun refreshesWholeQuestionAndEqualLengthReferenceReplacement() {
        val seed = question()
        val first = snapshot(seed, listOf(seed, answer("1")))
        val updated = question("2").copy(title = "fresh title", content = "fresh body")
        val second = snapshot(seed, listOf(updated, answer("1"), answer("2")))
        assertEquals(setOf("1"), first.existingAnswers.keys)
        assertEquals("fresh title", second.record.title)
        assertEquals("fresh body", second.record.content)
        assertEquals("summary", second.excerpt)
        assertEquals("topic", second.topics.single().name)
        assertEquals(listOf("2"), second.answerRefs.map { it.id })
        assertEquals(setOf("2"), second.existingAnswers.keys)
        assertEquals(7L, second.answerRefs.single().voteupCount)
    }

    @Test fun cachedAnswerArrivalUpdateAndDeletionAreReflected() {
        val seed = question()
        assertTrue(snapshot(seed, listOf(seed)).existingAnswers.isEmpty())
        val cached = answer("1")
        val before = snapshot(seed, listOf(seed, cached))
        cached.content = "updated body"
        val after = snapshot(seed, listOf(seed, cached))
        assertEquals("body 1", before.existingAnswers.getValue("1").content)
        assertEquals("updated body", after.existingAnswers.getValue("1").content)
        assertTrue(snapshot(seed, listOf(seed)).existingAnswers.isEmpty())
        val otherPlatform = cached.copy(platform = "other")
        assertTrue(snapshot(seed, listOf(seed, otherPlatform)).existingAnswers.isEmpty())
    }

    @Test fun explicitOtherQuestionRelationCannotEnableNavigation() {
        val seed = question()
        val unrelated = answer("1").copy(parentId = "zhihu_question_2")
        assertTrue(snapshot(seed, listOf(seed, unrelated)).existingAnswers.isEmpty())
        assertEquals(1, snapshot(seed, listOf(seed, unrelated.copy(parentId = ""))).existingAnswers.size)
    }

    @Test fun malformedMetadataAndDuplicateReferencesAreSafe() {
        val malformed = question().copy(extraMetadata = """{"topics":{},"answer_refs":[null,{}, {"id":"1","author":[],"voteup_count":{}},{"id":"1"}]}""")
        val result = snapshot(malformed, emptyList())
        assertTrue(result.topics.isEmpty())
        assertEquals(1, result.answerRefs.size)
        assertEquals(0L, result.answerRefs.single().voteupCount)
        assertTrue(snapshot(question().copy(extraMetadata = "invalid"), emptyList()).answerRefs.isEmpty())
    }

    @Test fun snapshotCopiesAllCollectionInputsAndRejectsCastMutation() {
        val current = question().toDetailRecord()
        val cached = answer("1").toDetailRecord()
        val topic = ZhihuTopicRef("t", "topic")
        val ref = ZhihuAnswerRef("1", "", 0, 0, 0, 0, "", "", "")
        val topics = mutableListOf(topic)
        val refs = mutableListOf(ref)
        val answers = mutableMapOf("1" to cached)
        val result = QuestionDetailSnapshot(current, topics = topics, answerRefs = refs, existingAnswers = answers)
        topics.clear()
        refs.clear()
        answers.clear()
        assertEquals(listOf(topic), result.topics)
        assertEquals(listOf(ref), result.answerRefs)
        assertEquals(mapOf("1" to cached), result.existingAnswers)
        assertThrows(UnsupportedOperationException::class.java) { (result.topics as MutableList).add(topic) }
        assertThrows(UnsupportedOperationException::class.java) { (result.answerRefs as MutableList)[0] = ref.copy(id = "2") }
        assertThrows(UnsupportedOperationException::class.java) { (result.existingAnswers as MutableMap)["2"] = cached }
        assertThrows(UnsupportedOperationException::class.java) { (result.existingAnswers as MutableMap).remove("1") }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.existingAnswers.entries as MutableSet).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.existingAnswers.entries.first() as MutableMap.MutableEntry).setValue(current)
        }
        assertEquals(mapOf("1" to cached), result.existingAnswers)
    }

    @Test fun emptySnapshotCollectionsAlsoRejectMutation() {
        val current = question().toDetailRecord()
        val result = QuestionDetailSnapshot(current)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.topics as MutableList).add(ZhihuTopicRef("t", "topic"))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.answerRefs as MutableList).add(ZhihuAnswerRef("1", "", 0, 0, 0, 0, "", "", ""))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.existingAnswers as MutableMap)["1"] = current
        }
    }

    @Test fun fallbackIsAnImmutableValue() {
        val seed = question()
        val result = snapshot(seed, emptyList())
        seed.content = "mutated"
        seed.extraMetadata = "{}"
        assertEquals("", result.record.content)
        assertEquals("summary", result.excerpt)
        assertEquals(1, result.answerRefs.size)
    }
}
