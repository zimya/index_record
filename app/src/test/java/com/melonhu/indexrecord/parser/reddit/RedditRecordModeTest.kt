package com.melonhu.indexrecord.parser.reddit

import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.settings.RedditRecordMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RedditRecordModeTest {
    private val url = "https://gql-fed.reddit.com/"
    private fun fixture(name: String) = javaClass.getResource("/reddit/$name.json")!!.readText()
    private fun raw(json: String) = RawData(url = url, hostApp = RedditParser.HOST_APP, json = json)
    private fun read(json: String, ids: Set<String> = RedditParser.readPostIds(raw(json))) =
        RedditParser.parseWithAdvertisingIds(raw(json), url, emptySet(), ids)

    @Test fun missingAndInvalidSettingsDefaultToRead() {
        for (value in listOf(null, "", "unknown", 0, 1)) {
            assertEquals(RedditRecordMode.READ, RedditRecordMode.fromStoredValue(value))
        }
        assertEquals(RedditRecordMode.PUSH, RedditRecordMode.fromStoredValue("PUSH"))
        assertEquals(RedditRecordMode.READ, RedditRecordMode.fromStoredValue("READ"))
    }

    @Test fun feedAndBulkHydrationDoNotEstablishReadVisits() {
        for (name in listOf("home", "posts")) {
            assertTrue(RedditParser.readPostIds(raw(fixture(name))).isEmpty())
            assertTrue(read(fixture(name)).isEmpty())
            assertTrue(RedditParser.parse(raw(fixture(name)), url).isNotEmpty())
        }
        val post = JSONObject(fixture("posts")).getJSONObject("response").getJSONObject("data")
            .getJSONArray("postsInfoByIds").getJSONObject(0)
        val detailWithoutForest = JSONObject().put("data", JSONObject().put("postInfoById", post)).toString()
        assertTrue(read(detailWithoutForest).isEmpty())
    }

    @Test fun detailRetainsPostAndAllCapturedCommentsAndOnlyItsHydratedBody() {
        val ids = RedditParser.readPostIds(raw(fixture("thread")))
        assertEquals(setOf("reddit_post_1xr0ctd"), ids)
        assertEquals(11, read(fixture("thread")).size)
        val hydrated = read(fixture("posts"), ids)
        assertEquals(listOf("reddit_post_1xr0ctd"), hydrated.map { it.platformId })
        assertTrue(hydrated.single().content.contains("preview.redd.it"))
    }

    @Test fun mixedBatchDoesNotAdmitUnopenedPosts() {
        val response = JSONArray().put(JSONObject(fixture("posts")).getJSONObject("response"))
            .put(JSONObject(fixture("thread")).getJSONObject("response"))
        val json = JSONObject().put("response", response).put("request", JSONArray().put(JSONObject.NULL).put(JSONObject.NULL)).toString()
        val records = read(json)
        assertEquals(11, records.size)
        assertFalse(records.any { it.platformId == "reddit_post_1xouqzu" })
    }

    @Test fun detailEvidenceSurvivesRepeatedHookProjectionAndArrayAliases() {
        val post = JSONObject().put("__typename", "SubredditPost").put("id", "t3_empty")
            .put("commentForest", JSONObject().put("trees", JSONArray()))
        var envelope = JSONObject().put("response", JSONObject().put("data", JSONObject()
            .put("aliasedPosts", JSONArray().put(post))))
        repeat(2) {
            envelope = requireNotNull(RedditCaptureFilter.envelope(envelope.opt("response"), envelope.opt("request"), linkedSetOf()))
            assertEquals(setOf("reddit_post_empty"), RedditParser.readPostIds(raw(envelope.toString())))
        }
        post.put("isPromoted", true)
        val ad = JSONObject().put("data", JSONObject().put("postInfoById", post))
        assertTrue(RedditParser.readPostIds(raw(ad.toString())).isEmpty())
    }

    @Test fun emptyForestAndBatchedAliasedForestUseDetailContext() {
        val empty = JSONObject().put("data", JSONObject().put("postInfoById", JSONObject()
            .put("id", "t3_empty").put("commentForest", JSONObject().put("trees", JSONArray()))))
        assertEquals(setOf("reddit_post_empty"), RedditParser.readPostIds(raw(empty.toString())))
        val forest = JSONObject().put("data", JSONObject().put("comments", JSONObject()
            .put("__typename", "CommentForest").put("trees", JSONArray())))
        val json = JSONObject().put("response", JSONArray().put(JSONObject().put("data", JSONObject())).put(forest))
            .put("request", JSONArray().put(JSONObject().put("variables", JSONObject().put("postId", "t3_wrong")))
                .put(JSONObject().put("variables", JSONObject().put("postId", "t3_right"))))
        assertEquals(setOf("reddit_post_right"), RedditParser.readPostIds(raw(json.toString())))
        assertTrue(RedditParser.readPostIds(raw("not json")).isEmpty())
    }
}
