package com.melonhu.indexrecord.hook;

import android.net.Uri;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.nio.charset.StandardCharsets;

public class WeiboHook implements PlatformHook {

    private final OkHttpHookHelper okHttpHelper = new OkHttpHookHelper();

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        context.onApplicationAttach(attachedContext -> {
            HookCaptureClient client = context.getCaptureClient();
            if (client != null) {
                client.setBeforeSync(() -> client.migrateLegacy(attachedContext.getPackageName()));
            }
            setupHooks(context, attachedContext.getClassLoader());
        });
        setupHooks(context, classLoader);
    }

    private void setupHooks(HookContext context, ClassLoader classLoader) {
        setupWeiboHttpResultHook(context, classLoader);
        okHttpHelper.setupOkHttpHook(
            context,
            classLoader,
            "WeiboRecordInterceptor",
            WeiboHook::isTargetUrl,
            5 * 1024 * 1024L,
            (url, contentType, bytes, response) -> {
                context.log("[Capture OK] Size: " + bytes.length);
                context.getExecutor().submit(() -> {
                    try {
                        HookCaptureClient client = context.getCaptureClient();
                        if (client != null) {
                            String hostPackage = context.getAndroidContext() != null ?
                                context.getAndroidContext().getPackageName() : context.getPackageName();
                            client.enqueue(url, bytes, contentType, hostPackage, System.currentTimeMillis());
                            client.sync();
                        }
                    } catch (Exception ignored) {}
                });
            }
        );
    }

    private void setupWeiboHttpResultHook(HookContext context, ClassLoader loader) {
        Class<?> resultClass = ReflectionHelper.findClassIfExists("com.sina.weibo.net.HttpResult", loader);
        if (resultClass == null) return;
        try {
            ReflectionHelper.hookAllConstructors(context.getXposed(), resultClass, chain -> {
                Object result = chain.proceed();
                Object[] args = chain.getArgs().toArray();
                if (args.length != 3) return result;
                try {
                    Object thisObj = chain.getThisObject();
                    String url = (String) ReflectionHelper.callMethod(thisObj, "getRequestUrl");
                    if (!isTargetUrl(url)) return result;

                    String text = (String) ReflectionHelper.callMethod(thisObj, "getResponseStr");
                    if (text == null || text.isEmpty()) return result;

                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    context.log("[Engine capture] " + Uri.parse(url).getPath() + " bytes=" + bytes.length);

                    context.getExecutor().submit(() -> {
                        try {
                            HookCaptureClient client = context.getCaptureClient();
                            if (client != null) {
                                String hostPackage = context.getAndroidContext() != null ?
                                    context.getAndroidContext().getPackageName() : context.getPackageName();
                                client.enqueue(url, bytes, "application/json; charset=utf-8", hostPackage, System.currentTimeMillis());
                                client.sync();
                            }
                        } catch (Exception e) {
                            context.log("Queue write failed: " + e.getClass().getSimpleName(), e);
                        }
                    });
                } catch (Throwable t) {
                    context.log("HttpResult capture failed: " + t.getClass().getSimpleName(), t);
                }
                return result;
            });
            context.log("Weibo HttpResult hook installed");
        } catch (Throwable t) {
            context.log("Weibo HttpResult hook failed: " + t, t);
        }
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String host = null;
        String path = null;
        try {
            Uri uri = Uri.parse(url);
            host = uri.getHost();
            path = uri.getPath();
        } catch (Throwable ignored) {}
        if (host == null || path == null) {
            try {
                java.net.URI u = java.net.URI.create(url);
                host = u.getHost();
                path = u.getPath();
            } catch (Throwable ignored) {}
        }
        if (
            host == null ||
            !(
                host.equals("weibo.cn") ||
                host.endsWith(".weibo.cn") ||
                host.equals("weibo.com") ||
                host.endsWith(".weibo.com")
            )
        ) return false;
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
}
