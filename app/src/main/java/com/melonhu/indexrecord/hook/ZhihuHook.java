package com.melonhu.indexrecord.hook;

import android.os.Handler;
import android.os.Looper;
import com.melonhu.indexrecord.parser.zhihu.ZhihuCaptureDecoder;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ZhihuHook implements PlatformHook {

    public static final String HOST_APP = "com.zhihu.android";
    public static final String ZHIHU_PLUS = "com.github.zly2006.zhplus";
    public static final String ZHIHU_PLUS_LITE = "com.github.zly2006.zhplus.lite";
    private final OkHttpHookHelper okHttpHelper = new OkHttpHookHelper();
    private final Set<Class<?>> hookedUrlConnClasses = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        if (isZhihuPlus(context.getPackageName())) {
            setupHttpUrlConnectionHooks(context, classLoader);
            context.onApplicationAttach(attachedContext ->
                setupHttpUrlConnectionHooks(context, attachedContext.getClassLoader())
            );
            return;
        }
        context.onApplicationAttach(attachedContext -> {
            HookCaptureClient client = context.getCaptureClient();
            if (client != null) {
                client.setBeforeSync(() -> client.migrateLegacy(HOST_APP));
            }
            setupHooks(context, attachedContext.getClassLoader());
        });
        setupHooks(context, classLoader);
    }

    private static boolean isZhihuPlus(String packageName) {
        return ZHIHU_PLUS.equals(packageName) || ZHIHU_PLUS_LITE.equals(packageName);
    }

    private void setupHooks(HookContext context, ClassLoader classLoader) {
        boolean installed = okHttpHelper.setupOkHttpHook(
            context,
            classLoader,
            "ZhihuRecordInterceptor",
            ZhihuHook::isTargetUrl,
            (long) ZhihuCaptureDecoder.MAX_BYTES + 1,
            (url, contentType, bytes, response) -> {
                String contentEncoding = (String) ReflectionHelper.callMethod(
                    response,
                    "header",
                    "Content-Encoding"
                );
                context.getExecutor().submit(() -> {
                    try {
                        byte[] decoded = ZhihuCaptureDecoder.decode(bytes, contentEncoding);
                        context.log("[Capture OK] Content-Encoding=" + contentEncoding +
                            ", bytes=" + bytes.length + " -> " + decoded.length);
                        HookCaptureClient client = context.getCaptureClient();
                        if (client != null) {
                            client.enqueue(url, decoded, contentType, HOST_APP, System.currentTimeMillis());
                            client.sync();
                        }
                    } catch (Exception e) {
                        context.log("Capture rejected for " + url.split("\\?", 2)[0] +
                            " (Content-Encoding=" + contentEncoding + "): " + e);
                    }
                });
            }
        );
        if (installed) {
            hookLaunchBuildViewMainThread(context, classLoader);
        }
    }

    /**
     * Zhihu's LaunchWellC invokes LaunchImpl.buildLaunchView on whichever ad-rocket thread finishes
     * last (e.g. GDT background thread). When OkHttp interception shifts that race, building
     * ScreenTouchView and adding the splash view off the main thread throws and gets swallowed,
     * leaving LauncherActivity stuck on the splash screen.
     */
    private void hookLaunchBuildViewMainThread(HookContext context, ClassLoader classLoader) {
        Class<?> launchImplClass = ReflectionHelper.findClassIfExists(
            "com.zhihu.android.launch.impl.LaunchImpl",
            classLoader
        );
        if (launchImplClass == null) {
            return;
        }
        Handler mainHandler = new Handler(Looper.getMainLooper());
        for (Method method : launchImplClass.getDeclaredMethods()) {
            if (!"buildLaunchView".equals(method.getName())) {
                continue;
            }
            method.setAccessible(true);
            try {
                context.getXposed().hook(method).intercept(chain -> {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        return chain.proceed();
                    }
                    Object thisObj = chain.getThisObject();
                    Object[] args = chain.getArgs().toArray();
                    mainHandler.post(() -> {
                        try {
                            method.invoke(thisObj, args);
                        } catch (Throwable t) {
                            context.log("Failed to dispatch buildLaunchView to main thread", t);
                        }
                    });
                    return null;
                });
            } catch (Throwable t) {
                context.log("Error hooking LaunchImpl.buildLaunchView: " + t.getMessage(), t);
            }
        }
    }

    public static boolean isTargetUrl(String url) {
        if (url == null || !url.contains("zhihu.com")) {
            return false;
        }
        return (
            url.contains("/articles/") ||
            url.contains("/answers/") ||
            url.contains("/pins/v2/") ||
            url.contains("/api/v4/pins/") ||
            url.contains("/appview/v2/answer/") ||
            url.contains("/questions/") ||
            url.contains("/comment_v5/") ||
            url.contains("/next-render")
        );
    }

    private void setupHttpUrlConnectionHooks(HookContext context, ClassLoader classLoader) {
        String[] candidates = new String[]{
            "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
            "sun.net.www.protocol.http.HttpURLConnection"
        };
        for (String className : candidates) {
            Class<?> clazz = ReflectionHelper.findClassIfExists(className, classLoader);
            if (clazz == null || !hookedUrlConnClasses.add(clazz)) {
                continue;
            }
            Method getInputStream = ReflectionHelper.findMethodExactIfExists(clazz, "getInputStream");
            if (getInputStream == null) {
                hookedUrlConnClasses.remove(clazz);
                continue;
            }
            try {
                context.getXposed().hook(getInputStream).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!(result instanceof InputStream) || result instanceof CaptureTeeInputStream) {
                        return result;
                    }
                    Object thisObj = chain.getThisObject();
                    if (!(thisObj instanceof HttpURLConnection)) {
                        return result;
                    }
                    HttpURLConnection conn = (HttpURLConnection) thisObj;
                    URL urlObj = conn.getURL();
                    String url = urlObj != null ? urlObj.toString() : "";
                    if (!isTargetUrl(url)) {
                        return result;
                    }
                    String contentType = conn.getContentType();
                    if (contentType == null) {
                        return result;
                    }
                    String ct = contentType.toLowerCase();
                    if (!ct.contains("json") && !ct.contains("text")) {
                        return result;
                    }
                    context.log("[Found URL] " + (url.length() > 100 ? url.substring(0, 100) : url));
                    return new CaptureTeeInputStream(
                        (InputStream) result,
                        context,
                        url,
                        contentType,
                        conn.getContentEncoding()
                    );
                });
                context.log("HttpURLConnection hook installed: " + className);
            } catch (Throwable t) {
                hookedUrlConnClasses.remove(clazz);
                context.log("Error hooking " + className + ".getInputStream(): " + t.getMessage(), t);
            }
        }
    }

    private static final class CaptureTeeInputStream extends FilterInputStream {
        private final HookContext context;
        private final String url;
        private final String contentType;
        private final String contentEncoding;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean completed = false;
        private boolean overflow = false;

        CaptureTeeInputStream(
            InputStream in,
            HookContext context,
            String url,
            String contentType,
            String contentEncoding
        ) {
            super(in);
            this.context = context;
            this.url = url;
            this.contentType = contentType;
            this.contentEncoding = contentEncoding;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b == -1) {
                finish();
            } else if (!overflow) {
                if (buffer.size() < ZhihuCaptureDecoder.MAX_BYTES) {
                    buffer.write(b);
                } else {
                    overflow = true;
                    buffer.reset();
                }
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n == -1) {
                finish();
            } else if (n > 0 && !overflow) {
                if (buffer.size() + n <= ZhihuCaptureDecoder.MAX_BYTES) {
                    buffer.write(b, off, n);
                } else {
                    overflow = true;
                    buffer.reset();
                }
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                finish();
            }
        }

        private synchronized void finish() {
            if (completed || overflow) {
                return;
            }
            completed = true;
            byte[] bytes = buffer.toByteArray();
            if (bytes.length == 0) {
                return;
            }
            long capturedAt = System.currentTimeMillis();
            context.getExecutor().submit(() -> {
                try {
                    byte[] decoded = ZhihuCaptureDecoder.decode(bytes, contentEncoding);
                    context.log("[Capture OK] Content-Encoding=" + contentEncoding +
                        ", bytes=" + bytes.length + " -> " + decoded.length);
                    HookCaptureClient client = context.getCaptureClient();
                    if (client != null) {
                        client.enqueue(url, decoded, contentType, context.getPackageName(), capturedAt);
                        client.sync();
                    }
                } catch (Exception e) {
                    context.log("Capture rejected for " + url.split("\\?", 2)[0] +
                        " (Content-Encoding=" + contentEncoding + "): " + e);
                }
            });
        }
    }
}
