package me.melon.record.ui.stats

import androidx.compose.ui.graphics.Color
import me.melon.record.ui.theme.DoubanGreen
import me.melon.record.ui.theme.RedditOrange
import me.melon.record.ui.theme.TiebaBlue
import me.melon.record.ui.theme.XBlack
import me.melon.record.ui.theme.ZhihuBlue

fun colorFromPackage(pkg: String): Color {
    return when (pkg.lowercase()) {
        "com.zhihu.android", "zhihu" -> ZhihuBlue
        "com.douban.frodo", "douban" -> DoubanGreen
        "com.baidu.tieba", "tieba" -> TiebaBlue
        "com.twitter.android", "twitter", "com.instagram.barcelona", "threads" -> XBlack
        "com.reddit.frontpage", "reddit" -> RedditOrange
        "com.sina.weibo", "com.weico.international", "weibo" -> me.melon.record.ui.theme.WeiboOrange
        else -> {
            val h = (pkg.hashCode().toLong().ushr(1) % 360L).toFloat()
            Color.hsv(h, 0.65f, 0.90f)
        }
    }
}

fun appLabel(pkg: String): String {
    return me.melon.record.data.PlatformCatalog.packageLabel(pkg)
        ?: me.melon.record.data.PlatformCatalog.knownDisplayName(pkg)
        ?: if (pkg.isBlank() || pkg == "unknown") "其他" else pkg
}
