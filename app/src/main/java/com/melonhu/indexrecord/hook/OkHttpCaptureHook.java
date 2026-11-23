package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 新增平台的公共 OkHttp 抓取通道。
 *
 * 各平台 Hook 只声明宿主包名和 URL 白名单；这里统一处理 RealCall 反射、
 * 响应体 peek、gzip 解压与队列投递，避免每个平台复制一份拦截器。
 * 混淆 OkHttp（如小黑盒）仍使用各自的 Hook。
 */
public final class OkHttpCaptureHook {

    public interface UrlMatcher {
        boolean matches(String url);
    }

    private static final Set<String> INSTALLED = new HashSet<>();

    private OkHttpCaptureHook() {}

    public static void install(HookContext context, ClassLoader classLoader, String hostApp, UrlMatcher matcher) {
        if (classLoader == null) return;
        synchronized (INSTALLED) {
            if (INSTALLED.contains(hostApp)) return;
        }

        Class<?> realCallClass = ReflectionHelper.findClassIfExists("okhttp3.internal.connection.RealCall", classLoader);
        if (realCallClass == null) return;

        Method targetMethod = ReflectionHelper.findMethodExactIfExists(
            realCallClass, "getResponseWithInterceptorChain$okhttp"
        );
        if (targetMethod == null) {
            targetMethod = ReflectionHelper.findMethodExactIfExists(realCallClass, "getResponseWithInterceptorChain");
        }
        if (targetMethod == null) return;

        Method requestMethod = ReflectionHelper.findMethodExactIfExists(realCallClass, "request");
        if (requestMethod == null) {
            requestMethod = ReflectionHelper.findMethodExactIfExists(realCallClass, "getOriginalRequest");
        }
        if (requestMethod == null) return;

        Method urlMethod = findUrlMethod(requestMethod.getReturnType());
        Class<?> responseClass = targetMethod.getReturnType();
        Method peekMethod = findPeekMethod(responseClass);
        Method headerMethod = findHeaderMethod(responseClass);
        if (urlMethod == null || peekMethod == null) return;

        Method bytesMethod = ReflectionHelper.findMethodExactIfExists(peekMethod.getReturnType(), "bytes");
        if (bytesMethod == null) return;

        final Method finalRequest = requestMethod;
        final Method finalUrl = urlMethod;
        final Method finalPeek = peekMethod;
        final Method finalHeader = headerMethod;
        final Method finalBytes = bytesMethod;

        try {
            context.getXposed().hook(targetMethod).intercept(chain -> {
                Object response = chain.proceed();
                if (response == null) return null;
                try {
                    Object request = finalRequest.invoke(chain.getThisObject());
                    if (request == null) return response;
                    Object urlObj = finalUrl.invoke(request);
                    String url = urlObj != null ? urlObj.toString() : null;
                    if (url == null || !matcher.matches(url)) return response;

                    String contentType = finalHeader != null
                        ? (String) finalHeader.invoke(response, "Content-Type")
                        : "application/json";

                    Object peeked = finalPeek.invoke(response, 8 * 1024 * 1024L);
                    if (peeked == null) return response;
                    byte[] bytes = (byte[]) finalBytes.invoke(peeked);
                    if (bytes != null && bytes.length > 0) {
                        submit(context, url, contentType != null ? contentType : "application/json", bytes, hostApp);
                    }
                } catch (Throwable error) {
                    context.log("Capture failed: " + error);
                }
                return response;
            });
            synchronized (INSTALLED) {
                INSTALLED.add(hostApp);
            }
            context.log("Capture hook installed for " + hostApp);
        } catch (Throwable error) {
            context.log("Cannot hook RealCall for " + hostApp, error);
        }
    }

    private static void submit(
        HookContext context,
        String url,
        String contentType,
        byte[] body,
        String hostApp
    ) {
        context.getExecutor().submit(() -> {
            try {
                byte[] plain = gunzipIfNeeded(body);
                HookCaptureClient client = context.getCaptureClient();
                if (client == null) return;
                client.enqueue(url, plain, contentType, hostApp, System.currentTimeMillis());
                client.sync();
            } catch (Exception error) {
                context.log("Capture rejected for " + url + ": " + error);
            }
        });
    }

    private static Method findUrlMethod(Class<?> requestClass) {
        Method method = ReflectionHelper.findMethodExactIfExists(requestClass, "url");
        if (method != null) return method;
        method = ReflectionHelper.findMethodExactIfExists(requestClass, "getUrl");
        if (method != null) return method;
        for (Method candidate : requestClass.getMethods()) {
            if (candidate.getParameterTypes().length != 0) continue;
            String type = candidate.getReturnType().getSimpleName();
            if (type.equals("HttpUrl") || type.equals("t")) return candidate;
        }
        return null;
    }

    private static Method findPeekMethod(Class<?> responseClass) {
        for (Method method : responseClass.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && (params[0] == long.class || params[0] == Long.TYPE)
                && method.getReturnType() != void.class) {
                return method;
            }
        }
        return null;
    }

    private static Method findHeaderMethod(Class<?> responseClass) {
        for (Method method : responseClass.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == String.class && method.getReturnType() == String.class) {
                return method;
            }
        }
        return null;
    }

    private static byte[] gunzipIfNeeded(byte[] bytes) {
        if (bytes != null && bytes.length >= 2 && (bytes[0] & 255) == 31 && (bytes[1] & 255) == 139) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[16384];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    out.write(chunk, 0, read);
                }
                return out.toByteArray();
            } catch (Exception ignored) {
            }
        }
        return bytes;
    }
}
