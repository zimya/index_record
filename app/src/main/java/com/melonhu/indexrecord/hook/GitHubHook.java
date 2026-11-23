package com.melonhu.indexrecord.hook;

/** GitHub 抓取入口。URL 白名单只保留图文与评论端点。 */
public class GitHubHook implements PlatformHook {

    public static final String HOST_APP = "com.github.android";

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        OkHttpCaptureHook.install(context, classLoader, HOST_APP, GitHubHook::isTargetUrl);
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String host = url.toLowerCase(java.util.Locale.ROOT);
        if (!host.contains("github.com")) return false;
        return host.contains("/issues/")
            || host.contains("/pulls/")
            || host.contains("/discussions")
            || host.contains("/releases")
            || host.contains("/readme")
            || host.contains("/comments")
            || host.contains("/graphql");
    }
}
