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
    return when (pkg) {
        "com.zhihu.android", "zhihu" -> "知乎"
        "com.douban.frodo", "douban" -> "豆瓣"
        "com.baidu.tieba", "tieba" -> "贴吧"
        "com.twitter.android", "twitter" -> "𝕏"
        "com.instagram.barcelona", "threads" -> "Threads"
        "com.reddit.frontpage", "reddit" -> "Reddit"
        "com.sina.weibo", "weibo" -> "微博"
        "com.weico.international" -> "微博国际版"
        else -> if (pkg.isBlank() || pkg == "unknown") "其他" else pkg
    }
}
