package com.melonhu.indexrecord.parser.x

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XConversationTimelineTest {
    private fun tweet(id: String, parent: String? = null, extra: String = ""): String = """
        {
          "__typename": "Tweet",
          "rest_id": "$id",
          "details": {"full_text": "Tweet $id"},
          "core": {"user_results": {"result": {"rest_id": "author", "core": {"name": "Author", "screen_name": "author"}}}}
          ${if (parent != null) ",\"reply_to_results\": {\"rest_id\": \"$parent\"}" else ""}
          $extra
        }
    """.trimIndent()

    private fun parse(entries: String, operation: String = "ConversationTimeline") = XTimelineParser.parse(
        RawData(
            url = "https://api.x.com/graphql/id/$operation",
            hostApp = "com.twitter.android",
            json = """{"data":{"timeline_response":{"instructions":[{"type":"TimelineAddEntries","entries":[$entries]}]}}}"""
        )
    )

    @Test
    fun keepsMainTweetAndNestedRepliesButNotAdditionalModules() {
        val records = parse("""
            {"entryId":"tweet-100","content":{"content":{"tweet_results":{"result":${tweet("100")}}}}},
            {"entryId":"conversationthread-101","content":{"items":[
              {"entryId":"conversationthread-101-tweet-101","item":{"content":{"tweet_results":{"result":${tweet("101", "100")}}}}},
              {"entryId":"conversationthread-101-tweet-102","item":{"content":{"tweet_results":{"result":${tweet("102", "101")}}}}}
            ]}},
            {"entryId":"tweetdetailrelatedtweets-200","content":{"items":[
              {"item":{"content":{"tweet_results":{"result":${tweet("200")}}}}},
              {"item":{"content":{"tweet_results":{"result":${tweet("201", "200")}}}}}
            ]}},
            {"entryId":"other-recommendations","content":{"tweet_results":{"result":${tweet("300")}}}}
        """)
        assertEquals(listOf("twitter_tweet_100", "twitter_comment_101", "twitter_comment_102"), records.map { it.platformId })
        val replyMeta = JsonParser.parseString(records[2].extraMetadata).asJsonObject
        assertEquals("101", replyMeta.get("in_reply_to_status_id").asString)
    }

    @Test
    fun skipsSnakeCaseRelatedTweetsAndDiscoverMore() {
        val records = parse("""
            {"entry_id":"tweet-100","content":{"content":{"tweet_results":{"result":${tweet("100")}}}}},
            {"entry_id":"conversationthread-101","content":{"items":[
              {"entry_id":"conversationthread-101-tweet-101","item":{"content":{"tweet_results":{"result":${tweet("101", "100")}}}}}
            ]}},
            {"entry_id":"tweetdetailrelatedtweets-200","content":{"items":[
              {"item":{"content":{"tweet_results":{"result":${tweet("200")}}}}}
            ]}},
            {"entry_id":"tweet-300","content":{"client_event_info":{"details":{"conversation_details":{"conversation_section":"RelatedTweet"}}},"tweet_results":{"result":${tweet("300")}}}},
            {"entry_id":"tweet-400","content":{"client_event_info":{"component":"related_tweet"},"tweet_results":{"result":${tweet("400")}}}}
        """)
        assertEquals(listOf("twitter_tweet_100", "twitter_comment_101"), records.map { it.platformId })
    }

    @Test
    fun skipsRelatedSectionAndPromotedEntriesEvenWithTweetEntryIds() {
        val records = parse("""
            {"entryId":"tweet-100","content":{"tweet_results":{"result":${tweet("100")}}}},
            {"entryId":"tweet-200","content":{"clientEventInfo":{"component":"related_tweet"},"tweet_results":{"result":${tweet("200")}}}},
            {"entryId":"tweet-201","content":{"clientEventInfo":{"details":{"conversationDetails":{"conversationSection":"RelatedTweet"}}},"tweet_results":{"result":${tweet("201")}}}},
            {"entryId":"tweet-300","content":{"promotedMetadata":{},"tweet_results":{"result":${tweet("300")}}}}
        """)
        assertEquals(listOf("twitter_tweet_100"), records.map { it.platformId })
    }

    @Test
    fun quoteStaysInMainContentWithoutCreatingAdditionalRecords() {
        val extra = ",\"quoted_tweet_results\": {\"result\": ${tweet("200")}}"
        val records = parse("""{"entryId":"tweet-100","content":{"tweet_results":{"result":${tweet("100", extra = extra)}}}}""")
        assertEquals(listOf("twitter_tweet_100"), records.map { it.platformId })
        assertTrue(records.single().content.contains("Tweet 200"))
    }

    @Test
    fun homeTimelineStillParsesAllFeedEntries() {
        val records = parse("""
            {"entryId":"tweet-100","content":{"tweet_results":{"result":${tweet("100")}}}},
            {"entryId":"other-module","content":{"tweet_results":{"result":${tweet("200")}}}}
        """, operation = "HomeTimeline")
        assertEquals(listOf("twitter_tweet_100", "twitter_tweet_200"), records.map { it.platformId })
    }
}
