package com.melonhu.indexrecord.hook;

import androidx.annotation.NonNull;
import io.github.libxposed.api.XposedModule;

public class IndexRecordModule extends XposedModule {

    private static final String TAG = "IndexRecord: ";

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String packageName = param.getPackageName();
        String processName = param.getApplicationInfo().processName;
        ClassLoader classLoader = param.getClassLoader();

        PlatformHook hook = null;
        String hookTag = TAG;

        switch (packageName) {
            case ZhihuHook.HOST_APP:
                hook = new ZhihuHook();
                hookTag = "ZhihuRecord: ";
                break;
            case DoubanHook.HOST_APP:
                hook = new DoubanHook();
                hookTag = "DoubanRecord: ";
                break;
            case XHook.HOST_APP:
                hook = new XHook();
                hookTag = "XRecord: ";
                break;
            case ThreadsHook.HOST_APP:
                hook = new ThreadsHook();
                hookTag = "ThreadsRecord: ";
                break;
            case RedditHook.HOST_APP:
                hook = new RedditHook();
                hookTag = "RedditRecord: ";
                break;
            case TiebaHook.HOST_APP:
                hook = new TiebaHook();
                hookTag = "TiebaRecord: ";
                break;
            case CoolapkHook.HOST_APP:
                hook = new CoolapkHook();
                hookTag = "CoolapkRecord: ";
                break;
            case HupuHook.HOST_APP:
                hook = new HupuHook();
                hookTag = "HupuRecord: ";
                break;
            case XiaoheiheHook.HOST_APP:
                hook = new XiaoheiheHook();
                hookTag = "XiaoheiheRecord: ";
                break;
            case BlueskyHook.HOST_APP:
                hook = new BlueskyHook();
                hookTag = "BlueskyRecord: ";
                break;
            case NgaHook.HOST_APP:
                hook = new NgaHook();
                hookTag = "NgaRecord: ";
                break;
            case "com.sina.weibo":
            case "com.weico.international":
                hook = new WeiboHook();
                hookTag = "WeiboRecord: ";
                break;
            default:
                detach();
                return;
        }

        HookContext context = new HookContext(this, packageName, processName, hookTag);
        context.log("Loaded in " + packageName + " (" + processName + ")");
        hook.init(context, classLoader);
    }
}
