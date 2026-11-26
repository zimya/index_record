package com.melonhu.indexrecord.hook;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;

/**
 * Passive HTTP/1.1 framing. Feed both directions from connection start, in order, on one executor.
 * Call close when that connection ends; do not reuse its ID before close or reenter from the callback.
 */
public final class PiliPlusHttpCapture {

    private static final String VIDEO_PATH = "/x/web-interface/wbi/view";
    private static final String MAIN_LIST_PATH =
        "/bilibili.main.community.reply.v1.Reply/MainList";
    private static final String VIDEO_URL =
        "https://api.bilibili.com" + VIDEO_PATH;
    private static final String MAIN_LIST_URL =
        "https://grpc.biliapi.net" + MAIN_LIST_PATH;
    private static final int MAX_CONNECTIONS = 64;
    private static final int MAX_REJECTED = 256;
    private static final int MAX_PENDING = 32;
    private static final int MAX_CAPTURING = 4;
    private static final int MAX_LINE = 8 * 1024;
    private static final int MAX_SECTION = 32 * 1024;
    // Same frame limit as PiliPlusGrpc.kt, including the five-byte message prefix.
    private static final int MAX_BODY = 4 * 1024 * 1024 + 5;
    private static final BadFraming BAD_FRAMING = new BadFraming();

    private final BiConsumer<String, byte[]> capture;
    private final Map<Long, Connection> connections = new HashMap<>();
    private final Set<Long> rejected = new HashSet<>();
    private boolean admissionClosed;
    private int capturing;

    /** The callback receives a canonical URL and a complete application/json payload. */
    public PiliPlusHttpCapture(BiConsumer<String, byte[]> capture) {
        this.capture = Objects.requireNonNull(capture);
    }

    public void accept(long connection, boolean inbound, byte[] bytes) {
        if (bytes.length == 0) return;
        Connection state = connections.get(connection);
        if (state == null) {
            // Null entries are tombstones: never attach in the middle of a rejected stream.
            if (
                connections.containsKey(connection) ||
                admissionClosed ||
                rejected.contains(connection)
            ) return;
            if (connections.size() == MAX_CONNECTIONS) {
                if (rejected.size() == MAX_REJECTED) admissionClosed = true;
                else rejected.add(connection);
                return;
            }
            if (inbound) {
                connections.put(connection, null);
                return;
            }
            state = new Connection();
            connections.put(connection, state);
        }
        try {
            (inbound ? state.response : state.request).consume(bytes);
        } catch (BadFraming ignored) {
            stop(connection, state);
        } catch (RuntimeException failure) {
            // The callback may have interrupted a chunk containing the next message.
            stop(connection, state);
            throw failure;
        }
    }

    public void close(long connection) {
        Connection state = connections.remove(connection);
        if (state != null) state.response.releaseBody();
        rejected.remove(connection);
    }

    private void stop(long connection, Connection state) {
        state.response.releaseBody();
        connections.put(connection, null);
    }

    private enum Kind {
        OTHER,
        HEAD,
        CONNECT,
        VIDEO,
        MAIN_LIST,
    }

    private enum Phase {
        START,
        HEADERS,
        FIXED,
        CHUNK_SIZE,
        CHUNK_DATA,
        CHUNK_CR,
        CHUNK_LF,
        TRAILERS,
    }

    private enum Encoding {
        IDENTITY,
        GZIP,
        UNSUPPORTED,
    }

    private static final class Pending {

        final Kind kind;
        boolean complete;

        Pending(Kind kind) {
            this.kind = kind;
        }
    }

    private final class Connection {

        final ArrayDeque<Pending> pending = new ArrayDeque<>();
        final Stream request = new Stream(false);
        final Stream response = new Stream(true);

        private final class Stream {

            final boolean inbound;
            final byte[] line = new byte[MAX_LINE];
            int lineSize;
            boolean sawCr;
            int sectionSize;
            Phase phase = Phase.START;
            long remaining;

            Kind kind;
            int status;
            boolean hostSeen;
            boolean hostMatches;
            long contentLength = -1;
            boolean chunked;
            Encoding encoding;
            Pending writing;
            ByteArrayOutputStream body;

            Stream(boolean inbound) {
                this.inbound = inbound;
            }

            void consume(byte[] bytes) throws BadFraming {
                int offset = 0;
                while (offset < bytes.length) {
                    switch (phase) {
                        case FIXED:
                        case CHUNK_DATA:
                            int count = (int) Math.min(
                                remaining,
                                bytes.length - offset
                            );
                            if (body != null) body.write(bytes, offset, count);
                            offset += count;
                            remaining -= count;
                            if (remaining == 0) {
                                if (phase == Phase.FIXED) finish();
                                else phase = Phase.CHUNK_CR;
                            }
                            break;
                        case CHUNK_CR:
                            if (bytes[offset++] != '\r') throw BAD_FRAMING;
                            phase = Phase.CHUNK_LF;
                            break;
                        case CHUNK_LF:
                            if (bytes[offset++] != '\n') throw BAD_FRAMING;
                            phase = Phase.CHUNK_SIZE;
                            break;
                        default:
                            // Check before accepting even a partial response start line.
                            if (
                                inbound &&
                                phase == Phase.START &&
                                pending.isEmpty()
                            ) throw BAD_FRAMING;
                            readLineByte(bytes[offset++]);
                    }
                }
            }

            private void readLineByte(byte b) throws BadFraming {
                if (
                    (phase == Phase.HEADERS || phase == Phase.TRAILERS) &&
                    ++sectionSize > MAX_SECTION
                ) {
                    throw BAD_FRAMING;
                }
                if (sawCr) {
                    if (b != '\n') throw BAD_FRAMING;
                    String text = new String(
                        line,
                        0,
                        lineSize,
                        StandardCharsets.ISO_8859_1
                    );
                    Arrays.fill(line, 0, lineSize, (byte) 0);
                    lineSize = 0;
                    sawCr = false;
                    switch (phase) {
                        case START:
                            if (inbound) status = responseStatus(text);
                            else kind = requestKind(text);
                            hostSeen = hostMatches = chunked = false;
                            contentLength = -1;
                            encoding = null;
                            sectionSize = 0;
                            phase = Phase.HEADERS;
                            break;
                        case HEADERS:
                            if (text.isEmpty()) endHeaders();
                            else header(text, false);
                            break;
                        case CHUNK_SIZE:
                            remaining = chunkSize(text);
                            if (remaining == 0) {
                                phase = Phase.TRAILERS;
                                sectionSize = 0;
                            } else {
                                if (
                                    body != null &&
                                    remaining > MAX_BODY - body.size()
                                ) releaseBody();
                                phase = Phase.CHUNK_DATA;
                            }
                            break;
                        case TRAILERS:
                            if (text.isEmpty()) finish();
                            else header(text, true);
                            break;
                        default:
                            throw new AssertionError(phase);
                    }
                } else if (b == '\r') {
                    sawCr = true;
                } else {
                    if (b == '\n' || lineSize == MAX_LINE) throw BAD_FRAMING;
                    line[lineSize++] = b;
                }
            }

            private void header(
                String text,
                boolean trailer
            ) throws BadFraming {
                int colon = text.indexOf(':');
                if (colon <= 0 || tokenEnd(text, 0) != colon) throw BAD_FRAMING;
                checkFieldValue(text, colon + 1);
                String name = text.substring(0, colon).toLowerCase(Locale.ROOT);
                if (trailer) {
                    if (
                        name.equals("content-length") ||
                        name.equals("transfer-encoding")
                    ) throw BAD_FRAMING;
                    return;
                }
                switch (name) {
                    case "host":
                        if (inbound) break;
                        String host = fieldValue(text, colon + 1);
                        if (hostSeen || host.isEmpty()) throw BAD_FRAMING;
                        hostSeen = true;
                        hostMatches =
                            (kind == Kind.VIDEO &&
                                hostIs(host, "api.bilibili.com")) ||
                            (kind == Kind.MAIN_LIST &&
                                hostIs(host, "app.bilibili.com"));
                        break;
                    case "content-length":
                        if (contentLength >= 0) throw BAD_FRAMING;
                        String length = fieldValue(text, colon + 1);
                        contentLength = number(length, length.length(), 10);
                        break;
                    case "transfer-encoding":
                        if (
                            chunked ||
                            !fieldValue(text, colon + 1).equalsIgnoreCase(
                                "chunked"
                            )
                        ) throw BAD_FRAMING;
                        chunked = true;
                        break;
                    case "content-encoding":
                        if (inbound) {
                            String value = fieldValue(text, colon + 1);
                            // Only a single effective HTTP content coding is supported.
                            if (encoding != null) encoding =
                                Encoding.UNSUPPORTED;
                            else if (value.equalsIgnoreCase("gzip")) encoding =
                                Encoding.GZIP;
                            else encoding = value.equalsIgnoreCase("identity")
                                ? Encoding.IDENTITY
                                : Encoding.UNSUPPORTED;
                        }
                        break;
                    default:
                        break;
                }
            }

            private void endHeaders() throws BadFraming {
                if (chunked && contentLength >= 0) throw BAD_FRAMING;
                if (!inbound) {
                    if (
                        !hostSeen || pending.size() == MAX_PENDING
                    ) throw BAD_FRAMING;
                    if (
                        (kind == Kind.VIDEO || kind == Kind.MAIN_LIST) &&
                        !hostMatches
                    ) kind = Kind.OTHER;
                    writing = new Pending(kind);
                    pending.addLast(writing);
                } else {
                    Pending current = pending.getFirst();
                    if (
                        status == 101 ||
                        (current.kind == Kind.CONNECT &&
                            status >= 200 &&
                            status < 300)
                    ) {
                        throw BAD_FRAMING;
                    }
                    if (status < 200) {
                        if (chunked || contentLength >= 0) throw BAD_FRAMING;
                        phase = Phase.START; // 100/103 etc. do not consume the request.
                        return;
                    }
                    // A final reply before the write completed can cause the client to abandon its body.
                    if (!current.complete) throw BAD_FRAMING;
                    if (
                        status == 204 && (chunked || contentLength > 0)
                    ) throw BAD_FRAMING;
                    if (
                        current.kind == Kind.HEAD ||
                        status == 204 ||
                        status == 304
                    ) {
                        finish();
                        return;
                    }
                    if (!chunked && contentLength < 0) throw BAD_FRAMING; // No close-delimited guessing.
                    if (
                        status >= 200 &&
                        status < 300 &&
                        (current.kind == Kind.VIDEO ||
                            current.kind == Kind.MAIN_LIST) &&
                        encoding != Encoding.UNSUPPORTED &&
                        contentLength != 0 &&
                        contentLength <= MAX_BODY &&
                        capturing < MAX_CAPTURING
                    ) {
                        body = new ByteArrayOutputStream();
                        capturing++;
                    }
                }
                if (chunked) phase = Phase.CHUNK_SIZE;
                else if (contentLength > 0) {
                    remaining = contentLength;
                    phase = Phase.FIXED;
                } else finish(); // Requests without a declared length have no body.
            }

            private void finish() {
                phase = Phase.START;
                contentLength = -1;
                chunked = false;
                sectionSize = 0;
                if (!inbound) {
                    writing.complete = true;
                    writing = null;
                    kind = null;
                    hostSeen = hostMatches = false;
                    return;
                }
                Pending current = pending.removeFirst();
                Encoding bodyEncoding = encoding;
                encoding = null;
                if (body != null) {
                    byte[] payload = body.toByteArray();
                    releaseBody();
                    deliver(current.kind, bodyEncoding, payload);
                }
            }

            private void releaseBody() {
                if (body != null) {
                    body = null;
                    capturing--;
                }
            }
        }
    }

    private void deliver(Kind kind, Encoding encoding, byte[] body) {
        byte[] payload;
        String url;
        try {
            if (encoding == Encoding.GZIP) body = gunzip(body);
            if (kind == Kind.VIDEO) {
                if (!isJsonObject(body)) return;
                url = VIDEO_URL;
                payload = body;
            } else {
                if (!isSingleGrpcFrame(body)) return;
                url = MAIN_LIST_URL;
                payload = (
                    "{\"grpc\":\"" +
                    Base64.getEncoder().encodeToString(body) +
                    "\"}"
                ).getBytes(StandardCharsets.US_ASCII);
            }
        } catch (IOException ignored) {
            return; // Body decoding failure does not invalidate a known message boundary.
        }
        capture.accept(url, payload);
    }

    private static byte[] gunzip(byte[] body) throws IOException {
        try (
            GZIPInputStream input = new GZIPInputStream(
                new ByteArrayInputStream(body)
            )
        ) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > MAX_BODY - output.size()) throw new IOException();
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean isJsonObject(byte[] body) throws IOException {
        try (
            JsonReader reader = new JsonReader(
                new InputStreamReader(
                    new ByteArrayInputStream(body),
                    StandardCharsets.UTF_8.newDecoder()
                )
            )
        ) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return false;
            int depth = 0;
            do {
                switch (reader.peek()) {
                    case BEGIN_OBJECT:
                        if (++depth > 64) return false;
                        reader.beginObject();
                        break;
                    case BEGIN_ARRAY:
                        if (++depth > 64) return false;
                        reader.beginArray();
                        break;
                    case END_OBJECT:
                        reader.endObject();
                        depth--;
                        break;
                    case END_ARRAY:
                        reader.endArray();
                        depth--;
                        break;
                    case NAME:
                        reader.nextName();
                        break;
                    case END_DOCUMENT:
                        return false;
                    default:
                        reader.skipValue();
                }
            } while (depth > 0);
            return reader.peek() == JsonToken.END_DOCUMENT;
        }
    }

    private static boolean isSingleGrpcFrame(byte[] body) {
        if (body.length < 5 || (body[0] != 0 && body[0] != 1)) return false;
        int size =
            ((body[1] & 255) << 24) |
            ((body[2] & 255) << 16) |
            ((body[3] & 255) << 8) |
            (body[4] & 255);
        return size == body.length - 5;
    }

    private static Kind requestKind(String text) throws BadFraming {
        int first = tokenEnd(text, 0);
        int second = text.indexOf(' ', first + 1);
        if (
            first == 0 ||
            first == text.length() ||
            text.charAt(first) != ' ' ||
            second <= first + 1 ||
            !text.substring(second + 1).equals("HTTP/1.1")
        ) throw BAD_FRAMING;
        for (int i = first + 1; i < second; i++) {
            char c = text.charAt(i);
            if (c <= ' ' || c >= 127 || c == '#') throw BAD_FRAMING;
        }
        int query = text.indexOf('?', first + 1);
        int pathEnd = query < 0 ? second : query;
        switch (text.substring(0, first)) {
            case "HEAD":
                return Kind.HEAD;
            case "CONNECT":
                return Kind.CONNECT;
            case "GET":
                return exactPath(text, first + 1, pathEnd, VIDEO_PATH)
                    ? Kind.VIDEO
                    : Kind.OTHER;
            case "POST":
                return exactPath(text, first + 1, second, MAIN_LIST_PATH)
                    ? Kind.MAIN_LIST
                    : Kind.OTHER;
            default:
                return Kind.OTHER;
        }
    }

    private static int responseStatus(String text) throws BadFraming {
        if (
            !text.startsWith("HTTP/1.1 ") ||
            text.length() < 12 ||
            (text.length() > 12 && text.charAt(12) != ' ')
        ) throw BAD_FRAMING;
        int status = (int) number(text.substring(9, 12), 3, 10);
        if (status < 100 || status > 599) throw BAD_FRAMING;
        checkFieldValue(text, 13);
        return status;
    }

    private static boolean exactPath(
        String text,
        int start,
        int end,
        String path
    ) {
        return end - start == path.length() && text.startsWith(path, start);
    }

    private static boolean hostIs(String host, String expected) {
        return (
            host.equalsIgnoreCase(expected) ||
            host.equalsIgnoreCase(expected + ":443")
        );
    }

    private static long chunkSize(String text) throws BadFraming {
        int i = 0;
        while (i < text.length() && digit(text.charAt(i), 16) >= 0) i++;
        long size = number(text, i, 16);
        // Ignore chunk extensions after checking their token/quoted-string boundaries.
        while (i < text.length()) {
            i = owsEnd(text, i);
            if (
                i == text.length() || text.charAt(i++) != ';'
            ) throw BAD_FRAMING;
            i = owsEnd(text, i);
            int end = tokenEnd(text, i);
            if (end == i) throw BAD_FRAMING;
            i = owsEnd(text, end);
            if (i < text.length() && text.charAt(i) == '=') {
                i = owsEnd(text, i + 1);
                if (i < text.length() && text.charAt(i) == '"') {
                    boolean closed = false;
                    while (++i < text.length()) {
                        char c = text.charAt(i);
                        if (c == '"') {
                            closed = true;
                            i++;
                            break;
                        }
                        if (
                            c == '\\' && ++i == text.length()
                        ) throw BAD_FRAMING;
                        if (!fieldChar(text.charAt(i))) throw BAD_FRAMING;
                    }
                    if (!closed) throw BAD_FRAMING;
                } else {
                    end = tokenEnd(text, i);
                    if (end == i) throw BAD_FRAMING;
                    i = end;
                }
            }
        }
        return size;
    }

    private static long number(
        String text,
        int end,
        int radix
    ) throws BadFraming {
        if (end == 0) throw BAD_FRAMING;
        long value = 0;
        for (int i = 0; i < end; i++) {
            int digit = digit(text.charAt(i), radix);
            if (
                digit < 0 || value > (Long.MAX_VALUE - digit) / radix
            ) throw BAD_FRAMING;
            value = value * radix + digit;
        }
        return value;
    }

    private static int digit(char c, int radix) {
        if (c >= '0' && c <= '9') return c - '0';
        if (radix == 16 && c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (radix == 16 && c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static int tokenEnd(String text, int start) {
        int i = start;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (
                !(
                    (c >= 'a' && c <= 'z') ||
                    (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') ||
                    "!#$%&'*+-.^_`|~".indexOf(c) >= 0
                )
            ) break;
        }
        return i;
    }

    private static String fieldValue(String text, int start) {
        start = owsEnd(text, start);
        int end = text.length();
        while (end > start && ows(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static int owsEnd(String text, int i) {
        while (i < text.length() && ows(text.charAt(i))) i++;
        return i;
    }

    private static boolean ows(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean fieldChar(char c) {
        return c == '\t' || (c >= ' ' && c != 127);
    }

    private static void checkFieldValue(
        String text,
        int start
    ) throws BadFraming {
        for (int i = start; i < text.length(); i++) if (
            !fieldChar(text.charAt(i))
        ) throw BAD_FRAMING;
    }

    private static final class BadFraming extends Exception {

        private static final long serialVersionUID = 1L;

        BadFraming() {
            super(null, null, false, false);
        }
    }
}
