package com.melonhu.indexrecord.hook;

import android.net.Uri;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;

public class ThreadsHook implements PlatformHook {

    public static final String HOST_APP = "com.instagram.barcelona";
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private final Set<ClassLoader> hookedClassLoaders = Collections.synchronizedSet(new HashSet<>());
    private final Map<Object, Capture> captures = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Class<?>> callbackClasses = Collections.synchronizedSet(new HashSet<>());

    private static class Capture {
        final String url;
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean overflow;

        Capture(String url) {
            this.url = url;
        }

        synchronized void append(ByteBuffer input) {
            ByteBuffer copy = input.duplicate();
            if (overflow || body.size() + copy.remaining() > MAX_BODY) {
                overflow = true;
                body.reset();
                return;
            }
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            body.write(bytes, 0, bytes.length);
        }
    }

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        if (!HOST_APP.equals(context.getPackageName()) || !HOST_APP.equals(context.getProcessName())) {
            return;
        }
        setupTigonHook(context, classLoader);
        context.onApplicationAttach(attachedContext -> {
            setupTigonHook(context, attachedContext.getClassLoader());
        });
    }

    private void setupTigonHook(HookContext context, ClassLoader loader) {
        if (loader == null || !hookedClassLoaders.add(loader)) return;
        Class<?> service = ReflectionHelper.findClassIfExists("com.facebook.tigon.TigonXplatService", loader);
        if (service == null) return;

        ReflectionHelper.hookAllMethods(context.getXposed(), service, "sendRequest", chain -> {
            try {
                Object[] args = chain.getArgs().toArray();
                if (args.length > 0 && args[0] != null) {
                    String url = (String) ReflectionHelper.callMethod(args[0], "url");
                    if (isTargetUrl(url)) {
                        for (Object arg : args) {
                            if (arg == null) continue;
                            Class<?> type = arg.getClass();
                            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                                if ("com.facebook.tigon.TigonCallbacks".equals(c.getName())) {
                                    installCallbacks(context, type);
                                    captures.put(arg, new Capture(url));
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                context.log("Request capture setup failed: " + t, t);
            }
            return chain.proceed();
        });
        context.log("Tigon sendRequest hook installed");
    }

    private void installCallbacks(HookContext context, Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!callbackClasses.add(c)) continue;

            ReflectionHelper.hookAllMethods(context.getXposed(), c, "onBody", chain -> {
                Capture capture = captures.get(chain.getThisObject());
                Object[] args = chain.getArgs().toArray();
                if (capture != null && args.length > 0) {
                    try {
                        if (args[0] instanceof ByteBuffer) {
                            capture.append((ByteBuffer) args[0]);
                        }
                    } catch (Throwable t) {
                        capture.overflow = true;
                    }
                }
                return chain.proceed();
            });

            ReflectionHelper.hookAllMethods(context.getXposed(), c, "onEOM", chain -> {
                Capture capture = captures.remove(chain.getThisObject());
                if (capture != null && !capture.overflow && context.getAndroidContext() != null) {
                    final byte[] bytes;
                    synchronized (capture) {
                        bytes = capture.body.toByteArray();
                    }
                    context.getExecutor().submit(() -> saveCapture(context, capture.url, bytes));
                }
                return chain.proceed();
            });

            ReflectionHelper.hookAllMethods(context.getXposed(), c, "onError", chain -> {
                captures.remove(chain.getThisObject());
                return chain.proceed();
            });

            ReflectionHelper.hookAllMethods(context.getXposed(), c, "onWillRetry", chain -> {
                Capture capture = captures.get(chain.getThisObject());
                if (capture != null) {
                    synchronized (capture) {
                        capture.body.reset();
                        capture.overflow = false;
                    }
                }
                return chain.proceed();
            });
        }
    }

    private void saveCapture(HookContext context, String url, byte[] rawBytes) {
        try {
            byte[] bytes = rawBytes;
            if (
                bytes.length >= 2 &&
                (bytes[0] & 255) == 31 &&
                (bytes[1] & 255) == 139
            ) {
                try (
                    GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes));
                    ByteArrayOutputStream out = new ByteArrayOutputStream()
                ) {
                    byte[] chunk = new byte[16384];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        if (out.size() + n > MAX_BODY) return;
                        out.write(chunk, 0, n);
                    }
                    bytes = out.toByteArray();
                }
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (!json.contains("\"text_post_app_info\"")) return;
            HookCaptureClient client = context.getCaptureClient();
            if (client != null) {
                client.enqueue(url, bytes, "application/json", HOST_APP, System.currentTimeMillis());
                context.log("Captured " + bytes.length + " bytes: " + Uri.parse(url).getPath());
                client.sync();
            }
        } catch (Throwable t) {
            context.log("Capture failed: " + t, t);
        }
    }

    public static boolean isTargetUrl(String url) {
        if (url == null) return false;
        String host = null;
        String path = null;
        try {
            Uri uri = Uri.parse(url);
            host = uri.getHost();
            path = uri.getPath();
        } catch (Throwable ignored) {}
        if (host == null || path == null) {
            try {
                java.net.URI u = java.net.URI.create(url);
                host = u.getHost();
                path = u.getPath();
            } catch (Throwable ignored) {}
        }
        if (
            host == null ||
            !(
                host.equals("instagram.com") ||
                host.endsWith(".instagram.com") ||
                host.equals("threads.net") ||
                host.endsWith(".threads.net") ||
                host.equals("threads.com") ||
                host.endsWith(".threads.com")
            )
        ) return false;
        if (
            path != null &&
            (path.contains("notifications") ||
                path.contains("logging") ||
                path.contains("heartbeat") ||
                path.contains("qp/batch_fetch"))
        ) return false;
        return (
            path != null &&
            (path.contains("/text_feed/") ||
                path.contains("/feed/") ||
                path.contains("/media/") ||
                path.contains("/graphql"))
        );
    }
}
