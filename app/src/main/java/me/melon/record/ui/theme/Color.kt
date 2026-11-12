package me.melon.record.ui.theme

import androidx.compose.ui.graphics.Color

val IndexWhite = Color(0xFFFDFDFD)
val IndexBlue = Color(0xFF87CEEB)
val IndexGold = Color(0xFFD4AF37)
val IndexDarkBlue = Color(0xFF4682B4)
val IndexPink = Color(0xFFFFB6C1)

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7d5260)

// --- 平台品牌主色与延伸色 ---

// 豆瓣绿
val DoubanGreen = Color(0xFF2E963D)
val DoubanGreenLightContainer = Color(0xFFE8F5E9)
val DoubanGreenLightText = Color(0xFF1B5E20)
val DoubanGreenDarkContainer = Color(0xFF14301B)
val DoubanGreenDarkText = Color(0xFF74C682)

// 知乎蓝
val ZhihuBlue = Color(0xFF0084FF)
val ZhihuBlueLightContainer = Color(0xFFE8F3FF)
val ZhihuBlueLightText = Color(0xFF0066CC)
val ZhihuBlueDarkContainer = Color(0xFF102842)
val ZhihuBlueDarkText = Color(0xFF70B8FF)

// 贴吧蓝紫
val TiebaBlue = Color(0xFF4E6EF2)
val TiebaLightContainer = Color(0xFFEDF1FF)
val TiebaLightText = Color(0xFF3050D6)
val TiebaDarkContainer = Color(0xFF15224D)
val TiebaDarkText = Color(0xFF8AA3FF)

val WeiboOrange = Color(0xFFE96B10)

val RedditOrange = Color(0xFFFF4500)
val RedditLightContainer = Color(0xFFFFEDE5)
val RedditLightText = Color(0xFFA92E00)
val RedditDarkContainer = Color(0xFF3C1D12)
val RedditDarkText = Color(0xFFFFAB87)

// 𝕏 黑
val XBlack = Color(0xFF000000)
val XLightContainer = Color(0xFFF0F0F0)
val XLightText = Color(0xFF0F1419)
val XDarkContainer = Color(0xFF242628)
val XDarkText = Color(0xFFE7E9EA)

data class PlatformThemeColor(
    val brand: Color,
    val container: Color,
    val text: Color
)

fun getPlatformThemeColor(platform: String, isDark: Boolean): PlatformThemeColor {
    return when (platform.lowercase()) {
        "zhihu", "com.zhihu.android" -> if (isDark) {
            PlatformThemeColor(ZhihuBlue, ZhihuBlueDarkContainer, ZhihuBlueDarkText)
        } else {
            PlatformThemeColor(ZhihuBlue, ZhihuBlueLightContainer, ZhihuBlueLightText)
        }
        "douban", "com.douban.frodo" -> if (isDark) {
            PlatformThemeColor(DoubanGreen, DoubanGreenDarkContainer, DoubanGreenDarkText)
        } else {
            PlatformThemeColor(DoubanGreen, DoubanGreenLightContainer, DoubanGreenLightText)
        }
        "tieba", "com.baidu.tieba" -> if (isDark) {
            PlatformThemeColor(TiebaBlue, TiebaDarkContainer, TiebaDarkText)
        } else {
            PlatformThemeColor(TiebaBlue, TiebaLightContainer, TiebaLightText)
        }
        "weibo", "com.sina.weibo", "com.weico.international" -> if (isDark) {
            PlatformThemeColor(WeiboOrange, Color(0xFF3B2314), Color(0xFFFFB77A))
        } else {
            PlatformThemeColor(WeiboOrange, Color(0xFFFFF0E3), Color(0xFF963E00))
        }
        "twitter", "com.twitter.android", "threads", "com.instagram.barcelona" -> if (isDark) {
            PlatformThemeColor(XBlack, XDarkContainer, XDarkText)
        } else {
            PlatformThemeColor(XBlack, XLightContainer, XLightText)
        }
        "reddit", "com.reddit.frontpage" -> if (isDark) {
            PlatformThemeColor(RedditOrange, RedditDarkContainer, RedditDarkText)
        } else {
            PlatformThemeColor(RedditOrange, RedditLightContainer, RedditLightText)
        }
        else -> if (isDark) {
            PlatformThemeColor(IndexBlue, Color(0xFF2A2B30), Color(0xFFD0D0D5))
        } else {
            PlatformThemeColor(IndexDarkBlue, Color(0xFFF0F0F3), Color(0xFF4A4B50))
        }
    }
}
