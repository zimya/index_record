package com.melonhu.indexrecord.parser.reddit

import com.google.gson.JsonParser
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.parser.ParserRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class RedditParserTest {
    private val url = "https://gql-fed.reddit.com/"
    private val capturedAt = 1794510745269L
    private fun fixture(name: String) = javaClass.getResource("/reddit/$name.json")!!.readText()
    private fun raw(json: String) = RawData(url = url, hostApp = RedditParser.HOST_APP, timestamp = capturedAt, json = json)
    private fun parse(json: String) = RedditParser.parse(raw(json), url)
    private fun meta(record: UniversalRecord) = JsonParser.parseString(record.extraMetadata).asJsonObject
    private fun root(json: String) = JSONObject(json).getJSONObject("response").getJSONObject("data")

    @Test fun postsUseNativeIdentityTimesAuthorMediaAndRealCounts() {
        val records = parse(fixture("posts"))
        assertEquals(2, records.size)
        val post = records.single { it.platformId == "reddit_post_1xr0ctd" }
        assertEquals("reddit", post.platform)
        assertEquals("post", post.recordType)
        assertEquals(RedditParser.HOST_APP, post.sourceApp)
        assertEquals("", post.parentId)
        assertEquals("t2_9117cvl1n", post.authorId)
        assertEquals("IdiotStickWasTkn", post.authorName)
        assertEquals(Instant.parse("2026-11-08T15:00:07.097Z").toEpochMilli(), post.publishTime)
        assertEquals(capturedAt, post.captureTime)
        assertTrue(post.sourceUrl.startsWith("https://www.reddit.com/r/theydidthemath/comments/1xr0ctd/"))
        assertTrue(post.content.contains("https://preview.redd.it/khmd70h3le0i1.jpeg?auto=webp"))
        val extra = meta(post)
        assertEquals(4020, extra["score"].asInt)
        assertEquals(409, extra["comment_count"].asInt)
        assertEquals(46, extra["share_count"].asInt)
        assertEquals("theydidthemath", extra["subreddit"].asString)
        assertFalse(extra["is_nsfw"].asBoolean)
        for (key in listOf("like_count", "voteup_count", "downvote_count", "crosspost_count", "view_count")) assertFalse(key, extra.has(key))
    }

    @Test fun realMarkdownIsPreservedIncludingHardBreaksEscapesAndTrailingSpaces() {
        val source = root(fixture("posts")).getJSONArray("postsInfoByIds").getJSONObject(2).getJSONObject("content").getString("markdown")
        val post = parse(fixture("posts")).single { it.platformId == "reddit_post_1xouqzu" }
        assertEquals(source, post.content)
        assertEquals("Prospective Student", meta(post)["flair"].asString)
        val records = parse(fixture("thread"))
        assertEquals("not minting =\\\\= not usable ", records.single { it.platformId == "reddit_comment_19she0uv0rbac" }.content)
        assertTrue(records.single { it.authorName == "bumbadabumbumbum" }.content.contains("$1"))
        val data = root(fixture("thread"))
        val node = data.getJSONObject("postInfoById").getJSONObject("commentForest").getJSONArray("trees").getJSONObject(1).getJSONObject("node")
        val markdown = "```html\n<p>not HTML</p>\n```\n\n**bold** [link](https://example.test/) $5 and $25.40 \\*literal\\*  \n"
        node.getJSONObject("content").put("markdown", markdown)
        assertEquals(markdown, parse(JSONObject().put("data", data).toString()).single { it.platformId == RedditParser.commentId(node.getString("id")) }.content)
    }

    @Test fun forestUsesAllEightLevelsAndExactImmediateParentsWithoutMorePlaceholders() {
        val json = fixture("thread")
        val records = parse(json)
        val comments = records.filter { it.recordType == "comment" }.associateBy { it.platformId }
        assertEquals(10, comments.size)
        assertEquals(11, records.size)
        val rootId = "reddit_post_1xr0ctd"
        val trees = root(json).getJSONObject("postInfoById").getJSONObject("commentForest").getJSONArray("trees")
        for (i in 0 until trees.length()) {
            val tree = trees.getJSONObject(i)
            val node = tree.optJSONObject("node") ?: continue
            val comment = comments.getValue(RedditParser.commentId(node.getString("id")))
            val expected = tree.optString("parentId", "").takeIf { it.isNotBlank() }?.let(RedditParser::commentId) ?: rootId
            assertEquals(expected, comment.parentId)
            assertEquals(rootId, meta(comment)["post_id"].asString)
            assertEquals(tree.getInt("depth"), meta(comment)["depth"].asInt)
            assertEquals(node.getJSONObject("content").getString("markdown"), comment.content)
        }
        var current = comments.getValue("reddit_comment_19she0uv0rbac")
        var levels = 1
        while (current.parentId != rootId) { current = comments.getValue(current.parentId); levels++ }
        assertEquals(8, levels)
        val postMeta = meta(records.single { it.recordType == "post" })
        assertEquals(409, postMeta["comment_count"].asInt)
        assertEquals(318, postMeta["comment_forest_count"].asInt)
        assertTrue(postMeta["comments_has_next_page"].asBoolean)
        assertEquals("CONFIDENCE", postMeta["comment_sort"].asString)
        assertEquals(1, postMeta["comment_more"].asJsonArray.size())
        val first = comments.getValue("reddit_comment_19sby1qmk6i90")
        assertEquals(85, meta(first)["child_count"].asInt)
        assertFalse(meta(first).has("comment_count"))
        assertFalse(meta(first).has("reply_count"))
        assertTrue(first.authorAvatar.contains("avatar_default_7.png"))
    }

    @Test fun signedHiddenAndUnknownScoresAreNotConvertedIntoLikes() {
        val records = parse(fixture("thread"))
        val hidden = meta(records.single { it.authorName == "AutoModerator" })
        assertTrue(hidden["is_score_hidden"].asBoolean)
        assertFalse(hidden.has("score"))
        assertEquals(-6, meta(records.single { it.authorName == "shaggy237" })["score"].asInt)
        val post = root(fixture("posts")).getJSONArray("postsInfoByIds").getJSONObject(0)
        post.put("score", 0).put("commentCount", 0).put("crosspostCount", 7)
        post.getJSONObject("postStats").put("shareAllTotal", JSONObject.NULL)
        val zero = meta(parse(JSONObject().put("data", JSONObject().put("postInfoById", post)).toString()).single())
        assertEquals(0, zero["score"].asInt)
        assertEquals(0, zero["comment_count"].asInt)
        assertEquals(7, zero["crosspost_count"].asInt)
        assertFalse(zero.has("share_count"))
        post.remove("score")
        assertFalse(meta(parse(JSONObject().put("data", JSONObject().put("postInfoById", post)).toString()).single()).has("score"))
    }

    @Test fun replyBeforeParentAndMissingParentKeepTheirRealRelations() {
        val source = root(fixture("thread"))
        val post = source.getJSONObject("postInfoById")
        val original = post.getJSONObject("commentForest").getJSONArray("trees")
        val last = original.getJSONObject(8)
        post.getJSONObject("commentForest").put("trees", JSONArray().put(last))
        val records = parse(JSONObject().put("data", source).toString())
        assertEquals(2, records.size)
        val child = records.single { it.recordType == "comment" }
        assertEquals("reddit_comment_19sg2le5j9p0l", child.parentId)
        assertEquals("reddit_post_1xr0ctd", meta(child)["post_id"].asString)
        assertFalse(records.any { it.platformId == child.parentId })
        last.remove("parentId")
        val noParent = parse(JSONObject().put("data", source).toString()).single { it.recordType == "comment" }
        assertEquals("", noParent.parentId) // Depth seven must not be flattened onto the post.
    }

    @Test fun registryTrustsOnlyTheActualEndpointAndHostAndMalformedRootsAreEmpty() {
        val raw = raw(fixture("posts"))
        assertSame(RedditParser, ParserRegistry.findParser(url, raw))
        assertTrue(RedditParser.canHandle("https://gql-fed.reddit.com/?request_timestamp=1", raw))
        assertFalse(RedditParser.canHandle("https://gql-fed.reddit.com.evil.test/", raw))
        assertFalse(RedditParser.canHandle("https://evil-gql-fed.reddit.com/", raw))
        assertFalse(RedditParser.canHandle("http://gql-fed.reddit.com/", raw))
        assertFalse(RedditParser.canHandle(url, raw.copy(hostApp = "com.twitter.android")))
        for (json in listOf("{}", "{", "null", "[]", "{\"data\":{\"identity\":{\"id\":\"t3_abc\"}}}")) assertTrue(json, parse(json).isEmpty())
        assertEquals("", RedditParser.postId("t1_abcd"))
        assertEquals("", RedditParser.commentId("t3_abcd"))
        assertEquals("reddit_comment_19she0uv0rbac", RedditParser.commentId("t1_19she0uv0rbac"))
    }

    @Test fun repeatedPagesAndAliasesDeduplicateWithoutScanningRecommendations() {
        val original = root(fixture("thread")).getJSONObject("postInfoById")
        val responses = JSONArray().put(JSONObject().put("data", JSONObject().put("postInfoById", original)))
            .put(JSONObject().put("data", JSONObject().put("threadAlias", original)))
        assertEquals(11, parse(responses.toString()).size)
        val data = JSONObject().put("identity", JSONObject().put("recommended", original))
            .put("subredditInfoById", JSONObject().put("posts", JSONArray().put(original)))
        assertTrue(parse(JSONObject().put("data", data).toString()).isEmpty())
    }

    @Test fun commentAndPostRichtextMediaIdsResolveToRealImageAndGifUrls() {
        val data = root(fixture("thread"))
        val post = data.getJSONObject("postInfoById")
        val trees = post.getJSONObject("commentForest").getJSONArray("trees")
        val pngComment = trees.getJSONObject(1).getJSONObject("node")
        pngComment.put("content", JSONObject()
            .put("markdown", "look at this\n\n![img](b9wip0noo12i1)\n\n")
            .put("richtextMedia", JSONArray().put(JSONObject()
                .put("id", "b9wip0noo12i1")
                .put("mimetype", "image/png")
                .put("url", "https://i.redd.it/b9wip0noo12i1.png")
            ))
        )
        val giphyComment = trees.getJSONObject(2).getJSONObject("node")
        giphyComment.put("content", JSONObject()
            .put("markdown", "![gif](giphy|R2yYkZbpnHCKc)")
            .put("richtextMedia", JSONArray().put(JSONObject()
                .put("id", "giphy|R2yYkZbpnHCKc")
                .put("mimetype", "image/gif")
                .put("url", "https://external-preview.redd.it/example.gif?auto=webp&s=abc")
            ))
        )
        val fallbackComment = trees.getJSONObject(3).getJSONObject("node")
        fallbackComment.put("content", JSONObject()
            .put("markdown", "![img](missing123)\n\n![img](fallback456 \"caption\")")
            .put("richtextMedia", JSONArray().put(JSONObject()
                .put("id", "fallback456")
                .put("medium", JSONObject().put("url", "https://preview.redd.it/fallback456.jpg?width=216"))
            ))
        )
        post.put("content", JSONObject()
            .put("markdown", "post inline\n\n![img](postmedia1)")
            .put("richtextMedia", JSONArray().put(JSONObject()
                .put("id", "postmedia1")
                .put("url", "https://i.redd.it/postmedia1.jpg")
            ))
        )

        val records = parse(JSONObject().put("data", data).toString()).associateBy { it.platformId }
        assertEquals(
            "post inline\n\n![img](https://i.redd.it/postmedia1.jpg)",
            records.getValue("reddit_post_1xr0ctd").content
        )
        assertEquals(
            "look at this\n\n![img](https://i.redd.it/b9wip0noo12i1.png)\n\n",
            records.getValue(RedditParser.commentId(pngComment.getString("id"))).content
        )
        assertEquals(
            "![gif](https://external-preview.redd.it/example.gif?auto=webp&s=abc)",
            records.getValue(RedditParser.commentId(giphyComment.getString("id"))).content
        )
        assertEquals(
            "![img](missing123)\n\n![img](https://preview.redd.it/fallback456.jpg?width=216 \"caption\")",
            records.getValue(RedditParser.commentId(fallbackComment.getString("id"))).content
        )
    }

    @Test fun retainedFullPrivateSampleWhenExplicitlyAvailable() {
        // Optional local replay neither copies private cache files nor prints their contents.
        val directory = System.getenv("REDDIT_EVIDENCE_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val full = File(directory, "comments-first-page.json").readText()
        val records = parse(full)
        val comments = records.filter { it.recordType == "comment" }.associateBy { it.platformId }
        assertEquals(200, comments.size)
        assertEquals(201, records.size)
        assertEquals((0..7).toSet(), comments.values.map { meta(it)["depth"].asInt }.toSet())
        val trees = root(full).getJSONObject("postInfoById").getJSONObject("commentForest").getJSONArray("trees")
        for (i in 0 until trees.length()) {
            val tree = trees.getJSONObject(i)
            val node = tree.optJSONObject("node") ?: continue
            val record = comments.getValue(RedditParser.commentId(node.getString("id")))
            assertEquals(node.getJSONObject("content").getString("markdown"), record.content)
            val parent = tree.optString("parentId", "").takeIf { it.isNotEmpty() }?.let(RedditParser::commentId) ?: "reddit_post_1xr0ctd"
            assertEquals(parent, record.parentId)
        }
        val files = File(directory, "dump").listFiles { f -> f.extension == "bin" }!!.sortedBy { it.name }
        val ads = files.flatMap { RedditParser.advertisingPostIds(raw(it.readText())) }.toSet()
        assertTrue(ads.contains("t3_1x0zpmg"))
        for (file in files) {
            val parsed = RedditParser.parseWithAdvertisingIds(raw(file.readText()), url, ads)
            assertTrue(parsed.all { it.platform == "reddit" && it.recordType in setOf("post", "comment") })
            assertFalse(parsed.any { it.platformId.removePrefix("reddit_post_").let { id -> "t3_$id" in ads } })
        }
    }
}
