package com.melonhu.indexrecord.hook;

import android.app.Application;
import android.os.Build;
import android.os.Process;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.io.File;
import java.util.concurrent.Semaphore;

public final class PiliPlusHook implements PlatformHook {
    public static final String HOST_APP = "com.example.piliplus";
    private static final String NATIVE_LIBRARY = "libindexrecord_pili.so";
    private static volatile PiliPlusHttpCapture transport;
    private final Semaphore pendingCaptures = new Semaphore(16);

    @Override
    public void init(HookContext context, ClassLoader loader) {
        if (!Process.is64Bit()) return;
        if (Build.VERSION.SDK_INT >= 28 && !HOST_APP.equals(Application.getProcessName())) return;
        context.onApplicationAttach(attached -> start(context));
    }

    private void start(HookContext context) {
        try {
            transport = new PiliPlusHttpCapture((url, payload) -> publish(context, url, payload));
            String libraryDir = context.getXposed().getModuleApplicationInfo().nativeLibraryDir;
            System.load(new File(libraryDir, NATIVE_LIBRARY).getAbsolutePath());
            if (nativeStart()) {
                context.log("PiliPlus native observer armed (verified arm64 Flutter engine; HTTP/1.1 only)");
            } else {
                transport = null;
                context.log("PiliPlus observer unavailable: native hook bridge did not start");
            }
        } catch (LinkageError | RuntimeException e) {
            transport = null;
            context.log("PiliPlus native observer unavailable", e);
        }
    }

    private void publish(HookContext context, String url, byte[] payload) {
        HookCaptureClient client = context.getCaptureClient();
        if (client == null || !pendingCaptures.tryAcquire()) return;
        long capturedAt = System.currentTimeMillis();
        try {
            context.getExecutor().execute(() -> {
                try {
                    client.enqueue(url, payload, "application/json", HOST_APP, capturedAt);
                    context.log("Captured PiliPlus " + url.substring(url.lastIndexOf('/') + 1) + " (" + payload.length + " bytes)");
                    client.sync();
                } catch (Exception e) {
                    context.log("Could not queue PiliPlus response", e);
                } finally {
                    pendingCaptures.release();
                }
            });
        } catch (RuntimeException e) {
            pendingCaptures.release();
            throw e;
        }
    }

    private static native boolean nativeStart();

    static synchronized void onTlsBytes(long connection, boolean inbound, byte[] bytes) {
        PiliPlusHttpCapture capture = transport;
        if (capture != null) capture.accept(connection, inbound, bytes);
    }

    static synchronized void onTlsClosed(long connection) {
        PiliPlusHttpCapture capture = transport;
        if (capture != null) capture.close(connection);
    }
}
