package me.melon.record.ui.stats

import androidx.compose.ui.graphics.Color
import me.melon.record.ui.theme.DoubanGreen
import me.melon.record.ui.theme.TiebaBlue
import me.melon.record.ui.theme.XBlack
import me.melon.record.ui.theme.XiaohongshuRed
import me.melon.record.ui.theme.ZhihuBlue

fun colorFromPackage(pkg: String): Color {
    return when (pkg.lowercase()) {
        "com.zhihu.android", "zhihu" -> ZhihuBlue
        "com.douban.frodo", "douban" -> DoubanGreen
        "com.baidu.tieba", "tieba" -> TiebaBlue
        "com.xingin.xhs", "xiaohongshu" -> XiaohongshuRed
        "com.twitter.android", "twitter" -> XBlack
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
        "com.xingin.xhs", "xiaohongshu" -> "小红书"
        "com.twitter.android", "twitter" -> "𝕏"
        "com.sina.weibo", "weibo" -> "微博"
        "com.weico.international" -> "微博国际版"
        else -> if (pkg.isBlank() || pkg == "unknown") "其他" else pkg
    }
}
