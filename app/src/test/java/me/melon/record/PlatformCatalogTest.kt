package me.melon.record

import me.melon.record.data.PlatformCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCatalogTest {
    @Test fun knownPlatformsHaveUniqueDefaultPackages() {
        assertEquals(7, PlatformCatalog.platforms.size)
        assertEquals(7, PlatformCatalog.platforms.map { it.defaultPackage }.distinct().size)
        PlatformCatalog.platforms.forEach {
            assertEquals(it.name, PlatformCatalog.displayName(it.id))
            assertEquals(it.defaultPackage, PlatformCatalog.defaultPackage(it.id))
            assertEquals(it.name, PlatformCatalog.packageLabel(it.defaultPackage))
        }
        assertEquals("微博国际版", PlatformCatalog.packageLabel("com.weico.international"))
        assertEquals(8, PlatformCatalog.hostPackages.size)
    }

    @Test fun unknownValuesKeepTheirExistingFallbacks() {
        assertEquals("Example", PlatformCatalog.displayName("example"))
        assertTrue(PlatformCatalog.defaultPackage("example").isEmpty())
        assertEquals("new_type", PlatformCatalog.typeLabel("example", "new_type"))
    }

    @Test fun cardAndFilterLabelsRetainTheirDistinctContexts() {
        assertEquals("楼中楼", PlatformCatalog.typeLabel("tieba", "comment"))
        assertEquals("回复", PlatformCatalog.typeLabel("reddit", "comment"))
        assertEquals("评论", PlatformCatalog.typeLabel("weibo", "comment"))
        assertEquals("回答", PlatformCatalog.typeLabel("zhihu", "answer"))
        assertEquals("知乎回答", PlatformCatalog.filterTypeLabel("answer"))
        assertEquals("thread", PlatformCatalog.typeLabel("threads", "thread"))
        assertEquals("Threads 帖子", PlatformCatalog.filterTypeLabel("thread"))
    }
}
