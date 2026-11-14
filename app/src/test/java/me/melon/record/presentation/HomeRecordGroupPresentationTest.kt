package me.melon.record.presentation

import me.melon.record.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test

class HomeRecordGroupPresentationTest {
    private fun answer(parent: String = "", metadata: String = "{}", title: String = "同名问题") =
        UniversalRecord(platform = "zhihu", recordType = "answer", parentId = parent, title = title, extraMetadata = metadata)

    private fun interest(parent: String = "", metadata: String = "{}", title: String = "电影短评：同名作品") =
        UniversalRecord(platform = "douban", recordType = "interest", parentId = parent, title = title, extraMetadata = metadata)

    @Test
    fun relationWinsOverConflictingMetadata() {
        val question = homeRecordGroupPresentation(answer("zhihu_question_1", """{"question_id":"2"}"""))!!
        assertEquals("zhihu_question_1", question.target.contentId)
        val subject = homeRecordGroupPresentation(interest("douban_movie_1", """{"subject_id":"2","subject_type":"movie"}"""))!!
        assertEquals("douban_movie_1", subject.target.contentId)
    }

    @Test
    fun metadataFallbackAndRelationsShareIdentity() {
        val question = homeRecordGroupPresentation(answer(metadata = """{"question_id":"1"}"""))!!
        assertTrue(question.sameGroupAs(homeRecordGroupPresentation(answer("zhihu_question_1", title = "改名"))))
        val subject = homeRecordGroupPresentation(interest(metadata = """{"subject_id":"1","subject_type":"movie"}"""))!!
        assertTrue(subject.sameGroupAs(homeRecordGroupPresentation(interest("douban_movie_1"))))
    }

    @Test
    fun missingAndDamagedMetadataNeverUseTitleAsIdentity() {
        for (metadata in listOf("", "{}", "null", "[]", "broken", """{"subject_id":{},"question_id":[],"subject_type":false}""")) {
            assertNull(homeRecordGroupPresentation(answer(metadata = metadata)))
            assertNull(homeRecordGroupPresentation(interest(metadata = metadata)))
            assertNotNull(homeRecordGroupPresentation(answer("zhihu_question_1", metadata)))
            assertNotNull(homeRecordGroupPresentation(interest("douban_movie_1", metadata)))
        }
    }

    @Test
    fun sameTitlesNeverOverrideDifferentTargetIdentitiesOrSubjectTypes() {
        val question = homeRecordGroupPresentation(answer("zhihu_question_1"))!!
        assertFalse(question.sameGroupAs(homeRecordGroupPresentation(answer("zhihu_question_2"))))
        val movie = homeRecordGroupPresentation(interest(metadata = """{"subject_id":"1","subject_type":"movie"}"""))!!
        val book = homeRecordGroupPresentation(interest(metadata = """{"subject_id":"1","subject_type":"book"}"""))!!
        assertFalse(movie.sameGroupAs(book))
    }

    @Test
    fun subjectTitlesAndVocabularyComeFromPresentationPolicy() {
        val labels = mapOf("movie" to "电影短评", "book" to "图书短评", "music" to "音乐短评", "game" to "游戏短评", "tv" to "剧集短评", "drama" to "舞台剧短评")
        for ((type, label) in labels) {
            val group = homeRecordGroupPresentation(interest(metadata = """{"subject_id":"1","subject_type":"$type","subject_title":"  作品  "}"""))!!
            assertEquals("作品", group.title)
            assertEquals(label, group.typeLabel)
            assertEquals(HomeRecordGroupKind.INTERESTS, group.kind)
        }
        val fallback = homeRecordGroupPresentation(interest("douban_movie_1", "broken"))!!
        assertEquals("同名作品", fallback.title)
        assertEquals("电影短评", fallback.typeLabel)
        assertEquals("无标题", homeRecordGroupPresentation(answer("zhihu_question_1", title = ""))!!.title)
        assertNull(homeRecordGroupPresentation(answer("zhihu_question_1").copy(recordType = "comment")))
    }
}
