package me.melon.record.parser

import me.melon.record.data.model.RawData
import me.melon.record.parser.reddit.RedditParser
import me.melon.record.parser.zhihu.ZhihuParser
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ParseResultTest {
    private fun fixture(platform: String, name: String): String = sequenceOf(
        File("../research/$platform/$name"), File("research/$platform/$name"), File("../../research/$platform/$name")
    ).first { it.exists() }.readText()

    @Test fun zhihuAppviewHtmlUsesProductionResultPathWithoutJsonObjectGate() {
        // The captured fixture is the extracted script state, not a JSON API response.
        val state = fixture("zhihu", "answer_appview_example.json")
        val url = "https://www.zhihu.com/appview/v2/answer/1977013387486573086"
        val raw = RawData(url = "$url?from=feed", hostApp = "com.zhihu.android", json =
            """<!doctype html><html><body><script id="js-initialData" type="text/json">$state</script></body></html>""")
        val direct = ZhihuParser.parseResult(raw, url)
        val result = ParserRegistry.parseResult(raw, url)
        for (parsed in listOf(direct, result)) {
            assertTrue(parsed.toString(), parsed is ParseResult.Success)
            assertTrue(parsed.consumeRaw)
            val record = parsed.records.single()
            assertEquals("zhihu_answer_1977013387486573086", record.platformId)
            assertTrue(record.title.contains("2036奥运会"))
            assertTrue(record.content.isNotBlank())
            assertTrue(record.authorName.isNotBlank())
            assertEquals(ZhihuParser.parse(raw, url).single().content, record.content)
        }
    }

    @Test fun invalidAppviewHtmlRemainsFailureRatherThanEmptySuccess() {
        val url = "https://www.zhihu.com/appview/v2/answer/42"
        for (body in listOf(
            "<html><body>Login required</body></html>",
            """<script id="js-initialData" type="text/json">{</script>""",
            """<script id="js-initialData" type="text/json">{"initialState":{"answers":{}}}</script>"""
        )) {
            val result = ParserRegistry.parseResult(RawData(url = url, json = body), url)
            assertTrue(result.toString(), result is ParseResult.Failure)
            assertFalse(result.consumeRaw)
            assertTrue(result.diagnostics.isNotEmpty())
        }
    }

    @Test fun zhihuNextRenderAndOtherJsonRoutesStillUseObjectValidation() {
        val url = "https://api.zhihu.com/next-render"
        val raw = RawData(url = "$url?limit=5", json = fixture("zhihu", "next_render_example.json"))
        val result = ParserRegistry.parseResult(raw, url)
        assertTrue(result.toString(), result is ParseResult.Success)
        assertEquals(5, result.records.size)
        assertEquals("zhihu_answer_3514967325", result.records.first().platformId)
        val empty = ParserRegistry.parseResult(raw.copy(json = """{"data":[]}"""), url)
        assertTrue(empty is ParseResult.Success)
        assertTrue(empty.consumeRaw)
        assertTrue(empty.records.isEmpty())
        for (jsonUrl in listOf(url, "https://api.zhihu.com/answers/v2/42", "https://api.zhihu.com/articles/v2/42")) {
            for (body in listOf("{\"data\":", "<html>Login required</html>", "[]")) {
                val invalid = ParserRegistry.parseResult(raw.copy(url = jsonUrl, json = body), jsonUrl)
                assertTrue("$jsonUrl: $invalid", invalid is ParseResult.Failure)
                assertFalse(invalid.consumeRaw)
            }
        }
    }

    @Test fun remainingDefaultParsersAcceptTheirObjectEnvelopesIncludingHtmlFields() {
        val captures = listOf(
            RawData(url = "https://frodo.douban.com/api/v2/movie/1292722", json = fixture("douban", "v2_movie.json")),
            RawData(url = "https://api.x.com/graphql/hash/HomeTimeline", json = fixture("x", "graphql_HomeTimeline.json")),
            // Weibo's HTML is a field in a JSON response, never a top-level HTML document.
            RawData(url = "https://api.weibo.cn/2/statuses/show", json = """{"idstr":"42","text":"<p>正文</p>","user":{"idstr":"7","screen_name":"作者"}}""")
        )
        for (raw in captures) {
            val result = ParserRegistry.parseResult(raw, raw.url)
            assertTrue("${raw.url}: $result", result is ParseResult.Success)
            assertTrue(raw.url, result.records.isNotEmpty())
            val invalid = ParserRegistry.parseResult(raw.copy(json = "<html>Login required</html>"), raw.url)
            assertTrue(raw.url, invalid is ParseResult.Failure)
            assertFalse(invalid.consumeRaw)
        }
    }

    @Test fun unknownCaptureIsExplicitlyIgnoredRatherThanNull() {
        val raw = RawData(url = "https://unknown.test/", json = "{")
        val result = ParserRegistry.parseResult(raw, raw.url)
        assertTrue(result is ParseResult.Ignored)
        assertTrue(result.consumeRaw)
    }

    @Test fun redditGraphqlErrorsKeepSuccessfulBatchRecordsAndDiagnostics() {
        val raw = RawData(url = "https://gql-fed.reddit.com/", json = """[
            {"errors":[{"message":"unavailable"}]},
            {"data":{"postInfoById":{"id":"t3_good","title":"valid"}}}
        ]""")
        val result = ParserRegistry.parseResult(raw, raw.url)
        assertTrue(result.toString(), result is ParseResult.Partial)
        assertEquals("reddit_post_good", result.records.single().platformId)
        assertEquals("reddit.response[0].errors", result.diagnostics.single().stage)
        assertFalse(result.consumeRaw)
    }

    @Test fun malformedRedditBodiesIncludingTrailingGarbageAreFailures() {
        for (body in listOf("{", "null", "{} trailing", "{\"response\":null}")) {
            val raw = RawData(url = "https://gql-fed.reddit.com/", json = body)
            val result = ParserRegistry.parseResult(raw, raw.url)
            assertTrue(body, result is ParseResult.Failure)
            assertFalse(result.consumeRaw)
        }
    }

    @Test fun redditErrorsWithoutContentFailButGenuineEmptyBatchSucceeds() {
        val raw = RawData(url = "https://gql-fed.reddit.com/", json = """{"errors":[{"message":"unavailable"}]}""")
        val failure = RedditParser.parseResult(raw, raw.url)
        assertTrue(failure is ParseResult.Failure)
        assertFalse(failure.consumeRaw)
        val empty = RedditParser.parseResult(raw.copy(json = "[]"), raw.url)
        assertTrue(empty is ParseResult.Success)
        assertTrue(empty.records.isEmpty())
        assertTrue(empty.consumeRaw)
    }

    @Test fun tiebaApiErrorAndMalformedBinaryAreNotSuccessfulEmptyPages() {
        val url = "https://tiebac.baidu.com/c/f/pb/page"
        for (body in listOf(
            """{"error_code":1}""",
            """{"encoding":"base64","body":"not base64!"}"""
        )) {
            val result = ParserRegistry.parseResult(RawData(url = url, json = body), url)
            assertTrue(result.toString(), result is ParseResult.Failure)
            assertFalse(result.consumeRaw)
        }
    }
}
