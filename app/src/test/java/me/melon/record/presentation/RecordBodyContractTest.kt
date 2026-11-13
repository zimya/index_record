package me.melon.record.presentation

import me.melon.record.data.model.*
import me.melon.record.markdown.*
import me.melon.record.parser.ParserRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecordBodyContractTest {
    private fun record(format: RecordBodyFormat, content: String, platform: String = "unknown") =
        UniversalRecord(platform = platform, content = content, contentFormat = format.storageValue)

    @Test fun explicitFormatWinsOverPlatformAndMarkup() {
        val source = "<p>literal</p> **not bold** &amp; \$x\$"
        for (platform in listOf("reddit", "zhihu", "threads", "douban", "future")) {
            val plain = record(RecordBodyFormat.PLAIN_TEXT, source, platform)
            assertEquals(source, plain.bodyPlainText())
            assertEquals(MarkdownContentFormat.PLAIN_TEXT, recordBodyPresentation(plain).contentFormat)
            assertFalse(recordBodyPresentation(plain).enableMath)
            assertEquals("literal", record(RecordBodyFormat.HTML, "<b>literal</b>", platform).bodyPlainText())
        }
    }

    @Test fun markdownCodeAndLiteralTagsAreNeverRedetectedAsHtml() {
        val body = record(RecordBodyFormat.MARKDOWN, "```html\n<p>example</p>\n```\n\n**bold** [label](https://hidden.example) ![alt](https://image.example)")
        assertEquals("<p>example</p> bold label [图片]", body.bodyPlainText())
        assertFalse(body.bodyPlainText().contains("hidden.example"))
        assertFalse(body.bodyPlainText().contains("image.example"))
        assertEquals("<b>literal</b>", record(RecordBodyFormat.MARKDOWN, "<b>literal</b>").bodyPlainText())
    }

    @Test fun summariesAndCommentTextShareTheRenderingAst() {
        for (format in RecordBodyFormat.entries) {
            val body = record(format, "**bold** [label](https://hidden.example)\n\n\$x + y\$")
            val presentation = recordBodyPresentation(body)
            val renderedText = contentToMdAst(presentation.content, presentation.contentFormat, presentation.enableMath).toPlainText()
            assertEquals(renderedText, body.bodyPlainText())
            assertEquals(renderedText, recordPreviewText(body))
            assertEquals(renderedText, commentPlainText(body))
        }
    }

    @Test fun mathIsPartOfTheDeclaredDialectNotThePlatform() {
        val source = "before \$x+y\$ after\n\n\$\$z^2\$\$"
        val math = record(RecordBodyFormat.MARKDOWN_MATH, source, "reddit")
        assertTrue(recordBodyPresentation(math).enableMath)
        assertEquals("before x+y after z^2", math.bodyPlainText())
        val literal = record(RecordBodyFormat.MARKDOWN, source, "zhihu")
        assertFalse(recordBodyPresentation(literal).enableMath)
        assertEquals("before \$x+y\$ after \$\$z^2\$\$", literal.bodyPlainText())
    }

    @Test fun defaultFormatIsLiteralPlainTextRegardlessOfPlatformOrMarkup() {
        val source = "<p>literal</p> **not bold** &amp; \$x\$"
        for (platform in listOf("reddit", "zhihu", "threads", "douban", "future")) {
            val body = UniversalRecord(platform = platform, content = source)
            assertEquals(RecordBodyFormat.PLAIN_TEXT.storageValue, body.contentFormat)
            assertEquals(RecordBodyFormat.PLAIN_TEXT, body.bodyFormat())
            assertEquals(source, body.bodyPlainText())
            assertEquals(source, body.toDetailRecord().toEntity().bodyPlainText())
        }
        assertEquals(RecordBodyFormat.PLAIN_TEXT.storageValue, DetailRecord().contentFormat)
    }

    @Test fun storageFormatAcceptsOnlyTheFourExactWireValues() {
        assertEquals(setOf("plain_text", "html", "markdown", "markdown_math"),
            RecordBodyFormat.entries.map { it.storageValue }.toSet())
        for (format in RecordBodyFormat.entries) {
            assertEquals(format, RecordBodyFormat.fromStorage(format.storageValue))
        }
        for (invalid in listOf("", " ", "future-format", "AUTO", "HTML", " markdown", "markdown ")) {
            assertThrows(IllegalArgumentException::class.java) { RecordBodyFormat.fromStorage(invalid) }
            for (platform in listOf("reddit", "zhihu", "douban", "future")) {
                val body = UniversalRecord(platform = platform, content = "<p>**body**</p>", contentFormat = invalid)
                assertThrows(IllegalArgumentException::class.java) { body.bodyFormat() }
                assertThrows(IllegalArgumentException::class.java) { body.bodyPlainText() }
                assertThrows(IllegalArgumentException::class.java) { recordBodyPresentation(body) }
            }
        }
    }

    @Test fun snapshotAndEntityRoundTripRetainFormatWithoutAliasing() {
        for (format in RecordBodyFormat.entries) {
            val entity = record(format, "source")
            val snapshot = entity.toDetailRecord()
            entity.contentFormat = "changed"
            val restored = snapshot.toEntity()
            assertEquals(format.storageValue, restored.contentFormat)
            assertEquals(format.storageValue, snapshot.contentFormat)
            restored.content = "changed"
            assertEquals("source", snapshot.content)
        }
        assertEquals(RecordBodyFormat.PLAIN_TEXT.storageValue, UniversalRecord().toDetailRecord().toEntity().contentFormat)
    }

    @Test fun missingAuthorIsExplicitlyUnknown() {
        assertEquals("作者未知", recordHeaderPresentation(UniversalRecord()).author)
        assertEquals("Alice", recordHeaderPresentation(UniversalRecord(authorName = "Alice")).author)
    }

    @Test fun plainTextEmbeddedInMarkdownMediaRemainsLiteral() {
        val source = "**literal** <b>not html</b> &amp; [not a link](url) # heading"
        val body = record(RecordBodyFormat.MARKDOWN, escapeMarkdownText(source) + "\n\n![](https://image.example)")
        assertEquals("$source [图片]", body.bodyPlainText())
    }

    @Test fun realCapturesDeclareFormatAtTheParserBoundary() {
        val fixtures = listOf(
            Triple("douban", "v2_group_topic.json", "https://frodo.douban.com/api/v2/group/topic/505200892"),
            Triple("douban", "v2_group_topic_comments.json", "https://frodo.douban.com/api/v2/group/topic/505200892/comments"),
            Triple("douban", "v2_review.json", "https://frodo.douban.com/api/v2/review/7379728"),
            Triple("douban", "v2_review_comments.json", "https://frodo.douban.com/api/v2/review/1967305/comments"),
            Triple("douban", "v2_forum_topic.json", "https://frodo.douban.com/api/v2/forum_topic/22546527"),
            Triple("douban", "v2_forum_topic_comments.json", "https://frodo.douban.com/api/v2/forum_topic/616438845/comments"),
            Triple("douban", "v2_movie.json", "https://frodo.douban.com/api/v2/movie/1292722"),
            Triple("douban", "v2_movie_hot_interests.json", "https://frodo.douban.com/api/v2/movie/1292722/hot_interests"),
            Triple("x", "graphql_HomeTimeline.json", "https://api.x.com/graphql/ReFu1Zs_f4GRKnj0FeW9Rg/HomeTimeline"),
            Triple("zhihu", "question_detail_resp.json", "https://api.zhihu.com/questions/42")
        )
        fixtures.forEach { (platform, name, url) ->
            val file = sequenceOf(File("../research/$platform/$name"), File("research/$platform/$name")).first { it.exists() }
            val records = requireNotNull(ParserRegistry.parse(RawData(url = url, json = file.readText()), url))
            assertTrue(name, records.isNotEmpty())
            records.forEach { assertEquals("$name: ${it.platformId}", it.contentFormat, it.bodyFormat().storageValue) }
        }
    }
}
