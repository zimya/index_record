package com.melonhu.indexrecord.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateCheckState {
    object Idle : UpdateCheckState
    object Checking : UpdateCheckState
    object UpToDate : UpdateCheckState
    data class Available(
        val info: AppUpdateInfo,
        val downloadProgress: Int? = null
    ) : UpdateCheckState
    data class Failed(val message: String) : UpdateCheckState
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    init {
        UpdateSettings.ensureInit(application)
    }

    private val _updateCheckState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateCheckState: StateFlow<UpdateCheckState> = _updateCheckState.asStateFlow()

    private val _pendingInstallApk = MutableStateFlow<File?>(null)
    val pendingInstallApk: StateFlow<File?> = _pendingInstallApk.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    val updateCheckFrequency: StateFlow<UpdateCheckFrequency> = UpdateSettings.updateCheckFrequency
    val useGithubProxy: StateFlow<Boolean> = UpdateSettings.useGithubProxy

    fun setUpdateCheckFrequency(frequency: UpdateCheckFrequency) {
        UpdateSettings.setUpdateCheckFrequency(frequency)
    }

    fun setUseGithubProxy(enabled: Boolean) {
        UpdateSettings.setUseGithubProxy(enabled)
    }

    fun checkForUpdate(silent: Boolean = false) {
        if (_updateCheckState.value is UpdateCheckState.Checking) return
        if (silent) {
            val frequency = UpdateSettings.updateCheckFrequency.value
            if (frequency == UpdateCheckFrequency.OFF) return
            if (frequency.intervalMs > 0L) {
                val elapsed = System.currentTimeMillis() - UpdateSettings.lastUpdateCheckTime
                if (elapsed < frequency.intervalMs) return
            }
        }
        _updateCheckState.value = UpdateCheckState.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdateChecker.checkForUpdate() }
            }
            _updateCheckState.value = result.fold(
                onSuccess = { info ->
                    UpdateSettings.lastUpdateCheckTime = System.currentTimeMillis()
                    if (info != null) {
                        if (silent && UpdateSettings.skippedUpdateVersion == info.versionKey) {
                            UpdateCheckState.Idle
                        } else {
                            UpdateCheckState.Available(info)
                        }
                    } else {
                        if (silent) UpdateCheckState.Idle else UpdateCheckState.UpToDate
                    }
                },
                onFailure = { error ->
                    if (silent) {
                        UpdateCheckState.Idle
                    } else {
                        UpdateCheckState.Failed(error.message ?: error.javaClass.simpleName)
                    }
                }
            )
        }
    }

    fun skipCurrentUpdate() {
        val current = _updateCheckState.value as? UpdateCheckState.Available ?: return
        UpdateSettings.skippedUpdateVersion = current.info.versionKey
        _updateCheckState.value = UpdateCheckState.Idle
    }

    fun downloadUpdate() {
        val current = _updateCheckState.value as? UpdateCheckState.Available ?: return
        if (current.downloadProgress != null) return
        val info = current.info
        _updateCheckState.value = current.copy(downloadProgress = 0)
        viewModelScope.launch {
            val downloadUrl = if (UpdateSettings.useGithubProxy.value) {
                info.proxyDownloadUrl
            } else {
                info.directDownloadUrl
            }
            val targetFile = File(getApplication<Application>().cacheDir, "updates/${info.assetFileName}")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppUpdateChecker.downloadApk(downloadUrl, targetFile) { percent ->
                        _updateCheckState.value = UpdateCheckState.Available(info, downloadProgress = percent)
                    }
                }
            }
            result.fold(
                onSuccess = { file ->
                    _updateCheckState.value = UpdateCheckState.Available(info, downloadProgress = null)
                    _pendingInstallApk.value = file
                },
                onFailure = { error ->
                    _updateCheckState.value = UpdateCheckState.Available(info, downloadProgress = null)
                    _downloadError.value = error.message ?: error.javaClass.simpleName
                }
            )
        }
    }

    fun consumePendingInstallApk() {
        _pendingInstallApk.value = null
    }

    fun consumeDownloadError() {
        _downloadError.value = null
    }

    fun dismissUpdateDialog() {
        _updateCheckState.value = UpdateCheckState.Idle
    }
}
