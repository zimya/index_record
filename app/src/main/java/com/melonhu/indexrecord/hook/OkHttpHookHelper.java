package com.melonhu.indexrecord.hook;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OkHttpHookHelper {

    public interface TargetUrlFilter {
        boolean isTargetUrl(String url);
    }

    public interface ContentTypeFilter {
        boolean isTargetContentType(String contentType);
    }

    public interface ResponseCaptureListener {
        void onResponse(String url, String contentType, byte[] bytes, Object response) throws Throwable;
    }

    private final Set<ClassLoader> hookedLoaders = Collections.synchronizedSet(new HashSet<>());

    public boolean setupOkHttpHook(
        HookContext context,
        ClassLoader classLoader,
        String interceptorTag,
        TargetUrlFilter urlFilter,
        long maxPeekBytes,
        ResponseCaptureListener listener
    ) {
        return setupOkHttpHook(
            context,
            classLoader,
            interceptorTag,
            urlFilter,
            contentType -> {
                if (contentType == null) return false;
                String ct = contentType.toLowerCase();
                return ct.contains("json") || ct.contains("text");
            },
            maxPeekBytes,
            listener
        );
    }

    public boolean setupOkHttpHook(
        HookContext context,
        ClassLoader classLoader,
        String interceptorTag,
        TargetUrlFilter urlFilter,
        ContentTypeFilter contentTypeFilter,
        long maxPeekBytes,
        ResponseCaptureListener listener
    ) {
        if (classLoader == null || !hookedLoaders.add(classLoader)) {
            return false;
        }

        Class<?> builderClass = ReflectionHelper.findClassIfExists("okhttp3.OkHttpClient$Builder", classLoader);
        if (builderClass == null) {
            hookedLoaders.remove(classLoader);
            return false;
        }

        Method buildMethod = ReflectionHelper.findMethodExactIfExists(builderClass, "build");
        if (buildMethod == null) {
            hookedLoaders.remove(classLoader);
            return false;
        }

        try {
            context.getXposed().hook(buildMethod).intercept(chain -> {
                Object builder = chain.getThisObject();
                installInterceptor(context, builder, classLoader, interceptorTag, urlFilter, contentTypeFilter, maxPeekBytes, listener);
                return chain.proceed();
            });
            context.log("OkHttp hook installed for classLoader: " + classLoader);
            return true;
        } catch (Throwable t) {
            hookedLoaders.remove(classLoader);
            context.log("Error hooking OkHttpClient$Builder.build(): " + t.getMessage(), t);
            return false;
        }
    }

    private void installInterceptor(
        HookContext context,
        Object builder,
        ClassLoader classLoader,
        String interceptorTag,
        TargetUrlFilter urlFilter,
        ContentTypeFilter contentTypeFilter,
        long maxPeekBytes,
        ResponseCaptureListener listener
    ) {
        try {
            Class<?> interceptorClass = ReflectionHelper.findClassIfExists("okhttp3.Interceptor", classLoader);
            if (interceptorClass == null) return;

            Object installed = ReflectionHelper.callMethod(builder, "interceptors");
            if (installed instanceof List<?>) {
                for (Object candidate : (List<?>) installed) {
                    if (candidate != null && interceptorTag.equals(candidate.toString())) {
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
                            return handleIntercept(args[0]);
                        }
                        if ("toString".equals(name)) {
                            return interceptorTag;
                        }
                        if ("equals".equals(name)) {
                            return proxy == (args != null && args.length > 0 ? args[0] : null);
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        try {
                            return method.invoke(this, args);
                        } catch (Throwable t) {
                            return null;
                        }
                    }

                    private Object handleIntercept(Object chain) throws Throwable {
                        Object request = ReflectionHelper.callMethod(chain, "request");
                        Object response;
                        try {
                            response = ReflectionHelper.callMethod(chain, "proceed", request);
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
                                Object urlObj = ReflectionHelper.callMethod(request, "url");
                                String url = urlObj != null ? urlObj.toString() : "";
                                if (urlFilter.isTargetUrl(url)) {
                                    handleTargetResponse(response, url);
                                }
                            } catch (Throwable ignored) {}
                        }

                        return response;
                    }

                    private void handleTargetResponse(Object response, String url) {
                        try {
                            context.log("[Found URL] " + (url.length() > 100 ? url.substring(0, 100) : url));
                            String contentType = (String) ReflectionHelper.callMethod(response, "header", "Content-Type");
                            if (!contentTypeFilter.isTargetContentType(contentType)) return;

                            Object peekedBody = ReflectionHelper.callMethod(response, "peekBody", maxPeekBytes);
                            if (peekedBody == null) return;
                            byte[] bytes = (byte[]) ReflectionHelper.callMethod(peekedBody, "bytes");

                            if (bytes != null && bytes.length > 0) {
                                listener.onResponse(url, contentType, bytes, response);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            );

            ReflectionHelper.callMethod(builder, "addInterceptor", interceptor);
        } catch (Throwable ignored) {}
    }
}
