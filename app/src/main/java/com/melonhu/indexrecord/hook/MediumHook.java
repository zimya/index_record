package com.melonhu.indexrecord.hook;

/** Medium 抓取入口。URL 白名单只保留图文与评论端点。 */
public class MediumHook implements PlatformHook {

    public static final String HOST_APP = "com.medium.reader";

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        OkHttpCaptureHook.install(context, classLoader, HOST_APP, MediumHook::isTargetUrl);
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String host = url.toLowerCase(java.util.Locale.ROOT);
        if (!host.contains("medium.com")) return false;
        return host.contains("/graphql")
            || host.contains("/_/api/")
            || host.contains("/p/");
    }
}
