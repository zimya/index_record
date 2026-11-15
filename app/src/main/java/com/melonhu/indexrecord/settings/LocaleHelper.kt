package com.melonhu.indexrecord.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {

    fun applyLanguage(context: Context): Context {
        val language = RecordSettings.getLanguage(context)
        val targetLocale = language.locale ?: return context
        return updateResources(context, targetLocale)
    }

    fun getLocalizedContext(context: Context, language: AppLanguage): Context {
        val targetLocale = language.locale ?: getSystemLocale()
        return updateResources(context, targetLocale)
    }

    fun updateResources(context: Context, targetLocale: Locale): Context {
        Locale.setDefault(targetLocale)

        val res = context.resources
        val config = Configuration(res.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(targetLocale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            config.locale = targetLocale
        }

        return context.createConfigurationContext(config)
    }

    fun getSystemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = Resources.getSystem().configuration.locales
            if (!list.isEmpty) list.get(0) else Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            Resources.getSystem().configuration.locale ?: Locale.getDefault()
        }
    }

    fun setApplicationLanguage(context: Context, language: AppLanguage) {
        RecordSettings.setAppLanguage(language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            if (language == AppLanguage.SYSTEM) {
                localeManager?.applicationLocales = LocaleList.getEmptyLocaleList()
            } else {
                localeManager?.applicationLocales = LocaleList.forLanguageTags(language.code)
            }
        }
        val targetLocale = language.locale ?: getSystemLocale()
        Locale.setDefault(targetLocale)
    }
}
