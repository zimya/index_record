package com.melonhu.indexrecord.parser.reddit

import com.melonhu.indexrecord.data.model.RawData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RedditReadVisitTest {
    private val url = "https://gql-fed.reddit.com/"
    private val capturedAt = 1234L
    private fun raw(json: String, time: Long = capturedAt) = RawData(
        url = url, hostApp = RedditParser.HOST_APP, json = json, timestamp = time
    )
    private fun request(operation: String? = "PostComments", id: String = "t3_post", token: String? = "visit") =
        JSONObject().apply {
            if (operation != null) put("operationName", operation)
            put("variables", JSONObject().put("id", id).apply {
                if (token != null) put("pdpConsistencyToken", token)
            })
        }
    private fun post(id: String = "t3_post") = JSONObject().put("__typename", "SubredditPost")
        .put("id", id).put("commentForest", JSONObject().put("trees", JSONArray()))
    private fun response(value: JSONObject = post(), key: String = "postInfoById") =
        JSONObject().put("data", JSONObject().put(key, value))
    private fun envelope(query: Any? = request(), value: Any = response()) =
        JSONObject().put("request", query ?: JSONObject.NULL).put("response", value)
    private fun visits(value: JSONObject) = RedditParser.readVisits(raw(value.toString()))

    @Test fun multipleCommentRequestsKeepTheSameVisitTokenAndCaptureTimes() {
        val requests = listOf(8, 0, 8).mapIndexed { index, truncate ->
            val query = request().apply {
                getJSONObject("variables").put("truncate", truncate).put("includeConsistencyToken", truncate == 8)
            }
            RedditParser.readVisits(raw(envelope(query).toString(), capturedAt + index)).single()
        }
        assertEquals(listOf("visit", "visit", "visit"), requests.map { it.token })
        assertEquals(listOf(capturedAt, capturedAt + 1, capturedAt + 2), requests.map { it.captureTime })
        assertTrue(requests.all { it.postId == "reddit_post_post" })
    }

    @Test fun reopeningTheSamePostRetainsADifferentToken() {
        val first = visits(envelope(request(token = "first"))).single()
        val second = visits(envelope(request(token = "second"))).single()
        assertEquals(first.postId, second.postId)
        assertNotEquals(first.token, second.token)
    }

    @Test fun tokenComesOnlyFromVariablesAndBlankOrNullTokensAreAbsent() {
        for (token in listOf(null, "", " \t\n")) {
            val query = request(token = token).put("pdpConsistencyToken", "wrong-root")
            assertNull(visits(envelope(query)).single().token)
        }
        val query = request().apply { getJSONObject("variables").put("pdpConsistencyToken", JSONObject.NULL) }
        assertNull(visits(envelope(query)).single().token)
    }

    @Test fun aliasedBatchStaysPairedAfterUnrelatedResponsesAreProjectedOut() {
        val forest = JSONObject().put("__typename", "CommentForest").put("trees", JSONArray())
        val responses = JSONArray()
            .put(JSONObject().put("data", JSONObject().put("identity", JSONObject())))
            .put(response(post("t3_first"), "threadAlias"))
            .put(response(forest, "commentsAlias"))
            .put(JSONObject().put("data", JSONObject().put("aliasedPosts", JSONArray().put(post("t3_third")))))
        val queries = JSONArray().put(request(id = "t3_wrong", token = "wrong"))
            .put(request(id = "t3_first", token = "first"))
            .put(request(id = "t3_second", token = "second"))
            .put(request(id = "t3_third", token = "third"))
        var value = envelope(queries, responses)
        repeat(2) {
            value = requireNotNull(RedditCaptureFilter.envelope(value.opt("response"), value.opt("request"), linkedSetOf()))
            assertEquals(listOf(
                RedditReadVisit("reddit_post_first", "first", capturedAt),
                RedditReadVisit("reddit_post_second", "second", capturedAt),
                RedditReadVisit("reddit_post_third", "third", capturedAt)
            ), visits(value))
        }
    }

    @Test fun bulkHydrationAndKnownNonCommentOperationsNeverEstablishVisits() {
        val bulk = javaClass.getResource("/reddit/posts.json")!!.readText()
        assertTrue(RedditParser.readVisits(raw(bulk)).isEmpty())
        for (operation in listOf("HomeFeedSdui", "HomeFeedPostsByIds", "PostsByIds", "AdaptivePDP")) {
            val value = envelope(request(operation))
            assertTrue(operation, visits(value).isEmpty())
            assertTrue(operation, RedditParser.readPostIds(raw(value.toString())).isEmpty())
        }
    }

    @Test fun unknownAndLegacyOperationsRetainEmptyForestEvidence() {
        for (operation in listOf(null, "", "FutureCommentOperation")) {
            assertEquals(listOf(RedditReadVisit("reddit_post_post", null, capturedAt)),
                visits(envelope(request(operation, token = null))))
        }
        assertEquals(listOf(RedditReadVisit("reddit_post_post", null, capturedAt)), visits(envelope(null)))
        assertEquals(setOf("reddit_post_post"), RedditParser.readPostIds(raw(response().toString())))
        val thread = javaClass.getResource("/reddit/thread.json")!!.readText()
        assertEquals(setOf("reddit_post_1xr0ctd"), RedditParser.readPostIds(raw(thread)))
    }

    @Test fun explicitRequestTargetsMustAgreeWithPostAndStandaloneForestResponses() {
        assertTrue(visits(envelope(request(id = "t3_other"))).isEmpty())
        val forest = JSONObject().put("__typename", "CommentForest").put("postId", "t3_post").put("trees", JSONArray())
        assertTrue(visits(envelope(request(id = "t3_other"), response(forest, "comments"))).isEmpty())
        val conflicting = request().apply { getJSONObject("variables").put("postId", "t3_other") }
        assertTrue(visits(envelope(conflicting)).isEmpty())
        val byPostId = request(id = "").apply { getJSONObject("variables").put("postId", "t3_post") }
        assertEquals("reddit_post_post", visits(envelope(byPostId)).single().postId)
        assertEquals("reddit_post_post", visits(envelope(request(id = "t1_comment"))).single().postId)
    }

    @Test fun adsNeverProduceVisitsEvenWithCommentForests() {
        val ad = post().put("isPromoted", true)
        assertTrue(visits(envelope(value = response(ad))).isEmpty())
        assertTrue(RedditParser.readPostIds(raw(envelope(value = response(ad)).toString())).isEmpty())
    }

    @Test fun repeatedAliasesWithinOneResponseDoNotDuplicateEvidence() {
        val value = envelope(value = JSONObject().put("data", JSONObject()
            .put("postInfoById", post()).put("alias", post())))
        assertEquals(listOf(RedditReadVisit("reddit_post_post", "visit", capturedAt)), visits(value))
    }

    @Test fun invalidJsonAndForeignHostsHaveNoVisits() {
        assertTrue(RedditParser.readVisits(raw("not json")).isEmpty())
        assertTrue(RedditParser.readVisits(raw(envelope().toString()).copy(hostApp = "com.twitter.android")).isEmpty())
    }
}
