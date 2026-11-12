package me.melon.record;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import me.melon.record.parser.reddit.RedditCaptureFilter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Captures GraphQL without consuming or replacing Reddit's response body. */
public class RedditHook implements IXposedHookLoadPackage {

    private static final String HOST_APP = "com.reddit.frontpage";
    private static final String TAG = "RedditRecord: ";
    private static final String QUEUE_DIR = "index_record_queue";
    private static final String DUMP_DIR = "index_record_dump";
    private static final String DUMP_MARKER = "index_record_reddit_dump";
    private static final String AD_IDS_FILE = "index_record_reddit_ad_ids";
    private static final String BRIDGE_DESCRIPTOR =
        "me.melon.record.sync.IRecordSyncBridge";
    private static final int TRANSACTION_SYNC_FILE =
        IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final int MAX_REQUEST_BODY = 1024 * 1024;
    private static final int MAX_AD_IDS = 4096;
    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private static final Set<ClassLoader> hookedClassLoaders =
        Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> requestFailures =
        Collections.synchronizedSet(new HashSet<>());
    private static Context mContext;
    private static volatile IBinder mSyncBinder;
    // Accessed only on the single capture/sync executor, including legacy-queue preparation.
    private static final Set<String> advertisingIds = new LinkedHashSet<>();
    private static boolean queuePrepared;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam)
        throws Throwable {
        if (
            !HOST_APP.equals(lpparam.packageName) ||
            !HOST_APP.equals(lpparam.processName)
        ) return;
        setupOkHttpHook(lpparam.classLoader);
        XposedHelpers.findAndHookMethod(
            Application.class,
            "attach",
            Context.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mContext != null) return;
                    mContext = (Context) param.args[0];
                    if (mContext == null) return;
                    setupOkHttpHook(mContext.getClassLoader());
                    setupSyncReceiver(mContext);
                    executor.submit(RedditHook::trySyncToApp);
                }
            }
        );
    }

    private static void setupOkHttpHook(ClassLoader loader) {
        if (loader == null || hookedClassLoaders.contains(loader)) return;
        Class<?> call = XposedHelpers.findClassIfExists("okhttp3.internal.connection.RealCall", loader);
        if (call == null || !hookedClassLoaders.add(loader)) return;
        try {
            // Observe the completed call without a Proxy: a minified Interceptor interface can
            // lose its throws declaration and wrap IOException in UndeclaredThrowableException.
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                call, "getResponseWithInterceptorChain$okhttp", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || param.getResult() == null) return;
                        try {
                            Object request = XposedHelpers.callMethod(param.thisObject, "request");
                            String url = XposedHelpers.callMethod(request, "url").toString();
                            if (isTargetUrl(url)) captureResponse(request, param.getResult(), url);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "Capture failed: " + t.getClass().getSimpleName());
                        }
                    }
                });
            if (hooks.isEmpty()) throw new IllegalStateException("OkHttp call method unavailable");
            XposedBridge.log(TAG + "OkHttp completed-call hook installed");
        } catch (Throwable t) {
            hookedClassLoaders.remove(loader);
            XposedBridge.log(TAG + "Hook setup failed: " + t);
        }
    }

    private static boolean isTargetUrl(String url) {
        Uri uri = Uri.parse(url);
        return (
            "https".equalsIgnoreCase(uri.getScheme()) &&
            "gql-fed.reddit.com".equalsIgnoreCase(uri.getHost())
        );
    }

    private static void captureResponse(
        Object request,
        Object response,
        String url
    ) throws Throwable {
        if (response == null || mContext == null) return;
        int code = (int) XposedHelpers.callMethod(response, "code");
        if (code < 200 || code >= 300) return;
        Object body = XposedHelpers.callMethod(response, "body");
        if (
            body == null ||
            (long) XposedHelpers.callMethod(body, "contentLength") > MAX_BODY
        ) return;
        Object peek = XposedHelpers.callMethod(
            response,
            "peekBody",
            (long) MAX_BODY + 1
        );
        if (peek == null) return;
        Object source = XposedHelpers.callMethod(peek, "source");
        final Object requestInfo = readRequest(request, source);
        final byte[] bytes = (byte[]) XposedHelpers.callMethod(peek, "bytes");
        if (
            bytes == null || bytes.length == 0 || bytes.length > MAX_BODY
        ) return;
        executor.submit(() -> saveCapture(url, requestInfo, bytes));
    }

    private static Object readRequest(Object request, Object detachedSource) {
        String stage = "body";
        try {
            Object body = XposedHelpers.callMethod(request, "body");
            if (body == null) return JSONObject.NULL;
            stage = "flags";
            if (
                Boolean.TRUE.equals(
                    XposedHelpers.callMethod(body, "isOneShot")
                ) ||
                Boolean.TRUE.equals(XposedHelpers.callMethod(body, "isDuplex"))
            ) return JSONObject.NULL;
            stage = "size";
            long size = (long) XposedHelpers.callMethod(body, "contentLength");
            // Apollo's streaming JSON body reports -1 even for small, repeatable queries.
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
                XposedBridge.log(
                    TAG + "Request metadata unavailable at " + stage + ": " + t
                );
            }
        }
        return JSONObject.NULL;
    }

    private static Object createRequestBuffer(Object source, Class<?> sinkType)
        throws Throwable {
        Class<?> type = source.getClass();
        if (sinkType.isAssignableFrom(type)) return emptyRequestBuffer(source);
        // Newer OkHttp may wrap the detached buffer in RealBufferedSource. Find its buffer
        // by type, not an R8-specific Okio class/field name, and instantiate a fresh one.
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
            java.lang.reflect.Constructor<?> constructor = seed
                .getClass()
                .getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException ignored) {
            // R8 can inline/remove Buffer's empty constructor. Its public clone still creates a
            // detached buffer through normal app code. Drain ONLY the clone, never Reddit's source.
            Object buffer = XposedHelpers.callMethod(seed, "clone");
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
        // No request headers, cookies or access tokens are captured.
        for (String key : new String[] {
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
            GZIPInputStream in = new GZIPInputStream(
                new ByteArrayInputStream(bytes)
            );
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (out.size() + n > MAX_BODY) throw new IOException(
                    "Body too large"
                );
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void saveCapture(String url, Object request, byte[] bytes) {
        try {
            Object response = new JSONTokener(
                new String(decompress(bytes), StandardCharsets.UTF_8)
            ).nextValue();
            if (
                !(response instanceof JSONObject) &&
                !(response instanceof JSONArray)
            ) return;
            prepareQueue();
            int oldAdCount = advertisingIds.size();
            JSONObject filtered = RedditCaptureFilter.envelope(
                response,
                request,
                advertisingIds
            );
            if (advertisingIds.size() != oldAdCount) persistAdvertisingIds();
            if (filtered == null) return; // Heartbeats, config, identity, ads and other roots never enter the queue.
            long timestamp = System.currentTimeMillis();
            String token = timestamp + "_" + System.nanoTime();
            if (new File(mContext.getCacheDir(), DUMP_MARKER).exists()) {
                File dump = new File(mContext.getCacheDir(), DUMP_DIR);
                File[] files = dump.listFiles((dir, name) ->
                    name.endsWith(".meta")
                );
                // Diagnostics are opt-in, private, bounded, and not deleted by queue sync.
                if (files == null || files.length < 96) {
                    JSONObject diagnostic = new JSONObject()
                        .put("request", request)
                        .put("response", response);
                    writeCapture(
                        dump,
                        token,
                        url,
                        diagnostic.toString().getBytes(StandardCharsets.UTF_8),
                        timestamp
                    );
                }
            }
            byte[] payload = filtered
                .toString()
                .getBytes(StandardCharsets.UTF_8);
            writeCapture(
                new File(mContext.getCacheDir(), QUEUE_DIR),
                token,
                url,
                payload,
                timestamp
            );
            XposedBridge.log(TAG + "Captured " + bytes.length + " bytes");
            trySyncToApp();
        } catch (Throwable t) {
            XposedBridge.log(
                TAG + "Save failed: " + t.getClass().getSimpleName()
            );
        }
    }

    private static void writeCapture(
        File directory,
        String token,
        String url,
        byte[] payload,
        long timestamp
    ) throws IOException {
        if (
            !directory.isDirectory() && !directory.mkdirs()
        ) throw new IOException("Cannot create queue");
        try (
            FileOutputStream out = new FileOutputStream(
                new File(directory, token + ".bin")
            )
        ) {
            out.write(payload);
        }
        String meta =
            url.replace('\n', ' ').replace('\r', ' ') +
            "\n" +
            HOST_APP +
            "\napplication/json\n" +
            timestamp +
            "\n";
        File pending = new File(directory, token + ".meta.tmp");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(meta.getBytes(StandardCharsets.UTF_8));
        }
        if (
            !pending.renameTo(new File(directory, token + ".meta"))
        ) throw new IOException("Cannot publish capture");
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void setupSyncReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (
                    !"me.melon.record.ACTION_SYNC".equals(intent.getAction())
                ) return;
                Bundle extras = intent.getExtras();
                if (extras != null && extras.getBinder("sync_binder") != null) {
                    mSyncBinder = extras.getBinder("sync_binder");
                }
                executor.submit(RedditHook::trySyncToApp);
            }
        };
        IntentFilter filter = new IntentFilter("me.melon.record.ACTION_SYNC");
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    receiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Sync receiver failed: " + t);
        }
    }

    private static void trySyncToApp() {
        if (mContext == null) return;
        prepareQueue();
        File queue = new File(mContext.getCacheDir(), QUEUE_DIR);
        File[] files = queue.listFiles((dir, name) -> name.endsWith(".meta"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File meta : files) {
            String token = meta
                .getName()
                .substring(0, meta.getName().length() - 5);
            File payload = new File(queue, token + ".bin");
            try {
                List<String> lines = Files.readAllLines(
                    meta.toPath(),
                    StandardCharsets.UTF_8
                );
                if (lines.size() < 4 || !payload.isFile()) continue;
                String url = lines.get(0);
                String hostApp = lines.get(1);
                long timestamp = Long.parseLong(lines.get(3));
                // Also sanitize captures produced by older debug hooks, after learning ads from the
                // entire pending queue. A sparse ad may otherwise precede its identifying payload.
                if (!sanitizeQueuedPayload(payload)) {
                    meta.delete();
                    payload.delete();
                    continue;
                }
                if (
                    syncViaBinder(payload, url, hostApp, timestamp) ||
                    syncViaProvider(payload, token, url, hostApp, timestamp)
                ) {
                    meta.delete();
                    payload.delete();
                } else {
                    return; // Retain the queue when the recorder is unavailable.
                }
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private static void prepareQueue() {
        if (queuePrepared || mContext == null) return;
        queuePrepared = true;
        File state = new File(mContext.getCacheDir(), AD_IDS_FILE);
        try {
            if (state.isFile()) for (String id : Files.readAllLines(
                state.toPath(),
                StandardCharsets.UTF_8
            )) {
                if (
                    id.startsWith("t3_") &&
                    id.equals(RedditCaptureFilter.fullname(id, "t3_"))
                ) advertisingIds.add(id);
            }
            File queue = new File(mContext.getCacheDir(), QUEUE_DIR);
            File[] payloads = queue.listFiles((dir, name) ->
                name.endsWith(".bin")
            );
            if (payloads != null) for (File payload : payloads) {
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
            persistAdvertisingIds();
        } catch (Throwable t) {
            XposedBridge.log(
                TAG + "Ad context unavailable: " + t.getClass().getSimpleName()
            );
        }
    }

    private static void persistAdvertisingIds() throws IOException {
        while (advertisingIds.size() > MAX_AD_IDS)
            advertisingIds.remove(advertisingIds.iterator().next());
        File state = new File(mContext.getCacheDir(), AD_IDS_FILE);
        File pending = new File(mContext.getCacheDir(), AD_IDS_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            for (String id : advertisingIds)
                out.write((id + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!pending.renameTo(state)) throw new IOException(
            "Cannot publish ad context"
        );
    }

    private static boolean sanitizeQueuedPayload(File payload)
        throws Exception {
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
            if (!pending.renameTo(payload)) throw new IOException(
                "Cannot sanitize queued capture"
            );
        }
        return true;
    }

    private static boolean syncViaBinder(
        File payload,
        String url,
        String hostApp,
        long timestamp
    ) {
        IBinder binder = mSyncBinder;
        if (binder == null || !binder.isBinderAlive()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try (
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                payload,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
        ) {
            data.writeInterfaceToken(BRIDGE_DESCRIPTOR);
            data.writeString(url);
            data.writeString(hostApp);
            data.writeLong(timestamp);
            data.writeInt(1);
            pfd.writeToParcel(data, 0);
            if (
                !binder.transact(TRANSACTION_SYNC_FILE, data, reply, 0)
            ) return false;
            reply.readException();
            return reply.readInt() == 1;
        } catch (Throwable t) {
            if (t instanceof android.os.DeadObjectException) mSyncBinder = null;
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static boolean syncViaProvider(
        File payload,
        String token,
        String url,
        String hostApp,
        long timestamp
    ) {
        Uri target = Uri.parse(
            "content://me.melon.record.provider/raw_payload/" + token
        );
        try (
            ParcelFileDescriptor pfd = mContext
                .getContentResolver()
                .openFileDescriptor(target, "w")
        ) {
            if (pfd == null) return false;
            try (
                FileInputStream in = new FileInputStream(payload);
                FileOutputStream out = new FileOutputStream(
                    pfd.getFileDescriptor()
                )
            ) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.flush();
            }
            ContentValues values = new ContentValues();
            values.put("url", url);
            values.put("hostApp", hostApp);
            values.put("payloadToken", token);
            values.put("timestamp", timestamp);
            return (
                mContext
                    .getContentResolver()
                    .insert(
                        Uri.parse(
                            "content://me.melon.record.provider/raw_data"
                        ),
                        values
                    ) != null
            );
        } catch (Throwable ignored) {
            return false;
        }
    }
}
