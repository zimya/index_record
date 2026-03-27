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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.annotation.SuppressLint;

public class ZhihuHook implements IXposedHookLoadPackage {

    private static Context mContext;
    
    // 【核心改进】使用单线程池处理本地缓存和跨进程同步，避免阻塞宿主 App 的网络请求
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final Pattern[] WHITELIST_PATTERNS = {
        Pattern.compile("https?://api\\.zhihu\\.com/answers/v2/[^/]+/?"),
        Pattern.compile("https?://www\\.zhihu\\.com/appview/v2/answer/[^/]+/?"),
        Pattern.compile("https?://api\\.zhihu\\.com/articles/v2/[^/]+/?"),
        Pattern.compile("https?://api\\.zhihu\\.com/articles/[^/]+/?"),
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("ZhihuHook: Loading for " + lpparam.processName);

        XposedHelpers.findAndHookMethod(
            Application.class,
            "attach",
            Context.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mContext = (Context) param.args[0];
                    ClassLoader classLoader = mContext.getClassLoader();
                    setupOkHttpHook(classLoader);
                    
                    // 注册广播接收器，当小茵记录启动时主动推送积压的数据
                    try {
                        BroadcastReceiver receiver = new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                if ("me.melon.record.ACTION_SYNC".equals(intent.getAction())) {
                                    XposedBridge.log("ZhihuHook: Received ACTION_SYNC from index_record, triggering sync.");
                                    executor.submit(() -> trySyncToApp());
                                }
                            }
                        };
                        IntentFilter filter = new IntentFilter("me.melon.record.ACTION_SYNC");
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            mContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                        } else {
                            mContext.registerReceiver(receiver, filter);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("ZhihuHook: Register receiver failed: " + t.getMessage());
                    }
                }
            }
        );
    }

    private void setupOkHttpHook(final ClassLoader classLoader) {
        final String builderClassName = "okhttp3.OkHttpClient$Builder";
        Class<?> okHttpBuilderClass = XposedHelpers.findClassIfExists(builderClassName, classLoader);

        if (okHttpBuilderClass == null) return;

        try {
            XposedHelpers.findAndHookMethod(
                okHttpBuilderClass,
                "build",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        installInterceptor(param.thisObject, classLoader);
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("ZhihuHook: Hook build() failed: " + t.getMessage());
        }
    }

    private void installInterceptor(Object builderObject, ClassLoader classLoader) {
        try {
            Class<?> interceptorClass = XposedHelpers.findClass("okhttp3.Interceptor", classLoader);
            Object myInterceptor = Proxy.newProxyInstance(
                classLoader,
                new Class[] { interceptorClass },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (!"intercept".equals(method.getName())) return method.invoke(this, args);

                        Object chain = args[0];
                        Object request = XposedHelpers.callMethod(chain, "request");
                        String url = XposedHelpers.callMethod(request, "url").toString();

                        Object response;
                        try {
                            response = XposedHelpers.callMethod(chain, "proceed", request);
                        } catch (Throwable t) {
                            Throwable cause = t.getCause();
                            while (cause != null && cause != t) {
                                if (cause instanceof IOException) throw cause;
                                t = cause;
                                cause = t.getCause();
                            }
                            throw t;
                        }

                        try {
                            if (shouldCaptureUrl(url)) saveResponse(response, url);
                        } catch (Throwable t) {
                            XposedBridge.log("Error printing response: " + t.getMessage());
                        }

                        return response;
                    }
                }
            );
            XposedHelpers.callMethod(builderObject, "addInterceptor", myInterceptor);
        } catch (Throwable t) {
            XposedBridge.log("ZhihuHook: Failed to install interceptor: " + t.getMessage());
        }
    }

    private boolean shouldCaptureUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String cleanUrl = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;

        for (Pattern pattern : WHITELIST_PATTERNS) {
            if (pattern.matcher(cleanUrl).matches()) return true;
        }
        return false;
    }

    private void saveResponse(Object response, String url) {
        if (mContext == null) return;

        try {
            String contentType = (String) XposedHelpers.callMethod(response, "header", "Content-Type");
            String encoding = (String) XposedHelpers.callMethod(response, "header", "Content-Encoding");

            if (encoding != null) {
                encoding = encoding.toLowerCase();
                if (encoding.contains("gzip") || encoding.contains("br") || encoding.contains("deflate")) return;
            }

            boolean isText = contentType != null && (contentType.contains("json") || contentType.contains("text") || contentType.contains("xml") || contentType.contains("html"));
            if (!isText) return;

            Object responseBody = XposedHelpers.callMethod(response, "peekBody", 1024 * 1024 * 5L); // 允许最大 5MB
            byte[] bytes = (byte[]) XposedHelpers.callMethod(responseBody, "bytes");

            if (bytes != null && bytes.length > 0) {
                final String content = new String(bytes, StandardCharsets.UTF_8);

                // 【核心改进】异步存入本地缓存文件，再尝试同步
                executor.submit(() -> {
                    try {
                        JSONObject wrapper = new JSONObject();
                        wrapper.put("url", url);
                        wrapper.put("json", content);
                        wrapper.put("hostApp", mContext.getPackageName());

                        // 1. 建立在宿主App（如知乎）沙盒下的缓存目录
                        File cacheDir = new File(mContext.getCacheDir(), "index_record_cache");
                        if (!cacheDir.exists()) cacheDir.mkdirs();

                        // 2. 写入缓存文件
                        String fileName = System.currentTimeMillis() + "_" + System.nanoTime() + ".json";
                        File cacheFile = new File(cacheDir, fileName);
                        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                            fos.write(wrapper.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        
                        XposedBridge.log("ZhihuHook: Cached to disk -> " + fileName);

                        // 3. 尝试将缓存目录中的文件同步到 小茵记录 App
                        trySyncToApp();

                    } catch (Exception e) {
                        XposedBridge.log("ZhihuHook: Background cache task failed: " + e.getMessage());
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("ZhihuHook: saveResponse exception: " + t.getMessage());
        }
    }

    private void trySyncToApp() {
        File cacheDir = new File(mContext.getCacheDir(), "index_record_cache");
        if (!cacheDir.exists()) return;

        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) return;

        Uri uri = Uri.parse("content://me.melon.record.provider/raw_data");

        for (File file : files) {
            try {
                // 读取文件内容
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                JSONObject wrapper = new JSONObject(sb.toString());
                ContentValues values = new ContentValues();
                values.put("url", wrapper.optString("url"));
                values.put("json", wrapper.optString("json"));
                values.put("hostApp", wrapper.optString("hostApp"));

                // 跨进程发送给小茵记录 App
                Uri resultUri = mContext.getContentResolver().insert(uri, values);
                
                if (resultUri != null) {
                    // 发送成功，删除本地缓存
                    boolean deleted = file.delete();
                    XposedBridge.log("ZhihuHook: Successfully synced and deleted " + file.getName());
                } else {
                    XposedBridge.log("ZhihuHook: Sync failed (Provider returned null), stop syncing current batch.");
                    break; // Provider 挂了/没权限唤醒，直接跳出循环，避免烧 CPU
                }
            } catch (Exception e) {
                XposedBridge.log("ZhihuHook: Error syncing file " + file.getName() + " -> " + e.getMessage());
                // 如果是 JSON 格式损坏导致抛异常，直接删掉避免死循环卡在这里
                if (e instanceof org.json.JSONException) {
                    file.delete();
                }
            }
        }
    }
}
