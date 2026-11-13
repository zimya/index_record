package me.melon.record;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.melon.record.sync.HookCaptureClient;

public class ThreadsHook implements IXposedHookLoadPackage {

    private static volatile Context mContext;
    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private static final String TAG = "ThreadsRecord: ";
    private static final Set<ClassLoader> hookedClassLoaders =
        Collections.synchronizedSet(new HashSet<>());
    private static volatile HookCaptureClient client;

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
                    Context attachedContext = (Context) param.args[0];
                    if (attachedContext == null) return;
                    client = new HookCaptureClient(
                        attachedContext,
                        executor,
                        TAG
                    );
                    mContext = attachedContext;
                    XposedBridge.log(
                        TAG + "Context attached, setting up hook."
                    );
                    setupTigonHook(attachedContext.getClassLoader());
                    client.registerReceiver();
                    executor.submit(client::sync);
                }
            }
        );
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
            client.enqueue(
                url,
                bytes,
                "application/json",
                "com.instagram.barcelona",
                System.currentTimeMillis()
            );
            XposedBridge.log(
                TAG +
                    "Captured " +
                    bytes.length +
                    " bytes: " +
                    Uri.parse(url).getPath()
            );
            client.sync();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Capture failed: " + t);
        }
    }
}
