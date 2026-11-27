package com.melonhu.indexrecord.hook;

import android.app.Application;
import android.os.Build;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public class NeteaseCloudMusicHook implements PlatformHook {

    public static final String HOST_APP = "com.netease.cloudmusic";
    private static final long MAX_PEEK_BYTES = 5 * 1024 * 1024L;
    private static final byte[] BATCH_COMMENTS_MARKER =
        "\"/api/v2/resource/comments\"".getBytes(StandardCharsets.UTF_8);

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT >= 28 && !HOST_APP.equals(Application.getProcessName())) {
            return;
        }
        context.onApplicationAttach(attachedContext ->
            setupHooks(context, attachedContext.getClassLoader())
        );
        setupHooks(context, classLoader);
    }

    private void setupHooks(HookContext context, ClassLoader classLoader) {
        if (classLoader == null) return;
        hookRealCall(context, classLoader, "okhttp3.internal.connection.RealCall", "getResponseWithInterceptorChain$okhttp");
        hookRealCall(context, classLoader, "okhttp3.RealCall", "getResponseWithInterceptorChain");
    }

    private void hookRealCall(HookContext context, ClassLoader classLoader, String className, String methodName) {
        Class<?> realCallClass = ReflectionHelper.findClassIfExists(className, classLoader);
        if (realCallClass == null) return;
        Method targetMethod = ReflectionHelper.findMethodExactIfExists(realCallClass, methodName);
        if (targetMethod == null) return;

        try {
            context.getXposed().hook(targetMethod).intercept(chain -> {
                Object response = chain.proceed();
                if (response != null) {
                    try {
                        Object request = ReflectionHelper.callMethod(response, "request");
                        Object urlObj = request != null ? ReflectionHelper.callMethod(request, "url") : null;
                        String url = urlObj != null ? urlObj.toString() : "";
                        if (isTargetUrl(url)) {
                            String contentType = (String) ReflectionHelper.callMethod(response, "header", "Content-Type");
                            if (isJsonOrText(contentType)) {
                                Object peekedBody = ReflectionHelper.callMethod(response, "peekBody", MAX_PEEK_BYTES);
                                if (peekedBody != null) {
                                    byte[] bytes = (byte[]) ReflectionHelper.callMethod(peekedBody, "bytes");
                                    if (bytes != null && bytes.length > 0) {
                                        handleResponse(context, url, contentType, bytes);
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        context.log("RealCall error: " + t.getMessage());
                    }
                }
                return response;
            });
            context.log("Hooked " + className + "." + methodName);
        } catch (Throwable t) {
            context.log("Failed to hook " + className + "." + methodName + ": " + t.getMessage());
        }
    }

    private void handleResponse(HookContext context, String url, String contentType, byte[] bytes) {
        long timestamp = System.currentTimeMillis();
        context.getExecutor().submit(() -> {
            try {
                byte[] decompressed = decompressIfNeeded(bytes);
                if (isBatchUrl(url) && !containsBytes(decompressed, BATCH_COMMENTS_MARKER)) {
                    return;
                }
                context.log("[Capture OK] bytes=" + decompressed.length + " url=" + url);
                HookCaptureClient client = context.getCaptureClient();
                if (client != null) {
                    client.enqueue(url, decompressed, contentType, HOST_APP, timestamp);
                    client.sync();
                }
            } catch (Exception e) {
                context.log("Capture rejected for " + url + ": " + e);
            }
        });
    }

    private static boolean isJsonOrText(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains("json") || ct.contains("text");
    }

    private static boolean isBatchUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            return "/eapi/batch".equals(path) || "/api/batch".equals(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || haystack.length < needle.length) return false;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] decompressIfNeeded(byte[] bytes) {
        if (bytes != null && bytes.length >= 2 && (bytes[0] & 255) == 31 && (bytes[1] & 255) == 139) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    out.write(chunk, 0, n);
                }
                return out.toByteArray();
            } catch (Exception ignored) {}
        }
        return bytes;
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null) return false;
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (!"music.163.com".equals(lowerHost) && !lowerHost.endsWith(".music.163.com")) {
                return false;
            }
            if (lowerHost.startsWith("clientlog") || lowerHost.startsWith("httpdns")) {
                return false;
            }
            return isSupportedPath(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isSupportedPath(String path) {
        return "/eapi/batch".equals(path)
            || "/api/batch".equals(path)
            || "/eapi/v2/resource/comments".equals(path)
            || "/api/v2/resource/comments".equals(path)
            || "/eapi/v2/resource/comment/floor/get".equals(path)
            || "/api/v2/resource/comment/floor/get".equals(path)
            || "/eapi/resource/comments/reply/preload".equals(path)
            || "/api/resource/comments/reply/preload".equals(path)
            || "/eapi/v3/song/detail".equals(path)
            || "/api/v3/song/detail".equals(path)
            || "/eapi/v6/playlist/detail".equals(path)
            || "/api/v6/playlist/detail".equals(path)
            || "/eapi/album/v3/detail".equals(path)
            || "/api/album/v3/detail".equals(path)
            || "/eapi/dj/program/detail".equals(path)
            || "/api/dj/program/detail".equals(path);
    }
}
