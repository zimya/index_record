package com.melonhu.indexrecord.hook;

/** 哔哩哔哩 抓取入口。URL 白名单只保留图文与评论端点。 */
public class BilibiliHook implements PlatformHook {

    public static final String HOST_APP = "tv.danmaku.bili";

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        OkHttpCaptureHook.install(context, classLoader, HOST_APP, BilibiliHook::isTargetUrl);
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String host = url.toLowerCase(java.util.Locale.ROOT);
        if (!host.contains("bilibili.com")) return false;
        return host.contains("/x/article/view")
            || host.contains("/x/polymer/web-dynamic/v1/opus")
            || host.contains("/x/v2/reply");
    }
}
