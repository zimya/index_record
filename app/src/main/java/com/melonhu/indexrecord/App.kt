package com.melonhu.indexrecord

import android.app.Application
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import io.objectbox.android.AndroidObjectBrowser
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        com.melonhu.indexrecord.settings.RecordSettings.init(this)

        // 初始化数据库
        ObjectBox.init(this)
        com.melonhu.indexrecord.search.SearchRuntime.init(this)
        com.melonhu.indexrecord.sync.RecordSyncBridge.init(this)

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
                } else if (url.contains("coolapk.com")) {
                    request.newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; 24129PN74C Build/UKQ1.231003.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.107 Mobile Safari/537.36 #CoolMarket#14.5.3")
                        .build()
                } else {
                    val imageUrl = secureBilibiliImageUrl(request.url)
                    if (imageUrl == request.url) request else request.newBuilder().url(imageUrl).build()
                }
                chain.proceed(newReq)
            }
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

// Bilibili still supplies HTTP CDN URLs in some captures. Upgrade at load time for old records too.
internal fun secureBilibiliImageUrl(url: HttpUrl): HttpUrl =
    if (!url.isHttps && url.port == 80 && url.host.endsWith(".hdslb.com")) {
        url.newBuilder().scheme("https").port(443).build()
    } else {
        url
    }
