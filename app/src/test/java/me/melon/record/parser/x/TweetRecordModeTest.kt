package me.melon.record.parser.x

import me.melon.record.settings.TweetRecordMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TweetRecordModeTest {
    @Test
    fun missingOrInvalidSettingDefaultsToRead() {
        assertEquals(TweetRecordMode.READ, TweetRecordMode.fromStoredValue(null))
        assertEquals(TweetRecordMode.READ, TweetRecordMode.fromStoredValue("unknown"))
        assertEquals(TweetRecordMode.PUSH, TweetRecordMode.fromStoredValue("PUSH"))
        assertEquals(TweetRecordMode.READ, TweetRecordMode.fromStoredValue("READ"))
    }

    @Test
    fun legacyNonStringSettingsDefaultToReadWithoutCrashing() {
        listOf(0, 1, -1, 99, 1L, true, setOf("PUSH")).forEach { value ->
            assertEquals(TweetRecordMode.READ, TweetRecordMode.fromStoredValue(value))
        }
    }

    @Test
    fun readOnlyProcessesMobileConversationTimelineUrls() {
        assertTrue(TweetRecordMode.READ.shouldProcess("https://api.x.com/graphql/SNs4kye4lnlklqpFC6SCtw/ConversationTimeline"))
        assertTrue(TweetRecordMode.READ.shouldProcess("https://api.twitter.com/graphql/id/ConversationTimeline?variables=test"))
        assertFalse(TweetRecordMode.READ.shouldProcess("https://x.com/i/api/graphql/id/TweetDetail"))
        assertFalse(TweetRecordMode.READ.shouldProcess("https://x.com/i/api/graphql/id/HomeTimeline"))
        assertFalse(TweetRecordMode.READ.shouldProcess("https://x.com/i/api/graphql/id/SearchTimeline"))
        assertFalse(TweetRecordMode.READ.shouldProcess("https://x.com/i/api/graphql/id/Bookmarks"))
        assertFalse(TweetRecordMode.READ.shouldProcess(""))
    }

    @Test
    fun pushDoesNotAddUrlRestrictions() {
        listOf("ConversationTimeline", "HomeTimeline", "SearchTimeline", "Bookmarks", "Likes", "UserTweets")
            .forEach { operation ->
                assertTrue(TweetRecordMode.PUSH.shouldProcess("https://x.com/i/api/graphql/id/$operation"))
            }
    }
}
