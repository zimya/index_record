package me.melon.record.parser.x

import com.google.gson.JsonParser
import me.melon.record.markdown.MdGif
import me.melon.record.markdown.markdownToMdAst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XGifTest {
    private val preview = "https://pbs.twimg.com/tweet_video_thumb/example.jpg"
    private val mp4 = "https://video.twimg.com/tweet_video/example.mp4?tag=12"

    private fun format(modern: Boolean, gif: Boolean, variants: String): Pair<String, List<String>> {
        val media = if (modern) {
            """{"media_entities":[{"url":"https://t.co/media","media_results":{"result":{"media_info":{
                "__typename":"${if (gif) "ApiGif" else "ApiVideo"}",
                "preview_image":{"original_img_url":"$preview"},"variants":$variants
            }}}}]}"""
        } else {
            """{"extended_entities":{"media":[{"url":"https://t.co/media",
                "type":"${if (gif) "animated_gif" else "video"}","media_url_https":"$preview",
                "video_info":{"variants":$variants}
            }]}}"""
        }
        val obj = JsonParser.parseString(media).asJsonObject
        val urls = mutableListOf<String>()
        return XUtils.formatTweetToMarkdown(
            "正文 https://t.co/media",
            if (modern) obj else null,
            if (modern) null else obj,
            urls
        ) to urls
    }

    @Test
    fun gifWithoutBitrateProducesPlayableBlockInBothSchemas() {
        for (modern in listOf(true, false)) {
            val (content, images) = format(modern, true, """[{"content_type":"video/mp4","url":"$mp4"}]""")
            assertEquals("正文\n\n[GIF]($mp4)", content)
            assertEquals(listOf(preview), images)
            assertEquals(mp4, (markdownToMdAst(content).children.last() as MdGif).url)
        }
    }

    @Test
    fun capturedAndroidGifRetainsItsMp4() {
        // 数据库记录 2736（推文 2120503022824616232）对应的媒体响应。
        val url = "https://video.twimg.com/tweet_video/HW2J_CBaYAA-TiD.mp4"
        val tweet = JsonParser.parseString("""{
            "media_entities":[{"media_results":{"result":{"media_info":{
                "__typename":"ApiGif",
                "preview_image":{"original_img_url":"https://pbs.twimg.com/tweet_video_thumb/HW2J_CBaYAA-TiD.jpg"},
                "variants":[{"bit_rate":0,"content_type":"video/mp4","url":"$url"}]
            }}}}]
        }""").asJsonObject
        val images = mutableListOf<String>()
        val content = XUtils.formatTweetToMarkdown("用下面的GIF试试吧", tweet, null, images)
        assertEquals("用下面的GIF试试吧\n\n[GIF]($url)", content)
        assertEquals(url, (markdownToMdAst(content).children.last() as MdGif).url)
    }

    @Test
    fun gifWithZeroOrNullBitrateRemainsPlayable() {
        for (modern in listOf(true, false)) {
            for (bitrate in listOf("0", "null")) {
                val (content, _) = format(modern, true, """[{"content_type":"video/mp4","bitrate":$bitrate,"url":"$mp4"}]""")
                assertTrue(content.contains("[GIF]($mp4)"))
            }
        }
    }

    @Test
    fun ordinaryVideoStillSelectsHighestBitrateAndKeepsPlaybackLink() {
        val variants = """[
            {"content_type":"application/x-mpegURL","url":"https://example.com/video.m3u8","bitrate":9999999},
            {"content_type":"video/mp4","url":"https://example.com/no-bitrate.mp4"},
            {"content_type":"video/mp4","url":"https://example.com/low.mp4","bitrate":256000},
            {"content_type":"video/mp4","url":"$mp4","bitrate":832000},
            {"content_type":"video/mp4","url":"","bitrate":9999999}
        ]"""
        for (modern in listOf(true, false)) {
            for (bitrateKey in listOf("bitrate", "bit_rate")) {
                val (content, _) = format(modern, false, variants.replace("bitrate\"", "$bitrateKey\""))
                assertTrue(content.contains("[▶ 点击播放视频]($mp4)"))
                assertFalse(content.contains("[GIF]"))
                assertFalse(content.contains("low.mp4"))
            }
        }
    }

    @Test
    fun invalidVariantsFallBackToPreviewWithoutInventingVideoUrl() {
        for (modern in listOf(true, false)) {
            for (variants in listOf("null", "[]", """[null,1,{}, {"content_type":"video/mp4","url":""}]""")) {
                val (content, images) = format(modern, true, variants)
                assertTrue(content.contains(preview))
                assertFalse(content.contains("[GIF]"))
                assertEquals(listOf(preview), images)
            }
        }
    }
}
