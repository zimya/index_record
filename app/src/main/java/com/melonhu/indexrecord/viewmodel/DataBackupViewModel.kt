package com.melonhu.indexrecord.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.melonhu.indexrecord.BuildConfig
import com.melonhu.indexrecord.logic.BackupErrorReason
import com.melonhu.indexrecord.logic.BackupException
import com.melonhu.indexrecord.logic.DataProcessor
import com.melonhu.indexrecord.logic.DatabaseBackupManager
import com.melonhu.indexrecord.logic.DatabaseClearResult
import com.melonhu.indexrecord.logic.DatabaseExportResult
import com.melonhu.indexrecord.logic.DatabaseImportResult
import com.melonhu.indexrecord.logic.DatabaseOverview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal sealed interface DataBackupError {
    data class Backup(val reason: BackupErrorReason) : DataBackupError
    data class Io(val message: String) : DataBackupError
}

internal sealed interface DataBackupOperationState {
    object Idle : DataBackupOperationState
    object Exporting : DataBackupOperationState
    object Importing : DataBackupOperationState
    object Clearing : DataBackupOperationState
    data class ExportSuccess(val result: DatabaseExportResult) : DataBackupOperationState
    data class ImportSuccess(val result: DatabaseImportResult) : DataBackupOperationState
    data class ClearSuccess(val result: DatabaseClearResult) : DataBackupOperationState
    data class Error(val error: DataBackupError) : DataBackupOperationState

    val isBusy: Boolean get() = this is Exporting || this is Importing || this is Clearing
}

internal class DataBackupViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = DatabaseBackupManager()

    private val _overview = MutableStateFlow<DatabaseOverview?>(null)
    val overview: StateFlow<DatabaseOverview?> = _overview.asStateFlow()

    private val _includeRawData = MutableStateFlow(false)
    val includeRawData: StateFlow<Boolean> = _includeRawData.asStateFlow()

    private val _operationState = MutableStateFlow<DataBackupOperationState>(DataBackupOperationState.Idle)
    val operationState: StateFlow<DataBackupOperationState> = _operationState.asStateFlow()

    init {
        refreshOverview()
    }

    fun setIncludeRawData(enabled: Boolean) {
        _includeRawData.value = enabled
    }

    fun refreshOverview() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { manager.overview() }.getOrNull()
            }
            if (loaded != null) {
                _overview.value = loaded
            }
        }
    }

    fun exportToUri(uri: Uri) {
        if (_operationState.value.isBusy) return
        val includeRaw = _includeRawData.value
        _operationState.value = DataBackupOperationState.Exporting
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val output = resolver.openOutputStream(uri)
                        ?: throw IOException("Unable to open target file")
                    output.use { stream ->
                        manager.exportZip(
                            outputStream = stream,
                            includeRawData = includeRaw,
                            appVersionName = BuildConfig.VERSION_NAME,
                            appVersionCode = BuildConfig.VERSION_CODE
                        )
                    }
                }
            }
            outcome.fold(
                onSuccess = { result ->
                    _operationState.value = DataBackupOperationState.ExportSuccess(result)
                    refreshOverview()
                },
                onFailure = { error ->
                    _operationState.value = DataBackupOperationState.Error(mapError(error))
                }
            )
        }
    }

    fun importFromUri(uri: Uri) {
        if (_operationState.value.isBusy) return
        _operationState.value = DataBackupOperationState.Importing
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    manager.importZip {
                        resolver.openInputStream(uri)
                            ?: throw IOException("Unable to open selected file")
                    }
                }
            }
            outcome.fold(
                onSuccess = { result ->
                    if (result.rawDataInserted > 0) {
                        DataProcessor.requestProcessing()
                    }
                    _operationState.value = DataBackupOperationState.ImportSuccess(result)
                    refreshOverview()
                },
                onFailure = { error ->
                    _operationState.value = DataBackupOperationState.Error(mapError(error))
                }
            )
        }
    }

    fun clearDatabase() {
        if (_operationState.value.isBusy) return
        _operationState.value = DataBackupOperationState.Clearing
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { manager.clearDatabase() }
            }
            outcome.fold(
                onSuccess = { result ->
                    _operationState.value = DataBackupOperationState.ClearSuccess(result)
                    refreshOverview()
                },
                onFailure = { error ->
                    _operationState.value = DataBackupOperationState.Error(mapError(error))
                }
            )
        }
    }

    fun dismissResult() {
        if (!_operationState.value.isBusy) {
            _operationState.value = DataBackupOperationState.Idle
        }
    }

    private fun mapError(error: Throwable): DataBackupError = when (error) {
        is BackupException -> DataBackupError.Backup(error.reason)
        else -> DataBackupError.Io(error.message ?: error.javaClass.simpleName)
    }
}
