package com.melonhu.indexrecord.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Shared image request policy (anti-hotlink headers & Bilibili HTTPS upgrade)
 * used by both UI image loading (Coil) and search image downloading/OCR.
 */
object PlatformImageInterceptor : Interceptor {
    private const val DOUBAN_USER_AGENT =
        "api-client/1 com.douban.frodo/7.132.0(360) Android/36  udid/db87f91e63b18b9f94b50c897f792525533f0517  douban_udid/25e33fbb4f6ecfb0d41a8c29e82dd0879c05305f model/PLZ110 brand/OnePlus  rom/android  network/wifi  platform/mobile  foldable/0 product/PLZ110 vendor/OnePlus udid/db87f91e63b18b9f94b50c897f792525533f0517"
    private const val COOLAPK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; 24129PN74C Build/UKQ1.231003.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.107 Mobile Safari/537.36 #CoolMarket#14.5.3"
    private const val DEFAULT_BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(rewrite(chain.request()))
    }

    internal fun rewrite(request: Request): Request {
        val upgradedUrl = secureBilibiliImageUrl(request.url)
        val url = upgradedUrl.toString()
        val builder = if (upgradedUrl == request.url) {
            request.newBuilder()
        } else {
            request.newBuilder().url(upgradedUrl)
        }
        when {
            url.contains("doubanio.com") || url.contains("douban.com") -> {
                builder.header("User-Agent", DOUBAN_USER_AGENT)
                builder.header("Referer", "https://frodo.douban.com/")
            }
            url.contains("coolapk.com") -> {
                builder.header("User-Agent", COOLAPK_USER_AGENT)
            }
            url.contains("zhimg.com") || url.contains("zhihu.com") -> {
                builder.header("User-Agent", DEFAULT_BROWSER_USER_AGENT)
                builder.header("Referer", "https://www.zhihu.com/")
            }
            else -> {
                builder.header("User-Agent", DEFAULT_BROWSER_USER_AGENT)
            }
        }
        return builder.build()
    }
}

// Bilibili still supplies HTTP CDN URLs in some captures. Upgrade at load time for old records too.
internal fun secureBilibiliImageUrl(url: HttpUrl): HttpUrl =
    if (!url.isHttps && url.port == 80 && url.host.endsWith(".hdslb.com")) {
        url.newBuilder().scheme("https").port(443).build()
    } else {
        url
    }
