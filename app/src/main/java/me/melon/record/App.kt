package me.melon.record

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.objectbox.android.AndroidObjectBrowser
import okhttp3.OkHttpClient

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        me.melon.record.settings.RecordSettings.init(this)

        // 初始化数据库
        ObjectBox.init(this)
        me.melon.record.sync.RecordSyncBridge.init(this)

        // 仅在 Debug 模式下开启浏览器调试
        if (BuildConfig.DEBUG) {
            val started = AndroidObjectBrowser(ObjectBox.store).start(this)
            Log.i("ObjectBox", "ObjectBrowser started: $started")
        }
    }

    override fun newImageLoader(): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val newReq = if (url.contains("doubanio.com") || url.contains("douban.com")) {
                    request.newBuilder()
                        .header("User-Agent", "api-client/1 com.douban.frodo/7.132.0(360) Android/36  udid/db87f91e63b18b9f94b50c897f792525533f0517  douban_udid/25e33fbb4f6ecfb0d41a8c29e82dd0879c05305f model/PLZ110 brand/OnePlus  rom/android  network/wifi  platform/mobile  foldable/0 product/PLZ110 vendor/OnePlus udid/db87f91e63b18b9f94b50c897f792525533f0517")
                        .header("Referer", "https://frodo.douban.com/")
                        .build()
                } else {
                    request
                }
                chain.proceed(newReq)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()
    }
}
