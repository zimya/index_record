package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class HupuHook implements PlatformHook {

    public static final String HOST_APP = "com.hupu.games";
    private final OkHttpHookHelper okHttpHelper = new OkHttpHookHelper();

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

    private void setupHooks(HookContext context, ClassLoader classLoader) {
        okHttpHelper.setupOkHttpHook(
            context,
            classLoader,
            "HupuRecordInterceptor",
            HupuHook::isTargetUrl,
            5 * 1024 * 1024L,
            (url, contentType, bytes, response) -> handleResponse(context, url, contentType, bytes)
        );

        Class<?> okOneHooker = ReflectionHelper.findClassIfExists("com.cdh.okone.InjectHelper$BuilderHooker", classLoader);
        if (okOneHooker != null) {
            context.log("Found OkOne BuilderHooker, inspecting methods...");
            for (Method m : okOneHooker.getDeclaredMethods()) {
                if ("buildOkHttpClient".equals(m.getName())) {
                    try {
                        context.getXposed().hook(m).intercept(chain -> {
                            Object builder = chain.getArg(0);
                            installInterceptor(context, builder, classLoader);
                            return chain.proceed();
                        });
                        context.log("Hooked OkOne buildOkHttpClient: " + m);
                    } catch (Throwable t) {
                        context.log("Failed to hook OkOne: " + t);
                    }
                }
            }
        }

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
                        context.log("[RealCall URL] " + url);
                        if (isTargetUrl(url)) {
                            String contentType = (String) ReflectionHelper.callMethod(response, "header", "Content-Type");
                            if (contentType != null) {
                                String ct = contentType.toLowerCase();
                                if (ct.contains("json") || ct.contains("text")) {
                                    Object peekedBody = ReflectionHelper.callMethod(response, "peekBody", 5 * 1024 * 1024L);
                                    if (peekedBody != null) {
                                        byte[] bytes = (byte[]) ReflectionHelper.callMethod(peekedBody, "bytes");
                                        if (bytes != null && bytes.length > 0) {
                                            handleResponse(context, url, contentType, bytes);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        context.log("RealCall error: " + t);
                    }
                }
                return response;
            });
            context.log("Hooked " + className + "." + methodName);
        } catch (Throwable t) {
            context.log("Failed to hook " + className + "." + methodName + ": " + t);
        }
    }

    private void installInterceptor(HookContext context, Object builder, ClassLoader classLoader) {
        if (builder == null) return;
        try {
            Class<?> interceptorClass = ReflectionHelper.findClassIfExists("okhttp3.Interceptor", classLoader);
            if (interceptorClass == null) return;

            Object installed = ReflectionHelper.callMethod(builder, "interceptors");
            if (installed instanceof List<?>) {
                for (Object candidate : (List<?>) installed) {
                    if (candidate != null && "HupuRecordInterceptor".equals(candidate.toString())) {
                        return;
                    }
                }
            }

            Object interceptor = Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{interceptorClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if ("intercept".equals(name)) {
                            Object chain = args[0];
                            Object request = ReflectionHelper.callMethod(chain, "request");
                            Object response = ReflectionHelper.callMethod(chain, "proceed", request);
                            if (response != null) {
                                Object urlObj = ReflectionHelper.callMethod(request, "url");
                                String url = urlObj != null ? urlObj.toString() : "";
                                context.log("[OkOne Intercept URL] " + url);
                                if (isTargetUrl(url)) {
                                    String contentType = (String) ReflectionHelper.callMethod(response, "header", "Content-Type");
                                    if (contentType != null) {
                                        String ct = contentType.toLowerCase();
                                        if (ct.contains("json") || ct.contains("text")) {
                                            Object peekedBody = ReflectionHelper.callMethod(response, "peekBody", 5 * 1024 * 1024L);
                                            if (peekedBody != null) {
                                                byte[] bytes = (byte[]) ReflectionHelper.callMethod(peekedBody, "bytes");
                                                if (bytes != null && bytes.length > 0) {
                                                    handleResponse(context, url, contentType, bytes);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            return response;
                        }
                        if ("toString".equals(name)) {
                            return "HupuRecordInterceptor";
                        }
                        if ("equals".equals(name)) {
                            return proxy == (args != null && args.length > 0 ? args[0] : null);
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        return method.invoke(this, args);
                    }
                }
            );
            ReflectionHelper.callMethod(builder, "addInterceptor", interceptor);
            context.log("OkOne interceptor installed into builder");
        } catch (Throwable t) {
            context.log("Failed to install OkOne interceptor: " + t);
        }
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
        if (url.contains("e-goblin.") || url.contains("collect.") || url.contains("hermes.") ||
            url.contains("/score/") || url.contains("/evaluate/") || url.contains("/reddot/") ||
            url.contains("/bubbles/")) {
            return false;
        }
        return url.contains("bbs.mobileapi.hupu.com") && url.contains("/threads/");
    }
}
