package me.melon.record;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class ZhihuHook implements IXposedHookLoadPackage {

    // 1. 定义一个静态变量来保存 Context
    private static Context mContext;

    // // 白名单关键词：通常我们只关心回答、文章、想法
    // private static final String[] TARGET_KEYWORDS = {
    //     "api.zhihu.com/answers/v2", // 知乎回答（卡片模式）
    //     "api.zhihu.com/v4/answers/", // 知乎回答（全文模式）
    //     "api.zhihu.com/articles/v2", // 知乎文章（卡片模式）
    //     "api.zhihu.com/v4/articles/", // 知乎文章（全文模式）
    //     "api.zhihu.com/questions/", // 问题详情
    //     "api.zhihu.com/pins/", // 想法
    // };
    //
    private static final Pattern[] WHITELIST_PATTERNS = {
        // 知乎回答（卡片模式）
        Pattern.compile("https?://api\\.zhihu\\.com/answers/v2/[^/]+/?"),
        // 知乎回答（全文模式）
        Pattern.compile("https?://www\\.zhihu\\.com/appview/v2/answer/[^/]+/?"),
        // 知乎文章（卡片模式）
        Pattern.compile("https?://api\\.zhihu\\.com/articles/v2/[^/]+/?"),
        // 知乎文章（全文模式）
        Pattern.compile("https?://api\\.zhihu\\.com/articles/[^/]+/?"),
    };

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam)
        throws Throwable {
        // 过滤包名，只在目标 App 生效 (调试时可先注释掉，生产环境建议打开)
        // if (!"com.zhihu.android".equals(lpparam.packageName)) return;

        XposedBridge.log("ZhihuHook: Loading for " + lpparam.processName);

        // Hook Application.attach 拿到 Context
        XposedHelpers.findAndHookMethod(
            Application.class,
            "attach",
            Context.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param)
                    throws Throwable {
                    // 2. 在这里赋值全局 Context
                    mContext = (Context) param.args[0];
                    ClassLoader classLoader = mContext.getClassLoader();

                    // 开始执行 OkHttp Hook
                    setupOkHttpHook(classLoader);
                }
            }
        );
    }

    private void setupOkHttpHook(final ClassLoader classLoader) {
        final String builderClassName = "okhttp3.OkHttpClient$Builder";
        Class<?> okHttpBuilderClass = XposedHelpers.findClassIfExists(
            builderClassName,
            classLoader
        );

        if (okHttpBuilderClass == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                okHttpBuilderClass,
                "build",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                        throws Throwable {
                        installInterceptor(param.thisObject, classLoader);
                    }
                }
            );
            XposedBridge.log("ZhihuHook: Successfully hooked OkHttp build()");
        } catch (Throwable t) {
            XposedBridge.log(
                "ZhihuHook: Hook build() failed: " + t.getMessage()
            );
        }
    }

    private void installInterceptor(
        Object builderObject,
        ClassLoader classLoader
    ) {
        try {
            Class<?> interceptorClass = XposedHelpers.findClass(
                "okhttp3.Interceptor",
                classLoader
            );

            Object myInterceptor = Proxy.newProxyInstance(
                classLoader,
                new Class[] { interceptorClass },
                new InvocationHandler() {
                    @Override
                    public Object invoke(
                        Object proxy,
                        Method method,
                        Object[] args
                    ) throws Throwable {
                        if (!"intercept".equals(method.getName())) {
                            return method.invoke(this, args);
                        }

                        Object chain = args[0];
                        Object request = XposedHelpers.callMethod(
                            chain,
                            "request"
                        );
                        String url = XposedHelpers.callMethod(
                            request,
                            "url"
                        ).toString();

                        Object response;
                        try {
                            response = XposedHelpers.callMethod(
                                chain,
                                "proceed",
                                request
                            );
                        } catch (Throwable t) {
                            Throwable cause = t.getCause();
                            while (cause != null && cause != t) {
                                if (cause instanceof IOException) {
                                    throw cause;
                                }
                                t = cause;
                                cause = t.getCause();
                            }
                            throw t;
                        }

                        try {
                            if (shouldCaptureUrl(url)) {
                                saveResponse(response, url);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(
                                "Error printing response: " + t.getMessage()
                            );
                        }

                        return response;
                    }
                }
            );

            XposedHelpers.callMethod(
                builderObject,
                "addInterceptor",
                myInterceptor
            );
        } catch (Throwable t) {
            XposedBridge.log(
                "ZhihuHook: Failed to install interceptor: " + t.getMessage()
            );
        }
    }

    private boolean shouldCaptureUrl(String url) {
        if (url == null || url.isEmpty()) return false;

        // 提取不带参数的原始 URL
        // 例如: https://api.zhihu.com/answers/v2/123?utm_source=... -> https://api.zhihu.com/answers/v2/123
        String cleanUrl;
        int queryIndex = url.indexOf('?');
        if (queryIndex != -1) {
            cleanUrl = url.substring(0, queryIndex);
        } else {
            cleanUrl = url;
        }

        for (Pattern pattern : WHITELIST_PATTERNS) {
            if (pattern.matcher(cleanUrl).matches()) {
                return true; // 符合白名单
            }
        }
        return false;
    }

    private void saveResponse(Object response, String url) {
        // 3. 使用全局 mContext，并进行判空
        if (mContext == null) {
            XposedBridge.log("ZhihuHook: Context is null, skip saving.");
            return;
        }

        try {
            String contentType = (String) XposedHelpers.callMethod(
                response,
                "header",
                "Content-Type"
            );
            String encoding = (String) XposedHelpers.callMethod(
                response,
                "header",
                "Content-Encoding"
            );

            if (encoding != null) {
                encoding = encoding.toLowerCase();
                if (
                    encoding.contains("gzip") ||
                    encoding.contains("br") ||
                    encoding.contains("deflate")
                ) {
                    return;
                }
            }

            boolean isText =
                contentType != null &&
                (contentType.contains("json") ||
                    contentType.contains("text") ||
                    contentType.contains("xml") ||
                    contentType.contains("html"));

            if (!isText) return;

            Object responseBody = XposedHelpers.callMethod(
                response,
                "peekBody",
                1024 * 512L
            );
            byte[] bytes = (byte[]) XposedHelpers.callMethod(
                responseBody,
                "bytes"
            );

            if (bytes != null && bytes.length > 0) {
                String content = new String(bytes, StandardCharsets.UTF_8);

                // 防止数据过大导致 Binder 崩溃 (ContentProvider 基于 Binder)
                if (content.length() > 200000) {
                    XposedBridge.log("ZhihuHook: Content too large, skipping.");
                    return;
                }

                // 4. 发送数据到 ContentProvider
                ContentValues values = new ContentValues();
                values.put("url", url);
                values.put("json", content);
                values.put("hostApp", mContext.getPackageName());

                // 这里的 Authority 必须和你 App Manifest 里注册的一致
                Uri uri = Uri.parse(
                    "content://me.melon.record.provider/raw_data"
                );

                mContext.getContentResolver().insert(uri, values);

                XposedBridge.log("ZhihuHook: Saved " + url);
            }
        } catch (Throwable t) {
            XposedBridge.log(
                "ZhihuHook: Send to provider failed: " + t.getMessage()
            );
        }
    }
}
