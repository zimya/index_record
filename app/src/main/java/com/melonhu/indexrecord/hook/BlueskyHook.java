package com.melonhu.indexrecord.hook;

import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class BlueskyHook implements PlatformHook {

    public static final String HOST_APP = "xyz.blueskyweb.app";
    private static final String NATIVE_RESPONSE_CLASS = "expo.modules.fetch.NativeResponse";

    private volatile boolean hooked = false;

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        context.onApplicationAttach(attachedContext -> {
            setupHooks(context, attachedContext.getClassLoader());
        });
        setupHooks(context, classLoader);
    }

    private void setupHooks(HookContext context, ClassLoader classLoader) {
        if (hooked || classLoader == null) return;
        Class<?> nativeResponseClass = ReflectionHelper.findClassIfExists(NATIVE_RESPONSE_CLASS, classLoader);
        if (nativeResponseClass == null) {
            return;
        }

        Method stateMethod = findSingleEnumMethod(nativeResponseClass);
        if (stateMethod == null) {
            context.log("NativeResponse state transition method not found");
            return;
        }

        try {
            context.getXposed().hook(stateMethod).intercept(chain -> {
                try {
                    Object self = chain.getThisObject();
                    Object state = chain.getArg(0);
                    String stateName = state != null ? state.toString() : "";
                    if ("BODY_COMPLETED".equals(stateName) && self != null) {
                        String url = extractUrl(self);
                        if (url != null && isTargetUrl(url)) {
                            byte[] bytes = extractAccumulatedBytes(self);
                            if (bytes != null && bytes.length > 0) {
                                context.log("[Capture OK] Size: " + bytes.length + " url=" + url);
                                context.getExecutor().submit(() -> {
                                    try {
                                        HookCaptureClient client = context.getCaptureClient();
                                        if (client != null) {
                                            client.enqueue(url, bytes, "application/json", HOST_APP, System.currentTimeMillis());
                                            client.sync();
                                        }
                                    } catch (Exception ignored) {}
                                });
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                return chain.proceed();
            });
            hooked = true;
            context.log("Bluesky NativeResponse hook installed");
        } catch (Throwable t) {
            context.log("Failed to hook NativeResponse: " + t.getMessage(), t);
        }
    }

    private static Method findSingleEnumMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isEnum()) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static String extractUrl(Object nativeResponse) {
        try {
            for (Field rf : nativeResponse.getClass().getDeclaredFields()) {
                if (rf.getType().isPrimitive() || rf.getType() == String.class) continue;
                rf.setAccessible(true);
                Object init = rf.get(nativeResponse);
                if (init == null) continue;
                for (Field f : init.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        Object val = f.get(init);
                        if (val instanceof String && ((String) val).startsWith("http")) {
                            return (String) val;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] extractAccumulatedBytes(Object nativeResponse) {
        try {
            for (Field rf : nativeResponse.getClass().getDeclaredFields()) {
                if (rf.getType().isPrimitive() || rf.getType() == String.class) continue;
                rf.setAccessible(true);
                Object accumulator = rf.get(nativeResponse);
                if (accumulator == null) continue;
                for (Field f : accumulator.getClass().getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object listObj = f.get(accumulator);
                        if (listObj instanceof List<?>) {
                            List<?> list = (List<?>) listObj;
                            if (!list.isEmpty() && list.get(0) instanceof byte[]) {
                                ByteArrayOutputStream out = new ByteArrayOutputStream();
                                for (Object chunk : list) {
                                    if (chunk instanceof byte[]) {
                                        out.write((byte[]) chunk);
                                    }
                                }
                                return out.toByteArray();
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        return url.contains("/xrpc/app.bsky.feed.getTimeline")
            || url.contains("/xrpc/app.bsky.feed.getFeed")
            || url.contains("/xrpc/app.bsky.feed.getAuthorFeed")
            || url.contains("/xrpc/app.bsky.feed.getListFeed")
            || url.contains("/xrpc/app.bsky.feed.getActorLikes")
            || url.contains("/xrpc/app.bsky.feed.getPosts")
            || url.contains("/xrpc/app.bsky.feed.searchPosts")
            || url.contains("/xrpc/app.bsky.feed.getPostThread")
            || url.contains("/xrpc/app.bsky.unspecced.getPostThreadV2");
    }
}
