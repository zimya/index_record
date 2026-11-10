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
import android.os.ParcelFileDescriptor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DoubanHook implements IXposedHookLoadPackage {

    private static Context mContext;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final String TAG = "DoubanRecord: ";
    private static final String LEGACY_CACHE_DIR = "index_record_cache";
    private static final String QUEUE_DIR = "index_record_queue";
    private static final Set<ClassLoader> hookedClassLoaders = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.douban.frodo".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + "Loading for " + lpparam.packageName + " in " + lpparam.processName);

        try {
            setupOkHttpHook(lpparam.classLoader);
        } catch (Throwable ignored) {}

        XposedHelpers.findAndHookMethod(
            Application.class,
            "attach",
            Context.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (mContext != null) return;
                    mContext = (Context) param.args[0];
                    if (mContext == null) return;
                    XposedBridge.log(TAG + "Context attached, setting up hook.");
                    setupOkHttpHook(mContext.getClassLoader());
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
                    if ("me.melon.record.ACTION_SYNC".equals(intent.getAction())) {
                        XposedBridge.log(TAG + "Received ACTION_SYNC broadcast, syncing queue...");
                        executor.submit(() -> trySyncToApp());
                    }
                }
            };
            IntentFilter filter = new IntentFilter("me.melon.record.ACTION_SYNC");

            if (Build.VERSION.SDK_INT >= 33) {
                int RECEIVER_EXPORTED = 2;
                try {
                    Method regMethod = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                    regMethod.invoke(context, receiver, filter, RECEIVER_EXPORTED);
                } catch (Throwable e) {
                    context.registerReceiver(receiver, filter);
                }
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Throwable ignored) {}
    }

    private void setupOkHttpHook(final ClassLoader classLoader) {
        if (classLoader == null) return;
        if (!hookedClassLoaders.add(classLoader)) return;

        final Class<?> builderClass = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient$Builder", classLoader);
        if (builderClass == null) return;

        try {
            XposedHelpers.findAndHookMethod(builderClass, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    installInterceptor(param.thisObject, classLoader);
                }
            });
            XposedBridge.log(TAG + "OkHttp hook installed for classLoader: " + classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking build(): " + t.getMessage());
        }
    }

    private void installInterceptor(Object builder, final ClassLoader classLoader) {
        try {
            List<?> interceptors = (List<?>) XposedHelpers.callMethod(builder, "interceptors");
            if (interceptors != null) {
                for (Object i : interceptors) {
                    if (i != null && "DoubanRecordInterceptor".equals(i.toString())) {
                        return;
                    }
                }
            }

            final Class<?> interceptorClass = XposedHelpers.findClassIfExists("okhttp3.Interceptor", classLoader);
            if (interceptorClass == null) return;

            Object interceptor = Proxy.newProxyInstance(classLoader, new Class[]{interceptorClass}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if ("intercept".equals(name)) {
                        return handleIntercept(args[0]);
                    }
                    if ("toString".equals(name)) return "DoubanRecordInterceptor";
                    if ("equals".equals(name)) {
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    }
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);

                    try {
                        return method.invoke(this, args);
                    } catch (Throwable t) {
                        return null;
                    }
                }

                private Object handleIntercept(Object chain) throws Throwable {
                    Object request = XposedHelpers.callMethod(chain, "request");
                    Object response;
                    try {
                        response = XposedHelpers.callMethod(chain, "proceed", request);
                    } catch (Throwable t) {
                        Throwable cause = t;
                        while (cause != null) {
                            if (cause instanceof IOException) throw (IOException) cause;
                            if (cause.getCause() == cause || cause.getCause() == null) break;
                            cause = cause.getCause();
                        }
                        throw t;
                    }

                    if (response != null) {
                        try {
                            Object urlObj = XposedHelpers.callMethod(request, "url");
                            String url = (urlObj != null) ? urlObj.toString() : "";
                            if (isTargetUrl(url)) {
                                saveResponseAsync(response, url);
                            }
                        } catch (Throwable ignored) {}
                    }

                    return response;
                }
            });

            XposedHelpers.callMethod(builder, "addInterceptor", interceptor);
        } catch (Throwable ignored) {}
    }

    private static boolean isTargetUrl(String url) {
        if (url == null || (!url.contains("douban.com") && !url.contains("doubanio.com"))) {
            return false;
        }
        return url.contains("/group/topic/")
            || url.contains("/hot_interests")
            || url.contains("/review/")
            || url.contains("/forum_topic/")
            || url.contains("/subject/")
            || url.contains("/movie/")
            || url.contains("/book/")
            || url.contains("/tv/")
            || url.contains("/game/")
            || url.contains("/music/")
            || url.contains("/drama/");
    }

    private void saveResponseAsync(Object response, final String url) {
        try {
            XposedBridge.log(TAG + "[Found URL] " + (url.length() > 100 ? url.substring(0, 100) : url));
            String contentType = (String) XposedHelpers.callMethod(response, "header", "Content-Type");
            if (contentType == null) return;
            String ct = contentType.toLowerCase();
            if (!ct.contains("json") && !ct.contains("text")) return;

            Object peekedBody = XposedHelpers.callMethod(response, "peekBody", (long) (1024 * 1024 * 5));
            if (peekedBody == null) return;
            final byte[] bytes = (byte[]) XposedHelpers.callMethod(peekedBody, "bytes");

            if (bytes != null && bytes.length > 0) {
                XposedBridge.log(TAG + "[Capture OK] Size: " + bytes.length);
                executor.submit(() -> {
                    try {
                        enqueueCapture(url, bytes, contentType);
                        trySyncToApp();
                    } catch (Exception ignored) {}
                });
            }
        } catch (Throwable ignored) {}
    }

    private void trySyncToApp() {
        if (mContext == null) return;
        try {
            migrateLegacyCacheFilesIfAny();

            Uri uri = Uri.parse("content://me.melon.record.provider/raw_data");
            File queueDir = new File(mContext.getCacheDir(), QUEUE_DIR);
            if (!queueDir.exists()) return;
            File[] metaFiles = queueDir.listFiles((dir, name) -> name != null && name.endsWith(".meta"));
            if (metaFiles == null || metaFiles.length == 0) return;

            java.util.Arrays.sort(metaFiles, java.util.Comparator.comparing(File::getName));

            for (File meta : metaFiles) {
                String token = meta.getName().substring(0, meta.getName().length() - ".meta".length());
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
                        String prefix = idx > 0 ? token.substring(0, idx) : token;
                        captureTs = Long.parseLong(prefix);
                    } catch (Throwable ignored) {}
                }
                if (captureTs <= 0L) captureTs = System.currentTimeMillis();

                Uri payloadUri = Uri.parse("content://me.melon.record.provider/raw_payload/" + token);
                ParcelFileDescriptor pfd = null;
                try {
                    pfd = mContext.getContentResolver().openFileDescriptor(payloadUri, "w");
                    if (pfd == null) return;
                    try (FileInputStream fis = new FileInputStream(payloadFile);
                         FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
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
                        try { pfd.close(); } catch (Throwable ignored) {}
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
                } else {
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private void enqueueCapture(String url, byte[] bytes, String contentType) throws IOException {
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
        String hostApp = "com.douban.frodo";
        String meta = safeLine(url) + "\n" + safeLine(hostApp) + "\n" + safeLine(contentType) + "\n" + captureTs + "\n";
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
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(metaFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            String[] lines = sb.toString().split("\n", -1);
            if (lines.length < 2) return null;
            return new String[]{lines[0], lines[1], lines.length >= 3 ? lines[2] : "", lines.length >= 4 ? lines[3] : ""};
        } catch (Throwable t) {
            return null;
        }
    }

    private void migrateLegacyCacheFilesIfAny() {
        try {
            File cacheDir = new File(mContext.getCacheDir(), LEGACY_CACHE_DIR);
            if (!cacheDir.exists()) return;
            File[] files = cacheDir.listFiles();
            if (files == null || files.length == 0) return;
            for (File file : files) {
                if (file == null || !file.isFile()) continue;
                try {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    JSONObject wrapper = new JSONObject(sb.toString());
                    String url = wrapper.optString("url", "");
                    String json = wrapper.optString("json", "");
                    String host = wrapper.optString("hostApp", "com.douban.frodo");
                    if (json != null && !json.isEmpty()) {
                        enqueueLegacy(url, json.getBytes(StandardCharsets.UTF_8), host);
                    }
                    file.delete();
                } catch (Throwable t) {
                    file.delete();
                }
            }
        } catch (Throwable ignored) {}
    }

    private void enqueueLegacy(String url, byte[] payload, String hostApp) throws IOException {
        File queueDir = new File(mContext.getCacheDir(), QUEUE_DIR);
        if (!queueDir.exists()) queueDir.mkdirs();
        long captureTs = System.currentTimeMillis();
        String token = captureTs + "_" + System.nanoTime();
        File payloadFile = new File(queueDir, token + ".bin");
        try (FileOutputStream fos = new FileOutputStream(payloadFile)) {
            fos.write(payload);
            fos.flush();
        }
        File metaFile = new File(queueDir, token + ".meta");
        String meta = safeLine(url) + "\n" + safeLine(hostApp) + "\n" + "application/json" + "\n" + captureTs + "\n";
        try (FileOutputStream fos = new FileOutputStream(metaFile)) {
            fos.write(meta.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }
}
