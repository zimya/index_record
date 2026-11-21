package com.melonhu.indexrecord.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun `compareVersionNames handles multi-digit segments and prefixes`() {
        assertTrue(AppUpdateChecker.compareVersionNames("v0.10.0", "0.9.0") > 0)
        assertTrue(AppUpdateChecker.compareVersionNames("0.7.2", "0.7.1") > 0)
        assertEquals(0, AppUpdateChecker.compareVersionNames("v0.7.1", "0.7.1"))
        assertTrue(AppUpdateChecker.compareVersionNames("0.7.1", "0.8.0") < 0)
    }

    @Test
    fun `isNewerVersion falls back to versionCode when versionName matches`() {
        assertTrue(AppUpdateChecker.isNewerVersion("0.7.1", 120, "0.7.1", 119))
        assertFalse(AppUpdateChecker.isNewerVersion("0.7.1", 120, "0.7.1", 120))
        assertTrue(AppUpdateChecker.isNewerVersion("0.7.2", 1, "0.7.1", 999))
    }

    @Test
    fun `selectApkAsset prefers matching ABI and falls back to universal`() {
        val assets = listOf(
            "index-record-v0.7.2-armeabi-v7a-release.apk",
            "index-record-v0.7.2-arm64-v8a-release.apk",
            "index-record-v0.7.2-universal-release.apk"
        )
        assertEquals(
            "index-record-v0.7.2-arm64-v8a-release.apk",
            AppUpdateChecker.selectApkAsset(assets, arrayOf("arm64-v8a", "armeabi-v7a"))
        )
        assertEquals(
            "index-record-v0.7.2-universal-release.apk",
            AppUpdateChecker.selectApkAsset(assets, arrayOf("x86_64"))
        )
        assertNull(AppUpdateChecker.selectApkAsset(listOf("version.json"), arrayOf("arm64-v8a")))
    }
}
