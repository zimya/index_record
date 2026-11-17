package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

public class XiaoheiheHook implements PlatformHook {

    public static final String HOST_APP = "com.max.xiaoheihe";

    private volatile boolean installed;

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        context.onApplicationAttach(attachedContext -> {
            HookCaptureClient client = context.getCaptureClient();
            if (client != null) {
                client.setBeforeSync(() -> client.migrateLegacy(HOST_APP));
            }
            setupHooks(context, attachedContext.getClassLoader());
        });
        setupHooks(context, classLoader);
    }

    private synchronized void setupHooks(HookContext context, ClassLoader classLoader) {
        if (classLoader == null || installed) return;

        Class<?> realCallClass = ReflectionHelper.findClassIfExists("okhttp3.internal.connection.RealCall", classLoader);
        if (realCallClass == null) return;

        Method targetMethod = ReflectionHelper.findMethodExactIfExists(
            realCallClass, "getResponseWithInterceptorChain$okhttp"
        );
        if (targetMethod == null) return;

        // 小黑盒采用混淆后的 OkHttp 4：
        // 1. RealCall.request() -> okhttp3.a0 (Request)
        Method reqMethod = ReflectionHelper.findMethodExactIfExists(realCallClass, "request");
        if (reqMethod == null) {
            reqMethod = ReflectionHelper.findMethodExactIfExists(realCallClass, "getOriginalRequest");
        }
        if (reqMethod == null) return;

        // 2. Request -> okhttp3.t (HttpUrl)
        Class<?> requestClass = reqMethod.getReturnType();
        Method urlMethod = findUrlMethod(requestClass);
        if (urlMethod == null) return;

        // 3. Response: c0.h0(long) -> d0 (ResponseBody); c0.P(String) -> String (header)
        Class<?> responseClass = targetMethod.getReturnType();
        Method peekMethod = findPeekMethod(responseClass);
        if (peekMethod == null) return;

        Method headerMethod = findHeaderMethod(responseClass);
        Method bytesMethod = ReflectionHelper.findMethodExactIfExists(peekMethod.getReturnType(), "bytes");
        if (bytesMethod == null) return;

        final Method finalReq = reqMethod;
        final Method finalUrl = urlMethod;
        final Method finalPeek = peekMethod;
        final Method finalHeader = headerMethod;
        final Method finalBytes = bytesMethod;

        try {
            context.getXposed().hook(targetMethod).intercept(chain -> {
                Object response = chain.proceed();
                if (response == null) return null;

                try {
                    Object realCall = chain.getThisObject();
                    Object request = finalReq.invoke(realCall);
                    if (request == null) return response;

                    Object urlObj = finalUrl.invoke(request);
                    String url = urlObj != null ? urlObj.toString() : null;
                    if (!isTargetUrl(url)) return response;

                    String contentType = finalHeader != null
                        ? (String) finalHeader.invoke(response, "Content-Type")
                        : "application/json";

                    Object peekedBody = finalPeek.invoke(response, 5 * 1024 * 1024L);
                    if (peekedBody == null) return response;

                    byte[] bodyBytes = (byte[]) finalBytes.invoke(peekedBody);
                    if (bodyBytes != null && bodyBytes.length > 0) {
                        handleResponse(context, url, contentType != null ? contentType : "application/json", bodyBytes);
                    }
                } catch (Throwable t) {
                    context.log("Failed to capture response: " + t);
                }
                return response;
            });
            installed = true;
            context.log("Xiaoheihe hook installed on " + targetMethod);
        } catch (Throwable t) {
            context.log("Failed to hook Xiaoheihe RealCall: " + t);
        }
    }

    private static Method findUrlMethod(Class<?> requestClass) {
        Method m = ReflectionHelper.findMethodExactIfExists(requestClass, "url");
        if (m != null) return m;
        m = ReflectionHelper.findMethodExactIfExists(requestClass, "getUrl");
        if (m != null) return m;
        m = ReflectionHelper.findMethodExactIfExists(requestClass, "q");
        if (m != null) return m;
        for (Method method : requestClass.getMethods()) {
            if (method.getParameterTypes().length == 0 && (
                method.getReturnType().getSimpleName().equals("HttpUrl") ||
                method.getReturnType().getSimpleName().equals("t")
            )) {
                return method;
            }
        }
        return null;
    }

    private static Method findPeekMethod(Class<?> responseClass) {
        // peekBody(long) 或 R8 混淆后的 h0(long)：单 long 参数且返回非 void (ResponseBody)
        for (Method m : responseClass.getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && (params[0] == long.class || params[0] == Long.TYPE) && m.getReturnType() != void.class) {
                return m;
            }
        }
        return null;
    }

    private static Method findHeaderMethod(Class<?> responseClass) {
        // header(String) 或 R8 混淆后的 P(String)：单 String 参数且返回 String
        for (Method m : responseClass.getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && params[0] == String.class && m.getReturnType() == String.class) {
                return m;
            }
        }
        return null;
    }

    private void handleResponse(HookContext context, String url, String contentType, byte[] bytes) {
        context.getExecutor().submit(() -> {
            try {
                byte[] decompressed = decompressIfNeeded(bytes);
                context.log("[Capture OK] bytes=" + bytes.length + " -> " + decompressed.length + ", url=" + url);
                HookCaptureClient client = context.getCaptureClient();
                if (client != null) {
                    client.enqueue(url, decompressed, contentType, HOST_APP, System.currentTimeMillis());
                    client.sync();
                }
            } catch (Exception e) {
                context.log("Capture rejected for " + url + ": " + e);
            }
        });
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
        if (!url.contains("xiaoheihe.cn") && !url.contains("maxjia.com") && !url.contains("heybox")) {
            return false;
        }
        return url.contains("/bbs/app/link/tree")
            || url.contains("/bbs/app/comment/sub/comments")
            || url.contains("/bbs/app/link/web/view");
    }
}
