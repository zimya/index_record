package com.melonhu.indexrecord.hook;

import android.app.Application;
import android.os.Build;
import com.melonhu.indexrecord.sync.HookCaptureClient;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public final class BilibiliHook implements PlatformHook {

    public static final String HOST_APP = "tv.danmaku.bili";
    public static final String INTERNATIONAL = "com.bilibili.app.in";
    private static final String TYPES = "com.bapis.bilibili.";
    private static final String CAPTURE_BASE =
        "https://grpc.biliapi.net/bilibili.";
    private final Set<Method> installed = new HashSet<>();
    private final ArrayDeque<WeakReference<Object>> recent = new ArrayDeque<>();

    private enum Route {
        VIDEO("app.viewunite.v1.View/View"),
        OPUS("app.dynamic.v2.Opus/OpusDetail"),
        MAIN("main.community.reply.v1.Reply/MainList"),
        DETAIL("main.community.reply.v1.Reply/DetailList");

        final String path;

        Route(String path) {
            this.path = path;
        }
    }

    @Override
    public void init(HookContext context, ClassLoader loader) {
        if (
            Build.VERSION.SDK_INT >= 28 &&
            !context.getPackageName().equals(Application.getProcessName())
        ) return;
        context.onApplicationAttach(attached ->
            setup(context, attached.getClassLoader())
        );
        setup(context, loader);
    }

    private synchronized void setup(HookContext context, ClassLoader loader) {
        // Observe the typed response when the client reads it, after transport decoding.
        for (String prefix : new String[] { "", "K" }) {
            hook(
                context,
                loader,
                "app.viewunite.v1." + prefix + "ViewReply",
                "getArc",
                Route.VIDEO
            );
            hook(
                context,
                loader,
                "main.community.reply.v1." + prefix + "MainListReply",
                prefix.isEmpty() ? "getRepliesList" : "getReplies",
                Route.MAIN
            );
            hook(
                context,
                loader,
                "main.community.reply.v1." + prefix + "DetailListReply",
                "getRoot",
                Route.DETAIL
            );
            hook(
                context,
                loader,
                "app.dynamic.v2." + prefix + "OpusDetailResp",
                "getOpusItem",
                Route.OPUS
            );
        }
        // R8 names for the Kotlin OpusDetail response in the two inspected APKs.
        hook(
            context,
            loader,
            "app.dynamic.v2." +
                (INTERNATIONAL.equals(context.getPackageName()) ? "j8" : "z8"),
            "getOpusItem",
            Route.OPUS
        );
    }

    private void hook(
        HookContext context,
        ClassLoader loader,
        String type,
        String getter,
        Route route
    ) {
        Class<?> response = ReflectionHelper.findClassIfExists(
            TYPES + type,
            loader
        );
        if (response == null) return;
        Method method = ReflectionHelper.findMethodExactIfExists(
            response,
            getter
        );
        if (method == null || !installed.add(method)) return;
        try {
            context
                .getXposed()
                .hook(method)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        if (result != null) capture(
                            context,
                            route,
                            chain.getThisObject()
                        );
                    } catch (Exception e) {
                        context.log(
                            "Bilibili capture scheduling failed: " + route,
                            e
                        );
                    }
                    return result;
                });
            context.log("Bilibili hook installed: " + type + "." + getter);
        } catch (RuntimeException e) {
            installed.remove(method);
            context.log(
                "Failed to install Bilibili hook: " + type + "." + getter,
                e
            );
        }
    }

    private void capture(HookContext context, Route route, Object response) {
        HookCaptureClient client = context.getCaptureClient();
        if (client == null) return;
        synchronized (recent) {
            // The UI and projector can read the same response repeatedly. Compare identity, not content.
            recent.removeIf(ref -> ref.get() == null);
            for (WeakReference<Object> ref : recent)
                if (ref.get() == response) return;
            if (recent.size() == 128) recent.removeFirst();
            recent.addLast(new WeakReference<>(response));
        }
        long time = System.currentTimeMillis();
        context.getExecutor().execute(() -> {
            try {
                JSONObject json;
                switch (route) {
                    case VIDEO:
                        json = BilibiliVideo.project(response);
                        break;
                    case OPUS:
                        json = BilibiliOpus.project(response);
                        break;
                    case MAIN:
                        json = BilibiliComments.project(response, false);
                        break;
                    case DETAIL:
                        json = BilibiliComments.project(response, true);
                        break;
                    default:
                        return;
                }
                if (json == null) return;
                byte[] payload = json
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
                client.enqueue(
                    CAPTURE_BASE + route.path,
                    payload,
                    "application/json",
                    context.getPackageName(),
                    time
                );
                context.log(
                    "Bilibili captured " +
                        route +
                        " (" +
                        payload.length +
                        " bytes)"
                );
                client.sync();
            } catch (Exception e) {
                context.log("Bilibili capture failed: " + route, e);
            }
        });
    }
}
