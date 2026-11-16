package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;

public class CoolapkHook implements PlatformHook {

    public static final String HOST_APP = "com.coolapk.market";
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
            "CoolapkRecordInterceptor",
            CoolapkHook::isTargetUrl,
            5 * 1024 * 1024L,
            (url, contentType, bytes, response) -> {
                context.getExecutor().submit(() -> {
                    try {
                        byte[] decompressed = decompressIfNeeded(bytes);
                        context.log("[Capture OK] bytes=" + bytes.length + " -> " + decompressed.length + ", url=" + url);
                        HookCaptureClient client = context.getCaptureClient();
                        if (client != null) {
                            client.enqueue(url, decompressed, contentType, HOST_APP, System.currentTimeMillis());
                            client.sync();
                        }
                    } catch (Exception e) {
                        context.log("Capture rejected for " + url + ": " + e);
                    }
                });
            }
        );
    }

    private static byte[] decompressIfNeeded(byte[] bytes) {
        if (bytes != null && bytes.length >= 2 && (bytes[0] & 255) == 31 && (bytes[1] & 255) == 139) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    out.write(chunk, 0, n);
                }
                return out.toByteArray();
            } catch (Exception ignored) {}
        }
        return bytes;
    }

    public static boolean isTargetUrl(String url) {
        if (url == null || !url.contains("coolapk.com")) {
            return false;
        }
        return url.contains("/v6/feed/detail")
            || url.contains("/v6/feed/replyList")
            || url.contains("/v6/feed/replyDetail");
    }
}
