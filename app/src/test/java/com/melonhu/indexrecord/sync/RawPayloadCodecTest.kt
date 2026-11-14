package com.melonhu.indexrecord.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class RawPayloadCodecTest {
    @Test fun tiebaBinarySurvivesTextOnlyStorage() {
        val bytes = ByteArray(256) { it.toByte() }
        val json = JsonParser.parseString(RawPayloadCodec.encode(bytes, "com.baidu.tieba")).asJsonObject
        assertEquals("base64", json["encoding"].asString)
        assertArrayEquals(bytes, Base64.getDecoder().decode(json["body"].asString))
    }

    @Test fun otherPlatformsKeepTheirOriginalText() {
        val json = "{\"正文\":\"Test🙂\"}"
        for (host in listOf("com.zhihu.android", "com.douban.frodo", "com.twitter.android")) {
            assertEquals(json, RawPayloadCodec.encode(json.toByteArray(Charsets.UTF_8), host))
        }
    }
}
