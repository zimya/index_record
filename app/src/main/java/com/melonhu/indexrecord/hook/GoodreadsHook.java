package com.melonhu.indexrecord.hook;

/** Goodreads 抓取入口。URL 白名单只保留图文与评论端点。 */
public class GoodreadsHook implements PlatformHook {

    public static final String HOST_APP = "com.goodreads";

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        OkHttpCaptureHook.install(context, classLoader, HOST_APP, GoodreadsHook::isTargetUrl);
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String host = url.toLowerCase(java.util.Locale.ROOT);
        if (!host.contains("goodreads.com")) return false;
        return host.contains("/review/show")
            || host.contains("/book/show")
            || host.contains("/api/");
    }
}
