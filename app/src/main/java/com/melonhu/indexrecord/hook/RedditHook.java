package com.melonhu.indexrecord.hook;

import android.content.Context;
import android.net.Uri;
import com.melonhu.indexrecord.parser.reddit.RedditCaptureFilter;
import com.melonhu.indexrecord.sync.CaptureQueue;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Captures GraphQL without consuming or replacing Reddit's response body.
 */
public class RedditHook implements PlatformHook {

    public static final String HOST_APP = "com.reddit.frontpage";
    private static final String DUMP_DIR = "index_record_dump";
    private static final String DUMP_MARKER = "index_record_reddit_dump";
    private static final String AD_IDS_FILE = "index_record_reddit_ad_ids";
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final int MAX_REQUEST_BODY = 1024 * 1024;
    private static final int MAX_AD_IDS = 4096;

    private final Set<ClassLoader> hookedClassLoaders = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> requestFailures = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> advertisingIds = new LinkedHashSet<>();
    private boolean queuePrepared;

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        if (!HOST_APP.equals(context.getPackageName()) || !HOST_APP.equals(context.getProcessName())) {
            return;
        }
        setupOkHttpHook(context, classLoader);
        context.onApplicationAttach(attachedContext -> {
            HookCaptureClient client = context.getCaptureClient();
            if (client != null) {
                client.setBeforeSync(() -> prepareQueue(context));
                client.setPayloadFilter(this::sanitizeQueuedPayload);
            }
            setupOkHttpHook(context, attachedContext.getClassLoader());
        });
    }

    private void setupOkHttpHook(HookContext context, ClassLoader loader) {
        if (loader == null || !hookedClassLoaders.add(loader)) return;
        Class<?> call = ReflectionHelper.findClassIfExists(
            "okhttp3.internal.connection.RealCall",
            loader
        );
        if (call == null) {
            hookedClassLoaders.remove(loader);
            return;
        }

        try {
            java.util.List<io.github.libxposed.api.XposedInterface.HookHandle> hooks = ReflectionHelper.hookAllMethods(
                context.getXposed(),
                call,
                "getResponseWithInterceptorChain$okhttp",
                chain -> {
                    Object response = chain.proceed();
                    if (response != null) {
                        try {
                            Object request = ReflectionHelper.callMethod(
                                chain.getThisObject(),
                                "request"
                            );
                            String url = ReflectionHelper.callMethod(
                                request,
                                "url"
                            ).toString();
                            if (isTargetUrl(url)) {
                                captureResponse(context, request, response, url);
                            }
                        } catch (Throwable t) {
                            context.log("Capture failed: " + t.getClass().getSimpleName(), t);
                        }
                    }
                    return response;
                }
            );

            if (hooks.isEmpty()) {
                throw new IllegalStateException("OkHttp call method unavailable");
            }
            context.log("OkHttp completed-call hook installed");
        } catch (Throwable t) {
            hookedClassLoaders.remove(loader);
            context.log("Hook setup failed: " + t, t);
        }
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme != null && host != null) {
                return "https".equalsIgnoreCase(scheme) && "gql-fed.reddit.com".equalsIgnoreCase(host);
            }
        } catch (Throwable ignored) {}
        return url.regionMatches(true, 0, "https://gql-fed.reddit.com", 0, 26);
    }

    private void captureResponse(
        HookContext context,
        Object request,
        Object response,
        String url
    ) throws Throwable {
        if (response == null || context.getAndroidContext() == null) return;
        int code = (int) ReflectionHelper.callMethod(response, "code");
        if (code < 200 || code >= 300) return;
        Object body = ReflectionHelper.callMethod(response, "body");
        if (
            body == null ||
            (long) ReflectionHelper.callMethod(body, "contentLength") > MAX_BODY
        ) return;
        Object peek = ReflectionHelper.callMethod(
            response,
            "peekBody",
            (long) MAX_BODY + 1
        );
        if (peek == null) return;
        Object source = ReflectionHelper.callMethod(peek, "source");
        final Object requestInfo = readRequest(context, request, source);
        final byte[] bytes = (byte[]) ReflectionHelper.callMethod(peek, "bytes");
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BODY) return;
        context.getExecutor().submit(() -> saveCapture(context, url, requestInfo, bytes));
    }

    private Object readRequest(HookContext context, Object request, Object detachedSource) {
        String stage = "body";
        try {
            Object body = ReflectionHelper.callMethod(request, "body");
            if (body == null) return JSONObject.NULL;
            stage = "flags";
            if (
                Boolean.TRUE.equals(ReflectionHelper.callMethod(body, "isOneShot")) ||
                Boolean.TRUE.equals(ReflectionHelper.callMethod(body, "isDuplex"))
            ) return JSONObject.NULL;
            stage = "size";
            long size = (long) ReflectionHelper.callMethod(body, "contentLength");
            if (size > MAX_REQUEST_BODY) return JSONObject.NULL;
            Method writeTo = null;
            for (Method method : body.getClass().getMethods()) {
                if (
                    method.getName().equals("writeTo") &&
                    method.getParameterTypes().length == 1
                ) {
                    writeTo = method;
                    break;
                }
            }
            if (writeTo == null) return JSONObject.NULL;
            stage = "buffer";
            Object buffer = createRequestBuffer(
                detachedSource,
                writeTo.getParameterTypes()[0]
            );
            if (buffer == null) return JSONObject.NULL;
            stage = "write";
            writeTo.setAccessible(true);
            writeTo.invoke(body, buffer);
            Class<?> bufferClass = buffer.getClass();
            stage = "read";
            for (Method method : bufferClass.getMethods()) {
                if (
                    method.getParameterTypes().length == 0 &&
                    method.getReturnType() == byte[].class
                ) {
                    method.setAccessible(true);
                    byte[] bytes = (byte[]) method.invoke(buffer);
                    if (bytes.length > MAX_REQUEST_BODY) return JSONObject.NULL;
                    Object json = new JSONTokener(
                        new String(decompress(bytes), StandardCharsets.UTF_8)
                    ).nextValue();
                    return requestMetadata(json);
                }
            }
        } catch (Throwable t) {
            if (requestFailures.add(stage + t.getClass().getName())) {
                context.log("Request metadata unavailable at " + stage + ": " + t, t);
            }
        }
        return JSONObject.NULL;
    }

    private static Object createRequestBuffer(Object source, Class<?> sinkType) throws Throwable {
        Class<?> type = source.getClass();
        if (sinkType.isAssignableFrom(type)) return emptyRequestBuffer(source);
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                Class<?> candidate = field.getType();
                if (
                    !candidate.isInterface() &&
                    !Modifier.isAbstract(candidate.getModifiers()) &&
                    sinkType.isAssignableFrom(candidate)
                ) {
                    field.setAccessible(true);
                    Object seed = field.get(source);
                    if (seed != null) return emptyRequestBuffer(seed);
                }
            }
        }
        return null;
    }

    private static Object emptyRequestBuffer(Object seed) throws Throwable {
        try {
            Constructor<?> constructor = seed
                .getClass()
                .getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException ignored) {
            Object buffer = ReflectionHelper.callMethod(seed, "clone");
            if (buffer == null || buffer == seed) return null;
            for (Method method : buffer.getClass().getMethods()) {
                if (
                    method.getParameterTypes().length == 0 &&
                    method.getReturnType() == byte[].class
                ) {
                    method.setAccessible(true);
                    method.invoke(buffer);
                    return buffer;
                }
            }
            return null;
        }
    }

    private static Object requestMetadata(Object json) throws Exception {
        if (json instanceof JSONArray) {
            JSONArray result = new JSONArray();
            for (int i = 0; i < ((JSONArray) json).length(); i++) {
                result.put(requestMetadata(((JSONArray) json).get(i)));
            }
            return result;
        }
        if (!(json instanceof JSONObject)) return JSONObject.NULL;
        JSONObject source = (JSONObject) json;
        JSONObject result = new JSONObject();
        for (String key : new String[]{
            "operationName",
            "variables",
            "extensions",
            "id",
            "query",
        }) {
            if (source.has(key)) result.put(key, source.get(key));
        }
        return result;
    }

    private static byte[] decompress(byte[] bytes) throws IOException {
        if (
            bytes.length < 2 ||
            (bytes[0] & 255) != 31 ||
            (bytes[1] & 255) != 139
        ) return bytes;
        try (
            GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes));
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (out.size() + n > MAX_BODY) throw new IOException("Body too large");
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void saveCapture(HookContext context, String url, Object request, byte[] bytes) {
        try {
            Object response = new JSONTokener(
                new String(decompress(bytes), StandardCharsets.UTF_8)
            ).nextValue();
            if (
                !(response instanceof JSONObject) &&
                !(response instanceof JSONArray)
            ) return;
            prepareQueue(context);
            int oldAdCount = advertisingIds.size();
            JSONObject filtered = RedditCaptureFilter.envelope(
                response,
                request,
                advertisingIds
            );
            if (advertisingIds.size() != oldAdCount) persistAdvertisingIds(context);
            if (filtered == null) return;
            long timestamp = System.currentTimeMillis();

            Context androidCtx = context.getAndroidContext();
            if (androidCtx != null && new File(androidCtx.getCacheDir(), DUMP_MARKER).exists()) {
                File dump = new File(androidCtx.getCacheDir(), DUMP_DIR);
                File[] files = dump.listFiles((dir, name) -> name.endsWith(".meta"));
                if (files == null || files.length < 96) {
                    JSONObject diagnostic = new JSONObject()
                        .put("request", request)
                        .put("response", response);
                    new CaptureQueue(dump, (message, error) ->
                        context.log(message + (error == null ? "" : ": " + error))
                    ).enqueue(
                        url,
                        diagnostic.toString().getBytes(StandardCharsets.UTF_8),
                        "application/json",
                        HOST_APP,
                        timestamp
                    );
                }
            }
            byte[] payload = filtered
                .toString()
                .getBytes(StandardCharsets.UTF_8);
            HookCaptureClient client = context.getCaptureClient();
            if (client != null) {
                client.enqueue(url, payload, "application/json", HOST_APP, timestamp);
                context.log("Captured " + bytes.length + " bytes");
                client.sync();
            }
        } catch (Throwable t) {
            context.log("Save failed: " + t.getClass().getSimpleName(), t);
        }
    }

    private synchronized void prepareQueue(HookContext context) {
        if (queuePrepared || context.getAndroidContext() == null) return;
        queuePrepared = true;
        File state = new File(context.getAndroidContext().getCacheDir(), AD_IDS_FILE);
        try {
            if (state.isFile()) {
                for (String id : Files.readAllLines(state.toPath(), StandardCharsets.UTF_8)) {
                    if (
                        id.startsWith("t3_") &&
                        id.equals(RedditCaptureFilter.fullname(id, "t3_"))
                    ) {
                        advertisingIds.add(id);
                    }
                }
            }
            File queue = new File(
                context.getAndroidContext().getCacheDir(),
                HookCaptureClient.QUEUE_DIR
            );
            File[] payloads = queue.listFiles((dir, name) -> name.endsWith(".bin"));
            if (payloads != null) {
                for (File payload : payloads) {
                    if (payload.length() > MAX_BODY + MAX_REQUEST_BODY) continue;
                    try {
                        JSONObject envelope = new JSONObject(
                            new String(
                                Files.readAllBytes(payload.toPath()),
                                StandardCharsets.UTF_8
                            )
                        );
                        RedditCaptureFilter.collectAdvertisingIds(
                            envelope.opt("response"),
                            advertisingIds
                        );
                    } catch (Throwable ignored) {}
                }
            }
            persistAdvertisingIds(context);
        } catch (Throwable t) {
            context.log("Ad context unavailable: " + t.getClass().getSimpleName(), t);
        }
    }

    private void persistAdvertisingIds(HookContext context) throws IOException {
        while (advertisingIds.size() > MAX_AD_IDS) {
            advertisingIds.remove(advertisingIds.iterator().next());
        }
        if (context.getAndroidContext() == null) return;
        File state = new File(context.getAndroidContext().getCacheDir(), AD_IDS_FILE);
        File pending = new File(context.getAndroidContext().getCacheDir(), AD_IDS_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            for (String id : advertisingIds) {
                out.write((id + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        if (!pending.renameTo(state)) {
            throw new IOException("Cannot publish ad context");
        }
    }

    private boolean sanitizeQueuedPayload(File payload) throws Exception {
        JSONObject old = new JSONObject(
            new String(
                Files.readAllBytes(payload.toPath()),
                StandardCharsets.UTF_8
            )
        );
        JSONObject filtered = RedditCaptureFilter.envelope(
            old.opt("response"),
            old.opt("request"),
            advertisingIds
        );
        if (filtered == null) return false;
        String json = filtered.toString();
        if (!json.equals(old.toString())) {
            File pending = new File(
                payload.getParentFile(),
                payload.getName() + ".tmp"
            );
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (!pending.renameTo(payload)) {
                throw new IOException("Cannot sanitize queued capture");
            }
        }
        return true;
    }
}
