package com.melonhu.indexrecord.parser.reddit

import com.google.gson.JsonParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RedditCaptureFilterTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/reddit/$name.json")!!.readText())
    private fun filter(response: Any, request: Any? = null, ads: MutableSet<String> = linkedSetOf()) =
        RedditCaptureFilter.envelope(response, request, ads)

    @Test fun configurationIdentityHeartbeatAndAdvertisingOnlyResponsesNeverEnterQueue() {
        for (key in listOf("performEventingHealthCheck", "exposeExperimentBatch", "dynamicConfigsByNames", "identity", "registerMobilePushToken", "badgeIndicators")) {
            assertNull(key, filter(JSONObject().put("data", JSONObject().put(key, JSONObject().put("id", "t3_not_a_post")))))
        }
        val ad = fixture("posts").getJSONObject("response").getJSONObject("data").getJSONArray("postsInfoByIds").getJSONObject(1)
        assertFalse(ad.getBoolean("isCommercialCommunication"))
        assertTrue(RedditCaptureFilter.isAd(ad))
        assertNull(filter(JSONObject().put("data", JSONObject().put("postInfoById", ad))))
    }

    @Test fun cellGroupContextBlocksSparseAdDetailsButNotFullOrganicProfilePosts() {
        val ads = linkedSetOf<String>()
        val feed = filter(fixture("home").getJSONObject("response"), ads = ads)!!
        assertEquals(setOf("t3_1x0zpmg"), ads)
        val edges = feed.getJSONObject("response").getJSONObject("data").getJSONObject("homeV3").getJSONObject("elements").getJSONArray("edges")
        assertEquals(1, edges.length())
        val sparse = JSONObject().put("__typename", "ProfilePost").put("id", "t3_1x0zpmg").put("title", "ad title").put("score", 0)
        assertNull(filter(JSONObject().put("data", JSONObject().put("postInfoById", sparse)), ads = ads))
        val organic = JSONObject().put("__typename", "ProfilePost").put("id", "t3_organic1")
            .put("title", "An ordinary profile post").put("authorInfo", JSONObject().put("name", "TradeStation"))
            .put("isCommercialCommunication", true).put("content", JSONObject().put("markdown", "Public text"))
        assertFalse(RedditCaptureFilter.isAd(organic))
        assertNotNull(filter(JSONObject().put("data", JSONObject().put("postInfoById", organic)), ads = ads))
    }

    @Test fun embeddedCommentAdsDoNotPoisonTheOrganicContainingPost() {
        val post = fixture("thread").getJSONObject("response").getJSONObject("data").getJSONObject("postInfoById")
        post.put("pdpCommentsAds", JSONObject().put("adPosts", JSONArray().put(JSONObject()
            .put("__typename", "AdPost").put("id", "t3_advert1").put("postTitle", "ad"))))
        val ads = linkedSetOf<String>()
        val filtered = filter(JSONObject().put("data", JSONObject().put("postInfoById", post)), ads = ads)!!
        assertEquals(setOf("t3_advert1"), ads)
        val kept = filtered.getJSONObject("response").getJSONObject("data").getJSONObject("postInfoById")
        assertEquals("t3_1xr0ctd", kept.getString("id"))
        assertFalse(kept.has("pdpCommentsAds"))
        assertEquals(11, kept.getJSONObject("commentForest").getJSONArray("trees").length())
    }

    @Test fun batchedQueryIndicesRemainPairedAndUnrelatedRequestMetadataIsDropped() {
        val responses = JSONArray().put(JSONObject().put("data", JSONObject().put("identity", JSONObject())))
            .put(fixture("posts").getJSONObject("response")).put(fixture("thread").getJSONObject("response"))
        val requests = JSONArray().put(JSONObject().put("operationName", "IgnoredIdentity").put("variables", JSONObject().put("token", "must-not-persist")))
            .put(JSONObject().put("operationName", "PostFixture")).put(JSONObject().put("operationName", "CommentFixture"))
        val filtered = filter(responses, requests)!!
        assertEquals(2, filtered.getJSONArray("response").length())
        assertEquals("PostFixture", filtered.getJSONArray("request").getJSONObject(0).getString("operationName"))
        assertEquals("CommentFixture", filtered.getJSONArray("request").getJSONObject(1).getString("operationName"))
        assertFalse(filtered.toString().contains("must-not-persist"))
    }

    @Test fun projectionIsIdempotentAndRemovesNonContentTracking() {
        val original = fixture("posts").getJSONObject("response")
        val post = original.getJSONObject("data").getJSONArray("postsInfoByIds").getJSONObject(0)
        post.put("llmPostSuggestions", JSONArray().put(JSONObject().put("__typename", "SubredditPost").put("id", "t3_suggestion")))
        post.getJSONObject("authorInfo").put("profileExemptedExperiments", JSONArray().put("irrelevant"))
        val once = filter(original)!!
        val twice = filter(once.getJSONObject("response"), once.opt("request"))!!
        assertEquals(JsonParser.parseString(once.toString()), JsonParser.parseString(twice.toString()))
        assertFalse(once.toString().contains("llmPostSuggestions"))
        assertFalse(once.toString().contains("profileExemptedExperiments"))
    }
}
