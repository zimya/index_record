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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadsHook implements IXposedHookLoadPackage {

    private static Context mContext;
    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private static final String TAG = "ThreadsRecord: ";
    private static final String QUEUE_DIR = "index_record_queue";
    private static final Set<ClassLoader> hookedClassLoaders =
        Collections.synchronizedSet(new HashSet<>());
    private static final String BRIDGE_DESCRIPTOR =
        "me.melon.record.sync.IRecordSyncBridge";
    private static final int TRANSACTION_SYNC_FILE =
        IBinder.FIRST_CALL_TRANSACTION + 1;
    private static volatile IBinder mSyncBinder = null;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam)
        throws Throwable {
        if (
            !"com.instagram.barcelona".equals(lpparam.packageName) ||
            !lpparam.packageName.equals(lpparam.processName)
        ) return;

        XposedBridge.log(
            TAG +
                "Loading for " +
                lpparam.packageName +
                " in " +
                lpparam.processName
        );

        try {
            setupTigonHook(lpparam.classLoader);
        } catch (Throwable ignored) {}

        XposedHelpers.findAndHookMethod(
            Application.class,
            "attach",
            Context.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param)
                    throws Throwable {
                    if (mContext != null) return;
                    mContext = (Context) param.args[0];
                    if (mContext == null) return;
                    XposedBridge.log(
                        TAG + "Context attached, setting up hook."
                    );
                    setupTigonHook(mContext.getClassLoader());
                    setupSyncReceiver(mContext);
                    executor.submit(() -> trySyncToApp());
                }
            }
        );
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void setupSyncReceiver(Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (
                        "me.melon.record.ACTION_SYNC".equals(intent.getAction())
                    ) {
                        XposedBridge.log(
                            TAG +
                                "Received ACTION_SYNC broadcast, syncing queue..."
                        );
                        Bundle extras = intent.getExtras();
                        if (extras != null) {
                            IBinder binder = extras.getBinder("sync_binder");
                            if (binder != null) {
                                mSyncBinder = binder;
                                XposedBridge.log(
                                    TAG + "Acquired live sync binder from app!"
                                );
                            }
                        }
                        executor.submit(() -> trySyncToApp());
                    }
                }
            };
            IntentFilter filter = new IntentFilter(
                "me.melon.record.ACTION_SYNC"
            );

            if (Build.VERSION.SDK_INT >= 33) {
                int RECEIVER_EXPORTED = 2;
                try {
                    Method regMethod = Context.class.getMethod(
                        "registerReceiver",
                        BroadcastReceiver.class,
                        IntentFilter.class,
                        int.class
                    );
                    regMethod.invoke(
                        context,
                        receiver,
                        filter,
                        RECEIVER_EXPORTED
                    );
                } catch (Throwable e) {
                    context.registerReceiver(receiver, filter);
                }
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Throwable ignored) {}
    }

    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final java.util.Map<Object, Capture> captures =
        Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Set<Class<?>> callbackClasses =
        Collections.synchronizedSet(new HashSet<>());

    private static class Capture {

        final String url;
        final java.io.ByteArrayOutputStream body =
            new java.io.ByteArrayOutputStream();
        boolean overflow;

        Capture(String url) {
            this.url = url;
        }

        synchronized void append(java.nio.ByteBuffer input) {
            // Native buffers belong to Tigon and may be released by the original callback.
            java.nio.ByteBuffer copy = input.duplicate();
            if (overflow || body.size() + copy.remaining() > MAX_BODY) {
                overflow = true;
                body.reset();
                return;
            }
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            body.write(bytes, 0, bytes.length);
        }
    }

    private void setupTigonHook(ClassLoader loader) {
        if (loader == null || hookedClassLoaders.contains(loader)) return;
        Class<?> service = XposedHelpers.findClassIfExists(
            "com.facebook.tigon.TigonXplatService",
            loader
        );
        if (service == null) return;
        if (!hookedClassLoaders.add(loader)) return;
        XposedBridge.hookAllMethods(
            service,
            "sendRequest",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String url = (String) XposedHelpers.callMethod(
                            param.args[0],
                            "url"
                        );
                        if (!isTargetUrl(url)) return;
                        for (Object arg : param.args) {
                            if (arg == null) continue;
                            Class<?> type = arg.getClass();
                            for (
                                Class<?> c = type;
                                c != null;
                                c = c.getSuperclass()
                            ) {
                                if (
                                    "com.facebook.tigon.TigonCallbacks".equals(
                                        c.getName()
                                    )
                                ) {
                                    installCallbacks(type);
                                    captures.put(arg, new Capture(url));
                                    return;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(
                            TAG + "Request capture setup failed: " + t
                        );
                    }
                }
            }
        );
        XposedBridge.log(TAG + "Tigon sendRequest hook installed");
    }

    private void installCallbacks(Class<?> type) {
        for (
            Class<?> c = type;
            c != null && c != Object.class;
            c = c.getSuperclass()
        ) {
            if (!callbackClasses.add(c)) continue;
            XposedBridge.hookAllMethods(
                c,
                "onBody",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Capture capture = captures.get(param.thisObject);
                        if (capture == null || param.args.length == 0) return;
                        try {
                            if (param.args[0] instanceof java.nio.ByteBuffer) {
                                capture.append(
                                    (java.nio.ByteBuffer) param.args[0]
                                );
                            }
                        } catch (Throwable t) {
                            capture.overflow = true;
                        }
                    }
                }
            );
            XposedBridge.hookAllMethods(
                c,
                "onEOM",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Capture capture = captures.remove(param.thisObject);
                        if (
                            capture == null ||
                            capture.overflow ||
                            mContext == null
                        ) return;
                        final byte[] bytes;
                        synchronized (capture) {
                            bytes = capture.body.toByteArray();
                        }
                        executor.submit(() -> saveCapture(capture.url, bytes));
                    }
                }
            );
            XposedBridge.hookAllMethods(
                c,
                "onError",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        captures.remove(param.thisObject);
                    }
                }
            );
            XposedBridge.hookAllMethods(
                c,
                "onWillRetry",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Capture capture = captures.get(param.thisObject);
                        if (capture != null) synchronized (capture) {
                            capture.body.reset();
                            capture.overflow = false;
                        }
                    }
                }
            );
        }
    }

    private static boolean isTargetUrl(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (
            host == null ||
            !(
                host.equals("instagram.com") ||
                host.endsWith(".instagram.com") ||
                host.equals("threads.net") ||
                host.endsWith(".threads.net") ||
                host.equals("threads.com") ||
                host.endsWith(".threads.com")
            )
        ) return false;
        String path = uri.getPath();
        if (
            path != null &&
            (path.contains("notifications") ||
                path.contains("logging") ||
                path.contains("heartbeat") ||
                path.contains("qp/batch_fetch"))
        ) return false;
        // GraphQL shares one endpoint: inspect its response rather than saving all calls.
        return (
            path != null &&
            (path.contains("/text_feed/") ||
                path.contains("/feed/") ||
                path.contains("/media/") ||
                path.contains("/graphql"))
        );
    }

    private void saveCapture(String url, byte[] bytes) {
        try {
            if (
                bytes.length >= 2 &&
                (bytes[0] & 255) == 31 &&
                (bytes[1] & 255) == 139
            ) {
                try (
                    java.util.zip.GZIPInputStream in =
                        new java.util.zip.GZIPInputStream(
                            new java.io.ByteArrayInputStream(bytes)
                        );
                    java.io.ByteArrayOutputStream out =
                        new java.io.ByteArrayOutputStream()
                ) {
                    byte[] chunk = new byte[16384];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        if (out.size() + n > MAX_BODY) return;
                        out.write(chunk, 0, n);
                    }
                    bytes = out.toByteArray();
                }
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (!json.contains("\"text_post_app_info\"")) return;
            enqueueCapture(url, bytes, "application/json");
            XposedBridge.log(
                TAG +
                    "Captured " +
                    bytes.length +
                    " bytes: " +
                    Uri.parse(url).getPath()
            );
            trySyncToApp();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Capture failed: " + t);
        }
    }

    private void trySyncToApp() {
        if (mContext == null) return;
        try {
            Uri uri = Uri.parse("content://me.melon.record.provider/raw_data");
            File queueDir = new File(mContext.getCacheDir(), QUEUE_DIR);
            if (!queueDir.exists()) return;
            File[] metaFiles = queueDir.listFiles(
                (dir, name) -> name != null && name.endsWith(".meta")
            );
            if (metaFiles == null || metaFiles.length == 0) return;

            java.util.Arrays.sort(
                metaFiles,
                java.util.Comparator.comparing(File::getName)
            );

            for (File meta : metaFiles) {
                String token = meta
                    .getName()
                    .substring(0, meta.getName().length() - ".meta".length());
                File payloadFile = new File(queueDir, token + ".bin");
                if (!payloadFile.exists()) {
                    meta.delete();
                    continue;
                }
                String[] metaParts = readMeta(meta);
                if (metaParts == null || metaParts.length < 2) {
                    meta.delete();
                    payloadFile.delete();
                    continue;
                }
                String url = metaParts[0];
                String hostApp = metaParts[1];
                long captureTs = 0L;
                if (metaParts.length >= 4) {
                    try {
                        captureTs = Long.parseLong(metaParts[3].trim());
                    } catch (Throwable ignored) {}
                }
                if (captureTs <= 0L) {
                    try {
                        int idx = token.indexOf('_');
                        String prefix =
                            idx > 0 ? token.substring(0, idx) : token;
                        captureTs = Long.parseLong(prefix);
                    } catch (Throwable ignored) {}
                }
                if (captureTs <= 0L) captureTs = System.currentTimeMillis();

                // 优先通过 Binder 直传同步，彻底规避 Android 11+ Package Visibility / ContentProvider 限制
                if (mSyncBinder != null && mSyncBinder.isBinderAlive()) {
                    if (syncViaBinder(payloadFile, url, hostApp, captureTs)) {
                        meta.delete();
                        payloadFile.delete();
                        XposedBridge.log(
                            TAG + "Synced via Binder and deleted " + token
                        );
                        continue;
                    }
                }

                Uri payloadUri = Uri.parse(
                    "content://me.melon.record.provider/raw_payload/" + token
                );
                ParcelFileDescriptor pfd = null;
                try {
                    pfd = mContext
                        .getContentResolver()
                        .openFileDescriptor(payloadUri, "w");
                    if (pfd == null) return;
                    try (
                        FileInputStream fis = new FileInputStream(payloadFile);
                        FileOutputStream fos = new FileOutputStream(
                            pfd.getFileDescriptor()
                        )
                    ) {
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                        fos.flush();
                    }
                } catch (Throwable t) {
                    return;
                } finally {
                    if (pfd != null) {
                        try {
                            pfd.close();
                        } catch (Throwable ignored) {}
                    }
                }

                ContentValues values = new ContentValues();
                values.put("url", url);
                values.put("hostApp", hostApp);
                values.put("payloadToken", token);
                values.put("timestamp", captureTs);

                if (mContext.getContentResolver().insert(uri, values) != null) {
                    meta.delete();
                    payloadFile.delete();
                    XposedBridge.log(TAG + "Synced and deleted " + token);
                } else {
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean syncViaBinder(
        File payloadFile,
        String url,
        String hostApp,
        long timestamp
    ) {
        IBinder binder = mSyncBinder;
        if (binder == null || !binder.isBinderAlive()) {
            mSyncBinder = null;
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ParcelFileDescriptor.open(
                payloadFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            );
            if (pfd == null) return false;

            data.writeInterfaceToken(BRIDGE_DESCRIPTOR);
            data.writeString(url);
            data.writeString(hostApp);
            data.writeLong(timestamp);
            data.writeInt(1); // has PFD
            pfd.writeToParcel(data, 0);

            boolean status = binder.transact(
                TRANSACTION_SYNC_FILE,
                data,
                reply,
                0
            );
            if (status) {
                reply.readException();
                int res = reply.readInt();
                return res == 1;
            }
            return false;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Binder sync error: " + t.getMessage());
            if (t instanceof android.os.DeadObjectException) {
                mSyncBinder = null;
            }
            return false;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {}
            }
            data.recycle();
            reply.recycle();
        }
    }

    private void enqueueCapture(String url, byte[] bytes, String contentType)
        throws IOException {
        File queueDir = new File(mContext.getCacheDir(), QUEUE_DIR);
        if (!queueDir.exists()) queueDir.mkdirs();
        long captureTs = System.currentTimeMillis();
        String token = captureTs + "_" + System.nanoTime();
        File payloadFile = new File(queueDir, token + ".bin");
        try (FileOutputStream fos = new FileOutputStream(payloadFile)) {
            fos.write(bytes);
            fos.flush();
        }

        File metaFile = new File(queueDir, token + ".meta");
        String hostApp = "com.instagram.barcelona";
        String meta =
            safeLine(url) +
            "\n" +
            safeLine(hostApp) +
            "\n" +
            safeLine(contentType) +
            "\n" +
            captureTs +
            "\n";
        try (FileOutputStream fos = new FileOutputStream(metaFile)) {
            fos.write(meta.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }

    private String safeLine(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private String[] readMeta(File metaFile) {
        try {
            StringBuilder sb = new StringBuilder();
            try (
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                        new FileInputStream(metaFile),
                        StandardCharsets.UTF_8
                    )
                )
            ) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            String[] lines = sb.toString().split("\n", -1);
            if (lines.length < 2) return null;
            return new String[] {
                lines[0],
                lines[1],
                lines.length >= 3 ? lines[2] : "",
                lines.length >= 4 ? lines[3] : "",
            };
        } catch (Throwable t) {
            return null;
        }
    }
}
