package com.melonhu.indexrecord.hook;

/** LOFTER 抓取入口。URL 白名单只保留图文与评论端点。 */
public class LofterHook implements PlatformHook {

    public static final String HOST_APP = "com.lofter.android";

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        OkHttpCaptureHook.install(context, classLoader, HOST_APP, LofterHook::isTargetUrl);
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String host = url.toLowerCase(java.util.Locale.ROOT);
        if (!host.contains("lofter.com")) return false;
        return host.contains("/post/")
            || host.contains("/api/")
            || host.contains("/dwr/");
    }
}
