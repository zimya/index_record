package com.melonhu.indexrecord.parser.zhihu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.brotli.dec.BrotliInputStream;

/** Decode before RawPayloadCodec turns bytes into text; that conversion is irreversible. */
public final class ZhihuCaptureDecoder {
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private ZhihuCaptureDecoder() {}

    public static byte[] decode(byte[] bytes, String contentEncoding) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Zhihu capture exceeds size limit");
        String[] encodings = (contentEncoding == null ? "" : contentEncoding).split(",");
        // HTTP applies content codings in order, so decoding must run in reverse.
        for (int i = encodings.length - 1; i >= 0; i--) {
            String encoding = encodings[i].trim().toLowerCase(Locale.ROOT);
            if (encoding.isEmpty() || "identity".equals(encoding)) continue;
            InputStream source = new ByteArrayInputStream(bytes);
            InputStream decoded;
            switch (encoding) {
                case "br": decoded = new BrotliInputStream(source); break;
                case "gzip": decoded = new GZIPInputStream(source); break;
                case "deflate": decoded = new InflaterInputStream(source); break;
                default: throw new IOException("Unsupported Zhihu Content-Encoding: " + encoding);
            }
            try (InputStream in = decoded; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > MAX_BYTES) {
                        throw new IOException("Decoded Zhihu capture exceeds size limit");
                    }
                    out.write(buffer, 0, count);
                }
                bytes = out.toByteArray();
            }
        }
        return bytes;
    }
}
