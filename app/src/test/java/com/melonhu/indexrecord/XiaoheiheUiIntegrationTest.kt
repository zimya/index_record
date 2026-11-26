package com.melonhu.indexrecord

import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.logic.StatsCalculator
import com.melonhu.indexrecord.ui.components.filterPlatformLabel
import com.melonhu.indexrecord.ui.stats.appLabel
import com.melonhu.indexrecord.ui.stats.colorFromPackage
import com.melonhu.indexrecord.ui.theme.XiaoheiheDark
import com.melonhu.indexrecord.ui.theme.getPlatformBrandColor
import com.melonhu.indexrecord.ui.theme.getPlatformThemeColor
import org.junit.Assert.assertEquals
import org.junit.Test

class XiaoheiheUiIntegrationTest {

    @Test
    fun platformAndPackageUseTheSameXiaoheiheNameAndTheme() {
        for (key in listOf("xiaoheihe", "com.max.xiaoheihe")) {
            assertEquals("小黑盒", appLabel(key))
            assertEquals("小黑盒", filterPlatformLabel(key))
            assertEquals(XiaoheiheDark, colorFromPackage(key))
            assertEquals(XiaoheiheDark, getPlatformBrandColor(key))
        }
        for (dark in listOf(false, true)) {
            val theme = getPlatformThemeColor("xiaoheihe", dark)
            assertEquals(XiaoheiheDark, theme.brand)
            assertEquals(theme, getPlatformThemeColor("com.max.xiaoheihe", dark))
        }
        for (type in listOf("post", "comment")) {
            assertEquals(
                "com.max.xiaoheihe",
                StatsCalculator.resolveSourceApp(UniversalRecord(platform = "xiaoheihe", recordType = type))
            )
        }
    }
}
