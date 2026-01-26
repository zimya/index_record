package me.melon.record

import android.app.Application
import android.util.Log
import io.objectbox.android.AndroidObjectBrowser
import me.melon.record.BuildConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化数据库
        ObjectBox.init(this)

        // 仅在 Debug 模式下开启浏览器调试
        if (BuildConfig.DEBUG) {
            val started = AndroidObjectBrowser(ObjectBox.store).start(this)
            Log.i("ObjectBox", "ObjectBrowser started: $started")
        }
    }
}
