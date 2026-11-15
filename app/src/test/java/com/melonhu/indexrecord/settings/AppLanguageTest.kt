package com.melonhu.indexrecord.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppLanguageTest {

    @Test
    fun testAppLanguageCodes() {
        assertEquals("", AppLanguage.SYSTEM.code)
        assertEquals("zh-CN", AppLanguage.SIMPLIFIED_CHINESE.code)
        assertEquals("zh-TW", AppLanguage.TRADITIONAL_CHINESE.code)
        assertEquals("en", AppLanguage.ENGLISH.code)
    }

    @Test
    fun testAppLanguageLocales() {
        assertNull(AppLanguage.SYSTEM.locale)
        assertEquals(Locale.SIMPLIFIED_CHINESE, AppLanguage.SIMPLIFIED_CHINESE.locale)
        assertEquals(Locale.TRADITIONAL_CHINESE, AppLanguage.TRADITIONAL_CHINESE.locale)
        assertEquals(Locale.ENGLISH, AppLanguage.ENGLISH.locale)
    }

    @Test
    fun testFromCodeMapping() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(""))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromCode("zh-CN"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromCode("zh-cn"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromCode("zh-TW"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromCode("zh-tw"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("EN"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode("unknown_language"))
    }
}
