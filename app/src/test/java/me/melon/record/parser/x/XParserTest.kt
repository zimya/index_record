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
        val url = "https://x.com/i/api/graphql/KmJCeZ__ef1fNos7tKN_fg/HomeTimeline"
        val cleanUrl = "https://x.com/i/api/graphql/KmJCeZ__ef1fNos7tKN_fg/HomeTimeline"
        val json = loadFixture("graphql_HomeTimeline.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.twitter.android"
        )

        assertTrue(XParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size >= 30)
        listOf("2110812392867193288", "2113645476633280797", "2117665231065239830").forEach { adId ->
            assertFalse(records.any { it.platformId == "twitter_tweet_$adId" })
        }

        // 验证其中包含原创推文
        val tweet = records.firstOrNull { it.recordType == "tweet" && it.platformId == "twitter_tweet_2120191790774960630" }
        assertNotNull(tweet)
        assertEquals("twitter", tweet!!.platform)
        assertEquals("com.twitter.android", tweet.sourceApp)
        assertTrue(tweet.authorName.isNotBlank())
        assertTrue(tweet.content.isNotBlank())
        assertTrue(tweet.publishTime > 0)

        // 验证元数据
        val meta = JsonParser.parseString(tweet.extraMetadata).asJsonObject
        assertTrue(meta.has("tweet_id"))
        assertTrue(meta.has("screen_name"))
        assertTrue(meta.has("voteup_count"))
    }

    @Test
    fun testConversationTimelineWithReplies() {
        val url = "https://api.x.com/graphql/SNs4kye4lnlklqpFC6SCtw/ConversationTimeline"
        val cleanUrl = url
        // 复用浏览器端 JSON 样本测试通用推文结构；这不是手机端响应样本。
        val json = loadFixture("graphql_tweetDetail.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.twitter.android"
        )

        assertTrue(XParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.isNotEmpty())

        // 验证 Focal Tweet (原创推文)
        val focalTweet = records.firstOrNull { it.platformId == "twitter_tweet_2120100463588917395" }
        assertNotNull(focalTweet)
        assertEquals("tweet", focalTweet!!.recordType)
        assertEquals("", focalTweet.parentId)
        assertTrue(focalTweet.content.contains("Gemini 4 carbon"))
        assertEquals("Hope C 陈刀仔", focalTweet.authorName)

        val focalMeta = JsonParser.parseString(focalTweet.extraMetadata).asJsonObject
        assertEquals("EdwinCh43136084", focalMeta.get("screen_name").asString)
        assertEquals(71L, focalMeta.get("voteup_count").asLong)
        assertEquals(10L, focalMeta.get("comment_count").asLong)

        // 验证回复评论 (Replies)
        val replies = records.filter { it.recordType == "comment" }
        assertTrue(replies.isNotEmpty())

        // 一级回复：回复给 focal tweet
        val topReply = replies.firstOrNull { it.platformId == "twitter_comment_2120145328502628821" }
        assertNotNull(topReply)
        assertEquals("twitter_tweet_2120100463588917395", topReply!!.parentId)
        assertTrue(topReply.content.contains("下个月价钱都要翻倍了"))
        assertEquals("Unicorn", topReply.authorName)

        // 二级回复：回复给一级回复
        val subReply = replies.firstOrNull { it.platformId == "twitter_comment_2120148488252260365" }
        assertNotNull(subReply)
        assertEquals("twitter_comment_2120145328502628821", subReply!!.parentId)
        assertTrue(subReply.content.contains("下个月Argon应该没人用了"))

        // 详情底部的附加推荐模块不应生成记录。
        assertFalse(records.any { it.platformId == "twitter_tweet_2120045931299316212" })
    }

    @Test
    fun testSearchTimeline() {
        val url = "https://x.com/i/api/graphql/vLHjqbDv7TPFMBkIIO1nOA/SearchTimeline?variables=%7B%22rawQuery%22%3A%22%5C%22Kirk%20Cousins%5C%22%22%7D"
        val cleanUrl = "https://x.com/i/api/graphql/vLHjqbDv7TPFMBkIIO1nOA/SearchTimeline"
        val json = loadFixture("graphql_SearchTimeline.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.twitter.android"
        )

        assertTrue(XParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size >= 20)
        assertTrue(records.any { it.platformId.startsWith("twitter_tweet_") })
    }

    @Test
    fun testBookmarks() {
        val url = "https://x.com/i/api/graphql/fjf5b5Lu82lYMSBRhppK2g/Bookmarks?variables=%7B%22count%22%3A20%7D"
        val cleanUrl = "https://x.com/i/api/graphql/fjf5b5Lu82lYMSBRhppK2g/Bookmarks"
        val json = loadFixture("graphql_Bookmarks.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.twitter.android"
        )

        assertTrue(XParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size >= 15)
        for (rec in records) {
            assertEquals("twitter", rec.platform)
            assertTrue(rec.authorName.isNotBlank())
            assertTrue(rec.content.isNotBlank())
        }
    }

    @Test
    fun testLikes() {
        val url = "https://x.com/i/api/graphql/ZjyvI1V9gajq5eclHCObzA/Likes?variables=%7B%22userId%22%3A%221227809033110769664%22%7D"
        val cleanUrl = "https://x.com/i/api/graphql/ZjyvI1V9gajq5eclHCObzA/Likes"
        val json = loadFixture("graphql_Likes.json")

        val raw = RawData(
            url = url,
            json = json,
            hostApp = "com.twitter.android"
        )

        assertTrue(XParser.canHandle(cleanUrl, raw))
        val records = ParserRegistry.parse(raw, cleanUrl)
        assertNotNull(records)
        assertTrue(records!!.size >= 15)
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
