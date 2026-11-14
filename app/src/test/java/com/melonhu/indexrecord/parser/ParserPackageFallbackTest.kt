package com.melonhu.indexrecord.parser

import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParserPackageFallbackTest {
    private fun fixture(platform: String, name: String): String = sequenceOf(
        File("../research/$platform/$name"), File("research/$platform/$name"), File("../../research/$platform/$name")
    ).first { it.exists() }.readText()

    private fun captures(): List<Pair<String, RawData>> {
        val douban = listOf(
            "group/topic/505200892" to "v2_group_topic.json",
            "group/topic/505200892/comments" to "v2_group_topic_comments.json",
            "movie/1292722/hot_interests" to "v2_movie_hot_interests.json",
            "review/7379728" to "v2_review.json",
            "review/1967305/comments" to "v2_review_comments.json",
            "forum_topic/22546527" to "v2_forum_topic.json",
            "forum_topic/616438845/comments" to "v2_forum_topic_comments.json",
            "movie/1292722" to "v2_movie.json"
        ).map { (endpoint, name) ->
            "douban" to RawData(url = "https://frodo.douban.com/api/v2/$endpoint", json = fixture("douban", name))
        }
        return douban + listOf(
            "twitter" to RawData(url = "https://api.x.com/graphql/ReFu1Zs_f4GRKnj0FeW9Rg/HomeTimeline",
                json = fixture("x", "graphql_HomeTimeline.json")),
            "tieba" to RawData(url = "https://tiebac.baidu.com/c/f/pb/page",
                json = """{"data":{"thread":{"id":"42","title":"主帖"},"post_list":[{"id":"1","floor":1,"content":[{"type":0,"text":"正文"}]}]}}""")
        )
    }

    @Test fun blankHostPackagesUseCatalogDefaultsForEveryChangedParser() {
        for ((platform, capture) in captures()) {
            for (blank in listOf("", " \t")) {
                val records = ParserRegistry.parse(capture.copy(hostApp = blank), capture.url).orEmpty()
                assertTrue(capture.url, records.isNotEmpty())
                for (record in records) {
                    assertEquals(capture.url, PlatformCatalog.defaultPackage(platform), record.sourceApp)
                }
            }
        }
    }

    @Test fun nonblankHostPackagesArePreservedInsteadOfNormalizedToDefaults() {
        for ((_, capture) in captures()) {
            val source = "custom.capture.package"
            val records = ParserRegistry.parse(capture.copy(hostApp = source), capture.url).orEmpty()
            assertTrue(capture.url, records.isNotEmpty())
            for (record in records) assertEquals(capture.url, source, record.sourceApp)
        }
    }
}
