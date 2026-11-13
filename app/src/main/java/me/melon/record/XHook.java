package me.melon.record;

import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.melon.record.sync.HookCaptureClient;

public class XHook implements IXposedHookLoadPackage {

    private static volatile Context mContext;
    private static volatile HookCaptureClient client;
    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private static final String TAG = "XRecord: ";
    private static final Set<ClassLoader> hookedClassLoaders =
        Collections.synchronizedSet(new HashSet<>());

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam)
        throws Throwable {
        if (!"com.twitter.android".equals(lpparam.packageName)) return;

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
                        captureClient.migrateLegacy("com.twitter.android")
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

        final Class<?> builderClass = XposedHelpers.findClassIfExists(
            "okhttp3.OkHttpClient$Builder",
            classLoader
        );
        if (builderClass == null) {
            XposedBridge.log(
                TAG + "OkHttpClient$Builder not found in " + classLoader
            );
            return;
        }

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
                        i != null && "XRecordInterceptor".equals(i.toString())
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
                        ) return "XRecordInterceptor";
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
        if (!url.contains("twitter.com") && !url.contains("x.com")) {
            return false;
        }
        // 过滤与推文内容无关的认证/心跳接口
        if (
            url.contains("GenerateAttestation") ||
            url.contains("ExchangeClientDataForAttestation") ||
            url.contains("GrokAccountJwt") ||
            url.contains("ClientEvent")
        ) {
            return false;
        }
        return (
            url.contains("/graphql/") ||
            url.contains("/i/api/") ||
            url.contains("/HomeTimeline") ||
            url.contains("/ConversationTimeline") ||
            url.contains("/SearchTimeline") ||
            url.contains("/Bookmarks") ||
            url.contains("/Likes") ||
            url.contains("/UserTweets") ||
            url.contains("/HomeLatestTimeline")
        );
    }

    private void saveResponseAsync(Object response, final String url) {
        try {
            XposedBridge.log(
                TAG +
                    "[Found URL] " +
                    (url.length() > 100 ? url.substring(0, 100) : url)
            );
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
                (long) (1024 * 1024 * 10)
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
        captureClient.enableQueueLink();
        captureClient.enqueue(
            url,
            bytes,
            contentType,
            "com.twitter.android",
            System.currentTimeMillis()
        );
    }
}
