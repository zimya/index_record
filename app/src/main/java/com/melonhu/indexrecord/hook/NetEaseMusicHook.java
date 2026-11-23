package com.melonhu.indexrecord.hook;

/** 网易云音乐 抓取入口。URL 白名单只保留图文与评论端点。 */
public class NetEaseMusicHook implements PlatformHook {

    public static final String HOST_APP = "com.netease.cloudmusic";

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        OkHttpCaptureHook.install(context, classLoader, HOST_APP, NetEaseMusicHook::isTargetUrl);
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String host = url.toLowerCase(java.util.Locale.ROOT);
        if (!host.contains("music.163.com")) return false;
        return host.contains("/api/")
            || host.contains("/eapi/")
            || host.contains("/weapi/");
    }
}
