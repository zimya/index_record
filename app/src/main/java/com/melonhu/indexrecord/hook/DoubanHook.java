package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.parser.douban.DoubanSubjectUrls;
import com.melonhu.indexrecord.sync.HookCaptureClient;

public class DoubanHook implements PlatformHook {

    public static final String HOST_APP = "com.douban.frodo";
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
            "DoubanRecordInterceptor",
            DoubanHook::isTargetUrl,
            5 * 1024 * 1024L,
            (url, contentType, bytes, response) -> {
                context.log("[Capture OK] Size: " + bytes.length);
                context.getExecutor().submit(() -> {
                    try {
                        HookCaptureClient client = context.getCaptureClient();
                        if (client != null) {
                            client.enqueue(url, bytes, contentType, HOST_APP, System.currentTimeMillis());
                            client.sync();
                        }
                    } catch (Exception ignored) {}
                });
            }
        );
    }

    public static boolean isTargetUrl(String url) {
        if (url == null || (!url.contains("douban.com") && !url.contains("doubanio.com"))) {
            return false;
        }
        return url.contains("/group/topic/")
            || url.contains("/hot_interests")
            || url.contains("/review/")
            || url.contains("/forum_topic/")
            || DoubanSubjectUrls.shouldCapture(url);
    }
}
