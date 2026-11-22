package com.melonhu.indexrecord.parser.douban

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.parser.ParserRegistry
import com.melonhu.indexrecord.parser.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DoubanParserTest {

    private fun loadFixture(filename: String): String {
        val candidates = listOf(
            File("../research/douban", filename),
            File("research/douban", filename),
            File("../../research/douban", filename)
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Fixture file $filename not found in candidates: $candidates")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun testDoubanGroupTopic() {
        val url = "https://frodo.douban.com/api/v2/group/topic/505200892?_spm_id=MTIxMzI3Njcx&show_recommend=1"
        val cleanUrl = "https://frodo.douban.com/api/v2/group/topic/505200892"
        val json = loadFixture("v2_group_topic.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.douban.frodo"
        )

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(1, records!!.size)

        val record = records[0]
        assertEquals("douban", record.platform)
        assertEquals("topic", record.recordType)
        assertEquals("douban_group_topic_505200892", record.platformId)
        assertTrue(record.title.contains("i飞雪"))
        assertEquals("画画的肖恩", record.authorName)
        assertEquals("121327671", record.authorId)
        assertTrue(record.content.contains("已获得原po主授权"))
        assertTrue(record.content.contains("https://img3.doubanio.com/view/group_topic/l/public/p753077423.webp"))
        assertEquals(
            1,
            Regex("p753077423\\.webp").findAll(record.content).count()
        )
        assertFalse(JsonParser.parseString(record.extraMetadata).asJsonObject.has("title_image"))
        assertTrue(record.publishTime > 0)
    }

    @Test
    fun testDoubanPersonalDynamicWithMultiplePhotos() {
        val url = "https://frodo.douban.com/api/v2/group/topic/506438877?subtype=personal&source=user_profile"
        val cleanUrl = "https://frodo.douban.com/api/v2/group/topic/506438877"
        val json = """
            {
              "id": "506438877",
              "type": "topic",
              "subtype": "personal",
              "title": "",
              "create_time": "2026-11-22 11:58:46",
              "cover_url": "https://img3.doubanio.com/view/group_topic/sqxs/public/p754553148.webp",
              "author": {"id": "178769601", "name": "Sally博物馆", "avatar": "https://img3.doubanio.com/icon/up178769601-20.webp"},
              "group": {"id": "0", "name": ""},
              "content": "<div id='content'><p data-align=\"left\">第一段内容</p></div>",
              "like_count": 226,
              "comments_count": 6,
              "collections_count": 49,
              "reshares_count": 129,
              "ip_location": "广东",
              "photos": [
                {"id": "754553148", "image": {"large": {"url": "https://img3.doubanio.com/view/group_topic/l/public/p754553148.webp"}, "normal": {"url": "https://img3.doubanio.com/view/group_topic/l/public/p754553148.webp"}, "raw": null}},
                {"id": "754553147", "image": {"large": {"url": "https://img3.doubanio.com/view/group_topic/l/public/p754553147.webp"}, "normal": {"url": "https://img3.doubanio.com/view/group_topic/l/public/p754553147.webp"}, "raw": null}}
              ]
            }
        """.trimIndent()

        val raw = RawData(url = url, json = json, hostApp = "com.douban.frodo")
        val record = ParserRegistry.parse(raw, cleanUrl)!!.single()

        assertEquals("douban", record.platform)
        assertEquals("feed", record.recordType)
        assertEquals("douban_group_topic_506438877", record.platformId)
        assertEquals("", record.title)
        assertTrue(record.content.contains("第一段内容"))
        assertTrue(record.content.contains("![图片](https://img3.doubanio.com/view/group_topic/l/public/p754553148.webp)"))
        assertTrue(record.content.contains("![图片](https://img3.doubanio.com/view/group_topic/l/public/p754553147.webp)"))

        val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertFalse(extra.has("title_image"))
        assertFalse(extra.has("group_id"))
        assertFalse(extra.has("group_name"))
        assertEquals(129L, extra.get("reshares_count").asLong)
        assertEquals(226L, extra.get("like_count").asLong)

        assertTrue(com.melonhu.indexrecord.presentation.RecordContentPolicy.isPrimaryContent(record))
        val header = com.melonhu.indexrecord.presentation.recordHeaderPresentation(record)
        assertEquals("", header.titleImage)
        val listPresentation = com.melonhu.indexrecord.presentation.recordListPresentation(record, "")
        assertEquals("豆瓣 · 动态", listPresentation.badgeText)
        assertEquals("Sally博物馆的动态", listPresentation.title)
        assertTrue(listPresentation.statistics.any {
            it.kind == com.melonhu.indexrecord.presentation.StatisticKind.REPOST && it.value == 129L
        })
    }

    @Test
    fun testDoubanStatusAndComments() {
        val statusUrl = "https://frodo.douban.com/api/v2/status/10057508302"
        val statusJson = """
            {
              "id": "10057508302",
              "type": "status",
              "activity": "玩过",
              "create_time": "2026-11-15 19:10:00",
              "author": {"id": "178769601", "name": "Sally博物馆", "avatar": "https://img3.doubanio.com/icon/up178769601-20.webp"},
              "text": "第一行\n第二行",
              "rating": {"value": 5, "star_count": 5},
              "like_count": 12,
              "comments_count": 1,
              "reshares_count": 3,
              "images": [
                {"id": "11", "image": {"large": {"url": "https://img3.doubanio.com/view/status/l/public/11.webp"}}},
                {"id": "12", "image": {"large": {"url": "https://img3.doubanio.com/view/status/l/public/12.webp"}}}
              ],
              "card": {
                "id": "37669001",
                "title": "测试游戏",
                "subtitle": "2026 / 游戏",
                "url": "https://www.douban.com/game/37669001/",
                "uri": "douban://douban.com/game/37669001",
                "images_block": {
                  "images": [
                    {"image": {"large": {"url": "https://img3.doubanio.com/view/note/l/public/99.webp"}}}
                  ]
                }
              }
            }
        """.trimIndent()

        val rawStatus = RawData(url = statusUrl, json = statusJson, hostApp = "com.douban.frodo")
        assertTrue(DoubanParser.canHandle(statusUrl, rawStatus))
        val statusRecord = ParserRegistry.parse(rawStatus, statusUrl)!!.single()
        assertEquals("feed", statusRecord.recordType)
        assertEquals("douban_status_10057508302", statusRecord.platformId)
        assertEquals("douban_game_37669001", statusRecord.parentId)
        assertTrue(statusRecord.content.contains("第一行"))
        assertTrue(statusRecord.content.contains("第二行"))
        assertTrue(statusRecord.content.contains("测试游戏"))
        assertTrue(statusRecord.content.contains("https://img3.doubanio.com/view/status/l/public/11.webp"))
        assertTrue(statusRecord.content.contains("https://img3.doubanio.com/view/status/l/public/12.webp"))
        assertTrue(statusRecord.content.contains("https://img3.doubanio.com/view/note/l/public/99.webp"))

        val commentsUrl = "https://frodo.douban.com/api/v2/status/10057508302/comments"
        val commentsJson = """
            {
              "comments": [
                {
                  "id": 468264521,
                  "author": {"id": "204718445", "name": "南瓜瓜"},
                  "text": "主评论",
                  "create_time": "2026-11-16 01:56:24",
                  "vote_count": 2,
                  "replies": [
                    {
                      "id": 468267692,
                      "parent_comment_id": "468264521",
                      "author": {"id": "218545496", "name": "东瓜"},
                      "text": "嵌套回复",
                      "create_time": "2026-11-16 09:14:47",
                      "vote_count": 0
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val commentRecords = ParserRegistry.parse(RawData(url = commentsUrl, json = commentsJson, hostApp = "com.douban.frodo"), commentsUrl)!!
        assertEquals(2, commentRecords.size)
        assertEquals("douban_status_10057508302", commentRecords[0].parentId)
        assertEquals("douban_comment_468264521", commentRecords[1].parentId)

        val repliesUrl = "https://frodo.douban.com/api/v2/status/comment/468264521/replies"
        val repliesJson = """
            {
              "replies": [
                {
                  "id": 468288852,
                  "parent_comment_id": "468264521",
                  "author": {"id": "254729518", "name": "lsh"},
                  "text": "二级回复",
                  "create_time": "2026-11-17 02:49:45",
                  "vote_count": 1
                }
              ]
            }
        """.trimIndent()
        val replyRecords = ParserRegistry.parse(RawData(url = repliesUrl, json = repliesJson, hostApp = "com.douban.frodo"), repliesUrl)!!
        assertEquals(1, replyRecords.size)
        assertEquals("douban_comment_468264521", replyRecords[0].parentId)
    }

    @Test
    fun testGroupTopicRexxarStatisticsAreNotFabricated() {
        val url = "https://frodo.douban.com/api/v2/group/topic/505438840"
        val json = """{"id":"505438840","title":"帖子","like_count":0,"comments_count":254}"""
        val raw = RawData(url = "$url?s=rexxar_new&channel=test", json = json, hostApp = "com.douban.frodo")
        val record = DoubanParser.parse(raw, url).single()
        val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals(254L, extra.get("comment_count").asLong)
        assertFalse(extra.has("like_count"))
        assertFalse(extra.has("voteup_count"))
        assertFalse(extra.has("favlists_count"))

        val nativeRecord = DoubanParser.parse(raw.copy(url = "$url?source=feed"), url).single()
        val nativeExtra = JsonParser.parseString(nativeRecord.extraMetadata).asJsonObject
        assertEquals(0L, nativeExtra.get("like_count").asLong)
        assertEquals(0L, nativeExtra.get("voteup_count").asLong)
    }

    @Test
    fun testGroupTopicUnknownStatisticsAndAliasFallback() {
        val url = "https://frodo.douban.com/api/v2/group/topic/505438840"
        for (invalid in listOf("null", "\"bad\"", "{}", "-1")) {
            val raw = RawData(
                url = url, hostApp = "com.douban.frodo",
                json = """{"like_count":$invalid,"reactions_count":6,"collections_count":$invalid,"comments_count":$invalid}"""
            )
            val record = DoubanParser.parse(raw, url).single()
            val extra = JsonParser.parseString(record.extraMetadata).asJsonObject
            assertEquals(6L, extra.get("like_count").asLong)
            assertFalse(extra.has("favlists_count"))
            assertFalse(extra.has("comment_count"))
        }
        val empty = DoubanParser.parse(RawData(url = url, json = "{}", hostApp = "com.douban.frodo"), url).single()
        val extra = JsonParser.parseString(empty.extraMetadata).asJsonObject
        assertFalse(extra.has("like_count"))
        assertFalse(extra.has("voteup_count"))
        assertFalse(extra.has("favlists_count"))
        assertFalse(extra.has("comment_count"))
    }

    @Test
    fun testDoubanGroupTopicComments() {
        val url = "https://frodo.douban.com/api/v2/group/topic/505200892/comments?start=20&count=20"
        val cleanUrl = "https://frodo.douban.com/api/v2/group/topic/505200892/comments"
        val json = loadFixture("v2_group_topic_comments.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.douban.frodo"
        )

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.isNotEmpty())

        val first = records.firstOrNull { it.platformId == "douban_comment_6816378666" }
        assertNotNull(first)
        assertEquals("douban", first!!.platform)
        assertEquals("comment", first.recordType)
        assertEquals("douban_group_topic_505200892", first.parentId)
        assertEquals("真遥大法好", first.authorName)
        assertEquals("老兵。。。你还有多少证据。。。", first.content)

        // 验证带图片的评论已提取图片
        val photoComment = records.firstOrNull { it.platformId == "douban_comment_6816379256" }
        assertNotNull(photoComment)
        assertTrue(photoComment!!.content.contains("p1098190714.jpg"))
    }

    @Test
    fun testDoubanMovieHotInterests() {
        val url = "https://frodo.douban.com/api/v2/movie/1292722/hot_interests?status=done&following=1"
        val cleanUrl = "https://frodo.douban.com/api/v2/movie/1292722/hot_interests"
        val json = loadFixture("v2_movie_hot_interests.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.douban.frodo"
        )

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(5, records!!.size)

        val first = records[0]
        assertEquals("douban", first.platform)
        assertEquals("interest", first.recordType)
        assertEquals("douban_interest_7195771", first.platformId)
        assertEquals("douban_movie_1292722", first.parentId)
        assertEquals("个篱", first.authorName)
        assertTrue(first.content.contains("那对对死无所畏惧的老夫妇"))
        assertEquals("电影短评：泰坦尼克号", first.title)
        assertTrue(first.extraMetadata.contains("\"star_count\":5"))
    }

    @Test
    fun testDoubanTvInterests() {
        val url = "https://frodo.douban.com/api/v2/tv/2373195/interests?start=0&count=20&status=mixed&order_by=latest"
        val cleanUrl = url.substringBefore('?')
        val raw = RawData(
            url = url,
            json = loadFixture("v2_interests.json"),
            hostApp = "com.douban.frodo",
            timestamp = 1794407394000L
        )

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)!!
        assertEquals(20, records.size)

        val first = records.first()
        assertEquals("douban", first.platform)
        assertEquals("interest", first.recordType)
        assertEquals("com.douban.frodo", first.sourceApp)
        assertEquals("douban_interest_4987032379", first.platformId)
        assertEquals("douban_tv_2373195", first.parentId)
        assertTrue(first.title.startsWith("剧集短评："))
        assertEquals("从第一季看不到过多人性的东西 待我再看看后四季", first.content)
        assertEquals("飞天螺旋桨", first.authorName)
        assertEquals("207209503", first.authorId)
        assertEquals("https://img9.doubanio.com/icon/up207209503-5.webp", first.authorAvatar)
        assertEquals(url, first.sourceUrl)
        assertEquals(1794406392000L, first.publishTime)
        assertEquals(raw.timestamp, first.captureTime)

        val extra = JsonParser.parseString(first.extraMetadata).asJsonObject
        assertEquals("tv", extra.get("subject_type").asString)
        assertEquals("2373195", extra.get("subject_id").asString)
        assertEquals("done", extra.get("status").asString)
        assertEquals("中国香港", extra.get("ip_info").asString)
        assertEquals(4, extra.get("rating_value").asInt)
        assertEquals(4, extra.get("star_count").asInt)
        assertEquals(0, extra.get("vote_count").asInt)

        val unrated = records.first { it.platformId == "douban_interest_4009903480" }
        val unratedExtra = JsonParser.parseString(unrated.extraMetadata).asJsonObject
        assertFalse(unratedExtra.has("rating_value"))
        assertFalse(unratedExtra.has("star_count"))

        val listItems = com.melonhu.indexrecord.ui.home.groupRecordsForHome(records)
        assertEquals(1, listItems.size)
        val group = listItems.single() as com.melonhu.indexrecord.ui.home.InterestGroupListItem
        assertEquals("剧集短评", group.typeLabel)
        assertEquals(20, group.interests.size)
    }

    @Test
    fun testDoubanInterestsSubjectTypesAndDeduplicationIds() {
        for (subjectType in listOf("movie", "tv", "book", "music", "game", "drama")) {
            val url = "https://frodo.douban.com/api/v2/$subjectType/2373195/interests/"
            val raw = RawData(url = url, json = loadFixture("v2_interests.json"), hostApp = "com.douban.frodo")
            assertTrue(DoubanParser.canHandle(url, raw))
            val records = ParserRegistry.parse(raw, url)!!
            val hotUrl = url.replace("/interests/", "/hot_interests/")
            val hotRecords = ParserRegistry.parse(raw.copy(url = hotUrl), hotUrl)!!

            assertEquals(20, records.size)
            assertTrue(records.all { it.parentId == "douban_${subjectType}_2373195" })
            assertEquals(hotRecords.map { it.platformId }, records.map { it.platformId })
        }
    }

    @Test
    fun testDoubanInterestsEmptyAndInvalidResponses() {
        val url = "https://frodo.douban.com/api/v2/tv/2373195/interests"
        for (json in listOf("{\"interests\":[]}", "{}", "not json")) {
            val raw = RawData(url = url, json = json, hostApp = "com.douban.frodo")
            assertEquals(emptyList<com.melonhu.indexrecord.data.model.UniversalRecord>(), ParserRegistry.parse(raw, url))
        }
        assertFalse(DoubanHotInterestsParser.canHandle("$url/4987032379"))
    }

    @Test
    fun testDoubanHotInterestsGrouping() {
        val url = "https://frodo.douban.com/api/v2/movie/1292722/hot_interests"
        val json = loadFixture("v2_movie_hot_interests.json")
        val raw = RawData(url = url, json = json, hostApp = "com.douban.frodo")

        val records = ParserRegistry.parse(raw, url)!!
        assertEquals(5, records.size)

        // 验证在首页中短评被聚合为一个条目标题卡片
        val listItems = com.melonhu.indexrecord.ui.home.groupRecordsForHome(records)
        assertEquals(1, listItems.size)
        assertTrue(listItems[0] is com.melonhu.indexrecord.ui.home.InterestGroupListItem)
        val groupItem = listItems[0] as com.melonhu.indexrecord.ui.home.InterestGroupListItem
        assertEquals("泰坦尼克号", groupItem.subjectTitle)
        assertEquals("电影短评", groupItem.typeLabel)
        assertEquals(5, groupItem.interests.size)
    }

    @Test
    fun testDoubanBookHotInterests() {
        val url = "https://frodo.douban.com/api/v2/book/1007305/hot_interests?status=done&following=1"
        val cleanUrl = "https://frodo.douban.com/api/v2/book/1007305/hot_interests"
        val json = loadFixture("v2_book_hot_interests.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.douban.frodo"
        )

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(5, records!!.size)

        val first = records[0]
        assertEquals("douban_interest_532193706", first.platformId)
        assertEquals("douban_book_1007305", first.parentId)
        assertEquals("图书短评：红楼梦", first.title)
    }

    @Test
    fun testDoubanGameHotInterests() {
        val url = "https://frodo.douban.com/api/v2/game/26817171/hot_interests?status=done&following=1"
        val cleanUrl = "https://frodo.douban.com/api/v2/game/26817171/hot_interests"
        val json = loadFixture("v2_game_hot_interests.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.douban.frodo"
        )

        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(5, records!!.size)
        assertEquals("douban_interest_3033409", records!![0].platformId)
        assertEquals("douban_game_26817171", records[0].parentId)
        assertEquals("游戏短评：塞尔达传说 旷野之息", records[0].title)
    }

    @Test
    fun testDoubanReview() {
        val url = "https://frodo.douban.com/api/v2/review/7379728"
        val cleanUrl = "https://frodo.douban.com/api/v2/review/7379728"
        val json = loadFixture("v2_review.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.douban.frodo"
        )

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size >= 1)

        val record = records.first { it.recordType == "review" }
        assertEquals("douban", record.platform)
        assertEquals("review", record.recordType)
        assertEquals("douban_review_7379728", record.platformId)
        assertEquals("露丝为什么要跳海", record.title)
        assertEquals("柴斯卡", record.authorName)
        assertEquals("douban_movie_1292722", record.parentId)
        assertTrue(record.content.contains("大概是十三年前，我在书市上买了一本"))
        assertTrue(record.extraMetadata.contains("\"star_count\":4"))

        // 同时附带沉淀了条目本体记录
        val subjectRecord = records.firstOrNull { it.recordType == "subject" }
        assertNotNull(subjectRecord)
        assertEquals("douban_movie_1292722", subjectRecord!!.platformId)
        assertEquals("泰坦尼克号", subjectRecord.title)
    }

    @Test
    fun testDoubanReviewBook() {
        val url = "https://frodo.douban.com/api/v2/review/1967305"
        val cleanUrl = "https://frodo.douban.com/api/v2/review/1967305"
        val json = loadFixture("v2_review_book.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.douban.frodo"
        )

        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size >= 1)
        val record = records.first { it.recordType == "review" }
        assertEquals("与世界的敌对，不等于生命的真诚", record.title)
        assertEquals("douban_book_1007305", record.parentId)
    }

    @Test
    fun testDoubanReviewComments() {
        val url = "https://frodo.douban.com/api/v2/review/1967305/comments?start=0&count=20&nested=1"
        val cleanUrl = "https://frodo.douban.com/api/v2/review/1967305/comments"
        val json = loadFixture("v2_review_comments.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.douban.frodo"
        )

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size > 10)

        // 验证主评论与楼中楼回复
        val rootComment = records.firstOrNull { it.platformId == "douban_comment_1488439" }
        assertNotNull(rootComment)
        assertEquals("douban_review_1967305", rootComment!!.parentId)

        val replyComment = records.firstOrNull { it.platformId == "douban_comment_8880655" }
        assertNotNull(replyComment)
        assertEquals("douban_comment_1488439", replyComment!!.parentId)
    }

    @Test
    fun testDoubanForumTopic() {
        val url = "https://frodo.douban.com/api/v2/forum_topic/22546527?enable_sdk_bidding=1"
        val cleanUrl = "https://frodo.douban.com/api/v2/forum_topic/22546527"
        val json = loadFixture("v2_forum_topic.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.douban.frodo"
        )

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size >= 1)

        val record = records.first { it.recordType == "topic" }
        assertEquals("douban", record.platform)
        assertEquals("topic", record.recordType)
        assertEquals("douban_forum_topic_22546527", record.platformId)
        assertEquals("douban_book_1007305", record.parentId)
        assertEquals("未注销", record.authorName)
        assertTrue(record.title.contains("真的还有别的小说，能比红楼吗"))
    }

    @Test
    fun testDoubanForumTopicComments() {
        val url = "https://frodo.douban.com/api/v2/forum_topic/616438845/comments?start=0&count=20"
        val cleanUrl = "https://frodo.douban.com/api/v2/forum_topic/616438845/comments"
        val json = loadFixture("v2_forum_topic_comments.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.douban.frodo"
        )

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.isNotEmpty())

        val rootComment = records.firstOrNull { it.platformId == "douban_comment_25663425" }
        assertNotNull(rootComment)
        assertEquals("douban_forum_topic_616438845", rootComment!!.parentId)

        val refComment = records.firstOrNull { it.platformId == "douban_comment_26034553" }
        assertNotNull(refComment)
        assertEquals("douban_comment_25663425", refComment!!.parentId)
    }

    @Test
    fun testSubjectAuxiliaryEndpointsAreNotCapturedOrParsedAsDetails() {
        for (type in listOf("movie", "book", "music", "game", "tv", "drama", "subject")) {
            for (suffix in listOf("seasons", "modules", "credits", "rating", "recommendations", "forum_topic/forum/extra")) {
                val cleanUrl = "https://frodo.douban.com/api/v2/$type/36343491/$suffix"
                val raw = RawData(
                    url = "$cleanUrl?count=10",
                    json = """[{"id":"35926862","title":"另一季","type":"tv"}]""",
                    hostApp = "com.douban.frodo"
                )
                assertFalse(cleanUrl, DoubanSubjectUrls.shouldCapture(raw.url))
                assertFalse(cleanUrl, DoubanSubjectParser.canHandle(cleanUrl))
                assertFalse(cleanUrl, DoubanParser.canHandle(cleanUrl, raw))
                val result = ParserRegistry.parseResult(raw, cleanUrl)
                assertTrue(cleanUrl, result is ParseResult.Ignored)
                assertTrue(cleanUrl, result.consumeRaw)
                assertTrue(result.records.isEmpty())
            }
        }
    }

    @Test
    fun testSubjectCaptureRoutesKeepDetailsInterestsAndForum() {
        for (type in listOf("movie", "book", "music", "game", "tv", "drama", "subject")) {
            for (slash in listOf("", "/")) {
                val cleanUrl = "https://frodo.douban.com/api/v2/$type/36343491$slash"
                assertTrue(DoubanSubjectUrls.shouldCapture("$cleanUrl?source=timeline"))
                assertTrue(DoubanSubjectParser.canHandle(cleanUrl))
                for (suffix in listOf("hot_interests", "interests")) {
                    assertTrue(DoubanSubjectUrls.shouldCapture(
                        "https://frodo.douban.com/api/v2/$type/36343491/$suffix$slash?count=20"
                    ))
                }
            }
        }
        val forum = "https://frodo.douban.com/api/v2/subject/36343491/forum_topic/forum"
        assertTrue(DoubanSubjectUrls.shouldCapture("$forum?count=20"))
        assertTrue(DoubanSubjectParser.canHandle(forum))
        assertFalse(DoubanSubjectUrls.shouldCapture("https://notdouban.com/api/v2/tv/36343491"))
    }

    @Test
    fun testInvalidSubjectDetailsStillRetainRawData() {
        val url = "https://frodo.douban.com/api/v2/tv/36343491"
        for (json in listOf("[]", "{broken")) {
            val raw = RawData(url = url, json = json, hostApp = "com.douban.frodo")
            val result = ParserRegistry.parseResult(raw, url)
            assertTrue(result is ParseResult.Failure)
            assertFalse(result.consumeRaw)
        }
    }

    @Test
    fun testDoubanSubjectMovie() {
        val url = "https://frodo.douban.com/api/v2/movie/1292722?source=top1sug"
        val cleanUrl = "https://frodo.douban.com/api/v2/movie/1292722"
        val json = loadFixture("v2_movie.json")
        val raw = RawData(url = url, json = json, hostApp = "com.douban.frodo")

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(1, records!!.size)

        val record = records[0]
        assertEquals("douban", record.platform)
        assertEquals("subject", record.recordType)
        assertEquals("douban_movie_1292722", record.platformId)
        assertEquals("泰坦尼克号", record.title)
        assertTrue(record.content.contains("1912年4月10日"))
        assertTrue(record.extraMetadata.contains("Titanic"))
        assertTrue(record.extraMetadata.contains("詹姆斯·卡梅隆"))
        assertTrue(record.extraMetadata.contains("莱昂纳多·迪卡普里奥"))
        assertTrue(record.extraMetadata.contains("header_bg_color"))
        assertTrue(record.extraMetadata.contains("No.3 豆瓣电影Top250"))
    }

    @Test
    fun testDoubanSubjectMovieRealUrlAndMerge() {
        val realUrl = "https://frodo.douban.com/api/v2/movie/1292722?source=top1sug&apikey=0dad551ec0f84ed02907ff5c42e8ec70&channel=OPPO_Market&udid=db87f91e63b18b9f94b50c897f792525533f0517&os_rom=android&douban_udid=25e33fbb4f6ecfb0d41a8c29e82dd0879c05305f&timezone=America%2FNew_York&_sig=xOyZJj5e8s3m2ijNQh9rxSR0j1A%3D&_ts=1794343170"
        val cleanUrl = if (realUrl.contains("?")) realUrl.substringBefore("?") else realUrl
        val json = loadFixture("v2_movie.json")
        val raw = RawData(url = realUrl, json = json, hostApp = "com.douban.frodo")

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(1, records!!.size)
        val rec = records[0]
        assertTrue(rec.extraMetadata.contains("rating_value"))
        assertTrue(rec.extraMetadata.contains("intro"))

        // 测试 mergeSubjectExtra 保持评分和演职员信息不被空壳覆盖
        val emptyExtra = "{\"subject_id\":\"1292722\",\"subject_title\":\"泰坦尼克号\"}"
        val merged = DoubanRelations.mergeSubjectExtra(rec.extraMetadata, emptyExtra)
        assertTrue(merged.contains("rating_value"))
        assertTrue(merged.contains("詹姆斯·卡梅隆"))

        // 空壳条目没有简介或副标题时，content 不应回退填充标题，应为空字符串以避免覆盖有效简介
        val emptySubjectObj = com.google.gson.JsonObject().apply {
            addProperty("id", "1292722")
            addProperty("title", "泰坦尼克号")
        }
        val emptyShell = DoubanSubjectParser.parseSubjectFromObject(emptySubjectObj, raw, cleanUrl)
        assertNotNull(emptyShell)
        assertEquals("", emptyShell!!.content)
    }

    @Test
    fun testDoubanSubjectBook() {
        val url = "https://frodo.douban.com/api/v2/book/1007305?event_source=search"
        val cleanUrl = "https://frodo.douban.com/api/v2/book/1007305"
        val json = loadFixture("v2_book.json")
        val raw = RawData(url = url, json = json, hostApp = "com.douban.frodo")

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(1, records!!.size)

        val record = records[0]
        assertEquals("douban", record.platform)
        assertEquals("subject", record.recordType)
        assertEquals("douban_book_1007305", record.platformId)
        assertEquals("红楼梦", record.title)
        assertTrue(record.content.contains("红楼梦"))
        assertTrue(record.extraMetadata.contains("曹雪芹"))
        assertTrue(record.extraMetadata.contains("人民文学出版社"))
    }

    @Test
    fun testDoubanSubjectTv() {
        val url = "https://frodo.douban.com/api/v2/tv/2373195?source=top1sug"
        val cleanUrl = "https://frodo.douban.com/api/v2/tv/2373195"
        val json = loadFixture("v2_tv.json")
        val raw = RawData(url = url, json = json, hostApp = "com.douban.frodo")

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(1, records!!.size)

        val record = records[0]
        assertEquals("douban", record.platform)
        assertEquals("subject", record.recordType)
        assertEquals("douban_tv_2373195", record.platformId)
        assertEquals("绝命毒师 第一季", record.title)
        assertTrue(record.content.contains("沃尔特·H·怀特"))
        assertTrue(record.extraMetadata.contains("Breaking Bad"))
        assertTrue(record.extraMetadata.contains("布莱恩·克兰斯顿"))
    }

    @Test
    fun testDoubanSubjectGame() {
        val url = "https://frodo.douban.com/api/v2/game/26817171?event_source=search"
        val cleanUrl = "https://frodo.douban.com/api/v2/game/26817171"
        val json = loadFixture("v2_game.json")
        val raw = RawData(url = url, json = json, hostApp = "com.douban.frodo")

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(1, records!!.size)

        val record = records[0]
        assertEquals("douban", record.platform)
        assertEquals("subject", record.recordType)
        assertEquals("douban_game_26817171", record.platformId)
        assertTrue(record.title.contains("塞尔达传说"))
        assertTrue(record.extraMetadata.contains("Nintendo Switch"))
        assertTrue(record.extraMetadata.contains("Nintendo EAD"))
    }

    @Test
    fun testDoubanSubjectMusic() {
        val url = "https://frodo.douban.com/api/v2/music/35282427?source=top1sug"
        val cleanUrl = "https://frodo.douban.com/api/v2/music/35282427"
        val json = loadFixture("v2_music.json")
        val raw = RawData(url = url, json = json, hostApp = "com.douban.frodo")

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(1, records!!.size)

        val record = records[0]
        assertEquals("douban", record.platform)
        assertEquals("subject", record.recordType)
        assertEquals("douban_music_35282427", record.platformId)
        assertEquals("One Last Kiss", record.title)
        assertTrue(record.content.contains("宇多田光"))
        assertTrue(record.extraMetadata.contains("宇多田ヒカル"))
    }

    @Test
    fun testDoubanSubjectDrama() {
        val url = "https://frodo.douban.com/api/v2/drama/10944557?event_source=search"
        val cleanUrl = "https://frodo.douban.com/api/v2/drama/10944557"
        val json = loadFixture("v2_drama.json")
        val raw = RawData(url = url, json = json, hostApp = "com.douban.frodo")

        assertTrue(DoubanParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertEquals(1, records!!.size)

        val record = records[0]
        assertEquals("douban", record.platform)
        assertEquals("subject", record.recordType)
        assertEquals("douban_drama_10944557", record.platformId)
        assertEquals("剧院魅影", record.title)
        assertTrue(record.content.contains("巴黎歌剧院"))
        assertTrue(record.extraMetadata.contains("哈罗德·普林斯"))
    }
}
