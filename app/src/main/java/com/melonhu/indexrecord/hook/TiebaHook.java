package com.melonhu.indexrecord.hook;

import android.net.Uri;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Capture at Tieba's protobuf response boundary, independent of its HTTP implementation.
 */
public class TiebaHook implements PlatformHook {

    public static final String HOST_APP = "com.baidu.tieba";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final ThreadLocal<Boolean> inResponse = new ThreadLocal<>();
    private final Set<Class<?>> hookedClasses = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void init(HookContext context, ClassLoader classLoader) {
        installHooks(context, classLoader);
        context.onApplicationAttach(attachedContext -> {
            installHooks(context, attachedContext.getClassLoader());
        });
    }

    private void installHooks(HookContext context, ClassLoader loader) {
        if (loader == null) return;
        hookResponse(
            context,
            loader,
            "com.baidu.tieba.pb.pb.main.pbPageHttpResponseMessage",
            "page"
        );
        hookResponse(
            context,
            loader,
            "com.baidu.tieba.pb.pb.sub.SubPbHttpResponseMessage",
            "floor"
        );
        hookWire(context, loader);
    }

    private void hookWire(HookContext context, ClassLoader loader) {
        Class<?> wire = ReflectionHelper.findClassIfExists("com.squareup.wire.Wire", loader);
        if (wire == null || !hookedClasses.add(wire)) return;
        try {
            Method parseFromMethod = ReflectionHelper.findMethodExact(wire, "parseFrom", byte[].class, Class.class);
            context.getXposed().hook(parseFromMethod).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length >= 2 && args[1] instanceof Class<?>) {
                    String name = ((Class<?>) args[1]).getName();
                    String endpoint = null;
                    if ("tbclient.PbList.PbListResIdl".equals(name)) {
                        endpoint = "list";
                    } else if ("tbclient.PbPage.PbPageResIdl".equals(name)) {
                        endpoint = "page";
                    } else if ("tbclient.PbFloor.PbFloorResIdl".equals(name)) {
                        endpoint = "floor";
                    }

                    if (endpoint != null && !Boolean.TRUE.equals(inResponse.get()) && args[0] instanceof byte[]) {
                        capture(
                            context,
                            (byte[]) args[0],
                            "https://tiebac.baidu.com/c/f/pb/" + endpoint + "?format=protobuf",
                            endpoint
                        );
                    }
                }
                return chain.proceed();
            });
            context.log("Wire protobuf hook installed");
        } catch (Throwable e) {
            hookedClasses.remove(wire);
            context.log("Cannot hook Wire: " + e, e);
        }
    }

    private void hookResponse(
        HookContext context,
        ClassLoader loader,
        String name,
        String endpoint
    ) {
        Class<?> type = ReflectionHelper.findClassIfExists(name, loader);
        if (type == null || !hookedClasses.add(type)) return;
        try {
            Method decodeMethod = ReflectionHelper.findMethodExact(type, "decodeInBackGround", int.class, byte[].class);
            context.getXposed().hook(decodeMethod).intercept(chain -> {
                inResponse.set(true);
                try {
                    Object[] args = chain.getArgs().toArray();
                    if (args.length >= 2 && args[1] instanceof byte[]) {
                        capture(
                            context,
                            (byte[]) args[1],
                            responseUrl(chain.getThisObject(), endpoint),
                            endpoint
                        );
                    }
                    return chain.proceed();
                } finally {
                    inResponse.remove();
                }
            });
            context.log("Hook installed: " + name);
        } catch (Throwable e) {
            hookedClasses.remove(type);
            context.log("Cannot hook " + name + ": " + e, e);
        }
    }

    private void capture(HookContext context, byte[] original, String url, String endpoint) {
        if (original == null || original.length == 0) return;
        if (original.length > MAX_BYTES) {
            context.log("Skipped oversized " + endpoint + ": " + original.length);
            return;
        }
        final byte[] bytes = original.clone();
        final long capturedAt = System.currentTimeMillis();
        context.getExecutor().submit(() -> {
            try {
                HookCaptureClient client = context.getCaptureClient();
                if (client != null) {
                    client.enqueue(url, bytes, "application/x-protobuf", HOST_APP, capturedAt);
                    context.log("Captured " + endpoint + " protobuf: " + bytes.length + " bytes");
                    client.sync();
                }
            } catch (Exception e) {
                context.log("Capture failed: " + e, e);
            }
        });
    }

    private static String responseUrl(Object response, String endpoint) {
        for (String getter : new String[]{"getRequestUrl", "getUrl"}) {
            try {
                Object value = ReflectionHelper.callMethod(response, getter);
                if (isEndpoint(value, endpoint)) return value.toString();
            } catch (Throwable ignored) {}
        }
        try {
            Object original = ReflectionHelper.callMethod(response, "getOrginalMessage");
            Object extra = ReflectionHelper.callMethod(original, "getExtra");
            for (Object request : new Object[]{extra, original}) {
                if (request == null) continue;
                for (String getter : new String[]{"getUrl", "getRequestUrl"}) {
                    try {
                        Object value = ReflectionHelper.callMethod(request, getter);
                        if (isEndpoint(value, endpoint)) return value.toString();
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return "https://tiebac.baidu.com/c/f/pb/" + endpoint + "?format=protobuf";
    }

    private static boolean isEndpoint(Object value, String endpoint) {
        if (!(value instanceof String)) return false;
        String str = (String) value;
        String host = null;
        String path = null;
        try {
            Uri uri = Uri.parse(str);
            host = uri.getHost();
            path = uri.getPath();
        } catch (Throwable ignored) {}
        if (host == null || path == null) {
            try {
                java.net.URI u = java.net.URI.create(str);
                host = u.getHost();
                path = u.getPath();
            } catch (Throwable ignored) {}
        }
        return (
            ("tiebac.baidu.com".equals(host) || "tieba.baidu.com".equals(host)) &&
            ("/c/f/pb/" + endpoint).equals(path)
        );
    }
}
