package com.melonhu.indexrecord.ui.stats

import androidx.compose.ui.graphics.Color
import com.melonhu.indexrecord.ui.theme.DoubanGreen
import com.melonhu.indexrecord.ui.theme.RedditOrange
import com.melonhu.indexrecord.ui.theme.TiebaBlue
import com.melonhu.indexrecord.ui.theme.XBlack
import com.melonhu.indexrecord.ui.theme.ZhihuBlue

fun colorFromPackage(pkg: String): Color {
    return when (pkg.lowercase()) {
        "com.zhihu.android", "zhihu" -> ZhihuBlue
        "com.douban.frodo", "douban" -> DoubanGreen
        "com.baidu.tieba", "tieba" -> TiebaBlue
        "com.twitter.android", "twitter", "com.instagram.barcelona", "threads" -> XBlack
        "com.reddit.frontpage", "reddit" -> RedditOrange
        "com.sina.weibo", "com.weico.international", "weibo" -> com.melonhu.indexrecord.ui.theme.WeiboOrange
        else -> {
            val h = (pkg.hashCode().toLong().ushr(1) % 360L).toFloat()
            Color.hsv(h, 0.65f, 0.90f)
        }
    }
}

fun appLabel(pkg: String): String {
    return com.melonhu.indexrecord.data.PlatformCatalog.packageLabel(pkg)
        ?: com.melonhu.indexrecord.data.PlatformCatalog.knownDisplayName(pkg)
        ?: if (pkg.isBlank() || pkg == "unknown") "其他" else pkg
}
