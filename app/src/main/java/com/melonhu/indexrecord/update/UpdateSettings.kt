package com.melonhu.indexrecord.update

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UpdateCheckFrequency(val intervalMs: Long) {
    EVERY_LAUNCH(0L),
    DAILY(24L * 60 * 60 * 1000),
    WEEKLY(7L * 24 * 60 * 60 * 1000),
    OFF(-1L);

    companion object {
        fun fromStoredValue(value: Any?): UpdateCheckFrequency =
            entries.firstOrNull { it.name == value } ?: DAILY
    }
}

object UpdateSettings {
    private const val UPDATE_CHECK_FREQUENCY = "update_check_frequency"
    private const val LAST_UPDATE_CHECK_TIME = "last_update_check_time"
    private const val SKIPPED_UPDATE_VERSION = "skipped_update_version"
    private const val USE_GITHUB_PROXY = "use_github_proxy"

    private val _updateCheckFrequency = MutableStateFlow(UpdateCheckFrequency.DAILY)
    val updateCheckFrequency = _updateCheckFrequency.asStateFlow()

    private val _useGithubProxy = MutableStateFlow(true)
    val useGithubProxy = _useGithubProxy.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun ensureInit(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val appContext = context.applicationContext ?: context
            val sp = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            _updateCheckFrequency.value =
                UpdateCheckFrequency.fromStoredValue(sp.getString(UPDATE_CHECK_FREQUENCY, null))
            _useGithubProxy.value = sp.getBoolean(USE_GITHUB_PROXY, true)
            prefs = sp
        }
    }

    fun setUpdateCheckFrequency(frequency: UpdateCheckFrequency) {
        prefs?.edit()?.putString(UPDATE_CHECK_FREQUENCY, frequency.name)?.apply()
        _updateCheckFrequency.value = frequency
    }

    fun setUseGithubProxy(enabled: Boolean) {
        prefs?.edit()?.putBoolean(USE_GITHUB_PROXY, enabled)?.apply()
        _useGithubProxy.value = enabled
    }

    var lastUpdateCheckTime: Long
        get() = prefs?.getLong(LAST_UPDATE_CHECK_TIME, 0L) ?: 0L
        set(value) {
            prefs?.edit()?.putLong(LAST_UPDATE_CHECK_TIME, value)?.apply()
        }

    var skippedUpdateVersion: String?
        get() = prefs?.getString(SKIPPED_UPDATE_VERSION, null)
        set(value) {
            prefs?.edit()?.putString(SKIPPED_UPDATE_VERSION, value)?.apply()
        }
}
