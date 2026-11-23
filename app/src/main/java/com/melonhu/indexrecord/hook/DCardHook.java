package com.melonhu.indexrecord.hook;

/** Dcard 抓取入口。URL 白名单只保留图文与评论端点。 */
public class DCardHook implements PlatformHook {

    public static final String HOST_APP = "com.dcard.app";

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        OkHttpCaptureHook.install(context, classLoader, HOST_APP, DCardHook::isTargetUrl);
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String host = url.toLowerCase(java.util.Locale.ROOT);
        if (!host.contains("dcard.tw")) return false;
        return host.contains("/service/api/")
            || host.contains("/api/");
    }
}
