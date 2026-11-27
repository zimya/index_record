package com.melonhu.indexrecord

import com.melonhu.indexrecord.data.PlatformCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCatalogTest {
    @Test fun knownPlatformsHaveUniqueDefaultPackages() {
        assertEquals(14, PlatformCatalog.platforms.size)
        assertEquals(14, PlatformCatalog.platforms.map { it.defaultPackage }.distinct().size)
        PlatformCatalog.platforms.forEach {
            assertEquals(it.name, PlatformCatalog.displayName(it.id))
            assertEquals(it.defaultPackage, PlatformCatalog.defaultPackage(it.id))
            assertEquals(it.name, PlatformCatalog.packageLabel(it.defaultPackage))
        }
        assertEquals("微博国际版", PlatformCatalog.packageLabel("com.weico.international"))
        assertEquals("酷安", PlatformCatalog.packageLabel("com.coolapk.market"))
        assertEquals("虎扑", PlatformCatalog.packageLabel("com.hupu.games"))
        assertEquals("小黑盒", PlatformCatalog.packageLabel("com.max.xiaoheihe"))
        assertEquals("Bluesky", PlatformCatalog.packageLabel("xyz.blueskyweb.app"))
        assertEquals("NGA", PlatformCatalog.packageLabel("gov.pianzong.androidnga"))
        assertEquals("Bilibili", PlatformCatalog.packageLabel("tv.danmaku.bili"))
        assertEquals("Bilibili 国际版", PlatformCatalog.packageLabel("com.bilibili.app.in"))
        assertEquals("网易云音乐", PlatformCatalog.packageLabel("com.netease.cloudmusic"))
        assertEquals("知乎++", PlatformCatalog.packageLabel("com.github.zly2006.zhplus"))
        assertEquals("知乎++ Lite", PlatformCatalog.packageLabel("com.github.zly2006.zhplus.lite"))
        assertEquals("Tieba Lite", PlatformCatalog.packageLabel("com.huanchengfly.tieba.post"))
        assertEquals("zhihu", PlatformCatalog.platformForPackage("com.github.zly2006.zhplus"))
        assertEquals("zhihu", PlatformCatalog.platformForPackage("com.github.zly2006.zhplus.lite"))
        assertEquals("tieba", PlatformCatalog.platformForPackage("com.huanchengfly.tieba.post"))
        assertEquals(19, PlatformCatalog.hostPackages.size)
    }

    @Test fun unknownValuesRemainLiteralAndBlankValuesAreExplicitlyUnknown() {
        assertEquals("example", PlatformCatalog.displayName("example"))
                assertEquals("平台未知", PlatformCatalog.displayName(""))
                assertEquals("类型未知", PlatformCatalog.typeLabel("", ""))
        assertTrue(PlatformCatalog.defaultPackage("example").isEmpty())
        assertEquals("new_type", PlatformCatalog.typeLabel("example", "new_type"))
    }

    @Test fun cardAndFilterLabelsRetainTheirDistinctContexts() {
        assertEquals("楼中楼", PlatformCatalog.typeLabel("tieba", "comment"))
        assertEquals("回复", PlatformCatalog.typeLabel("reddit", "comment"))
        assertEquals("回复", PlatformCatalog.typeLabel("hupu", "comment"))
        assertEquals("回复", PlatformCatalog.typeLabel("xiaoheihe", "comment"))
        assertEquals("回复", PlatformCatalog.typeLabel("nga", "comment"))
        assertEquals("评论", PlatformCatalog.typeLabel("weibo", "comment"))
        assertEquals("回答", PlatformCatalog.typeLabel("zhihu", "answer"))
        assertEquals("知乎回答", PlatformCatalog.filterTypeLabel("answer"))
        assertEquals("帖子", PlatformCatalog.typeLabel("threads", "thread"))
        assertEquals("Threads 帖子", PlatformCatalog.filterTypeLabel("thread"))
        assertEquals("动态", PlatformCatalog.typeLabel("coolapk", "feed"))
        assertEquals("点评", PlatformCatalog.typeLabel("coolapk", "rating"))
        assertEquals("图文", PlatformCatalog.typeLabel("coolapk", "feedArticle"))
        assertEquals("酷安动态", PlatformCatalog.filterTypeLabel("feed"))
        assertEquals("酷安点评", PlatformCatalog.filterTypeLabel("rating"))
        assertEquals("酷安图文", PlatformCatalog.filterTypeLabel("feedArticle"))
    }
}
