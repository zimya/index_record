package me.melon.record.parser.tieba

import com.google.gson.JsonObject
import me.melon.record.data.model.RawData
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class TiebaBoundaryTest {
    private fun v(input: Long): ByteArray {
        var value = input
        return ByteArrayOutputStream().apply {
            while (value and -128L != 0L) {
                write((value and 127L).toInt() or 128)
                value = value ushr 7
            }
            write(value.toInt())
        }.toByteArray()
    }
    private fun n(tag: Int, value: Long) = v(tag.toLong() shl 3) + v(value)
    private fun b(tag: Int, bytes: ByteArray) = v((tag.toLong() shl 3) or 2) + v(bytes.size.toLong()) + bytes
    private fun parse(bytes: ByteArray, query: String = ""): List<me.melon.record.data.model.UniversalRecord> {
        val url = "https://tiebac.baidu.com/c/f/pb/page?format=protobuf$query"
        val json = JsonObject().apply {
            addProperty("encoding", "base64")
            addProperty("body", Base64.getEncoder().encodeToString(bytes))
        }
        return TiebaParser.parse(RawData(url = url, json = json.toString()), url)
    }

    @Test fun wireVarintsPreserveAll64BitsAtSignedAndUnsignedLimits() {
        for (value in listOf(0L, 127L, 128L, Int.MAX_VALUE.toLong(), 4294967295L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)) {
            val reader = TiebaWireReader(v(value))
            assertEquals(value, reader.varint())
            assertTrue(reader.exhausted)
        }
        for (bytes in listOf(ByteArray(10) { 0x80.toByte() }, ByteArray(9) { 0xff.toByte() } + byteArrayOf(2))) {
            assertThrows(IllegalArgumentException::class.java) { TiebaWireReader(bytes).varint() }
        }
    }

    @Test fun maximumFieldNumberWorksButTagOverflowZeroAndInvalidWireTypesFail() {
        val reader = TiebaWireReader(v(0xfffffffdL) + ByteArray(4)) // field 2^29-1, fixed32
        val tag = reader.tag()
        assertEquals(536870911, tag ushr 3)
        reader.skip(tag)
        assertTrue(reader.exhausted)
        for (value in listOf(0L, 1L, 6L, 7L, 0x100000000L, Long.MAX_VALUE, -1L)) {
            assertThrows(IllegalArgumentException::class.java) { TiebaWireReader(v(value)).tag() }
        }
    }

    @Test fun lengthAtIntLongAndUnsignedLimitsCannotCrossMessageBoundary() {
        for (length in listOf(1L, Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE, Long.MIN_VALUE, -1L)) {
            val bytes = byteArrayOf(10) + v(length)
            assertThrows(IllegalArgumentException::class.java) {
                val reader = TiebaWireReader(bytes)
                reader.skip(reader.tag())
            }
        }
        // The child cannot consume valid bytes belonging to a later sibling.
        val reader = TiebaWireReader(b(1, byteArrayOf(10, 2, 65)) + b(2, byteArrayOf(66)))
        reader.tag()
        val child = reader.message()
        assertThrows(IllegalArgumentException::class.java) { child.skip(child.tag()) }
    }

    @Test fun nestedMessagesAndGroupsAreBoundedEvenWhenUnknown() {
        var bytes = byteArrayOf(0)
        repeat(33) { bytes = b(1, bytes) }
        var reader = TiebaWireReader(bytes)
        repeat(32) { reader.tag(); reader = reader.message() }
        reader.tag()
        val deepest = reader
        assertThrows(IllegalArgumentException::class.java) { deepest.message() }
        val groupStart = v((100L shl 3) or 3)
        val groupEnd = v((100L shl 3) or 4)
        val groups = ByteArrayOutputStream().apply {
            repeat(33) { write(groupStart) }
            repeat(33) { write(groupEnd) }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            val group = TiebaWireReader(groups)
            group.skip(group.tag())
        }
    }

    @Test fun fieldBudgetIsSharedByNestedReaders() {
        val payload = ByteArrayOutputStream().apply { repeat(100000) { write(n(100, 0)) } }.toByteArray()
        val reader = TiebaWireReader(b(1, payload) + b(2, payload))
        reader.tag()
        val first = reader.message()
        while (!first.exhausted) first.skip(first.tag())
        reader.tag()
        val second = reader.message()
        assertThrows(IllegalArgumentException::class.java) {
            while (!second.exhausted) second.skip(second.tag())
        }
    }

    @Test fun messageSizeIsBoundedWithoutNarrowingOverflow() {
        assertTrue(!TiebaWireReader(ByteArray(TiebaWireReader.MAX_BYTES)).exhausted)
        assertThrows(IllegalArgumentException::class.java) { TiebaWireReader(ByteArray(TiebaWireReader.MAX_BYTES + 1)) }
    }

    @Test fun signedSchemaIdsAreNotReinterpretedAsUnsignedButPostIdsCanUseUint64() {
        val post = n(1, -1) + n(3, 2)
        assertTrue(parse(b(2, b(8, n(1, -1)) + b(6, post))).isEmpty())
        val record = parse(b(2, b(8, n(1, Long.MAX_VALUE)) + b(6, post + n(19, Long.MIN_VALUE)))).single()
        assertEquals("tieba_thread_9223372036854775807", record.parentId)
        assertEquals("tieba_post_18446744073709551615", record.platformId)
        assertEquals("", record.authorId)
        assertEquals("18446744073709551615", "18446744073709551615".validId())
        for (id in listOf("18446744073709551616", "99999999999999999999", "123456789012345678901", "-1", "0", "", "1e10")) {
            assertEquals(id, "", id.validId())
        }
        assertTrue(parse(b(2, b(6, n(1, 1) + n(3, 2))), "&tid=18446744073709551616").isEmpty())
    }

    @Test fun publicationTimeConversionCannotOverflowOrInventCurrentTime() {
        for (seconds in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val record = parse(b(2, b(8, n(1, 123)) + b(6, n(1, 456) + n(3, 2) + n(4, seconds)))).single()
            assertEquals(0L, record.publishTime)
        }
        val limit = Long.MAX_VALUE / 1000
        val record = parse(b(2, b(8, n(1, 123)) + b(6, n(1, 456) + n(3, 2) + n(4, limit)))).single()
        assertEquals(limit * 1000, record.publishTime)
    }

    @Test fun floorQueryIdsDoNotInventAnAbsentOrEmptyParentPost() {
        val url = "https://tiebac.baidu.com/c/f/pb/floor?tid=123&pid=456"
        for (json in listOf("""{"data":{"thread":{"id":"123"}}}""",
            """{"data":{"thread":{"id":"123"},"post":{}}}""")) {
            assertTrue(TiebaParser.parse(RawData(url = url, json = json), url).isEmpty())
        }
    }

    @Test(timeout = 2000) fun excessivelyLongReplyPrefixIsNotScannedAsAnUnboundedRegex() {
        val url = "https://tiebac.baidu.com/c/f/pb/floor"
        val json = JsonObject().apply {
            add("data", JsonObject().apply {
                add("thread", JsonObject().apply { addProperty("id", "123") })
                add("post", JsonObject().apply { addProperty("id", "456"); addProperty("floor", 2) })
                add("subpost_list", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("id", "789")
                        add("content", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply { addProperty("type", 0); addProperty("text", " 回复 名称" + " ".repeat(100000) + ":正文") })
                        })
                    })
                })
            })
        }
        val records = TiebaParser.parse(RawData(url = url, json = json.toString()), url)
        assertEquals(2, records.size)
        assertFalse(boundedTiebaJson(records[1].extraMetadata).has("reply_to_name"))
    }

    @Test fun jsonDepthGuardTreatsEscapedQuotesAndBracketsInsideStringsAsText() {
        val value = JsonObject().apply { addProperty("text", "\\\"" + "[{}]".repeat(100) + "\\") }.toString()
        assertTrue(boundedTiebaJson(value).text("text").contains("[{}]"))
        assertThrows(IllegalArgumentException::class.java) { boundedTiebaJson("{\"a\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}") }
        assertThrows(IllegalArgumentException::class.java) { boundedTiebaJson("{\"a\":\"unterminated}") }
    }
}
