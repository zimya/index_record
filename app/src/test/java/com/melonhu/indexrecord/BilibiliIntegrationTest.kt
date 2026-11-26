package com.melonhu.indexrecord

import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.*
import com.melonhu.indexrecord.data.repository.RecordDetailRepository
import com.melonhu.indexrecord.network.secureBilibiliImageUrl
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.ParserRegistry
import com.melonhu.indexrecord.presentation.*
import com.melonhu.indexrecord.ui.detail.record.RecordDetailCommentSort
import com.melonhu.indexrecord.ui.detail.record.buildRecordDetailCommentRows
import com.melonhu.indexrecord.ui.stats.colorFromPackage
import com.melonhu.indexrecord.ui.theme.BilibiliPink
import com.melonhu.indexrecord.ui.theme.getPlatformThemeColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BilibiliIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val base = "https://grpc.biliapi.net/"
    private val video = base + "bilibili.app.viewunite.v1.View/View"
    private val opus = base + "bilibili.app.dynamic.v2.Opus/OpusDetail"
    private val main = base + "bilibili.main.community.reply.v1.Reply/MainList"
    private val detail = base + "bilibili.main.community.reply.v1.Reply/DetailList"
    private val local = "tv.danmaku.bili"
    private val international = "com.bilibili.app.in"

    private fun parse(url: String, body: String, host: String = local) =
        ParserRegistry.parseResult(RawData(url = url, hostApp = host, json = body, timestamp = 42L), url.substringBefore('?'))

    @Test fun homeBadgeAndStatsShareBilibiliPinkForBothApps() {
        val light = getPlatformThemeColor("bilibili", isDark = false)
        val dark = getPlatformThemeColor("bilibili", isDark = true)
        assertNotEquals(light.container, dark.container)
        for (key in listOf("bilibili", local, international)) {
            assertEquals(BilibiliPink, colorFromPackage(key))
            assertEquals(light, getPlatformThemeColor(key, isDark = false))
            assertEquals(dark, getPlatformThemeColor(key, isDark = true))
            assertEquals(colorFromPackage(key), light.brand)
            assertEquals(colorFromPackage(key), dark.brand)
        }
    }

    @Test fun routeRequiresAnExactEndpointAndRealHostPackage() {
        for (pkg in listOf(local, international)) {
            assertTrue(pkg in PlatformCatalog.hostPackages)
            assertEquals("bilibili", PlatformCatalog.platformForPackage(pkg))
            assertEquals(pkg, parse(video, """{"kind":"video","aid":"42"}""", pkg).records.single().sourceApp)
        }
        for (url in listOf("$video/extra", "$video?x=1", video.replace("grpc.biliapi.net", "other.invalid"))) {
            assertTrue(parse(url, "{}").consumeRaw)
            assertTrue(parse(url, "{}") is ParseResult.Ignored)
        }
        assertTrue(parse(video, "{}", "bilibili") is ParseResult.Ignored)
        assertTrue(parse(video, """{"kind":"opus","topic_type":17,"topic_id":42}""") is ParseResult.Failure)
        assertTrue(parse(video, """{"kind":"video","aid":1.5}""") is ParseResult.Failure)
        assertTrue(parse(video, "{broken") is ParseResult.Failure)
    }

    @Test fun videoPreservesLiteralBodyAndKnownMetrics() {
        val record = parse(video, """{
            "kind":"video","aid":"9007199254740993","bvid":"BV1xx411c7mD","title":"Title",
            "description":"<b>literal</b> **text**","author":"Uploader","published_at":1700000000,
            "cover":"http://i0.hdslb.com/bfs/archive/cover.jpg","author_avatar":"//i1.hdslb.com/bfs/face/avatar.jpg",
            "duration":65,"view":"1234","danmaku":0,
            "like":4,"reply":0,"coin":3,"favorite":2,"share":-1
        }""").records.single()
        assertEquals("bilibili_video_9007199254740993", record.identity.contentId)
        assertEquals(RecordIdentityKind.POST, record.identity.kind)
        assertEquals(RecordBodyFormat.PLAIN_TEXT, record.bodyFormat())
        assertEquals("<b>literal</b> **text**", record.bodyPlainText())
        assertEquals("Title", record.title)
        assertEquals("Uploader", record.authorName)
        assertEquals(1700000000000L, record.publishTime)
        assertEquals(42L, record.captureTime)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", record.sourceUrl)
        val cover = recordHeaderPresentation(record).titleImage
        assertEquals("http://i0.hdslb.com/bfs/archive/cover.jpg", cover)
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", secureBilibiliImageUrl(cover.toHttpUrl()).toString())
        assertEquals("https://i1.hdslb.com/bfs/face/avatar.jpg", record.authorAvatar)
        assertEquals(65L, PlatformMetadataAdapters(record.extraMetadata, record.platform).bilibili?.durationSeconds)
        assertTrue(RecordContentPolicy.isPrimaryContent(record))
        assertEquals("Bilibili视频", RecordContentPolicy.filterTypeLabel(record))
        assertTrue(contentStructure(record).supportsLocalReplies)
        assertNull(record.directRelation())
        assertEquals(0L, replySectionPresentation(record, 0).platformCount)
        val stats = recordStatistics(record, StatisticsSurface.DETAIL).associate { it.kind to it.value }
        assertEquals(mapOf(StatisticKind.VIEW to 1234L, StatisticKind.DANMAKU to 0L, StatisticKind.LIKE to 4L,
            StatisticKind.REPLY to 0L, StatisticKind.COIN to 3L, StatisticKind.FAVORITE to 2L), stats)
    }

    @Test fun upgradesOnlyStandardHttpOnBilibiliCdnWithoutChangingResource() {
        val url = "http://i2.hdslb.com/bfs/face/user.jpg@96w.webp?x=%2F#v".toHttpUrl()
        assertEquals("https://i2.hdslb.com/bfs/face/user.jpg@96w.webp?x=%2F#v", secureBilibiliImageUrl(url).toString())
        for (address in listOf("https://i2.hdslb.com/face.jpg", "http://i2.hdslb.com:8080/face.jpg",
            "http://i2.hdslb.com.example.org/face.jpg", "http://example.org/?host=i2.hdslb.com")) {
            val other = address.toHttpUrl()
            assertEquals(other, secureBilibiliImageUrl(other))
        }
    }

    @Test fun opusUsesCommentOidAndRetainsImagesAndUnknownCounts() {
        val record = parse(opus, """{
            "kind":"opus","topic_type":17,"topic_id":"42","opus_id":"73","text":"[plain](text)",
            "images":["https://i.example/one.jpg","//i.example/two.jpg"],"like":"bad","author_avatar":"file:///test"
        }""").records.single()
        assertEquals("bilibili_opus_17_42", record.platformId)
        assertEquals("https://www.bilibili.com/opus/73", record.sourceUrl)
        assertEquals("[plain](text)", record.bodyPlainText())
        assertEquals("", record.authorAvatar)
        assertEquals(listOf("https://i.example/one.jpg", "https://i.example/two.jpg"), recordBodyPresentation(record).images)
        assertTrue(RecordContentPolicy.isPrimaryContent(record))
        assertTrue(contentStructure(record).supportsLocalReplies)
        assertNull(replySectionPresentation(record, 0).platformCount)
        assertTrue(recordStatistics(record, StatisticsSurface.DETAIL).isEmpty())
        assertTrue(parse(opus, """{"kind":"opus","topic_type":1,"topic_id":42}""") is ParseResult.Failure)
        assertEquals(listOf("video", "opus", "comment"), RecordContentPolicy.statsRecordTypes("bilibili", true))
    }

    @Test fun commentsDistinguishEmptyAndPartialPagesAndKeepDirectParent() {
        assertEquals(ParseResult.Success(emptyList()), parse(main, """{"kind":"comments","topic_type":1,"topic_id":42,"replies":[]}"""))
        assertTrue(parse(main, """{"kind":"comments","topic_id":42,"replies":[]}""") is ParseResult.Failure)
        assertTrue(parse(main, """{"kind":"comments","topic_type":1,"topic_id":42}""") is ParseResult.Failure)
        val result = parse(detail, """{"kind":"comments","topic_type":11,"topic_id":42,"replies":[
            null,{"id":"9","parent":8,"root":"7","author":"Reply author","text":"<test>","like":0,"reply":2,
            "images":["https://i.example/reply.jpg"],"author_avatar":"http://i2.hdslb.com/bfs/face/reply.jpg"},{"id":10,"root":0}
        ]}""")
        assertTrue(result is ParseResult.Partial)
        assertFalse(result.consumeRaw)
        assertEquals(2, result.diagnostics.size)
        val comment = result.records.single()
        assertEquals(RecordIdentityKind.COMMENT, comment.identity.kind)
        assertEquals("bilibili_comment_11_42_9", comment.platformId)
        assertEquals("bilibili_comment_11_42_8", comment.replyTarget()?.contentId)
        assertEquals("<test>", comment.bodyPlainText())
        assertEquals("http://i2.hdslb.com/bfs/face/reply.jpg", comment.authorAvatar)
        assertEquals(listOf("https://i.example/reply.jpg"), recordBodyPresentation(comment).images)
        assertEquals(0L, commentStatistics(comment).likeCount)
        assertEquals(2L, commentStatistics(comment).replyCount)
        assertFalse(RecordContentPolicy.isPrimaryContent(comment))
    }

    @Test fun repositoryIncludesOwnedOrphansWithoutMixingTopicsOrRewritingParents() = runBlocking {
        val videoRecord = parse(video, """{"kind":"video","aid":42}""").records.single()
        val opusRecord = parse(opus, """{"kind":"opus","topic_type":17,"topic_id":42,"opus_id":73}""").records.single()
        fun replies(type: Int, oid: Long, rows: String, url: String = main) =
            parse(url, """{"kind":"comments","topic_type":$type,"topic_id":$oid,"replies":[$rows]}""").records
        val (top, connected) = replies(17, 42, """{"id":7,"parent":0,"root":0},{"id":8,"parent":7,"root":7}""")
        val orphan = replies(17, 42, """{"id":9,"parent":99,"root":7}""", detail).single()
        val videoComment = replies(1, 42, """{"id":7,"parent":0,"root":0}""").single()
        val otherType = replies(11, 42, """{"id":7,"parent":0,"root":0}""").single()
        val otherOid = replies(17, 420, """{"id":7,"parent":0,"root":0}""").single()
        val inconsistent = orphan.copy(platformId = "bilibili_comment_17_42_11", extraMetadata = otherType.extraMetadata)
        val foreignParent = orphan.copy(platformId = "bilibili_comment_17_42_12", parentId = videoComment.platformId)
        MyObjectBox.builder().directory(temp.newFolder()).build().use { store ->
            store.boxFor(UniversalRecord::class.java).put(listOf(videoRecord, opusRecord, top, connected, orphan,
                videoComment, otherType, otherOid, inconsistent, foreignParent))
            val repository = RecordDetailRepository(store)
            suspend fun snapshot(record: UniversalRecord) = withTimeout(5_000L) { repository.observe(record).first().getOrThrow() }
            val opusSnapshot = snapshot(opusRecord)
            assertEquals(setOf(top.platformId, connected.platformId, orphan.platformId), opusSnapshot.comments.map { it.platformId }.toSet())
            val tree = buildRecordDetailCommentRows(opusSnapshot.comments, RecordDetailCommentSort.DEFAULT, opusSnapshot.record)
            assertEquals(orphan.parentId, tree.single { it.record.platformId == orphan.platformId }.missingParentId)
            assertEquals(listOf(videoComment.platformId), snapshot(videoRecord).comments.map { it.platformId })
            assertEquals(listOf(connected.platformId), snapshot(top).comments.map { it.platformId })
            assertEquals(opusRecord.platformId, snapshot(top).related?.record?.platformId)
            assertEquals(RelatedTargetState.MISSING, snapshot(orphan).related?.targetState)
        }
    }
}
