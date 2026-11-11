package me.melon.record

import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import me.melon.record.logic.mergeWeiboRecord
import org.junit.Assert.*
import org.junit.Test

class WeiboMergeTest {
    private fun record(body: String, meta: String = "{}", platform: String = "weibo") =
        UniversalRecord(platform = platform, content = body, extraMetadata = meta)

    @Test fun shortFullShortDoesNotRegressAndCountsStillUpdate() {
        val preview = record("预览", """{"is_long_text":true,"like_count":10,"comment_count":3,"source":"手机"}""")
        val full = record("预览之后是完整正文", """{"like_count":12,"views_count":100}""")
        mergeWeiboRecord(preview, full)
        val next = record("预览", """{"is_long_text":true,"like_count":0,"source":"","region_name":null}""")
        mergeWeiboRecord(full, next)
        assertEquals(full.content, next.content)
        val meta = JsonParser.parseString(next.extraMetadata).asJsonObject
        assertEquals(0, meta["like_count"].asInt)
        assertEquals(3, meta["comment_count"].asInt)
        assertEquals(100, meta["views_count"].asInt)
        assertEquals("手机", meta["source"].asString)
    }

    @Test fun truncationFlagDoesNotProveCompleteness() {
        val old = record("这是更长的完整正文")
        val preview = record("短预览", """{"is_long_text":true}""")
        mergeWeiboRecord(old, preview)
        assertEquals(old.content, preview.content)
        assertFalse(JsonParser.parseString(preview.extraMetadata).asJsonObject.has("full_text"))
    }

    @Test fun explicitFullTextWinsEvenWhenShorterAndSurvivesLongerPreview() {
        val old = record("很长的预览以及展开全文链接")
        val full = record("完整正文", """{"full_text":true}""")
        mergeWeiboRecord(old, full)
        assertEquals("完整正文", full.content)
        val preview = record("很长的预览以及展开全文链接", """{"full_text":false,"like_count":5}""")
        mergeWeiboRecord(full, preview)
        assertEquals("完整正文", preview.content)
        assertTrue(JsonParser.parseString(preview.extraMetadata).asJsonObject["full_text"].asBoolean)
    }

    @Test fun missingMetadataAndEmptyCollectionsDoNotEraseExistingValues() {
        val old = record("正文", """{"images":["image"],"custom":{"a":1},"author_headline":"简介","retweet_count":7}""")
        val patch = record("", """{"images":[],"custom":{},"author_headline":" "}""")
        mergeWeiboRecord(old, patch)
        assertEquals(old.content, patch.content)
        assertEquals(JsonParser.parseString(old.extraMetadata), JsonParser.parseString(patch.extraMetadata))
    }

    @Test fun malformedOrMissingMetadataDoesNotEraseValidMetadata() {
        val old = record("完整正文", """{"comment_count":9}""")
        val patch = record("短", "invalid")
        mergeWeiboRecord(old, patch)
        assertEquals(old.extraMetadata, patch.extraMetadata)
        val valid = record("更新正文", """{"like_count":2}""")
        mergeWeiboRecord(record("短", "invalid"), valid)
        assertEquals(2, JsonParser.parseString(valid.extraMetadata).asJsonObject["like_count"].asInt)
    }

    @Test fun otherPlatformsAreUntouched() {
        for (platform in listOf("twitter", "zhihu", "douban", "tieba")) {
            val old = record("原有较长正文", """{"like_count":9}""", platform)
            val incoming = record("短", "{}", platform)
            val before = incoming.copy()
            mergeWeiboRecord(old, incoming)
            assertEquals(before, incoming)
        }
    }
}
