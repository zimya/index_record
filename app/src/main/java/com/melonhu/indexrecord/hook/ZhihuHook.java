package com.melonhu.indexrecord.hook;

import android.os.Handler;
import android.os.Looper;
import com.melonhu.indexrecord.parser.zhihu.ZhihuCaptureDecoder;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.lang.reflect.Method;

public class ZhihuHook implements PlatformHook {

    public static final String HOST_APP = "com.zhihu.android";
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
            url.contains("/appview/v2/answer/") ||
            url.contains("/questions/") ||
            url.contains("/comment_v5/") ||
            url.contains("/next-render")
        );
    }
}
