package com.melonhu.indexrecord.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.melonhu.indexrecord.ui.theme.ThemeMode
import com.melonhu.indexrecord.settings.RecordSettings
import com.melonhu.indexrecord.settings.RedditRecordMode
import com.melonhu.indexrecord.settings.TweetRecordMode
import com.melonhu.indexrecord.settings.WeiboRecordMode
import com.melonhu.indexrecord.settings.ThreadsRecordMode

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

    private val _lsposedStatus = MutableStateFlow<LsposedStatus>(LsposedStatus.Checking)
    val lsposedStatus: StateFlow<LsposedStatus> = _lsposedStatus.asStateFlow()

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _tweetRecordMode = MutableStateFlow(RecordSettings.tweetRecordMode)
    val tweetRecordMode: StateFlow<TweetRecordMode> = _tweetRecordMode.asStateFlow()

    private val _weiboRecordMode = MutableStateFlow(RecordSettings.weiboRecordMode)
    val weiboRecordMode: StateFlow<WeiboRecordMode> = _weiboRecordMode.asStateFlow()

    private val _threadsRecordMode = MutableStateFlow(RecordSettings.threadsRecordMode)
    val threadsRecordMode: StateFlow<ThreadsRecordMode> = _threadsRecordMode.asStateFlow()

    private val _redditRecordMode = MutableStateFlow(RecordSettings.redditRecordMode)
    val redditRecordMode: StateFlow<RedditRecordMode> = _redditRecordMode.asStateFlow()

    init {
        initLsposedService()
    }

    private fun initLsposedService() {
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    val scopes = try {
                        service.scope?.toList() ?: emptyList()
                    } catch (e: Throwable) {
                        emptyList()
                    }
                    val frameworkName = try {
                        service.frameworkName ?: "LSPosed"
                    } catch (e: Throwable) {
                        "LSPosed"
                    }
                    val frameworkVersion = try {
                        service.frameworkVersion
                    } catch (e: Throwable) {
                        null
                    }
                    _lsposedStatus.value = LsposedStatus.Active(
                        frameworkName = frameworkName,
                        frameworkVersion = frameworkVersion,
                        activeScopes = scopes
                    )
                }

                override fun onServiceDied(service: XposedService) {
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

    val includeRepliesAndCommentsInStats: StateFlow<Boolean> = RecordSettings.includeRepliesAndCommentsInStats

    fun setIncludeRepliesAndCommentsInStats(enabled: Boolean) {
        RecordSettings.setIncludeRepliesAndCommentsInStats(enabled)
    }

    val collapseMultipleItems: StateFlow<Boolean> = RecordSettings.collapseMultipleItems

    fun setCollapseMultipleItems(enabled: Boolean) {
        RecordSettings.setCollapseMultipleItems(enabled)
    }

    fun setRedditRecordMode(mode: RedditRecordMode) {
        RecordSettings.redditRecordMode = mode
        _redditRecordMode.value = mode
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
}
