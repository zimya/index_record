package com.melonhu.indexrecord

import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.*
import com.melonhu.indexrecord.data.repository.RecordDetailRepository
import com.melonhu.indexrecord.network.secureNeteaseImageUrl
import com.melonhu.indexrecord.parser.ParseResult
import com.melonhu.indexrecord.parser.ParserRegistry
import com.melonhu.indexrecord.presentation.*
import com.melonhu.indexrecord.ui.detail.record.RecordDetailCommentSort
import com.melonhu.indexrecord.ui.detail.record.buildRecordDetailCommentRows
import com.melonhu.indexrecord.ui.stats.colorFromPackage
import com.melonhu.indexrecord.ui.theme.NeteaseRed
import com.melonhu.indexrecord.ui.theme.getPlatformThemeColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NeteaseIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    private val pkg = PlatformCatalog.NETEASE_PACKAGE
    private val batchUrl = "https://interface3.music.163.com/eapi/batch"
    private val commentsUrl = "https://interface3.music.163.com/eapi/v2/resource/comments"
    private val floorUrl = "https://interface3.music.163.com/eapi/v2/resource/comment/floor/get"
    private val preloadUrl = "https://interface3.music.163.com/eapi/resource/comments/reply/preload"
    private val songUrl = "https://interface3.music.163.com/eapi/v3/song/detail"
    private val playlistUrl = "https://interface3.music.163.com/eapi/v6/playlist/detail?id=2936255937&t=0"
    private val albumUrl = "https://interface3.music.163.com/eapi/album/v3/detail"
    private val programUrl = "https://interface3.music.163.com/eapi/dj/program/detail"

    private fun parse(url: String, body: String, host: String = pkg) =
        ParserRegistry.parseResult(
            RawData(url = url, hostApp = host, json = body, timestamp = 42L),
            url.substringBefore('?')
        )

    @Test fun themeColorsAndCatalogResolveNetease() {
        val light = getPlatformThemeColor("netease", isDark = false)
        val dark = getPlatformThemeColor("netease", isDark = true)
        assertNotEquals(light.container, dark.container)
        for (key in listOf("netease", pkg)) {
            assertEquals(NeteaseRed, colorFromPackage(key))
            assertEquals(light, getPlatformThemeColor(key, isDark = false))
            assertEquals(dark, getPlatformThemeColor(key, isDark = true))
            assertEquals(NeteaseRed, light.brand)
            assertEquals(NeteaseRed, dark.brand)
        }
        assertTrue(pkg in PlatformCatalog.hostPackages)
        assertEquals("netease", PlatformCatalog.platformForPackage(pkg))
        assertEquals("网易云音乐", PlatformCatalog.displayName("netease"))
        assertEquals("网易云音乐歌曲", RecordContentPolicy.filterTypeLabel("netease:song"))
        assertEquals("网易云音乐歌单", RecordContentPolicy.filterTypeLabel("netease:playlist"))
        assertEquals("网易云音乐专辑", RecordContentPolicy.filterTypeLabel("netease:album"))
        assertEquals("网易云音乐播客", RecordContentPolicy.filterTypeLabel("netease:program"))
        assertEquals("网易云音乐评论", RecordContentPolicy.filterTypeLabel("netease:comment"))
        assertEquals(
            listOf("song", "playlist", "album", "program", "comment"),
            RecordContentPolicy.statsRecordTypes("netease", true)
        )
    }

    @Test fun routeValidationHandlesIgnoredAndMalformedPayloads() {
        assertTrue(parse(songUrl, "{}", "other.pkg") is ParseResult.Ignored)
        assertTrue(parse("https://interface3.music.163.com/eapi/ad/get", "{}") is ParseResult.Ignored)
        assertTrue(parse(batchUrl, """{"code":200,"/api/link/position/show/resource":{"code":200}}""") is ParseResult.Ignored)
        assertTrue(parse(songUrl, """{"code":-460,"message":"cheating"}""") is ParseResult.Ignored)
        assertTrue(parse(songUrl, "{broken") is ParseResult.Failure)
        assertTrue(parse(commentsUrl, """{"code":200,"data":{}}""") is ParseResult.Failure)
        assertTrue(parse(playlistUrl, """{"code":200,"playlist":{"id":0}}""") is ParseResult.Failure)
    }

    @Test fun songPlaylistAlbumAndProgramDetailsParseCleanly() {
        val song = parse(songUrl, """{
            "code":200,
            "songs":[{
                "id":1973665667,"name":"海屿你","alia":["求你别离开我"],
                "ar":[{"id":1,"name":"马也_Crabbit"}],
                "al":{"id":150006421,"name":"海屿你","picUrl":"http://p1.music.126.net/cover.jpg"},
                "dt":295940
            }]
        }""").records.single()
        assertEquals("netease_song_1973665667", song.platformId)
        assertEquals(RecordIdentityKind.POST, song.identity.kind)
        assertEquals("海屿你 (求你别离开我)", song.title)
        assertEquals("马也_Crabbit", song.authorName)
        assertEquals("专辑：海屿你", song.content)
        assertEquals("https://music.163.com/#/song?id=1973665667", song.sourceUrl)
        assertEquals(RecordBodyFormat.PLAIN_TEXT, song.bodyFormat())
        assertTrue(RecordContentPolicy.isPrimaryContent(song))
        assertTrue(contentStructure(song).supportsLocalReplies)
        assertEquals("http://p1.music.126.net/cover.jpg", recordHeaderPresentation(song).titleImage)
        assertEquals("海屿你 · 4:55", recordContext(song))

        val playlist = parse(playlistUrl, """{
            "code":200,
            "playlist":{
                "id":2936255937,"name":"倾吐隐秘情绪","description":"**literal** desc",
                "commentThreadId":"A_PL_0_2936255937","createTime":1565759532471,
                "coverImgUrl":"https://p1.music.126.net/pl.jpg",
                "commentCount":354,"shareCount":2023,"subscribedCount":81473,"playCount":10646719,
                "creator":{"nickname":"蜜糖男孩","avatarUrl":"http://p1.music.126.net/u.jpg"}
            }
        }""").records.single()
        assertEquals("netease_playlist_2936255937", playlist.platformId)
        assertEquals("playlist", playlist.recordType)
        assertEquals("**literal** desc", playlist.bodyPlainText())
        assertEquals(1565759532471L, playlist.publishTime)
        val plStats = recordStatistics(playlist, StatisticsSurface.DETAIL).associate { it.kind to it.value }
        assertEquals(
            mapOf(
                StatisticKind.REPLY to 354L,
                StatisticKind.SHARE to 2023L,
                StatisticKind.FAVORITE to 81473L,
                StatisticKind.VIEW to 10646719L
            ),
            plStats
        )

        val album = parse(albumUrl, """{
            "code":200,
            "album":{
                "id":150006421,"name":"海屿你","description":"专辑简介",
                "publishTime":1660924800000,"commentThreadId":"R_AL_3_150006421",
                "picUrl":"https://p1.music.126.net/al.jpg",
                "artist":{"name":"马也_Crabbit","picUrl":"https://p1.music.126.net/ar.jpg"}
            },
            "info":{"commentCount":186,"shareCount":250}
        }""").records.single()
        assertEquals("netease_album_150006421", album.platformId)
        assertEquals("album", album.recordType)
        assertEquals(186L, replySectionPresentation(album, 0).platformCount)

        val program = parse(programUrl, """{
            "code":200,
            "program":{
                "id":3712106492,"name":"揭秘我的世界","description":"节目简介",
                "commentThreadId":"A_DJ_1_3712106492","coverUrl":"https://p1.music.126.net/dj.jpg",
                "createTime":1768724730815,"duration":354864,"listenerCount":512969,
                "likedCount":156,"commentCount":17,"shareCount":0,
                "dj":{"nickname":"粒粒星河","avatarUrl":"http://p1.music.126.net/dj_u.jpg"},
                "radio":{"name":"帕斯诺尔实验室"}
            }
        }""").records.single()
        assertEquals("netease_program_3712106492", program.platformId)
        assertEquals("program", program.recordType)
        assertEquals("帕斯诺尔实验室 · 5:54", recordContext(program))
    }

    @Test fun upgradesOnlyStandardHttpOnNeteaseImageCdn() {
        val url = "http://p1.music.126.net/avatar.jpg?param=100y100".toHttpUrl()
        assertEquals("https://p1.music.126.net/avatar.jpg?param=100y100", secureNeteaseImageUrl(url).toString())
        for (other in listOf("https://p1.music.126.net/a.jpg", "http://p1.music.126.net:8080/a.jpg", "http://example.com/a.jpg")) {
            val parsed = other.toHttpUrl()
            assertEquals(parsed, secureNeteaseImageUrl(parsed))
        }
    }

    @Test fun commentsParseBatchFloorPreloadAndMediaAndPreserveRelations() {
        val batchResult = parse(batchUrl, """{
            "code":200,
            "/api/v2/resource/comments":{
                "code":200,
                "data":{
                    "comments":[
                        {
                            "commentId":6009272950,"threadId":"R_SO_4_1973665667",
                            "content":"主评正文","time":1700497680000,"likedCount":99255,"replyCount":1128,
                            "parentCommentId":0,
                            "user":{"nickname":"子芩木蓠","avatarUrl":"http://p1.music.126.net/a.jpg"},
                            "ipLocation":{"location":"湖南"},
                            "contentPicUrl":"https://p1.music.126.net/pic.jpg",
                            "hideSerialComments":[
                                {"commentId":6009272951,"threadId":"R_SO_4_1973665667","content":"续评","time":1700497690000,"likedCount":10,"parentCommentId":0,"user":{"nickname":"子芩木蓠"}}
                            ]
                        },
                        {
                            "commentId":9703224268,"threadId":"R_SO_4_1973665667",
                            "content":"","time":1700500000000,"likedCount":5,"parentCommentId":0,
                            "voiceDurationMillSecond":50758,
                            "voiceExt":{"asrText":"语音识别内容"},
                            "user":{"nickname":"唱将"}
                        }
                    ]
                }
            }
        }""")
        assertTrue(batchResult is ParseResult.Success)
        assertEquals(3, batchResult.records.size)
        val top = batchResult.records[0]
        assertEquals("netease_comment_R_SO_4_1973665667_6009272950", top.platformId)
        assertEquals("netease_song_1973665667", top.parentId)
        assertEquals(RecordIdentityKind.COMMENT, top.identity.kind)
        assertEquals(listOf("https://p1.music.126.net/pic.jpg"), recordBodyPresentation(top).images)
        assertEquals("湖南", recordStatistics(top, StatisticsSurface.DETAIL).first { it.kind == StatisticKind.LOCATION }.text)
        assertEquals(99255L, commentStatistics(top).likeCount)
        assertEquals(1128L, commentStatistics(top).replyCount)
        assertEquals("[语音] 语音识别内容", batchResult.records[2].content)

        val floorResult = parse(floorUrl, """{
            "code":200,
            "data":{
                "ownerComment":{"commentId":6009272950,"threadId":"R_SO_4_1973665667","content":"楼主","time":1700497680000,"likedCount":99255,"parentCommentId":0,"user":{"nickname":"子芩木蓠"}},
                "bestComments":[{"commentId":6017204987,"threadId":"R_SO_4_1973665667","content":"精评","time":1701828639677,"likedCount":2180,"parentCommentId":6009272950,"user":{"nickname":"林下"}}],
                "comments":[
                    {"commentId":6017204987,"threadId":"R_SO_4_1973665667","content":"精评","time":1701828639677,"likedCount":2180,"parentCommentId":6009272950,"user":{"nickname":"林下"}},
                    {"commentId":6018126407,"threadId":"R_SO_4_1973665667","content":"回复精评","time":1701969250065,"likedCount":10,"parentCommentId":6009272950,"beReplied":[{"beRepliedCommentId":6017204987}],"user":{"nickname":"墨藏"}}
                ]
            }
        }""")
        assertEquals(3, floorResult.records.size)
        assertEquals("netease_comment_R_SO_4_1973665667_6009272950", floorResult.records[1].parentId)
        assertEquals("netease_comment_R_SO_4_1973665667_6017204987", floorResult.records[2].parentId)

        val preloadResult = parse(preloadUrl, """{
            "code":200,
            "data":{
                "preloadCommentMap":{
                    "6009272950":[null,{"commentId":6023532291,"threadId":"R_SO_4_1973665667","content":"预加载回复","time":1702796362775,"likedCount":220,"parentCommentId":6009272950,"user":{"nickname":"暄暄"}}]
                }
            }
        }""")
        assertTrue(preloadResult is ParseResult.Partial)
        assertEquals(1, preloadResult.records.size)
        assertEquals(1, preloadResult.diagnostics.size)
    }

    @Test fun repositorySelectsOwnedThreadCommentsWithoutCrossThreadLeakage() = runBlocking {
        val song = parse(songUrl, """{"code":200,"songs":[{"id":42,"name":"Song 42"}]}""").records.single()
        val album = parse(albumUrl, """{"code":200,"album":{"id":42,"name":"Album 42","commentThreadId":"R_AL_3_42"}}""").records.single()
        val songBatch = parse(batchUrl, """{
            "code":200,
            "/api/v2/resource/comments":{"code":200,"data":{"comments":[
                {"commentId":1,"threadId":"R_SO_4_42","content":"Top","time":1000,"likedCount":10,"parentCommentId":0},
                {"commentId":2,"threadId":"R_SO_4_42","content":"Reply","time":2000,"likedCount":5,"parentCommentId":1}
            ]}}
        }""").records
        val songOrphan = parse(floorUrl, """{
            "code":200,
            "data":{"comments":[
                {"commentId":3,"threadId":"R_SO_4_42","content":"Orphan","time":3000,"likedCount":1,"parentCommentId":999}
            ]}
        }""").records.single()
        val albumComment = parse(commentsUrl, """{
            "code":200,
            "data":{"comments":[
                {"commentId":1,"threadId":"R_AL_3_42","content":"Album comment","time":4000,"likedCount":2,"parentCommentId":0}
            ]}
        }""").records.single()

        MyObjectBox.builder().directory(temp.newFolder()).build().use { store ->
            store.boxFor(UniversalRecord::class.java).put(songBatch + listOf(song, album, songOrphan, albumComment))
            val repository = RecordDetailRepository(store)
            suspend fun snapshot(record: UniversalRecord) =
                withTimeout(5_000L) { repository.observe(record).first().getOrThrow() }

            val songSnapshot = snapshot(song)
            assertEquals(
                setOf(songBatch[0].platformId, songBatch[1].platformId, songOrphan.platformId),
                songSnapshot.comments.map { it.platformId }.toSet()
            )
            val tree = buildRecordDetailCommentRows(songSnapshot.comments, RecordDetailCommentSort.DEFAULT, songSnapshot.record)
            assertEquals(songOrphan.parentId, tree.single { it.record.platformId == songOrphan.platformId }.missingParentId)
            assertEquals(listOf(albumComment.platformId), snapshot(album).comments.map { it.platformId })
            assertEquals(listOf(songBatch[1].platformId), snapshot(songBatch[0]).comments.map { it.platformId })
            assertEquals(song.platformId, snapshot(songBatch[0]).related?.record?.platformId)
            assertEquals(RelatedTargetState.MISSING, snapshot(songOrphan).related?.targetState)
        }
    }
}
