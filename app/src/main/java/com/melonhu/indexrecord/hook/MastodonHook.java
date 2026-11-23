package com.melonhu.indexrecord.hook;

/** Mastodon 抓取入口。URL 白名单只保留图文与评论端点。 */
public class MastodonHook implements PlatformHook {

    public static final String HOST_APP = "org.joinmastodon.android";

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        OkHttpCaptureHook.install(context, classLoader, HOST_APP, MastodonHook::isTargetUrl);
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String path = url.toLowerCase(java.util.Locale.ROOT);
        return path.contains("/api/v1/statuses/")
            || path.contains("/api/v1/timelines/")
            || path.contains("/api/v1/accounts/")
            || path.contains("/api/v2/search");
    }
}
