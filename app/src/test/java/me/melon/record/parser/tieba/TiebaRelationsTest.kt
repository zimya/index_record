package me.melon.record.parser.tieba

import com.google.gson.JsonParser
import me.melon.record.data.model.UniversalRecord
import org.junit.Assert.*
import org.junit.Test

class TiebaRelationsTest {
    private fun merged(old: String, new: String) = JsonParser.parseString(TiebaRelations.mergeExtra(old, new)).asJsonObject

    private fun contentRecord(content: String, preview: Boolean? = null) = UniversalRecord(
        platform = "tieba", platformId = "tieba_post_456", content = content,
        extraMetadata = preview?.let { "{\"is_preview\":$it}" } ?: "{}"
    )

    @Test fun knownFullBodyWinsInBothDirectionsEvenWhenPreviewIsLonger() {
        val full = contentRecord("完整正文", false)
        val preview = contentRecord("预览附带很多装饰文字，长度反而超过完整正文", true)
        assertEquals(full.content, TiebaRelations.mergeRecordContent(full, preview))
        assertEquals(full.content, TiebaRelations.mergeRecordContent(preview, full))
    }

    @Test fun previewFullPreviewAndFullPreviewFullRoundTripsKeepFullBody() {
        val full = contentRecord("完整正文", false)
        val preview = contentRecord("预览内容长于完整正文，但仍然不是完整响应", true)
        fun save(existing: UniversalRecord, incoming: UniversalRecord): UniversalRecord {
            val body = TiebaRelations.mergeRecordContent(existing, incoming)
            return existing.copy(content = body,
                extraMetadata = TiebaRelations.mergeExtra(existing.extraMetadata, incoming.extraMetadata))
        }
        val previewFirst = save(save(preview, full), preview)
        val fullFirst = save(save(full, preview), full)
        for (record in listOf(previewFirst, fullFirst)) {
            assertEquals(full.content, record.content)
            assertFalse(JsonParser.parseString(record.extraMetadata).asJsonObject.get("is_preview").asBoolean)
        }
    }

    @Test fun equallyCompleteBodiesPreferLongerTextInBothDirections() {
        for (preview in listOf(true, false, null)) {
            val short = contentRecord("截断", preview)
            val long = contentRecord("未截断的正文与 ![图片](https://example.com/image.jpg)", preview)
            assertEquals(long.content, TiebaRelations.mergeRecordContent(short, long))
            assertEquals(long.content, TiebaRelations.mergeRecordContent(long, short))
        }
    }

    @Test fun blankBodiesNeverReplaceNonblankContentRegardlessOfCompleteness() {
        for (oldPreview in listOf(true, false, null)) {
            for (newPreview in listOf(true, false, null)) {
                val nonblank = contentRecord("非空正文", oldPreview)
                for (blank in listOf("", " \n\t")) {
                    val empty = contentRecord(blank, newPreview)
                    assertEquals(nonblank.content, TiebaRelations.mergeRecordContent(nonblank, empty))
                    assertEquals(nonblank.content, TiebaRelations.mergeRecordContent(empty, nonblank))
                }
            }
        }
    }

    @Test fun unknownOrMalformedCompletenessIsNeutralAndCannotOverwriteKnownFull() {
        val full = contentRecord("完整", false)
        val preview = contentRecord("很长的预览", true)
        for (metadata in listOf("", "{", "[]", "{}", "{\"is_preview\":{}}", "{\"is_preview\":\"unexpected\"}")) {
            val unknown = contentRecord("完整度未知的较长正文").copy(extraMetadata = metadata)
            assertEquals(full.content, TiebaRelations.mergeRecordContent(full, unknown))
            assertEquals(full.content, TiebaRelations.mergeRecordContent(unknown, full))
            assertEquals(unknown.content, TiebaRelations.mergeRecordContent(preview, unknown))
            assertEquals(unknown.content, TiebaRelations.mergeRecordContent(unknown, preview))
        }
    }

    @Test fun contentMergeIsPureAndKeepsExistingBodyOnEqualQualityAndLength() {
        val old = contentRecord("原文", false)
        val incoming = contentRecord("新文", false)
        val oldSnapshot = old.copy()
        val incomingSnapshot = incoming.copy()
        assertEquals(old.content, TiebaRelations.mergeRecordContent(old, incoming))
        assertEquals(oldSnapshot, old)
        assertEquals(incomingSnapshot, incoming)
    }

    @Test fun previewCannotEraseFullContextCountsOrMedia() {
        val old = """{"thread_id":"123","post_id":"456","floor":5,"reply_count":20,"forum_name":"贴吧","thread_title":"完整标题","reply_to_name":"目标","reply_to_id":"789","is_preview":false,"image_urls":["original.jpg"],"content_segments":[{"text":"完整正文"}]}"""
        val incoming = """{"thread_id":"0","post_id":"456","floor":0,"reply_count":1,"forum_name":"","thread_title":"预览","reply_to_id":"0","is_preview":true,"image_urls":[],"content_segments":[{"text":"短"}]}"""
        val result = merged(old, incoming)
        assertEquals("123", result.text("thread_id"))
        assertEquals("完整标题", result.text("thread_title"))
        assertEquals("贴吧", result.text("forum_name"))
        assertEquals("789", result.text("reply_to_id"))
        assertEquals(5L, result.number("floor"))
        assertEquals(20L, result.number("reply_count"))
        assertEquals("original.jpg", result.array("image_urls")[0].asString)
        assertEquals("完整正文", result.objects("content_segments")[0].text("text"))
        assertFalse(result.get("is_preview").asBoolean)
    }

    @Test fun completeResponseEnrichesPreviewWithoutLosingReplyOrParentIds() {
        val result = merged(
            """{"thread_id":"123","floor_post_id":"456","is_preview":true,"thread_title":"短标题","reply_to_name":"目标","image_urls":["a"],"content_segments":[{"text":"短"}]}""",
            """{"is_preview":false,"thread_title":"更完整的标题","forum_name":"贴吧","reply_count":10,"image_urls":["a","b"],"content_segments":[{"text":"更完整的正文"}]}"""
        )
        assertEquals("123", result.text("thread_id"))
        assertEquals("456", result.text("floor_post_id"))
        assertEquals("目标", result.text("reply_to_name"))
        assertEquals("更完整的标题", result.text("thread_title"))
        assertEquals(2, result.array("image_urls").size())
        assertEquals(1, result.array("content_segments").size())
        assertFalse(result.get("is_preview").asBoolean)
    }

    @Test fun paginationUnionsKeyedObjectsAndRecursivelyKeepsRicherFields() {
        val old = """{"page":2,"reply_count":10,"comments":[{"id":"1","name":"甲","images":["a"]}],"context":{"forum":"吧","title":"标题"}}"""
        val new = """{"page":3,"reply_count":8,"comments":[{"id":"1","name":"","images":["a","b"]},{"id":"2","name":"乙"}],"context":{"forum":"","other":"新"}}"""
        val result = merged(old, new)
        assertEquals(3L, result.number("page"))
        assertEquals(10L, result.number("reply_count"))
        assertEquals(2, result.array("comments").size())
        assertEquals("甲", result.objects("comments")[0].text("name"))
        assertEquals(2, result.objects("comments")[0].array("images").size())
        assertEquals("吧", result.obj("context").text("forum"))
        assertEquals("新", result.obj("context").text("other"))
        assertEquals(result, merged(result.toString(), new))
    }

    @Test fun boundedMergeRejectsDeepJsonAndToleratesWrongValueTypes() {
        val deep = "{\"nested\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}"
        assertEquals("123", merged("""{"thread_id":"123"}""", deep).text("thread_id"))
        val result = merged("""{"reply_count":{},"is_preview":{},"thread_id":"123"}""",
            """{"reply_count":1,"is_preview":false}""")
        assertEquals("123", result.text("thread_id"))
    }

    @Test fun higherQualityResponseDoesNotChangeEstablishedIdentityOrReplaceLongBodyWithShortBody() {
        val result = merged(
            """{"thread_id":"123","post_id":"456","floor":5,"is_preview":true,"content_segments":[{"text":"更完整的正文"}]}""",
            """{"thread_id":"999","post_id":"888","floor":6,"is_preview":false,"content_segments":[{"text":"短"}]}"""
        )
        assertEquals("123", result.text("thread_id"))
        assertEquals("456", result.text("post_id"))
        assertEquals(5L, result.number("floor"))
        assertEquals("更完整的正文", result.objects("content_segments").single().text("text"))
    }

    @Test fun missingOrCorruptMetadataNeverReplacesValidMetadata() {
        val valid = """{"thread_id":"123","reply_count":5}"""
        assertEquals("123", merged(valid, "not json").text("thread_id"))
        assertEquals("123", merged("not json", valid).text("thread_id"))
        assertEquals("123", merged("", valid).text("thread_id"))
        assertEquals("123", merged(valid, "{}").text("thread_id"))
        assertEquals("{}", TiebaRelations.mergeExtra("[]", "garbage"))
    }
}
