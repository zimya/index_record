package me.melon.record.ui.home

import me.melon.record.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test

class HomeRecordGroupingTest {
    private fun answer(id: Long, question: String = "1") = UniversalRecord(
        id = id, platform = "zhihu", recordType = "answer", platformId = "zhihu_answer_$id",
        parentId = "zhihu_question_$question", title = "同名问题"
    )

    private fun interest(id: Long, subject: String = "1") = UniversalRecord(
        id = id, platform = "douban", recordType = "interest", platformId = "douban_interest_$id",
        parentId = "douban_movie_$subject", title = "电影短评：同名作品"
    )

    @Test
    fun consecutiveGroupingPreservesOrderAndDoesNotMergeAcrossOtherRecords() {
        val a = answer(1)
        val b = answer(2)
        val separator = interest(3)
        val c = answer(4)
        val d = answer(5)
        val rows = groupRecordsForHome(listOf(a, b, separator, c, d))
        assertEquals(3, rows.size)
        assertEquals(listOf(a, b), (rows[0] as AnswerGroupListItem).answers)
        assertEquals(separator, (rows[1] as RecordListItem).record)
        assertEquals(listOf(c, d), (rows[2] as AnswerGroupListItem).answers)
        assertEquals(3, rows.map { it.key }.toSet().size)
    }

    @Test
    fun sameTitleWithDifferentIdsStaysInSeparateRuns() {
        val rows = groupRecordsForHome(listOf(answer(1), answer(2), answer(3, "2"), answer(4, "2")))
        assertEquals(listOf("zhihu_question_1", "zhihu_question_2"), rows.map { (it as AnswerGroupListItem).questionKey })
        val interests = groupRecordsForHome(listOf(interest(1), interest(2), interest(3, "2"), interest(4, "2")))
        assertEquals(listOf("douban_movie_1", "douban_movie_2"), interests.map { (it as InterestGroupListItem).subjectKey })
    }

    @Test
    fun identitylessSameTitlesAndSeparatedSingletonsStaySingle() {
        val records = listOf(
            interest(1).copy(parentId = "", extraMetadata = "broken"),
            interest(2).copy(parentId = "", extraMetadata = "{}"),
            answer(3), interest(4), answer(5)
        )
        assertEquals(records, groupRecordsForHome(records).map { (it as RecordListItem).record })
    }

    @Test
    fun relationAndMetadataFallbackGroupTogetherUsingFirstPresentation() {
        val first = interest(1).copy(extraMetadata = """{"subject_type":"movie","subject_title":"作品"}""")
        val second = interest(2).copy(parentId = "", extraMetadata = """{"subject_type":"movie","subject_id":"1","subject_title":"另一标题"}""")
        val row = groupRecordsForHome(listOf(first, second)).single() as InterestGroupListItem
        assertEquals("作品", row.subjectTitle)
        assertEquals("电影短评", row.typeLabel)
        assertEquals(listOf(first, second), row.interests)
        assertEquals(1L, row.firstId)
        assertTrue(groupRecordsForHome(emptyList()).isEmpty())
    }
}
