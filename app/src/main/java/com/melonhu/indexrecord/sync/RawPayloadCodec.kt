package com.melonhu.indexrecord.sync

import com.melonhu.indexrecord.data.PlatformCatalog
import java.nio.charset.StandardCharsets
import java.util.Base64

/** RawData is text-only; protobuf must not pass through lossy UTF-8 decoding. */
object RawPayloadCodec {
    fun encode(bytes: ByteArray, hostApp: String): String =
        if (PlatformCatalog.platformForPackage(hostApp) == "tieba") {
            "{\"encoding\":\"base64\",\"body\":\"${Base64.getEncoder().encodeToString(bytes)}\"}"
        } else {
            String(bytes, StandardCharsets.UTF_8)
        }
}
