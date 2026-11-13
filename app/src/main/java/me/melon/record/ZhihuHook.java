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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.melon.record.sync.HookCaptureClient;

public class ZhihuHook implements IXposedHookLoadPackage {

    private static volatile Context mContext;
    private static volatile HookCaptureClient client;
    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private static final String TAG = "ZhihuRecord: ";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam)
        throws Throwable {
        if (!"com.zhihu.android".equals(lpparam.packageName)) return;

        XposedBridge.log(
            TAG +
                "Loading for " +
                lpparam.packageName +
                " in " +
                lpparam.processName
        );

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
                        captureClient.migrateLegacy("com.zhihu.android")
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
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking build(): " + t.getMessage());
        }
    }

    private void installInterceptor(
        Object builder,
        final ClassLoader classLoader
    ) {
        try {
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
                        ) return "ZhihuRecordInterceptor";
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
                            // 【修复核心】解包 Xposed 的反射异常，确保 IOException 正确抛出
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
        if (url == null || !url.contains("zhihu.com")) {
            return false;
        }
        return (
            url.contains("/articles/") ||
            url.contains("/answers/") ||
            url.contains("/pins/v2/") ||
            url.contains("/appview/v2/answer/") ||
            url.contains("/questions/") ||
            url.contains("/comment_v5/") ||
            url.contains("/next-render")
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

            // 限制截取大小，恢复到 5MB 以应对大型知乎数据包
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
            "com.zhihu.android",
            System.currentTimeMillis()
        );
    }
}
