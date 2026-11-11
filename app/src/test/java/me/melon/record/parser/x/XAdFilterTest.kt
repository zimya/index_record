package me.melon.record.parser.x

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import me.melon.record.data.model.RawData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class XAdFilterTest {
    private fun tweet(id: String) = JsonObject().apply {
        addProperty("__typename", "Tweet")
        addProperty("rest_id", id)
        add("details", JsonObject().apply { addProperty("full_text", "Tweet $id") })
    }

    private fun results(result: JsonObject) = JsonObject().apply { add("result", result) }

    private fun entry(id: String, result: JsonObject = tweet(id)) = JsonObject().apply {
        addProperty("entryId", "tweet-$id")
        add("content", JsonObject().apply {
            add("itemContent", JsonObject().apply { add("tweet_results", results(result)) })
        })
    }

    private fun parse(operation: String, entries: List<JsonObject>) = XTimelineParser.parse(
        RawData(
            url = "https://api.x.com/graphql/id/$operation",
            hostApp = "com.twitter.android",
            json = JsonObject().apply {
                add("entries", JsonArray().apply { entries.forEach { add(it) } })
            }.toString()
        )
    )

    @Test
    fun filtersPromotedEntriesInEveryTimelineWithoutLeakingRelatedTweets() {
        val adTweet = tweet("200").apply {
            add("quoted_tweet_results", results(tweet("201")))
            add("retweeted_tweet_results", results(tweet("202")))
        }
        val ad = entry("200", adTweet).apply {
            getAsJsonObject("content").getAsJsonObject("itemContent")
                .add("promotedMetadata", JsonObject())
        }
        listOf("ConversationTimeline", "HomeTimeline", "HomeLatestTimeline", "SearchTimeline", "Bookmarks", "Likes", "UserTweets")
            .forEach { operation ->
                assertEquals(operation, listOf("twitter_tweet_100"),
                    parse(operation, listOf(entry("100"), ad)).map { it.platformId })
            }
    }

    @Test
    fun recognizesEntryIdsEventMarkersAndMetadataAtDifferentLevels() {
        val variants = listOf(
            entry("200").apply { addProperty("entryId", "promoted-tweet-200-impression") },
            entry("200").apply { add("promoted_metadata", JsonObject()) },
            entry("200").apply {
                getAsJsonObject("content").add("clientEventInfo", JsonObject().apply {
                    addProperty("component", "for_you_promoted")
                })
            },
            entry("200").apply {
                getAsJsonObject("content").add("clientEventInfo", JsonObject().apply {
                    add("details", JsonObject().apply {
                        add("timelinesDetails", JsonObject().apply { addProperty("injectionType", "ForYouPromoted") })
                    })
                })
            },
            entry("200", tweet("200").apply { add("promotedMetadata", JsonObject()) }),
            entry("200", JsonObject().apply {
                addProperty("__typename", "TweetWithVisibilityResults")
                add("tweet", tweet("200").apply { add("promoted_metadata", JsonObject()) })
            })
        )
        variants.forEachIndexed { index, ad ->
            assertEquals("variant $index", listOf("twitter_tweet_100"),
                parse("HomeTimeline", listOf(entry("100"), ad)).map { it.platformId })
        }
    }

    @Test
    fun recursiveQuoteAndRetweetParsingAlsoRejectsPromotedTweets() {
        val ad = tweet("200").apply { add("promotedMetadata", JsonObject()) }
        val raw = RawData(url = "https://api.x.com/graphql/id/HomeTimeline", json = "{}")
        assertNull(XTweetParser.parseTweet(ad, raw))
        listOf("quoted_tweet_results", "retweeted_tweet_results").forEach { key ->
            val ordinary = tweet("100").apply { add(key, results(ad)) }
            val records = parse("HomeTimeline", listOf(entry("100", ordinary)))
            assertEquals(listOf("twitter_tweet_100"), records.map { it.platformId })
            assertFalse(records.single().content.contains("Tweet 200"))
        }
    }

    @Test
    fun nullMetadataAndAdWordsInBodyDoNotFilterOrdinaryTweets() {
        val ordinary = tweet("100").apply {
            add("promotedMetadata", JsonNull.INSTANCE)
            getAsJsonObject("details").addProperty("full_text", "Discussing promoted tweets and advertising")
        }
        assertEquals(listOf("twitter_tweet_100"),
            parse("HomeTimeline", listOf(entry("100", ordinary))).map { it.platformId })
    }
}
