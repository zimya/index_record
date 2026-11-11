package me.melon.record;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Capture at Tieba's protobuf response boundary, independent of its HTTP implementation. */
public class TiebaHook implements IXposedHookLoadPackage {

    private static final String TAG = "TiebaRecord: ";
    private static final String HOST = "com.baidu.tieba";
    private static final String QUEUE_DIR = "index_record_queue";
    private static final String DESCRIPTOR =
        "me.melon.record.sync.IRecordSyncBridge";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private static final Set<Class<?>> hookedClasses =
        Collections.synchronizedSet(new HashSet<>());
    private static volatile Context context;
    private static volatile IBinder syncBinder;

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
                    context = (Context) hook.args[0];
                    XposedBridge.log(TAG + "Context attached");
                    installHooks(context.getClassLoader());
                    registerSyncReceiver();
                    executor.submit(TiebaHook::syncQueue);
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
                syncQueue();
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

    private static void registerSyncReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                if (
                    !"me.melon.record.ACTION_SYNC".equals(intent.getAction())
                ) return;
                Bundle extras = intent.getExtras();
                if (extras != null) {
                    IBinder binder = extras.getBinder("sync_binder");
                    if (binder != null) syncBinder = binder;
                }
                executor.submit(TiebaHook::syncQueue);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(
                "me.melon.record.ACTION_SYNC"
            );
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_EXPORTED
            );
            else context.registerReceiver(receiver, filter);
        } catch (Throwable e) {
            XposedBridge.log(TAG + "Sync receiver failed: " + e);
        }
    }

    private static void enqueue(String url, byte[] bytes, long timestamp)
        throws Exception {
        File dir = new File(context.getCacheDir(), QUEUE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException(
            "Cannot create capture queue"
        );
        String token =
            timestamp +
            "_" +
            android.os.Process.myPid() +
            "_" +
            System.nanoTime();
        File payload = new File(dir, token + ".bin");
        try (FileOutputStream out = new FileOutputStream(payload)) {
            out.write(bytes);
        }
        // The meta rename commits the pair; sync never observes a partly written response.
        File pending = new File(dir, token + ".meta.tmp");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            String meta =
                url.replace('\n', ' ').replace('\r', ' ') +
                "\n" +
                HOST +
                "\napplication/x-protobuf\n" +
                timestamp +
                "\n";
            out.write(meta.getBytes(StandardCharsets.UTF_8));
        }
        if (!pending.renameTo(new File(dir, token + ".meta"))) {
            throw new java.io.IOException("Cannot commit capture metadata");
        }
    }

    private static void syncQueue() {
        if (context == null) return;
        File dir = new File(context.getCacheDir(), QUEUE_DIR);
        File[] metas = dir.listFiles((parent, name) -> name.endsWith(".meta"));
        if (metas == null) return;
        Arrays.sort(metas, Comparator.comparing(File::getName));
        for (File meta : metas) {
            String token = meta
                .getName()
                .substring(0, meta.getName().length() - 5);
            File payload = new File(dir, token + ".bin");
            try {
                if (!payload.isFile()) continue;
                String[] parts = new String(
                    Files.readAllBytes(meta.toPath()),
                    StandardCharsets.UTF_8
                ).split("\n");
                if (parts.length < 4) {
                    XposedBridge.log(
                        TAG + "Invalid metadata retained: " + meta.getName()
                    );
                    continue;
                }
                long timestamp = Long.parseLong(parts[3]);
                if (
                    !syncViaBinder(payload, parts[0], timestamp) &&
                    !syncViaProvider(payload, token, parts[0], timestamp)
                ) return;
                meta.delete();
                payload.delete();
                XposedBridge.log(TAG + "Synced " + token);
            } catch (Throwable e) {
                XposedBridge.log(TAG + "Sync deferred, capture retained: " + e);
                return;
            }
        }
    }

    private static boolean syncViaBinder(
        File payload,
        String url,
        long timestamp
    ) {
        IBinder binder = syncBinder;
        if (binder == null || !binder.isBinderAlive()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try (
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(
                payload,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
        ) {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(url);
            data.writeString(HOST);
            data.writeLong(timestamp);
            data.writeInt(1);
            fd.writeToParcel(data, 0);
            if (
                !binder.transact(
                    IBinder.FIRST_CALL_TRANSACTION + 1,
                    data,
                    reply,
                    0
                )
            ) return false;
            reply.readException();
            return reply.readInt() == 1;
        } catch (Throwable e) {
            if (e instanceof android.os.DeadObjectException) syncBinder = null;
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static boolean syncViaProvider(
        File payload,
        String token,
        String url,
        long timestamp
    ) {
        Uri uri = Uri.parse(
            "content://me.melon.record.provider/raw_payload/" + token
        );
        try (
            ParcelFileDescriptor fd = context
                .getContentResolver()
                .openFileDescriptor(uri, "w")
        ) {
            if (fd == null) return false;
            try (
                FileInputStream input = new FileInputStream(payload);
                FileOutputStream output = new FileOutputStream(
                    fd.getFileDescriptor()
                )
            ) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1)
                    output.write(buffer, 0, count);
            }
            ContentValues values = new ContentValues();
            values.put("url", url);
            values.put("hostApp", HOST);
            values.put("payloadToken", token);
            values.put("timestamp", timestamp);
            return (
                context
                    .getContentResolver()
                    .insert(
                        Uri.parse(
                            "content://me.melon.record.provider/raw_data"
                        ),
                        values
                    ) != null
            );
        } catch (Throwable ignored) {
            return false;
        }
    }
}
