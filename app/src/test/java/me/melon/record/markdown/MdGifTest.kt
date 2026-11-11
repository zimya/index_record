package me.melon.record.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdGifTest {

    @Test
    fun testStandaloneGifMp4ParsingAndRoundTrip() {
        val urls = listOf(
            "https://video.twimg.com/tweet_video/example.mp4",
            "https://video.twimg.com/tweet_video/example.mp4?tag=12&token=original",
            "https://video.twimg.com/tweet_video/example.MP4?tag=12"
        )
        for (url in urls) {
            val markdown = "[GIF]($url)"
            val doc = markdownToMdAst(markdown)
            assertTrue(doc.children.single() is MdGif)
            assertEquals(url, (doc.children.single() as MdGif).url)
            assertEquals(markdown, doc.toMarkdown())
            assertEquals(url, (markdownToMdAst(doc.toMarkdown()).children.single() as MdGif).url)
        }
    }

    @Test
    fun testGifRemainsAnIndependentBlockBetweenParagraphs() {
        val url = "https://video.twimg.com/tweet_video/example.mp4?tag=12"
        val markdown = "正文\n\n[GIF]($url)\n\n后文"
        val doc = markdownToMdAst(markdown)

        assertEquals(3, doc.children.size)
        assertTrue(doc.children[0] is MdParagraph)
        assertTrue(doc.children[1] is MdGif)
        assertTrue(doc.children[2] is MdParagraph)
        assertEquals(markdown, doc.toMarkdown())
    }

    @Test
    fun testOrdinaryVideoAndOtherLinksRemainLinks() {
        val links = listOf(
            "视频" to "https://video.twimg.com/ext_tw_video/example.mp4?tag=12",
            "video" to "https://video.twimg.com/ext_tw_video/example.mp4",
            "gif" to "https://video.twimg.com/tweet_video/example.mp4",
            "GIF" to "https://example.com/example.gif",
            "GIF" to "https://example.com/example.mp4/more",
            "GIF" to "https://example.com/watch?file=example.mp4"
        )
        for ((label, url) in links) {
            val markdown = "[$label]($url)"
            val doc = markdownToMdAst(markdown)
            val paragraph = doc.children.single()
            assertTrue(paragraph is MdParagraph)
            assertTrue(paragraph.children.single() is MdLink)
            assertEquals(url, (paragraph.children.single() as MdLink).destination)
            assertEquals(markdown, doc.toMarkdown())
        }
    }

    @Test
    fun testInlineOrFormattedGifLinksDoNotBecomeGifBlocks() {
        val link = "[GIF](https://video.twimg.com/tweet_video/example.mp4)"
        val markdowns = listOf(
            "前文 $link",
            "$link 后文",
            "$link $link",
            "$link\n下一行",
            "[**GIF**](https://video.twimg.com/tweet_video/example.mp4)",
            "[GIF `code`](https://video.twimg.com/tweet_video/example.mp4)"
        )
        for (markdown in markdowns) {
            val doc = markdownToMdAst(markdown)
            assertTrue(doc.children.single() is MdParagraph)
            assertTrue(doc.children.single().children.none { it is MdGif })
        }
    }

    @Test
    fun testCopiaElGifTweetKeepsPlayableBlockAfterNumberedInstructions() {
        val url = "https://video.twimg.com/tweet_video/HW089lsWMAAxU7N.mp4"
        val markdown = "1) copia el gif\n2) pegalo en un mensaje de whatsapp\n\ny listo, sticker nuevo\n\n[GIF]($url)"
        val doc = markdownToMdAst(markdown)
        assertTrue(doc.children.first() is MdList)
        assertEquals(url, (doc.children.last() as MdGif).url)
        assertEquals(url, (markdownToMdAst(doc.toMarkdown()).children.last() as MdGif).url)
    }

    @Test
    fun testGifSerializationKeepsOriginalUrl() {
        val url = "https://video.twimg.com/tweet_video/example.mp4?tag=12&token=original"
        assertEquals("[GIF]($url)", MdGif(url).toMarkdown())
    }
}
