package me.melon.record.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.melon.record.ui.theme.ThemeMode
import me.melon.record.settings.RecordSettings
import me.melon.record.settings.TweetRecordMode
import me.melon.record.settings.WeiboRecordMode
import me.melon.record.settings.ThreadsRecordMode

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _tweetRecordMode = MutableStateFlow(RecordSettings.tweetRecordMode)
    val tweetRecordMode: StateFlow<TweetRecordMode> = _tweetRecordMode.asStateFlow()

    private val _weiboRecordMode = MutableStateFlow(RecordSettings.weiboRecordMode)
    val weiboRecordMode: StateFlow<WeiboRecordMode> = _weiboRecordMode.asStateFlow()

    private val _threadsRecordMode = MutableStateFlow(RecordSettings.threadsRecordMode)
    val threadsRecordMode: StateFlow<ThreadsRecordMode> = _threadsRecordMode.asStateFlow()

    val collapseMultipleItems: StateFlow<Boolean> = RecordSettings.collapseMultipleItems

    fun setCollapseMultipleItems(enabled: Boolean) {
        RecordSettings.setCollapseMultipleItems(enabled)
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
