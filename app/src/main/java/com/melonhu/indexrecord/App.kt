package com.melonhu.indexrecord

import android.app.Application
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.melonhu.indexrecord.logic.DataProcessor
import com.melonhu.indexrecord.network.PlatformImageInterceptor
import io.objectbox.android.AndroidObjectBrowser
import okhttp3.OkHttpClient

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        com.melonhu.indexrecord.settings.RecordSettings.init(this)
        com.melonhu.indexrecord.viewmodel.LsposedServiceMonitor.init()

        // 初始化数据库
        ObjectBox.init(this)
        com.melonhu.indexrecord.search.SearchRuntime.init(this)
        DataProcessor.start(ObjectBox.store)
        com.melonhu.indexrecord.sync.RecordSyncBridge.init(this)

        // 仅在 Debug 模式下开启浏览器调试
        if (BuildConfig.DEBUG) {
            val started = AndroidObjectBrowser(ObjectBox.store).start(this)
            Log.i("ObjectBox", "ObjectBrowser started: $started")
        }
    }

    override fun newImageLoader(): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(PlatformImageInterceptor)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }
}
