package com.melonhu.indexrecord

import com.melonhu.indexrecord.data.PlatformCatalog
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.presentation.RecordContentPolicy
import com.melonhu.indexrecord.ui.components.filterTypesForApps
import com.melonhu.indexrecord.ui.components.typeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordFilterTypeTest {
    @Test
    fun platformSpecificTypesHaveDistinctLabels() {
        val labels = mapOf(
            "status" to "微博",
            "question" to "知乎问题",
            "answer" to "知乎回答",
            "article" to "知乎文章",
            "pin" to "知乎想法",
            "comment" to "评论",
            "topic" to "豆瓣帖子",
            "post" to "Reddit 帖子",
            "lou" to "贴吧主帖",
            "thread" to "Threads 帖子",
            "tweet" to "X 推文",
            "floor" to "贴吧楼层",
            "review" to "豆瓣长评",
            "interest" to "豆瓣短评",
            "subject" to "豆瓣条目",
            "note" to "豆瓣日记"
        )
        labels.forEach { (type, label) -> assertEquals(label, typeLabel(type)) }
        assertEquals(labels.size, labels.keys.map(::typeLabel).toSet().size)
        assertEquals("unknown", typeLabel("unknown"))
    }
    @Test
    fun qualifiedHomeOptionsShowPlatformSpecificReplyLabels() {
        val labels = mapOf(
            "zhihu:comment" to "知乎评论",
            "tieba:floor" to "贴吧楼层",
            "tieba:comment" to "贴吧楼中楼",
            "reddit:comment" to "Reddit回复",
            "twitter:comment" to "X评论",
            "weibo:comment" to "微博评论",
            "douban:comment" to "豆瓣评论",
            "threads:comment" to "Threads回复"
        )
        labels.forEach { (key, label) -> assertEquals(label, typeLabel(key)) }
        assertEquals(labels.size, labels.keys.map(::typeLabel).toSet().size)
    }

    @Test
    fun storedTypesAndFilterLabelsAreDistinct() {
        val records = listOf(
            UniversalRecord(platform = "tieba", recordType = "lou"),
            UniversalRecord(platform = "threads", recordType = "thread")
        )
        assertEquals(listOf("tieba"), records.filter { it.recordType == "lou" }.map { it.platform })
        assertEquals(listOf("threads"), records.filter { it.recordType == "thread" }.map { it.platform })
        assertEquals("贴吧主帖", typeLabel("lou"))
        assertEquals("Threads 帖子", typeLabel("thread"))
    }

    @Test
    fun platformForPackageResolvesAllSupportedPackages() {
        assertEquals("zhihu", PlatformCatalog.platformForPackage("com.zhihu.android"))
        assertEquals("douban", PlatformCatalog.platformForPackage("com.douban.frodo"))
        assertEquals("tieba", PlatformCatalog.platformForPackage("com.baidu.tieba"))
        assertEquals("twitter", PlatformCatalog.platformForPackage("com.twitter.android"))
        assertEquals("threads", PlatformCatalog.platformForPackage("com.instagram.barcelona"))
        assertEquals("reddit", PlatformCatalog.platformForPackage("com.reddit.frontpage"))
        assertEquals("weibo", PlatformCatalog.platformForPackage("com.sina.weibo"))
        assertEquals("weibo", PlatformCatalog.platformForPackage("com.weico.international"))
        assertEquals("coolapk", PlatformCatalog.platformForPackage("com.coolapk.market"))
        assertEquals("hupu", PlatformCatalog.platformForPackage("com.hupu.games"))
        assertEquals("xiaoheihe", PlatformCatalog.platformForPackage("com.max.xiaoheihe"))
        assertEquals("bluesky", PlatformCatalog.platformForPackage("xyz.blueskyweb.app"))
        assertEquals("nga", PlatformCatalog.platformForPackage("gov.pianzong.androidnga"))
        assertEquals("zhihu", PlatformCatalog.platformForPackage("zhihu"))
        assertEquals(null, PlatformCatalog.platformForPackage("com.unknown.package"))
    }

    @Test
    fun platformFromFilterTypeKeyExtractsPlatform() {
        assertEquals("zhihu", RecordContentPolicy.platformFromFilterTypeKey("zhihu:question"))
        assertEquals("zhihu", RecordContentPolicy.platformFromFilterTypeKey("zhihu:answer"))
        assertEquals("tieba", RecordContentPolicy.platformFromFilterTypeKey("tieba:lou"))
        assertEquals("weibo", RecordContentPolicy.platformFromFilterTypeKey("weibo:status"))
        assertEquals("douban", RecordContentPolicy.platformFromFilterTypeKey("douban:topic"))
        // Legacy keys without colon
        assertEquals("zhihu", RecordContentPolicy.platformFromFilterTypeKey("question"))
        assertEquals("tieba", RecordContentPolicy.platformFromFilterTypeKey("lou"))
        assertEquals("weibo", RecordContentPolicy.platformFromFilterTypeKey("status"))
    }

    @Test
    fun filterTypesForAppsOnlyShowsTypesOfSelectedApps() {
        val allTypes = listOf(
            "zhihu:question", "zhihu:answer", "zhihu:article",
            "douban:topic", "douban:review",
            "tieba:lou", "tieba:floor",
            "twitter:tweet", "weibo:status"
        )

        // No app selected -> empty types
        assertTrue(filterTypesForApps(allTypes, emptySet()).isEmpty())

        // Single app selected (zhihu)
        val zhihuTypes = filterTypesForApps(allTypes, setOf("com.zhihu.android"))
        assertEquals(listOf("zhihu:question", "zhihu:answer", "zhihu:article"), zhihuTypes)

        // Multiple apps selected (zhihu + tieba)
        val zhihuTiebaTypes = filterTypesForApps(allTypes, setOf("com.zhihu.android", "com.baidu.tieba"))
        assertEquals(
            listOf("zhihu:question", "zhihu:answer", "zhihu:article", "tieba:lou", "tieba:floor"),
            zhihuTiebaTypes
        )

        // Weibo international package
        val weiboTypes = filterTypesForApps(allTypes, setOf("com.weico.international"))
        assertEquals(listOf("weibo:status"), weiboTypes)

        // Unselecting zhihu leaves tieba
        val remainingTieba = filterTypesForApps(allTypes, setOf("com.baidu.tieba"))
        assertEquals(listOf("tieba:lou", "tieba:floor"), remainingTieba)

        // Pruning logic: previously selected zhihu types do not belong to tieba
        val previousSelectedTypes = setOf("zhihu:question", "tieba:lou")
        val validForTieba = previousSelectedTypes.filter { it in remainingTieba }.toSet()
        assertEquals(setOf("tieba:lou"), validForTieba)
    }
}
