package com.melonhu.indexrecord;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.IOException;
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
import com.melonhu.indexrecord.sync.HookCaptureClient;

public class WeiboHook implements IXposedHookLoadPackage {

    private static volatile Context mContext;
    private static volatile HookCaptureClient client;
    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private static final String TAG = "WeiboRecord: ";
    private static final Set<ClassLoader> hookedClassLoaders =
        Collections.synchronizedSet(new HashSet<>());

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam)
        throws Throwable {
        if (
            !"com.sina.weibo".equals(lpparam.packageName) &&
            !"com.weico.international".equals(lpparam.packageName)
        ) return;

        XposedBridge.log(
            TAG +
                "Loading for " +
                lpparam.packageName +
                " in " +
                lpparam.processName
        );

        try {
            setupOkHttpHook(lpparam.classLoader);
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
                    HookCaptureClient captureClient = new HookCaptureClient(
                        attachedContext,
                        executor,
                        TAG
                    );
                    captureClient.setBeforeSync(() ->
                        captureClient.migrateLegacy(
                            attachedContext.getPackageName()
                        )
                    );
                    client = captureClient;
                    mContext = attachedContext;
                    XposedBridge.log(
                        TAG + "Context attached, setting up hook."
                    );
                    setupOkHttpHook(mContext.getClassLoader());
                    captureClient.registerReceiver();
                    executor.submit(() -> trySyncToApp());
                }
            }
        );
    }

    private void setupOkHttpHook(final ClassLoader classLoader) {
        if (classLoader == null) return;
        if (!hookedClassLoaders.add(classLoader)) return;
        setupWeiboHttpHook(classLoader);

        final Class<?> builderClass = XposedHelpers.findClassIfExists(
            "okhttp3.OkHttpClient$Builder",
            classLoader
        );
        if (builderClass == null) return;

        try {
            XposedHelpers.findAndHookMethod(
                builderClass,
                "build",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                        throws Throwable {
                        installInterceptor(param.thisObject, classLoader);
                    }
                }
            );
            XposedBridge.log(
                TAG + "OkHttp hook installed for classLoader: " + classLoader
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking build(): " + t.getMessage());
        }
    }

    private void setupWeiboHttpHook(ClassLoader loader) {
        Class<?> result = XposedHelpers.findClassIfExists(
            "com.sina.weibo.net.HttpResult",
            loader
        );
        if (result == null) return;
        try {
            // HttpResult is built after the selected engine has decoded the body.
            // Observe the shared result instead of consuming a live network stream.
            XposedBridge.hookAllConstructors(
                result,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // The one-argument constructor delegates to the three-argument one.
                        if (
                            param.hasThrowable() || param.args.length != 3
                        ) return;
                        try {
                            String url = (String) XposedHelpers.callMethod(
                                param.thisObject,
                                "getRequestUrl"
                            );

                            if (!isTargetUrl(url)) return;
                            String text = (String) XposedHelpers.callMethod(
                                param.thisObject,
                                "getResponseStr"
                            );
                            if (text == null || text.isEmpty()) return;
                            byte[] bytes = text.getBytes(
                                StandardCharsets.UTF_8
                            );
                            XposedBridge.log(
                                TAG +
                                    "[Engine capture] " +
                                    Uri.parse(url).getPath() +
                                    " bytes=" +
                                    bytes.length
                            );
                            executor.submit(() -> {
                                try {
                                    enqueueCapture(
                                        url,
                                        bytes,
                                        "application/json; charset=utf-8"
                                    );
                                    trySyncToApp();
                                } catch (Exception e) {
                                    XposedBridge.log(
                                        TAG +
                                            "Queue write failed: " +
                                            e.getClass().getSimpleName()
                                    );
                                }
                            });
                        } catch (Throwable t) {
                            XposedBridge.log(
                                TAG +
                                    "HttpResult capture failed: " +
                                    t.getClass().getSimpleName()
                            );
                        }
                    }
                }
            );
            XposedBridge.log(TAG + "Weibo HttpResult hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Native hook failed: " + t);
        }
    }

    private void installInterceptor(
        Object builder,
        final ClassLoader classLoader
    ) {
        try {
            List<?> interceptors = (List<?>) XposedHelpers.callMethod(
                builder,
                "interceptors"
            );
            if (interceptors != null) {
                for (Object i : interceptors) {
                    if (
                        i != null &&
                        "WeiboRecordInterceptor".equals(i.toString())
                    ) {
                        return;
                    }
                }
            }

            final Class<?> interceptorClass = XposedHelpers.findClassIfExists(
                "okhttp3.Interceptor",
                classLoader
            );
            if (interceptorClass == null) return;

            Object interceptor = Proxy.newProxyInstance(
                classLoader,
                new Class[] { interceptorClass },
                new InvocationHandler() {
                    @Override
                    public Object invoke(
                        Object proxy,
                        Method method,
                        Object[] args
                    ) throws Throwable {
                        String name = method.getName();
                        if ("intercept".equals(name)) {
                            return handleIntercept(args[0]);
                        }
                        if (
                            "toString".equals(name)
                        ) return "WeiboRecordInterceptor";
                        if ("equals".equals(name)) {
                            return (
                                proxy ==
                                (args != null && args.length > 0
                                    ? args[0]
                                    : null)
                            );
                        }
                        if (
                            "hashCode".equals(name)
                        ) return System.identityHashCode(proxy);

                        try {
                            return method.invoke(this, args);
                        } catch (Throwable t) {
                            return null;
                        }
                    }

                    private Object handleIntercept(Object chain)
                        throws Throwable {
                        Object request = XposedHelpers.callMethod(
                            chain,
                            "request"
                        );
                        Object response;
                        try {
                            response = XposedHelpers.callMethod(
                                chain,
                                "proceed",
                                request
                            );
                        } catch (Throwable t) {
                            Throwable cause = t;
                            while (cause != null) {
                                if (
                                    cause instanceof IOException
                                ) throw (IOException) cause;
                                if (
                                    cause.getCause() == cause ||
                                    cause.getCause() == null
                                ) break;
                                cause = cause.getCause();
                            }
                            throw t;
                        }

                        if (response != null) {
                            try {
                                Object urlObj = XposedHelpers.callMethod(
                                    request,
                                    "url"
                                );
                                String url =
                                    urlObj != null ? urlObj.toString() : "";
                                if (isTargetUrl(url)) {
                                    saveResponseAsync(response, url);
                                }
                            } catch (Throwable ignored) {}
                        }

                        return response;
                    }
                }
            );

            XposedHelpers.callMethod(builder, "addInterceptor", interceptor);
        } catch (Throwable ignored) {}
    }

    private static boolean isTargetUrl(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (
            host == null ||
            !(
                host.equals("weibo.cn") ||
                host.endsWith(".weibo.cn") ||
                host.equals("weibo.com") ||
                host.endsWith(".weibo.com")
            )
        ) return false;
        String path = uri.getPath();
        return (
            path != null &&
            (path.contains("/statuses/") ||
                path.contains("/comments/") ||
                path.contains("/container/getIndex") ||
                path.contains("/cardlist") ||
                path.contains("/searchall") ||
                path.contains("/statuses_show"))
        );
    }

    private void saveResponseAsync(Object response, final String url) {
        try {
            XposedBridge.log(TAG + "[Found URL] " + Uri.parse(url).getPath());
            String contentType = (String) XposedHelpers.callMethod(
                response,
                "header",
                "Content-Type"
            );
            if (contentType == null) return;
            String ct = contentType.toLowerCase();
            if (!ct.contains("json") && !ct.contains("text")) return;

            Object peekedBody = XposedHelpers.callMethod(
                response,
                "peekBody",
                (long) (1024 * 1024 * 5)
            );
            if (peekedBody == null) return;
            final byte[] bytes = (byte[]) XposedHelpers.callMethod(
                peekedBody,
                "bytes"
            );

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
        HookCaptureClient captureClient = client;
        if (captureClient != null) captureClient.sync();
    }

    private void enqueueCapture(String url, byte[] bytes, String contentType)
        throws IOException {
        HookCaptureClient captureClient = client;
        if (captureClient == null) return;
        captureClient.enqueue(
            url,
            bytes,
            contentType,
            mContext.getPackageName(),
            System.currentTimeMillis()
        );
    }
}
