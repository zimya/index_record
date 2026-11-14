package com.melonhu.indexrecord.data.model
import org.junit.Assert.*
import org.junit.Test

class DoubanSubjectMetadataTest {
    private fun metadata(json: String) = DoubanSubjectMetadata(
        DetailRecord(title = "记录标题", content = "记录正文", extraMetadata = json)
    )

    @Test fun invalidAndNonObjectJsonUseRecordFallbacks() {
        for (json in listOf("broken", "[]", "null", "42", "{}")) {
            val subject = metadata(json)
            assertEquals("记录标题", subject.subjectTitle)
            assertEquals("记录正文", subject.intro)
            assertEquals("movie", subject.subtype)
            assertEquals("电影", subject.subtypeLabel)
            assertEquals("", subject.coverUrl)
            assertEquals(0L, subject.ratingCount)
            assertEquals(0.0, subject.ratingVal, 0.0)
            assertTrue(subject.directors.isEmpty())
        }
    }

    @Test fun preservesTextFallbacksAndNumericConversions() {
        val subject = metadata("""{
            "subject_title":" ", "intro":null, "cover_url":"", "title_image":"cover",
            "original_title":"Original", "year":2024, "card_subtitle":"subtitle",
            "header_bg_color":"#123456", "rating_value":"8.9", "rating_stars":4.5,
            "rating_count":"12345", "episodes_count":12.9, "binding":true,
            "isbn13":"123", "release_date":"2024-01-01"
        }""")
        assertEquals("记录标题", subject.subjectTitle)
        assertEquals("记录正文", subject.intro)
        assertEquals("cover", subject.coverUrl)
        assertEquals("Original", subject.originalTitle)
        assertEquals("2024", subject.year)
        assertEquals("subtitle", subject.cardSubtitle)
        assertEquals("#123456", subject.headerBackgroundColor)
        assertEquals(8.9, subject.ratingVal, 0.0)
        assertEquals(4.5, subject.ratingStars, 0.0)
        assertEquals(12345L, subject.ratingCount)
        assertEquals(12L, subject.episodesCount)
        assertEquals("true", subject.binding)
        assertEquals("123", subject.isbn13)
        assertEquals("2024-01-01", subject.releaseDate)
    }

    @Test fun retainsMalformedFieldDefaultsAndStructuredText() {
        val subject = metadata("""{
            "subject_title":{"name":"title"}, "intro":["intro"],
            "rating_value":{}, "rating_stars":true, "rating_count":"1.5",
            "episodes_count":[], "genres":{}, "countries":null
        }""")
        assertEquals("""{"name":"title"}""", subject.subjectTitle)
        assertEquals("""["intro"]""", subject.intro)
        assertEquals(0.0, subject.ratingVal, 0.0)
        assertEquals(0.0, subject.ratingStars, 0.0)
        assertEquals(0L, subject.ratingCount)
        assertEquals(0L, subject.episodesCount)
        assertTrue(subject.genres.isEmpty())
        assertTrue(subject.countries.isEmpty())
    }

    @Test fun listsPreserveTrimmingObjectNameFallbacksAndOrder() {
        val subject = metadata("""{
            "directors":[" A ",null,{},[],{"name":"B"},{"name":"","title":"C"},
                {"title":" ","cn_name":"D"},7,true," "], "genres":" Drama "
        }""")
        assertEquals(listOf("A", "B", "C", "D", "7", "true"), subject.directors)
        assertEquals(listOf("Drama"), subject.genres)
    }

    @Test fun mapsAllSubjectDomainLists() {
        val keys = listOf("genres", "countries", "directors", "actors", "writers", "pubdate",
            "durations", "languages", "author", "translator", "press", "pages", "price",
            "developers", "publishers", "platforms", "singer", "discs", "honor_infos", "tags")
        val subject = metadata(keys.joinToString(prefix = "{", postfix = "}") { "\"$it\":[\"$it\"]" })
        val lists = listOf(subject.genres, subject.countries, subject.directors, subject.actors,
            subject.writers, subject.pubdate, subject.durations, subject.languages, subject.author,
            subject.translator, subject.press, subject.pages, subject.price, subject.developers,
            subject.publishers, subject.platforms, subject.singer, subject.discs, subject.honors, subject.tags)
        assertEquals(keys.map { listOf(it) }, lists)
        for (values in lists) {
            assertCannotMutate { (values as MutableList<String>).add("injected") }
            assertCannotMutate { (values as MutableList<String>)[0] = "injected" }
            assertCannotMutate { (values as MutableList<String>).clear() }
        }
    }

    @Test fun labelsAllSupportedSubtypesWithoutChangingUnknownTypes() {
        for ((type, label) in mapOf("movie" to "电影", "book" to "图书", "music" to "音乐",
            "game" to "游戏", "tv" to "电视剧", "drama" to "舞台剧", "other" to "条目")) {
            val subject = metadata("""{"subject_type":"$type"}""")
            assertEquals(type, subject.subtype)
            assertEquals(label, subject.subtypeLabel)
        }
    }

    @Test fun relatedStatisticsKeepTheirExactKeysAndDoNotUseGeneralRecordFallbacks() {
        val subject = DoubanSubjectRelatedMetadata(DetailRecord(extraMetadata = """{
            "star_count":"3.5", "voteup_count":"7", "like_count":99, "rating_value":9
        }"""))
        assertEquals(3.5, subject.starCount, 0.0)
        assertEquals(7L, subject.voteCount)
        val fallback = DoubanSubjectRelatedMetadata(DetailRecord(
            extraMetadata = """{"rating_value":9,"like_count":99}"""
        ))
        assertEquals(0.0, fallback.starCount, 0.0)
        assertEquals(0L, fallback.voteCount)
    }

    @Test fun emptyAndScalarListsAreAlsoUnmodifiable() {
        for (subject in listOf(metadata("{}"), metadata("""{"genres":"Drama"}"""))) {
            assertCannotMutate { (subject.genres as MutableList<String>).add("injected") }
        }
    }

    private fun assertCannotMutate(block: () -> Unit) {
        try {
            block()
            fail("Domain collections must reject mutation")
        } catch (_: UnsupportedOperationException) {
            // Expected for the shared immutable collection boundary.
        }
    }

    @Test fun mappingIsDetachedFromLaterEntityMutation() {
        val record = UniversalRecord(title = "before", content = "intro", extraMetadata = """{"rating_count":1}""")
        val subject = DoubanSubjectMetadata(record.toDetailRecord())
        record.title = "after"
        record.content = "changed"
        record.extraMetadata = """{"rating_count":2}"""
        assertEquals("before", subject.subjectTitle)
        assertEquals("intro", subject.intro)
        assertEquals(1L, subject.ratingCount)
    }
}
