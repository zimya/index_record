package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class NgaHook implements PlatformHook {

    public static final String HOST_APP = "gov.pianzong.androidnga";
    private static final Set<String> SAFE_QUERY_PARAMS = new HashSet<>(
        Arrays.asList("tid", "page", "pid", "uid", "authorid", "fid", "stid")
    );

    private final OkHttpHookHelper okHttpHelper = new OkHttpHookHelper();
    private volatile boolean volleyHooked = false;

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
        if (classLoader == null) return;
        okHttpHelper.setupOkHttpHook(
            context,
            classLoader,
            "NgaRecordInterceptor",
            NgaHook::isTargetUrl,
            5 * 1024 * 1024L,
            (url, contentType, bytes, response) -> enqueueCapture(context, url, contentType, bytes)
        );
        setupVolleyHook(context, classLoader);
    }

    private synchronized void setupVolleyHook(HookContext context, ClassLoader classLoader) {
        if (volleyHooked) return;
        Class<?> requestClass = ReflectionHelper.findClassIfExists("ov.j", classLoader);
        Class<?> networkResponseClass = ReflectionHelper.findClassIfExists("com.android.volley.NetworkResponse", classLoader);
        if (requestClass == null || networkResponseClass == null) {
            return;
        }
        Method parseMethod = ReflectionHelper.findMethodExactIfExists(requestClass, "parseNetworkResponse", networkResponseClass);
        Field dataField = null;
        try {
            dataField = networkResponseClass.getField("data");
            dataField.setAccessible(true);
        } catch (NoSuchFieldException ignored) {}
        if (parseMethod == null || dataField == null) {
            return;
        }

        final Field finalDataField = dataField;
        try {
            context.getXposed().hook(parseMethod).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object req = chain.getThisObject();
                    Object nr = chain.getArg(0);
                    if (req != null && nr != null) {
                        Object urlObj = ReflectionHelper.callMethod(req, "getUrl");
                        String url = urlObj != null ? urlObj.toString() : null;
                        if (isTargetUrl(url)) {
                            byte[] bytes = (byte[]) finalDataField.get(nr);
                            if (bytes != null && bytes.length > 0) {
                                String enrichedUrl = appendSafeParamsToUrl(url, req);
                                enqueueCapture(context, enrichedUrl, "application/json", bytes);
                            }
                        }
                    }
                } catch (Throwable t) {
                    context.log("NGA Volley capture error: " + t.getMessage(), t);
                }
                return result;
            });
            volleyHooked = true;
            context.log("NGA Volley hook installed");
        } catch (Throwable t) {
            context.log("Failed to hook ov.j.parseNetworkResponse: " + t.getMessage(), t);
        }
    }

    private void enqueueCapture(HookContext context, String url, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        context.log("[Capture OK] Size: " + bytes.length + " url=" + url);
        context.getExecutor().submit(() -> {
            try {
                HookCaptureClient client = context.getCaptureClient();
                if (client != null) {
                    client.enqueue(url, bytes, contentType != null ? contentType : "application/json", HOST_APP, System.currentTimeMillis());
                    client.sync();
                }
            } catch (Exception ignored) {}
        });
    }

    private static String appendSafeParamsToUrl(String url, Object req) {
        try {
            Object paramsObj = ReflectionHelper.callMethod(req, "getParams");
            if (paramsObj instanceof Map<?, ?>) {
                Map<?, ?> params = (Map<?, ?>) paramsObj;
                if (!params.isEmpty()) {
                    StringBuilder sb = new StringBuilder(url);
                    boolean hasQuery = url.contains("?");
                    for (Map.Entry<?, ?> entry : params.entrySet()) {
                        Object k = entry.getKey();
                        Object v = entry.getValue();
                        if (k == null || v == null) continue;
                        String key = k.toString();
                        if (!SAFE_QUERY_PARAMS.contains(key)) continue;
                        if (hasQuery) {
                            char last = sb.charAt(sb.length() - 1);
                            if (last != '&' && last != '?') {
                                sb.append('&');
                            }
                        } else {
                            sb.append('?');
                            hasQuery = true;
                        }
                        sb.append(key).append('=').append(v.toString());
                    }
                    return sb.toString();
                }
            }
        } catch (Throwable ignored) {}
        return url;
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        boolean isNgaDomain = lower.contains("ngabbs.com")
            || lower.contains("nga.cn")
            || lower.contains("ngacn.cc")
            || lower.contains("nga.178.com")
            || lower.contains("bigccq.cn");
        if (!isNgaDomain) return false;
        return (lower.contains("__lib=post") && lower.contains("__act=list"))
            || lower.contains("/read.php");
    }
}
