package me.melon.record.sync;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;

/** Shared capture storage and transport for hooked host processes. */
public final class HookCaptureClient {

    public static final String QUEUE_DIR = "index_record_queue";
    private static final String ACTION_SYNC = "me.melon.record.ACTION_SYNC";
    private static final String BRIDGE_DESCRIPTOR =
        "me.melon.record.sync.IRecordSyncBridge";
    private static final int TRANSACTION_SYNC_FILE =
        IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final Uri RAW_DATA = Uri.parse(
        "content://me.melon.record.provider/raw_data"
    );

    public interface Preparation {
        void run() throws Exception;
    }

    public interface PayloadFilter {
        boolean keep(File payload) throws Exception;
    }

    private final Context context;
    private final ExecutorService executor;
    private final String tag;
    private final CaptureQueue queue;
    private volatile IBinder syncBinder;
    private volatile Preparation beforeSync;
    private volatile PayloadFilter payloadFilter;
    private boolean receiverRegistered;
    private volatile boolean queueLinkEnabled;

    public HookCaptureClient(
        Context context,
        ExecutorService executor,
        String tag
    ) {
        this.context = context;
        this.executor = executor;
        this.tag = tag;
        this.queue = new CaptureQueue(
            new File(context.getCacheDir(), QUEUE_DIR),
            this::log
        );
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public synchronized void registerReceiver() {
        if (receiverRegistered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                if (
                    intent == null || !ACTION_SYNC.equals(intent.getAction())
                ) return;
                try {
                    Bundle extras = intent.getExtras();
                    IBinder binder =
                        extras == null ? null : extras.getBinder("sync_binder");
                    if (binder != null) syncBinder = binder;
                    executor.submit(HookCaptureClient.this::sync);
                } catch (Exception error) {
                    log("Cannot schedule queue sync", error);
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(ACTION_SYNC);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    receiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception error) {
            log("Cannot register sync receiver", error);
        }
    }

    public void enqueue(
        String url,
        byte[] bytes,
        String contentType,
        String hostApp,
        long timestamp
    ) throws IOException {
        try {
            queue.enqueue(url, bytes, contentType, hostApp, timestamp);
        } catch (IOException error) {
            log("Cannot commit capture; incomplete files retained", error);
            throw error;
        }
        if (queueLinkEnabled) createQueueLink();
    }

    public void sync() {
        queue.sync(
            () -> {
                Preparation callback = beforeSync;
                if (callback != null) callback.run();
            },
            payload -> {
                PayloadFilter callback = payloadFilter;
                return callback == null || callback.keep(payload);
            },
            entry -> syncViaBinder(entry) || syncViaProvider(entry)
        );
    }

    public void setBeforeSync(Preparation callback) {
        beforeSync = callback;
    }

    public void setPayloadFilter(PayloadFilter callback) {
        payloadFilter = callback;
    }

    public void migrateLegacy(String defaultHost) throws Exception {
        File legacy = new File(context.getCacheDir(), "index_record_cache");
        if (!legacy.exists()) return;
        File[] files = legacy.listFiles();
        if (files == null) throw new IOException(
            "Cannot list legacy cache: " + legacy
        );
        for (File file : files) {
            if (!file.isFile()) continue;
            try {
                JSONObject wrapper = new JSONObject(
                    new String(
                        Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8
                    )
                );
                String json = wrapper.optString("json", "");
                if (json.isEmpty()) throw new IOException(
                    "Legacy wrapper has no payload: " + file
                );
                enqueue(
                    wrapper.optString("url", ""),
                    json.getBytes(StandardCharsets.UTF_8),
                    "application/json",
                    wrapper.optString("hostApp", defaultHost),
                    System.currentTimeMillis()
                );
                Files.delete(file.toPath());
            } catch (Exception error) {
                log("Legacy capture retained: " + file, error);
            }
        }
    }

    public void enableQueueLink() {
        queueLinkEnabled = true;
        createQueueLink();
    }

    private void createQueueLink() {
        try {
            File directory = new File(context.getCacheDir(), QUEUE_DIR);
            Files.createDirectories(directory.toPath());
            File link = new File(
                context.getApplicationInfo().dataDir,
                QUEUE_DIR
            );
            if (
                !Files.exists(
                    link.toPath(),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS
                )
            ) {
                Files.createSymbolicLink(link.toPath(), directory.toPath());
            }
        } catch (Exception error) {
            log("Optional queue link unavailable", error);
        }
    }

    private boolean syncViaBinder(CaptureQueue.Entry entry) {
        IBinder binder = syncBinder;
        if (binder == null || !binder.isBinderAlive()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try (
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                entry.payload,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
        ) {
            data.writeInterfaceToken(BRIDGE_DESCRIPTOR);
            data.writeString(entry.url);
            data.writeString(entry.hostApp);
            data.writeLong(entry.timestamp);
            data.writeInt(1);
            pfd.writeToParcel(data, 0);
            if (
                !binder.transact(TRANSACTION_SYNC_FILE, data, reply, 0)
            ) return false;
            reply.readException();
            return reply.readInt() == 1;
        } catch (Exception error) {
            if (error instanceof DeadObjectException) syncBinder = null;
            log("Binder sync failed; trying Provider", error);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean syncViaProvider(CaptureQueue.Entry entry) throws Exception {
        Uri target = Uri.parse(
            "content://me.melon.record.provider/raw_payload/" + entry.token
        );
        ParcelFileDescriptor pfd = context
            .getContentResolver()
            .openFileDescriptor(target, "w");
        if (pfd == null) return false;
        try (
            ParcelFileDescriptor.AutoCloseOutputStream out =
                new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
            FileInputStream in = new FileInputStream(entry.payload)
        ) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush();
        }
        ContentValues values = new ContentValues();
        values.put("url", entry.url);
        values.put("hostApp", entry.hostApp);
        values.put("payloadToken", entry.token);
        values.put("timestamp", entry.timestamp);
        return context.getContentResolver().insert(RAW_DATA, values) != null;
    }

    private void log(String message, Exception error) {
        XposedBridge.log(
            tag + " " + message + (error == null ? "" : ": " + error)
        );
    }
}
