package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.parser.zhihu.ZhihuCaptureDecoder;
import com.melonhu.indexrecord.sync.HookCaptureClient;

public class ZhihuHook implements PlatformHook {

    public static final String HOST_APP = "com.zhihu.android";
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
            "ZhihuRecordInterceptor",
            ZhihuHook::isTargetUrl,
            (long) ZhihuCaptureDecoder.MAX_BYTES + 1,
            (url, contentType, bytes, response) -> {
                String contentEncoding = (String) ReflectionHelper.callMethod(
                    response,
                    "header",
                    "Content-Encoding"
                );
                context.getExecutor().submit(() -> {
                    try {
                        byte[] decoded = ZhihuCaptureDecoder.decode(bytes, contentEncoding);
                        context.log("[Capture OK] Content-Encoding=" + contentEncoding +
                            ", bytes=" + bytes.length + " -> " + decoded.length);
                        HookCaptureClient client = context.getCaptureClient();
                        if (client != null) {
                            client.enqueue(url, decoded, contentType, HOST_APP, System.currentTimeMillis());
                            client.sync();
                        }
                    } catch (Exception e) {
                        context.log("Capture rejected for " + url.split("\\?", 2)[0] +
                            " (Content-Encoding=" + contentEncoding + "): " + e);
                    }
                });
            }
        );
    }

    public static boolean isTargetUrl(String url) {
        if (url == null || !url.contains("zhihu.com")) {
            return false;
        }
        return (
            url.contains("/articles/") ||
            url.contains("/answers/") ||
            url.contains("/pins/v2/") ||
            url.contains("/appview/v2/answer/") ||
            url.contains("/questions/") ||
            url.contains("/comment_v5/") ||
            url.contains("/next-render")
        );
    }
}
