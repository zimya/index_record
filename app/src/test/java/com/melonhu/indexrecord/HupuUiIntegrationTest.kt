package com.melonhu.indexrecord

import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.logic.StatsCalculator
import com.melonhu.indexrecord.ui.components.filterPlatformLabel
import com.melonhu.indexrecord.ui.stats.appLabel
import com.melonhu.indexrecord.ui.stats.colorFromPackage
import com.melonhu.indexrecord.ui.theme.CoolapkGreen
import com.melonhu.indexrecord.ui.theme.HupuRed
import com.melonhu.indexrecord.ui.theme.getPlatformBrandColor
import com.melonhu.indexrecord.ui.theme.getPlatformThemeColor
import org.junit.Assert.assertEquals
import org.junit.Test

class HupuUiIntegrationTest {

    @Test
    fun platformAndPackageUseTheSameHupuNameAndTheme() {
        for (key in listOf("hupu", "com.hupu.games")) {
            assertEquals("虎扑", appLabel(key))
            assertEquals("虎扑", filterPlatformLabel(key))
            assertEquals(HupuRed, colorFromPackage(key))
            assertEquals(HupuRed, getPlatformBrandColor(key))
        }
        for (dark in listOf(false, true)) {
            val theme = getPlatformThemeColor("hupu", dark)
            assertEquals(HupuRed, theme.brand)
            assertEquals(theme, getPlatformThemeColor("com.hupu.games", dark))
        }
        for (type in listOf("post", "comment")) {
            assertEquals(
                "com.hupu.games",
                StatsCalculator.resolveSourceApp(UniversalRecord(platform = "hupu", recordType = type))
            )
        }
    }

    @Test
    fun coolapkPlatformAndPackageUseTheSameNameAndTheme() {
        for (key in listOf("coolapk", "com.coolapk.market")) {
            assertEquals("酷安", appLabel(key))
            assertEquals("酷安", filterPlatformLabel(key))
            assertEquals(CoolapkGreen, colorFromPackage(key))
            assertEquals(CoolapkGreen, getPlatformBrandColor(key))
        }
        for (dark in listOf(false, true)) {
            val theme = getPlatformThemeColor("coolapk", dark)
            assertEquals(CoolapkGreen, theme.brand)
            assertEquals(theme, getPlatformThemeColor("com.coolapk.market", dark))
        }
        for (type in listOf("feed", "rating", "feedArticle")) {
            assertEquals(
                "com.coolapk.market",
                StatsCalculator.resolveSourceApp(UniversalRecord(platform = "coolapk", recordType = type))
            )
        }
    }
}
