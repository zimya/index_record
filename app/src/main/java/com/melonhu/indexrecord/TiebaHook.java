package com.melonhu.indexrecord;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.melonhu.indexrecord.sync.HookCaptureClient;

/** Capture at Tieba's protobuf response boundary, independent of its HTTP implementation. */
public class TiebaHook implements IXposedHookLoadPackage {

    private static final String TAG = "TiebaRecord: ";
    private static final String HOST = "com.baidu.tieba";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private static final Set<Class<?>> hookedClasses =
        Collections.synchronizedSet(new HashSet<>());
    private static volatile Context context;
    private static volatile HookCaptureClient client;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!HOST.equals(param.packageName)) return;
        installHooks(param.classLoader);
        XposedHelpers.findAndHookMethod(
            Application.class,
            "attach",
            Context.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    if (context != null) return;
                    Context attachedContext = (Context) hook.args[0];
                    if (attachedContext == null) return;
                    client = new HookCaptureClient(
                        attachedContext,
                        executor,
                        TAG
                    );
                    context = attachedContext;
                    XposedBridge.log(TAG + "Context attached");
                    installHooks(attachedContext.getClassLoader());
                    client.registerReceiver();
                    executor.submit(client::sync);
                }
            }
        );
    }

    private static void installHooks(ClassLoader loader) {
        hookResponse(
            loader,
            "com.baidu.tieba.pb.pb.main.pbPageHttpResponseMessage",
            "page"
        );
        hookResponse(
            loader,
            "com.baidu.tieba.pb.pb.sub.SubPbHttpResponseMessage",
            "floor"
        );
        hookWire(loader);
    }

    private static void hookWire(ClassLoader loader) {
        Class<?> wire = XposedHelpers.findClassIfExists(
            "com.squareup.wire.Wire",
            loader
        );
        if (wire == null || !hookedClasses.add(wire)) return;
        try {
            // PbList is delivered through a separate response path in newer Tieba clients.
            XposedHelpers.findAndHookMethod(
                wire,
                "parseFrom",
                byte[].class,
                Class.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String name = ((Class<?>) param.args[1]).getName();
                        String endpoint;
                        if (
                            "tbclient.PbList.PbListResIdl".equals(name)
                        ) endpoint = "list";
                        else if (
                            "tbclient.PbPage.PbPageResIdl".equals(name)
                        ) endpoint = "page";
                        else if (
                            "tbclient.PbFloor.PbFloorResIdl".equals(name)
                        ) endpoint = "floor";
                        else return;
                        // Response hooks already capture these calls. Wire covers preloading/cache paths.
                        if (Boolean.TRUE.equals(inResponse.get())) return;
                        capture(
                            (byte[]) param.args[0],
                            "https://tiebac.baidu.com/c/f/pb/" +
                                endpoint +
                                "?format=protobuf",
                            endpoint
                        );
                    }
                }
            );
            XposedBridge.log(TAG + "Wire protobuf hook installed");
        } catch (Throwable e) {
            hookedClasses.remove(wire);
            XposedBridge.log(TAG + "Cannot hook Wire: " + e);
        }
    }

    private static final ThreadLocal<Boolean> inResponse = new ThreadLocal<>();

    private static void capture(byte[] original, String url, String endpoint) {
        if (context == null || original == null || original.length == 0) return;
        if (original.length > MAX_BYTES) {
            XposedBridge.log(
                TAG + "Skipped oversized " + endpoint + ": " + original.length
            );
            return;
        }
        final byte[] bytes = original.clone();
        final long capturedAt = System.currentTimeMillis();
        executor.submit(() -> {
            try {
                enqueue(url, bytes, capturedAt);
                XposedBridge.log(
                    TAG +
                        "Captured " +
                        endpoint +
                        " protobuf: " +
                        bytes.length +
                        " bytes"
                );
                client.sync();
            } catch (Exception e) {
                XposedBridge.log(TAG + "Capture failed: " + e);
            }
        });
    }

    private static void hookResponse(
        ClassLoader loader,
        String name,
        String endpoint
    ) {
        Class<?> type = XposedHelpers.findClassIfExists(name, loader);
        if (type == null || !hookedClasses.add(type)) return;
        try {
            XposedHelpers.findAndHookMethod(
                type,
                "decodeInBackGround",
                int.class,
                byte[].class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        inResponse.set(true);
                        capture(
                            (byte[]) param.args[1],
                            responseUrl(param.thisObject, endpoint),
                            endpoint
                        );
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        inResponse.remove();
                    }
                }
            );
            XposedBridge.log(TAG + "Hook installed: " + name);
        } catch (Throwable e) {
            hookedClasses.remove(type);
            XposedBridge.log(TAG + "Cannot hook " + name + ": " + e);
        }
    }

    private static String responseUrl(Object response, String endpoint) {
        // Newer versions keep the POST URL on the original request message.
        for (String getter : new String[] { "getRequestUrl", "getUrl" }) {
            try {
                Object value = XposedHelpers.callMethod(response, getter);
                if (isEndpoint(value, endpoint)) return value.toString();
            } catch (Throwable ignored) {}
        }
        try {
            Object original = XposedHelpers.callMethod(
                response,
                "getOrginalMessage"
            );
            Object extra = XposedHelpers.callMethod(original, "getExtra");
            for (Object request : new Object[] { extra, original }) {
                if (request == null) continue;
                for (String getter : new String[] {
                    "getUrl",
                    "getRequestUrl",
                }) {
                    try {
                        Object value = XposedHelpers.callMethod(
                            request,
                            getter
                        );
                        if (
                            isEndpoint(value, endpoint)
                        ) return value.toString();
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        // IDs are decoded from the response, never inferred from the cmd number.
        return (
            "https://tiebac.baidu.com/c/f/pb/" + endpoint + "?format=protobuf"
        );
    }

    private static boolean isEndpoint(Object value, String endpoint) {
        if (!(value instanceof String)) return false;
        Uri uri = Uri.parse((String) value);
        return (
            ("tiebac.baidu.com".equals(uri.getHost()) ||
                "tieba.baidu.com".equals(uri.getHost())) &&
            ("/c/f/pb/" + endpoint).equals(uri.getPath())
        );
    }

    private static void enqueue(String url, byte[] bytes, long timestamp)
        throws IOException {
        client.enqueue(url, bytes, "application/x-protobuf", HOST, timestamp);
    }
}
