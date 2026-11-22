package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public class CoolapkHook implements PlatformHook {

    public static final String HOST_APP = "com.coolapk.market";
    private static final long MAX_PEEK_BYTES = 5 * 1024 * 1024L;
    private static final String[] NETWORK_INTERCEPTOR_CLASSES = {
        "com.coolapk.market.network.HttpClientFactory$CoolMarketHeaderInterceptor",
        "com.coolapk.market.network.HttpClientFactory$ClientInterceptor",
        "com.coolapk.market.network.HttpClientFactory$CoolMarketDynamicHostInterceptor",
        "com.coolapk.market.network.RequestResponseInterceptor"
    };

    private final OkHttpHookHelper okHttpHelper = new OkHttpHookHelper();
    private volatile boolean hookInstalled;

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
        if (classLoader == null || hookInstalled) {
            return;
        }
        if (okHttpHelper.setupOkHttpHook(
            context,
            classLoader,
            "CoolapkRecordInterceptor",
            CoolapkHook::isTargetUrl,
            MAX_PEEK_BYTES,
            (url, contentType, bytes, response) -> enqueueCapture(context, url, contentType, bytes)
        )) {
            hookInstalled = true;
            return;
        }
        setupObfuscatedOkHttpHook(context, classLoader);
    }

    static final class ObfuscatedOkHttpMethods {
        final Method targetIntercept;
        final Method chainRequest;
        final Method responseRequest;
        final Method requestUrl;
        final Method responseHeader;
        final Method responsePeekBody;
        final Method bodyBytes;

        ObfuscatedOkHttpMethods(
            Method targetIntercept,
            Method chainRequest,
            Method responseRequest,
            Method requestUrl,
            Method responseHeader,
            Method responsePeekBody,
            Method bodyBytes
        ) {
            this.targetIntercept = targetIntercept;
            this.chainRequest = chainRequest;
            this.responseRequest = responseRequest;
            this.requestUrl = requestUrl;
            this.responseHeader = responseHeader;
            this.responsePeekBody = responsePeekBody;
            this.bodyBytes = bodyBytes;
        }
    }

    static ObfuscatedOkHttpMethods resolveObfuscatedMethods(ClassLoader classLoader) {
        Method targetIntercept = findTargetInterceptMethod(classLoader);
        if (targetIntercept == null) {
            return null;
        }
        return resolveObfuscatedMethodsFromIntercept(targetIntercept);
    }

    static ObfuscatedOkHttpMethods resolveObfuscatedMethodsFromIntercept(Method targetIntercept) {
        if (targetIntercept == null || targetIntercept.getParameterCount() != 1) {
            return null;
        }
        Class<?> chainClass = targetIntercept.getParameterTypes()[0];
        Class<?> responseClass = targetIntercept.getReturnType();

        Method proceedMethod = findMethod(chainClass, 1, null, responseClass);
        if (proceedMethod == null) {
            return null;
        }

        Class<?> requestClass = proceedMethod.getParameterTypes()[0];
        Method chainRequestMethod = findMethod(chainClass, 0, null, requestClass);
        Method responseRequestMethod = findMethod(responseClass, 0, null, requestClass);
        Method requestUrlMethod = findRequestUrlMethod(requestClass);
        Method headerMethod = findMethod(responseClass, 1, String.class, String.class);
        Method peekBodyMethod = findPeekBodyMethod(responseClass);
        if (chainRequestMethod == null || requestUrlMethod == null || headerMethod == null || peekBodyMethod == null) {
            return null;
        }

        Method bytesMethod = findMethod(peekBodyMethod.getReturnType(), 0, null, byte[].class);
        if (bytesMethod == null) {
            return null;
        }

        targetIntercept.setAccessible(true);
        return new ObfuscatedOkHttpMethods(
            targetIntercept,
            chainRequestMethod,
            responseRequestMethod,
            requestUrlMethod,
            headerMethod,
            peekBodyMethod,
            bytesMethod
        );
    }

    private void setupObfuscatedOkHttpHook(HookContext context, ClassLoader classLoader) {
        ObfuscatedOkHttpMethods methods = resolveObfuscatedMethods(classLoader);
        if (methods == null) {
            return;
        }

        try {
            context.getXposed().hook(methods.targetIntercept).intercept(chain -> {
                Object response = chain.proceed();
                if (response == null) {
                    return null;
                }

                try {
                    Object interceptorChain = chain.getArg(0);
                    Object request = methods.responseRequest != null
                        ? methods.responseRequest.invoke(response)
                        : null;
                    if (request == null && interceptorChain != null) {
                        request = methods.chainRequest.invoke(interceptorChain);
                    }
                    if (request == null) {
                        return response;
                    }

                    Object urlObj = methods.requestUrl.invoke(request);
                    String url = urlObj != null ? urlObj.toString() : "";
                    if (!isTargetUrl(url)) {
                        return response;
                    }

                    context.log("[Found URL] " + (url.length() > 100 ? url.substring(0, 100) : url));
                    String contentType = (String) methods.responseHeader.invoke(response, "Content-Type");
                    if (contentType == null) {
                        return response;
                    }
                    String ct = contentType.toLowerCase();
                    if (!ct.contains("json") && !ct.contains("text")) {
                        return response;
                    }

                    Object peekedBody = methods.responsePeekBody.invoke(response, MAX_PEEK_BYTES);
                    if (peekedBody == null) {
                        return response;
                    }
                    byte[] bytes = (byte[]) methods.bodyBytes.invoke(peekedBody);
                    if (bytes != null && bytes.length > 0) {
                        enqueueCapture(context, url, contentType, bytes);
                    }
                } catch (Throwable t) {
                    context.log("Obfuscated OkHttp capture failed: " + t.getMessage(), t);
                }
                return response;
            });

            hookInstalled = true;
            context.log(
                "Obfuscated OkHttp hook installed on "
                    + methods.targetIntercept.getDeclaringClass().getName()
                    + "#"
                    + methods.targetIntercept.getName()
            );
        } catch (Throwable t) {
            context.log("Error hooking obfuscated OkHttp interceptor: " + t.getMessage(), t);
        }
    }

    private static void enqueueCapture(HookContext context, String url, String contentType, byte[] bytes) {
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

    private static Method findTargetInterceptMethod(ClassLoader classLoader) {
        for (String className : NETWORK_INTERCEPTOR_CLASSES) {
            Class<?> implClass = ReflectionHelper.findClassIfExists(className, classLoader);
            if (implClass == null) {
                continue;
            }
            for (Class<?> iface : implClass.getInterfaces()) {
                Method candidate = null;
                int abstractCount = 0;
                for (Method m : iface.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) && Modifier.isAbstract(m.getModifiers())) {
                        candidate = m;
                        abstractCount++;
                    }
                }
                if (abstractCount != 1 || candidate == null || candidate.getParameterCount() != 1) {
                    continue;
                }
                Class<?> returnType = candidate.getReturnType();
                if (returnType == void.class || returnType.isPrimitive() || returnType == String.class) {
                    continue;
                }
                Method implMethod = ReflectionHelper.findMethodExactIfExists(
                    implClass,
                    candidate.getName(),
                    candidate.getParameterTypes()
                );
                if (implMethod != null && implMethod.getReturnType() == returnType) {
                    implMethod.setAccessible(true);
                    return implMethod;
                }
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, int paramCount, Class<?> paramType, Class<?> returnType) {
        for (Class<?> cur = clazz; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                    || m.getParameterCount() != paramCount
                    || m.getReturnType() != returnType) {
                    continue;
                }
                if (paramCount == 1 && paramType != null && m.getParameterTypes()[0] != paramType) {
                    continue;
                }
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static Method findRequestUrlMethod(Class<?> requestClass) {
        for (Class<?> cur = requestClass; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
                    continue;
                }
                Class<?> ret = m.getReturnType();
                if (ret == void.class || ret.isPrimitive() || ret == String.class) {
                    continue;
                }
                if ("okhttp3.HttpUrl".equals(ret.getName())
                    || findMethod(ret, 0, null, URL.class) != null
                    || findMethod(ret, 0, null, URI.class) != null) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static Method findPeekBodyMethod(Class<?> responseClass) {
        for (Class<?> cur = responseClass; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1) {
                    continue;
                }
                Class<?> param = m.getParameterTypes()[0];
                Class<?> ret = m.getReturnType();
                if ((param == long.class || param == Long.TYPE)
                    && ret != void.class
                    && !ret.isPrimitive()
                    && findMethod(ret, 0, null, byte[].class) != null) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
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
        if (url == null || !url.contains("coolapk.com")) {
            return false;
        }
        return url.contains("/v6/feed/detail")
            || url.contains("/v6/feed/replyList")
            || url.contains("/v6/feed/replyDetail");
    }
}
