package com.melonhu.indexrecord.hook;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class HookContext {

    private final XposedInterface xposed;
    private final String packageName;
    private final String processName;
    private final String tag;
    private final ExecutorService executor;
    private volatile Context androidContext;
    private volatile HookCaptureClient captureClient;

    public HookContext(
        XposedInterface xposed,
        String packageName,
        String processName,
        String tag
    ) {
        this.xposed = xposed;
        this.packageName = packageName;
        this.processName = processName;
        this.tag = tag;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public XposedInterface getXposed() {
        return xposed;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getProcessName() {
        return processName;
    }

    public String getTag() {
        return tag;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public Context getAndroidContext() {
        return androidContext;
    }

    public HookCaptureClient getCaptureClient() {
        return captureClient;
    }

    public void log(String message) {
        xposed.log(Log.INFO, tag, message);
        Log.i(tag, message);
    }

    public void log(String message, Throwable tr) {
        xposed.log(Log.ERROR, tag, message, tr);
        Log.e(tag, message, tr);
    }

    public synchronized void onApplicationAttach(Consumer<Context> onAttached) {
        try {
            Method attachMethod = ReflectionHelper.findMethodExact(
                Application.class,
                "attach",
                Context.class
            );
            xposed.hook(attachMethod).intercept(chain -> {
                Object result = chain.proceed();
                if (androidContext == null) {
                    Context attached = (Context) chain.getArg(0);
                    if (attached != null) {
                        androidContext = attached;
                        HookCaptureClient client = new HookCaptureClient(
                            attached,
                            executor,
                            tag,
                            (msg, err) -> {
                                if (err != null) {
                                    log(msg, err);
                                } else {
                                    log(msg);
                                }
                            }
                        );
                        captureClient = client;
                        log("Context attached, setting up host hooks.");
                        if (onAttached != null) {
                            try {
                                onAttached.accept(attached);
                            } catch (Throwable t) {
                                log("Error in onAttached callback", t);
                            }
                        }
                        client.registerReceiver();
                        executor.submit(client::sync);
                    }
                }
                return result;
            });
        } catch (Throwable t) {
            log("Failed to hook Application.attach", t);
        }
    }
}
