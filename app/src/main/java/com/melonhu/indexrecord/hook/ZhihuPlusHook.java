package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.parser.zhihu.ZhihuCaptureDecoder;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URLConnection;

/**
 * 知乎++（Zhihu++ Lite）抓取入口。
 *
 * 知乎++ 使用 Ktor 的 Android engine，底层是 java.net.HttpURLConnection，
 * 不经过 OkHttp，因此单独 hook HttpURLConnection 的输入流并旁路复制响应体。
 */
public class ZhihuPlusHook implements PlatformHook {

    public static final String HOST_APP = "com.github.zly2006.zhplus.lite";
    public static final String HOST_APP_FULL = "com.github.zly2006.zhplus";
    private static final int LIMIT = ZhihuCaptureDecoder.MAX_BYTES + 1;

    private static final String[] CONNECTION_CLASSES = {
        "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
        "com.android.okhttp.internal.huc.DelegatingHttpsURLConnection",
        "com.android.okhttp.internal.huc.HttpsURLConnectionImpl",
        "sun.net.www.protocol.http.HttpURLConnection"
    };

    private volatile boolean installed;

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        context.onApplicationAttach(attached -> setupHooks(context, attached.getClassLoader()));
        setupHooks(context, classLoader);
    }

    private synchronized void setupHooks(HookContext context, ClassLoader classLoader) {
        if (installed) return;
        for (String className : CONNECTION_CLASSES) {
            Class<?> target = loadClass(className, classLoader);
            if (target == null) continue;
            Method method = findGetInputStream(target);
            if (method == null) continue;
            try {
                context.getXposed().hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!(result instanceof InputStream)) return result;
                    Object self = chain.getThisObject();
                    if (!(self instanceof URLConnection)) return result;
                    URLConnection connection = (URLConnection) self;
                    String url = connection.getURL() != null ? connection.getURL().toString() : null;
                    if (url == null || !isTargetUrl(url)) return result;
                    return new TeeInputStream(
                        (InputStream) result,
                        context,
                        url,
                        connection.getContentType(),
                        connection.getHeaderField("Content-Encoding")
                    );
                });
                installed = true;
                context.log("Zhihu++ stream hook installed on " + className);
                return;
            } catch (Throwable error) {
                context.log("Cannot hook " + className + ": " + error);
            }
        }
    }

    private static Class<?> loadClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
        }
        return ReflectionHelper.findClassIfExists(name, classLoader);
    }

    private static Method findGetInputStream(Class<?> target) {
        for (Method method : target.getMethods()) {
            if ("getInputStream".equals(method.getName()) && method.getParameterTypes().length == 0) {
                return method;
            }
        }
        return null;
    }

    public static boolean isTargetUrl(String url) {
        if (url == null || !url.contains("zhihu.com") || !url.contains("/api/v4/")) return false;
        return url.contains("/api/v4/answers/")
            || url.contains("/api/v4/articles/")
            || url.contains("/api/v4/questions/")
            || url.contains("/api/v4/pins/")
            || url.contains("/api/v4/comment_v5/")
            || url.contains("/api/v4/feed/");
    }

    private static final class TeeInputStream extends FilterInputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final HookContext context;
        private final String url;
        private final String contentType;
        private final String contentEncoding;
        private boolean delivered;
        private boolean truncated;

        TeeInputStream(
            InputStream source,
            HookContext context,
            String url,
            String contentType,
            String contentEncoding
        ) {
            super(source);
            this.context = context;
            this.url = url;
            this.contentType = contentType;
            this.contentEncoding = contentEncoding;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) append(new byte[]{(byte) value}, 0, 1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) append(bytes, offset, count);
            return count;
        }

        private void append(byte[] bytes, int offset, int length) {
            if (truncated) return;
            if (buffer.size() + length > LIMIT) {
                truncated = true;
                return;
            }
            buffer.write(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                deliver();
            }
        }

        private void deliver() {
            if (delivered) return;
            delivered = true;
            if (truncated) {
                context.log("Zhihu++ capture dropped (body over " + LIMIT + " bytes): " + url);
                return;
            }
            byte[] bytes = buffer.toByteArray();
            if (bytes.length == 0) return;
            context.getExecutor().submit(() -> {
                try {
                    byte[] decoded = ZhihuCaptureDecoder.decode(bytes, contentEncoding);
                    HookCaptureClient client = context.getCaptureClient();
                    if (client == null) return;
                    client.enqueue(
                        url,
                        decoded,
                        contentType != null ? contentType : "application/json",
                        context.getPackageName(),
                        System.currentTimeMillis()
                    );
                    client.sync();
                    context.log("[Capture OK] bytes=" + bytes.length + " -> " + decoded.length + ", url=" + url);
                } catch (Exception error) {
                    context.log("Zhihu++ capture rejected for " + url + ": " + error);
                }
            });
        }
    }
}
