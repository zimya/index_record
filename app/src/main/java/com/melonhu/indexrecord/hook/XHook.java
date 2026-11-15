package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.sync.HookCaptureClient;

public class XHook implements PlatformHook {

    public static final String HOST_APP = "com.twitter.android";
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
            "XRecordInterceptor",
            XHook::isTargetUrl,
            10 * 1024 * 1024L,
            (url, contentType, bytes, response) -> {
                context.log("[Capture OK] Size: " + bytes.length);
                context.getExecutor().submit(() -> {
                    try {
                        HookCaptureClient client = context.getCaptureClient();
                        if (client != null) {
                            client.enableQueueLink();
                            client.enqueue(url, bytes, contentType, HOST_APP, System.currentTimeMillis());
                            client.sync();
                        }
                    } catch (Exception ignored) {}
                });
            }
        );
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        if (!url.contains("twitter.com") && !url.contains("x.com")) {
            return false;
        }
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
}
