package me.melon.record.data.model

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RecordSnapshotTest {
    @Test fun explicitWireRoundTripsEveryFieldWithoutReflectingOnEntityNames() {
        val record = UniversalRecord(
            id = 7, platform = "twitter", recordType = "comment", sourceApp = "com.twitter.android",
            platformId = "twitter_comment_42", title = "标题", content = "body\n\"quoted\"",
            authorId = "a", authorName = "作者", authorAvatar = "https://avatar", sourceUrl = "https://source",
            parentId = "twitter_tweet_1", publishTime = 23, captureTime = 45,
            extraMetadata = "{\"nested\":[1,2]}", contentFormat = "html"
        )
        val snapshot = recordSnapshot("capture", record)
        assertEquals(record, snapshot.toRecord())
        assertEquals("twitter_status_42", snapshot.identityId)
        assertEquals(45L, snapshot.captureTime)
        val json = JsonParser.parseString(snapshot.recordJson).asJsonObject
        assertEquals("twitter_comment_42", json["platformId"].asString)
        assertEquals(1, json["version"].asInt)
        assertEquals(17, json.size())
    }

    @Test fun nullableSourceAndPayloadAreIndependentOfMutableEntities() {
        val original = UniversalRecord(platformId = "p", sourceApp = null, content = "raw", captureTime = 10)
        val snapshot = recordSnapshot("capture", original)
        original.content = "merged"
        assertNull(snapshot.toRecord().sourceApp)
        assertEquals("raw", snapshot.toRecord().content)
        val restored = snapshot.toRecord()
        restored.content = "UI mutation"
        assertEquals("raw", snapshot.toRecord().content)
    }
}
