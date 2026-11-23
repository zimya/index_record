package com.melonhu.indexrecord.hook;

/** 微信读书 抓取入口。URL 白名单只保留图文与评论端点。 */
public class WeReadHook implements PlatformHook {

    public static final String HOST_APP = "com.tencent.weread";

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        OkHttpCaptureHook.install(context, classLoader, HOST_APP, WeReadHook::isTargetUrl);
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String host = url.toLowerCase(java.util.Locale.ROOT);
        if (!host.contains("weread.qq.com")) return false;
        return host.contains("/web/")
            || host.contains("/book/")
            || host.contains("/review");
    }
}
