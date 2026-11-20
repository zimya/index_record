package com.melonhu.indexrecord.parser.bluesky

import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.RawData
import com.melonhu.indexrecord.data.model.RecordBodyFormat
import com.melonhu.indexrecord.data.model.RecordRelation
import com.melonhu.indexrecord.data.model.directRelation
import com.melonhu.indexrecord.presentation.PlatformMetadataAdapters
import com.melonhu.indexrecord.presentation.RecordContentPolicy
import com.melonhu.indexrecord.settings.BlueskyRecordMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueskyParserTest {

    @Test
    fun parsesTimelineFeedPostWithImagesAndRichTextFacets() {
        val json = """
        {
          "feed": [
            {
              "post": {
                "uri": "at://did:plc:user1/app.bsky.feed.post/3n2qzlwkhus2b",
                "cid": "bafyrei123",
                "author": {
                  "did": "did:plc:user1",
                  "handle": "alice.bsky.social",
                  "displayName": "Alice",
                  "avatar": "https://cdn.bsky.app/img/avatar/alice.jpg"
                },
                "record": {
                  "${'$'}type": "app.bsky.feed.post",
                  "createdAt": "2026-11-20T16:39:37.094Z",
                  "text": "Hello bsky.app!",
                  "facets": [
                    {
                      "index": { "byteStart": 6, "byteEnd": 14 },
                      "features": [
                        { "${'$'}type": "app.bsky.richtext.facet#link", "uri": "https://bsky.app" }
                      ]
                    }
                  ]
                },
                "embed": {
                  "${'$'}type": "app.bsky.embed.images#view",
                  "images": [
                    {
                      "fullsize": "https://cdn.bsky.app/img/full.jpg",
                      "alt": "Sample photo"
                    }
                  ]
                },
                "replyCount": 14,
                "repostCount": 3,
                "likeCount": 42,
                "quoteCount": 1,
                "bookmarkCount": 5
              }
            }
          ]
        }
        """.trimIndent()

        val raw = RawData(
            url = "https://enoki.us-east.host.bsky.network/xrpc/app.bsky.feed.getTimeline?limit=30",
            hostApp = "xyz.blueskyweb.app",
            json = json,
            timestamp = 1795216439000L
        )

        val records = BlueskyParser.parseResult(raw, raw.url.substringBefore('?')).records
        assertEquals(1, records.size)
        val post = records.first()
        assertEquals("bluesky", post.platform)
        assertEquals("post", post.recordType)
        assertEquals("bluesky_post_did:plc:user1_3n2qzlwkhus2b", post.platformId)
        assertEquals("", post.parentId)
        assertEquals(RecordBodyFormat.MARKDOWN.storageValue, post.contentFormat)
        assertTrue(post.content.contains("Hello [bsky.app](https://bsky.app)!"))
        assertTrue(post.content.contains("![Sample photo](https://cdn.bsky.app/img/full.jpg)"))
        assertEquals("Alice", post.authorName)
        assertEquals("https://bsky.app/profile/alice.bsky.social/post/3n2qzlwkhus2b", post.sourceUrl)
        assertTrue(RecordContentPolicy.isPrimaryContent(post))
        val engagement = PlatformMetadataAdapters(post.extraMetadata, "bluesky").engagement
        assertEquals(42L, engagement.likeCount)
        assertEquals(14L, engagement.replyCount)
        assertEquals(3L, engagement.repostCount)
        assertEquals(1L, engagement.quoteCount)
        assertEquals(5L, engagement.bookmarkCount)
    }

    @Test
    fun parsesPostThreadV2RootAndRepliesWithParentRelations() {
        val json = """
        {
          "thread": [
            {
              "uri": "at://did:plc:root/app.bsky.feed.post/post1",
              "depth": 0,
              "value": {
                "${'$'}type": "app.bsky.unspecced.defs#threadItemPost",
                "post": {
                  "uri": "at://did:plc:root/app.bsky.feed.post/post1",
                  "author": { "did": "did:plc:root", "handle": "root.bsky.social" },
                  "record": { "createdAt": "2026-11-20T10:00:00Z", "text": "Root post" },
                  "likeCount": 10,
                  "replyCount": 1
                }
              }
            },
            {
              "uri": "at://did:plc:replier/app.bsky.feed.post/reply1",
              "depth": 1,
              "value": {
                "${'$'}type": "app.bsky.unspecced.defs#threadItemPost",
                "post": {
                  "uri": "at://did:plc:replier/app.bsky.feed.post/reply1",
                  "author": { "did": "did:plc:replier", "handle": "replier.bsky.social" },
                  "record": {
                    "createdAt": "2026-11-20T10:05:00Z",
                    "text": "Nice post!",
                    "reply": {
                      "root": { "uri": "at://did:plc:root/app.bsky.feed.post/post1" },
                      "parent": { "uri": "at://did:plc:root/app.bsky.feed.post/post1" }
                    }
                  },
                  "likeCount": 2
                }
              }
            }
          ]
        }
        """.trimIndent()

        val raw = RawData(
            url = "https://enoki.us-east.host.bsky.network/xrpc/app.bsky.unspecced.getPostThreadV2",
            hostApp = "xyz.blueskyweb.app",
            json = json,
            timestamp = 1795216474000L
        )
        val records = BlueskyParser.parseResult(raw, raw.url).records
        assertEquals(2, records.size)
        val root = records[0]
        val reply = records[1]
        assertEquals("post", root.recordType)
        assertEquals("comment", reply.recordType)
        assertEquals(root.platformId, reply.parentId)
        assertFalse(RecordContentPolicy.isPrimaryContent(reply))
        assertTrue(reply.directRelation() is RecordRelation.ReplyTo)
        assertEquals("回复", PlatformCatalog.typeLabel("bluesky", reply.recordType))
    }

    @Test
    fun recordModeFiltersFeedVsDetail() {
        val timelineUrl = "https://enoki.us-east.host.bsky.network/xrpc/app.bsky.feed.getTimeline?limit=30"
        val threadUrl = "https://enoki.us-east.host.bsky.network/xrpc/app.bsky.unspecced.getPostThreadV2?anchor=at://did:plc:abc"
        val threadV1Url = "https://enoki.us-east.host.bsky.network/xrpc/app.bsky.feed.getPostThread?uri=at://did:plc:abc"

        assertTrue(BlueskyRecordMode.PUSH.shouldProcess(timelineUrl))
        assertTrue(BlueskyRecordMode.PUSH.shouldProcess(threadUrl))

        assertFalse(BlueskyRecordMode.READ.shouldProcess(timelineUrl))
        assertTrue(BlueskyRecordMode.READ.shouldProcess(threadUrl))
        assertTrue(BlueskyRecordMode.READ.shouldProcess(threadV1Url))
    }
}
