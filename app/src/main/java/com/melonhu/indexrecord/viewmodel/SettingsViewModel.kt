package com.melonhu.indexrecord.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.melonhu.indexrecord.logic.DataProcessor
import com.melonhu.indexrecord.ui.theme.ThemeColor
import com.melonhu.indexrecord.ui.theme.ThemeMode
import com.melonhu.indexrecord.settings.AppLanguage
import com.melonhu.indexrecord.settings.LocaleHelper
import com.melonhu.indexrecord.settings.RecordSettings
import com.melonhu.indexrecord.settings.RedditRecordMode
import com.melonhu.indexrecord.settings.TweetRecordMode
import com.melonhu.indexrecord.settings.WeiboRecordMode
import com.melonhu.indexrecord.settings.ThreadsRecordMode
import com.melonhu.indexrecord.settings.BlueskyRecordMode

sealed interface LsposedStatus {
    object Checking : LsposedStatus
    object Inactive : LsposedStatus
    data class Active(
        val frameworkName: String,
        val frameworkVersion: String?,
        val activeScopes: List<String>
    ) : LsposedStatus
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    @Volatile
    private var boundService: XposedService? = null

    val syncProgress: StateFlow<DataProcessor.SyncProgress> = MutableStateFlow(
        DataProcessor.SyncProgress(isRunning = false, total = 0, processed = 0)
    )

    suspend fun refreshData(): Int {
        withContext(Dispatchers.IO) {
            val service = boundService
            if (service != null) {
                _lsposedStatus.value = readLsposedStatus(service)
            } else {
                delay(300)
            }
        }
        return 0
    }

    private val _lsposedStatus = MutableStateFlow<LsposedStatus>(LsposedStatus.Checking)
    val lsposedStatus: StateFlow<LsposedStatus> = _lsposedStatus.asStateFlow()

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _monetDynamicColor = MutableStateFlow(loadMonetDynamicColor())
    val monetDynamicColor: StateFlow<Boolean> = _monetDynamicColor.asStateFlow()

    private val _themeColor = MutableStateFlow(loadThemeColor())
    val themeColor: StateFlow<ThemeColor> = _themeColor.asStateFlow()

    private val _tweetRecordMode = MutableStateFlow(RecordSettings.tweetRecordMode)
    val tweetRecordMode: StateFlow<TweetRecordMode> = _tweetRecordMode.asStateFlow()

    private val _weiboRecordMode = MutableStateFlow(RecordSettings.weiboRecordMode)
    val weiboRecordMode: StateFlow<WeiboRecordMode> = _weiboRecordMode.asStateFlow()

    private val _threadsRecordMode = MutableStateFlow(RecordSettings.threadsRecordMode)
    val threadsRecordMode: StateFlow<ThreadsRecordMode> = _threadsRecordMode.asStateFlow()

    private val _redditRecordMode = MutableStateFlow(RecordSettings.redditRecordMode)
    val redditRecordMode: StateFlow<RedditRecordMode> = _redditRecordMode.asStateFlow()

    private val _blueskyRecordMode = MutableStateFlow(RecordSettings.blueskyRecordMode)
    val blueskyRecordMode: StateFlow<BlueskyRecordMode> = _blueskyRecordMode.asStateFlow()

    init {
        initLsposedService()
    }

    private fun initLsposedService() {
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    boundService = service
                    _lsposedStatus.value = readLsposedStatus(service)
                }

                override fun onServiceDied(service: XposedService) {
                    boundService = null
                    _lsposedStatus.value = LsposedStatus.Inactive
                }
            })
        } catch (e: Throwable) {
            _lsposedStatus.value = LsposedStatus.Inactive
        }

        viewModelScope.launch {
            delay(1500)
            if (_lsposedStatus.value is LsposedStatus.Checking) {
                _lsposedStatus.value = LsposedStatus.Inactive
            }
        }
    }

    private fun readLsposedStatus(service: XposedService): LsposedStatus {
        return try {
            val scopes = try {
                service.scope?.toList() ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
            val frameworkName = try {
                service.frameworkName ?: "LSPosed"
            } catch (_: Throwable) {
                "LSPosed"
            }
            val frameworkVersion = try {
                service.frameworkVersion
            } catch (_: Throwable) {
                null
            }
            LsposedStatus.Active(
                frameworkName = frameworkName,
                frameworkVersion = frameworkVersion,
                activeScopes = scopes
            )
        } catch (_: Throwable) {
            boundService = null
            LsposedStatus.Inactive
        }
    }

    val includeRepliesAndCommentsInStats: StateFlow<Boolean> = RecordSettings.includeRepliesAndCommentsInStats

    fun setIncludeRepliesAndCommentsInStats(enabled: Boolean) {
        RecordSettings.setIncludeRepliesAndCommentsInStats(enabled)
    }

    val collapseMultipleItems: StateFlow<Boolean> = RecordSettings.collapseMultipleItems

    fun setCollapseMultipleItems(enabled: Boolean) {
        RecordSettings.setCollapseMultipleItems(enabled)
    }

    val appLanguage: StateFlow<AppLanguage> = RecordSettings.appLanguage

    fun setAppLanguage(language: AppLanguage) {
        val app = getApplication<Application>()
        LocaleHelper.setApplicationLanguage(app, language)
    }

    fun setRedditRecordMode(mode: RedditRecordMode) {
        RecordSettings.redditRecordMode = mode
        _redditRecordMode.value = mode
    }

    fun setBlueskyRecordMode(mode: BlueskyRecordMode) {
        RecordSettings.blueskyRecordMode = mode
        _blueskyRecordMode.value = mode
    }

    fun setThreadsRecordMode(mode: ThreadsRecordMode) {
        RecordSettings.threadsRecordMode = mode
        _threadsRecordMode.value = mode
    }

    fun setWeiboRecordMode(mode: WeiboRecordMode) {
        RecordSettings.weiboRecordMode = mode
        _weiboRecordMode.value = mode
    }

    fun setTweetRecordMode(mode: TweetRecordMode) {
        RecordSettings.tweetRecordMode = mode
        _tweetRecordMode.value = mode
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putInt("theme_mode", mode.ordinal).apply()
        _themeMode.value = mode
    }

    private fun loadThemeMode(): ThemeMode {
        val v = prefs.getInt("theme_mode", ThemeMode.SYSTEM.ordinal)
        return ThemeMode.entries.getOrNull(v) ?: ThemeMode.SYSTEM
    }

    fun setMonetDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("monet_dynamic_color", enabled).apply()
        _monetDynamicColor.value = enabled
    }

    private fun loadMonetDynamicColor(): Boolean {
        return prefs.getBoolean("monet_dynamic_color", false)
    }

    fun setThemeColor(color: ThemeColor) {
        prefs.edit().putString("theme_color", color.name).apply()
        _themeColor.value = color
    }

    private fun loadThemeColor(): ThemeColor {
        val name = prefs.getString("theme_color", null)
        return ThemeColor.entries.firstOrNull { it.name == name } ?: ThemeColor.INDEX_BLUE
    }
}
