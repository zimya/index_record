package com.melonhu.indexrecord.settings

import java.util.Locale

enum class AppLanguage(val code: String, val locale: Locale?) {
    SYSTEM("", null),
    SIMPLIFIED_CHINESE("zh-CN", Locale.SIMPLIFIED_CHINESE),
    TRADITIONAL_CHINESE("zh-TW", Locale.TRADITIONAL_CHINESE),
    ENGLISH("en", Locale.ENGLISH);

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SYSTEM
    }
}
