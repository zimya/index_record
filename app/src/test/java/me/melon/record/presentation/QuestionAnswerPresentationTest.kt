package me.melon.record.presentation

import me.melon.record.data.model.RecordBodyFormat
import me.melon.record.data.model.UniversalRecord
import me.melon.record.data.model.toDetailRecord
import me.melon.record.data.repository.ZhihuAnswerRef
import org.junit.Assert.*
import org.junit.Test

class QuestionAnswerPresentationTest {
    private val ref = ZhihuAnswerRef(
        id = "1",
        excerpt = "<p>API <b>摘要</b></p>",
        createdTimeSec = 10,
        updatedTimeSec = 20,
        voteupCount = 999,
        commentCount = 888,
        authorId = "ref-author",
        authorName = "API 作者",
        authorAvatar = "ref-avatar"
    )

    private fun answer(content: String, format: RecordBodyFormat) = UniversalRecord(
        platform = "zhihu",
        recordType = "answer",
        platformId = "zhihu_answer_1",
        content = content,
        contentFormat = format.storageValue,
        authorName = "已记录作者",
        authorAvatar = "stored-avatar",
        publishTime = 30000,
        extraMetadata = """{"voteup_count":0,"comment_count":12}"""
    )

    @Test
    fun storedAnswerUsesSameAuthorPreviewAndStatisticsAsOtherEntrances() {
        val record = answer("**正文** [链接](https://hidden.example)", RecordBodyFormat.MARKDOWN)
        val result = questionAnswerPresentation(ref, record.toDetailRecord())

        assertEquals(displayAuthor(record.authorName), result.author)
        assertEquals(record.authorAvatar, result.authorAvatar)
        assertEquals(record.publishTime, result.publishTime)
        assertEquals(recordPreviewText(record), result.preview)
        assertEquals("正文 链接", result.preview)
        assertEquals(recordStatistics(record, StatisticsSurface.LIST), result.statistics)
        assertEquals(recordStatistics(record, StatisticsSurface.DETAIL), result.statistics)
        assertEquals(listOf(0L, 12L), result.statistics.map { it.value })
        assertEquals(listOf("赞同数 0", "平台评论数 12"), result.statistics.map { it.accessibilityText })
    }

    @Test
    fun storedPreviewHonorsDeclaredFormatRatherThanPlatformOrApiExcerpt() {
        val cases = listOf(
            Triple("<b>正文</b>", RecordBodyFormat.PLAIN_TEXT, "<b>正文</b>"),
            Triple("<p><b>正文</b> &amp; 内容</p>", RecordBodyFormat.HTML, "正文 & 内容"),
            Triple("**正文** [链接](https://hidden.example)", RecordBodyFormat.MARKDOWN, "正文 链接"),
            Triple("**正文** [链接](https://hidden.example)", RecordBodyFormat.MARKDOWN_MATH, "正文 链接")
        )
        cases.forEach { (content, format, expected) ->
            val record = answer(content, format)
            assertEquals(format.name, expected, questionAnswerPresentation(ref, record.toDetailRecord()).preview)
            assertEquals(content, record.content)
        }
    }

    @Test
    fun missingStoredFieldsDoNotBorrowConflictingReferenceValues() {
        val record = answer("", RecordBodyFormat.PLAIN_TEXT).copy(
            authorName = "", authorAvatar = "", publishTime = 0, extraMetadata = "{}"
        )
        val result = questionAnswerPresentation(ref, record.toDetailRecord())
        assertEquals(displayAuthor(""), result.author)
        assertEquals("", result.authorAvatar)
        assertEquals("", result.preview)
        assertEquals(0L, result.publishTime)
        assertTrue(result.statistics.isEmpty())
    }

    @Test
    fun uncachedReferenceKeepsItsOwnExcerptFormatAndShowsZeroVotesAndComments() {
        val zeroRef = ref.copy(voteupCount = 0, commentCount = 0)
        val result = questionAnswerPresentation(zeroRef, null)
        assertEquals("API 摘要", result.preview)
        assertEquals(ref.authorName, result.author)
        assertEquals(ref.authorAvatar, result.authorAvatar)
        assertEquals(20000L, result.publishTime)
        assertEquals(listOf(0L, 0L), result.statistics.map { it.value })
        assertEquals(listOf("赞同", "评论"), result.statistics.map { it.label })
        assertEquals(
            ref.excerpt,
            questionAnswerPresentation(ref.copy(excerptFormat = RecordBodyFormat.PLAIN_TEXT), null).preview
        )
        assertEquals(
            "摘要",
            questionAnswerPresentation(ref.copy(excerpt = "**摘要**", excerptFormat = RecordBodyFormat.MARKDOWN), null).preview
        )
        assertTrue(questionAnswerPresentation(ref.copy(voteupCount = -1, commentCount = -1), null).statistics.isEmpty())
        assertEquals(10000L, questionAnswerPresentation(ref.copy(updatedTimeSec = 0), null).publishTime)
    }
}
