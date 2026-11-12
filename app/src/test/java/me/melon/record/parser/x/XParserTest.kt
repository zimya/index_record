package me.melon.record.parser.x

import com.google.gson.JsonParser
import me.melon.record.data.model.RawData
import me.melon.record.parser.ParserRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XParserTest {

    private fun loadFixture(filename: String): String {
        val candidates = listOf(
            File("../research/x", filename),
            File("research/x", filename),
            File("../../research/x", filename)
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Fixture file $filename not found in candidates: $candidates")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun testHomeTimeline() {
        val url = "https://api.x.com/graphql/ReFu1Zs_f4GRKnj0FeW9Rg/HomeTimeline"
        val cleanUrl = url
        val json = loadFixture("graphql_HomeTimeline.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.twitter.android"
        )

        assertTrue(XParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        // 本次手机响应包含 28 个非广告时间线条目。
        assertTrue(records!!.size >= 28)
        listOf("2116264863253368881", "2120268439897911489", "2120278509843062807",
            "2121002344964108362", "2107528206568006125", "2119699087402692874",
            "2110446548211700221").forEach { adId ->
            assertFalse(records.any { it.platformId == "twitter_tweet_$adId" })
        }

        // 验证其中包含原创推文
        val tweet = records.firstOrNull { it.recordType == "tweet" && it.platformId == "twitter_tweet_2120929354549166490" }
        assertNotNull(tweet)
        assertEquals("twitter", tweet!!.platform)
        assertEquals("com.twitter.android", tweet.sourceApp)
        assertEquals("Aurora", tweet.authorName)
        assertTrue(tweet.content.contains("Gemini 4 Hydrogen 已经上线 antigravity"))
        assertEquals(1794503937000L, tweet.publishTime)

        // 验证元数据
        val meta = JsonParser.parseString(tweet.extraMetadata).asJsonObject
        assertEquals("2120929354549166490", meta.get("tweet_id").asString)
        assertEquals("Aurora0x3f", meta.get("screen_name").asString)
        assertEquals(198L, meta.get("voteup_count").asLong)
    }

    @Test
    fun testConversationTimelineWithReplies() {
        val url = "https://api.x.com/graphql/SNs4kye4lnlklqpFC6SCtw/ConversationTimeline"
        val cleanUrl = url
        // 来自 Android 客户端缓存 bin 的真实详情响应，包含两级回复和广告。
        val json = loadFixture("graphql_ConversationTimeline.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.twitter.android"
        )

        assertTrue(XParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.isNotEmpty())

        val focalTweet = records.single { it.platformId == "twitter_tweet_2120806999126626618" }
        assertEquals("tweet", focalTweet.recordType)
        assertEquals("", focalTweet.parentId)
        assertTrue(focalTweet.content.contains("世界真是个巨大的草台班子"))
        assertEquals("码农暖爸", focalTweet.authorName)
        assertEquals(1794474765000L, focalTweet.publishTime)

        val focalMeta = JsonParser.parseString(focalTweet.extraMetadata).asJsonObject
        assertEquals("Delroy715", focalMeta.get("screen_name").asString)
        assertEquals(238L, focalMeta.get("voteup_count").asLong)
        assertEquals(28L, focalMeta.get("comment_count").asLong)

        val replies = records.filter { it.recordType == "comment" }
        val topReply = replies.single { it.platformId == "twitter_comment_2120807002012270856" }
        assertEquals(focalTweet.platformId, topReply.parentId)
        assertTrue(topReply.content.contains("1/ 抓包实录"))
        assertEquals("码农暖爸", topReply.authorName)

        val subReply = replies.single { it.platformId == "twitter_comment_2120807004545683677" }
        // 此现代响应没有 legacy.conversation_id_str；解析阶段暂用 tweet 前缀，
        // 入库后的 XRelations 再按已存在的父评论校正。这里验证原始父 ID 未丢失。
        assertEquals(topReply.platformId.replace("twitter_comment_", "twitter_tweet_"), subReply.parentId)
        assertTrue(subReply.content.contains("2/ 而在 SSE 流式模式"))

        // 实际响应中的 promoted conversationthread 不能成为推文或评论。
        val adIds = setOf("2082948371141288050", "2111180223446143055", "2120991413378625835", "2120120527352205628")
        assertFalse(records.any { it.platformId.removePrefix("twitter_tweet_").removePrefix("twitter_comment_") in adIds })
    }

    @Test
    fun testSearchTimeline() {
        val url = "https://api.x.com/graphql/z8S7aXB4CGiCyFQP255TeQ/SearchTimelineQuery"
        val cleanUrl = url
        val json = loadFixture("graphql_SearchTimelineQuery.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.twitter.android"
        )

        assertTrue(XParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size >= 20)
        val tweet = records.single { it.platformId == "twitter_tweet_1861072804239847914" }
                assertEquals("Ben Dicken", tweet.authorName)
                assertTrue(tweet.content.contains("Java and Kotlin are quick!"))
                assertEquals(1732549308000L, tweet.publishTime)
    }

    @Test
    fun testBookmarks() {
        val url = "https://api.x.com/graphql/OaASvY3iNBBU8r1ENpxUxQ/BookmarksTimelineQuery"
        val cleanUrl = url
        val json = loadFixture("graphql_BookmarksTimelineQuery.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.twitter.android"
        )

        assertTrue(XParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size >= 15)
        val bookmark = records.single { it.platformId == "twitter_tweet_2120503022824616232" }
        assertEquals("lifcc", bookmark.authorName)
        assertTrue(bookmark.content.contains("最近疯传的 X GIF 保存方法"))
        assertEquals(1794402292000L, bookmark.publishTime)
        for (rec in records) {
            assertEquals("twitter", rec.platform)
            assertTrue(rec.authorName.isNotBlank())
            assertTrue(rec.content.isNotBlank())
        }
    }

    @Test
    fun testLikes() {
        val url = "https://api.x.com/graphql/POnlJt30N6mI4bX4E9nkSA/UserProfileFavoritesTimelineQuery"
        val cleanUrl = url
        val json = loadFixture("graphql_UserProfileFavoritesTimelineQuery.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.twitter.android"
        )

        assertTrue(XParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size >= 15)
        val liked = records.single { it.platformId == "twitter_comment_2117281617186017693" }
        assertEquals("Yuhang Shen", liked.authorName)
        assertTrue(liked.content.contains("8.2分在影视圈已经是很高的分数了"))
        assertEquals(1793634249000L, liked.publishTime)
    }

    @Test
    fun testModernAndroidTweetSchema() {
        val json = """
        {
          "data": {
            "home": {
              "home_timeline_urt": {
                "instructions": [
                  {
                    "type": "TimelineAddEntries",
                    "entries": [
                      {
                        "entryId": "tweet-2120313557405331788",
                        "content": {
                          "entryType": "TimelineTimelineItem",
                          "content": {
                            "tweet_results": {
                              "result": {
                                "__typename": "Tweet",
                                "rest_id": "2120313557405331788",
                                "core": {
                                  "user_results": {
                                    "result": {
                                      "__typename": "User",
                                      "rest_id": "1486017329662423044",
                                      "core": {
                                        "name": "lifcc",
                                        "screen_name": "mylifcc"
                                      },
                                      "avatar": {
                                        "image_url": "https://pbs.twimg.com/profile_images/2034239213487681536/2gjQXlXx_normal.jpg"
                                      }
                                    }
                                  }
                                },
                                "counts": {
                                  "bookmark_count": 4,
                                  "favorite_count": 21,
                                  "quote_count": 0,
                                  "reply_count": 10,
                                  "retweet_count": 2
                                },
                                "details": {
                                  "created_at_ms": 1794357120000,
                                  "display_text_range": [0, 15],
                                  "full_text": "what can I say？ https://t.co/JQBcA1Erfh"
                                },
                                "media_entities": [
                                  {
                                    "url": "https://t.co/JQBcA1Erfh",
                                    "media_results": {
                                      "result": {
                                        "__typename": "ApiMedia",
                                        "media_info": {
                                          "__typename": "ApiImage",
                                          "original_img_url": "https://pbs.twimg.com/media/HWx13TDaYAA5jPD.jpg"
                                        }
                                      }
                                    }
                                  }
                                ],
                                "views": {
                                  "count": "7801"
                                }
                              }
                            }
                          }
                        }
                      },
                      {
                        "entryId": "empty-stub-tweet",
                        "content": {
                          "entryType": "TimelineTimelineItem",
                          "content": {
                            "tweet_results": {
                              "result": {
                                "__typename": "Tweet",
                                "rest_id": "9999999999999999999",
                                "legacy": {}
                              }
                            }
                          }
                        }
                      }
                    ]
                  }
                ]
              }
            }
          }
        }
        """.trimIndent()

        val raw = RawData(
            url = "https://api.x.com/graphql/KmJCeZ__ef1fNos7tKN_fg/HomeTimeline",
            json = json,
            hostApp = "com.twitter.android"
        )
        val cleanUrl = "https://api.x.com/graphql/KmJCeZ__ef1fNos7tKN_fg/HomeTimeline"

        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        // 应该只有1条有效推文，空推文 stub 应该被过滤掉
        assertEquals(1, records!!.size)

        val record = records[0]
        assertEquals("twitter_tweet_2120313557405331788", record.platformId)
        assertEquals("lifcc", record.authorName)
        assertEquals("what can I say？", record.title)
        assertTrue(record.content.contains("what can I say？"))
        assertTrue(record.content.contains("![图片](https://pbs.twimg.com/media/HWx13TDaYAA5jPD.jpg)"))
        assertEquals(1794357120000L, record.publishTime)

        val meta = JsonParser.parseString(record.extraMetadata).asJsonObject
        assertEquals(21L, meta.get("like_count").asLong)
        assertEquals(2L, meta.get("retweet_count").asLong)
        assertEquals(10L, meta.get("reply_count").asLong)
        assertEquals(4L, meta.get("bookmark_count").asLong)
        assertEquals(7801L, meta.get("views_count").asLong)
        assertEquals("https://pbs.twimg.com/media/HWx13TDaYAA5jPD.jpg", meta.get("title_image").asString)
    }

    @Test
    fun testCannotHandleOtherPlatforms() {
        val zhihuRaw = RawData(
            url = "https://api.zhihu.com/articles/123",
            json = "{}",
            hostApp = "com.zhihu.android"
        )
        assertFalse(XParser.canHandle("https://api.zhihu.com/articles/123", zhihuRaw))
    }
}
