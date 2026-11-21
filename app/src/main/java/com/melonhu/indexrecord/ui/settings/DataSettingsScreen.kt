package com.melonhu.indexrecord.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.logic.BackupErrorReason
import com.melonhu.indexrecord.viewmodel.DataBackupError
import com.melonhu.indexrecord.viewmodel.DataBackupOperationState
import com.melonhu.indexrecord.viewmodel.DataBackupViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ZIP_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream"
)

@Composable
internal fun DataSettingsScreen(
    onBack: () -> Unit,
    viewModel: DataBackupViewModel = viewModel()
) {
    val overview by viewModel.overview.collectAsState()
    val includeRawData by viewModel.includeRawData.collectAsState()
    val operationState by viewModel.operationState.collectAsState()
    val isBusy = operationState.isBusy
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshOverview()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportToUri(uri)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importFromUri(uri)
        }
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_section_data),
        onBack = onBack
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.data_overview_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                val summary = overview
                if (summary == null) {
                    Text(
                        text = stringResource(R.string.data_overview_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.data_overview_counts,
                            summary.recordCount,
                            summary.snapshotCount
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (summary.rawDataCount > 0L) {
                        Text(
                            text = stringResource(R.string.data_overview_pending_raw, summary.rawDataCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        if (isBusy) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(8.dp))

        DataActionRow(
            title = stringResource(R.string.data_export_title),
            description = if (operationState is DataBackupOperationState.Exporting) {
                stringResource(R.string.data_exporting)
            } else {
                stringResource(R.string.data_export_desc)
            },
            enabled = !isBusy,
            onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                exportLauncher.launch("index_record_backup_$stamp.zip")
            }
        )

        DataActionRow(
            title = stringResource(R.string.data_import_title),
            description = if (operationState is DataBackupOperationState.Importing) {
                stringResource(R.string.data_importing)
            } else {
                stringResource(R.string.data_import_desc)
            },
            enabled = !isBusy,
            onClick = { importLauncher.launch(ZIP_MIME_TYPES) }
        )

        SettingsSwitchRow(
            title = stringResource(R.string.data_include_raw_title),
            description = stringResource(R.string.data_include_raw_desc),
            checked = includeRawData,
            onCheckedChange = {
                if (!isBusy) viewModel.setIncludeRawData(it)
            }
        )

        Spacer(modifier = Modifier.height(24.dp))
        DangerWarningActionCard(
            title = stringResource(R.string.data_clear_title),
            description = if (operationState is DataBackupOperationState.Clearing) {
                stringResource(R.string.data_clearing)
            } else {
                stringResource(R.string.data_clear_desc)
            },
            enabled = !isBusy,
            onClick = { showClearConfirmDialog = true }
        )
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showClearConfirmDialog) {
        val summary = overview
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.data_clear_confirm_title),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (summary != null) {
                        stringResource(
                            R.string.data_clear_confirm_message,
                            summary.recordCount,
                            summary.snapshotCount
                        )
                    } else {
                        stringResource(R.string.data_clear_confirm_message_generic)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        viewModel.clearDatabase()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = stringResource(R.string.data_clear_confirm_action),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                Button(onClick = { showClearConfirmDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    when (val state = operationState) {
        is DataBackupOperationState.ExportSuccess -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissResult,
                title = { Text(stringResource(R.string.data_export_success_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.data_export_success_message,
                            state.result.recordCount,
                            state.result.snapshotCount,
                            state.result.rawDataCount
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissResult) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            )
        }
        is DataBackupOperationState.ImportSuccess -> {
            val baseMessage = stringResource(
                R.string.data_import_success_message,
                state.result.recordsInserted,
                state.result.recordsMerged,
                state.result.recordsSkipped,
                state.result.snapshotsInserted,
                state.result.snapshotsSkipped
            )
            val rawSummary = if (state.result.rawDataInserted > 0 || state.result.rawDataSkipped > 0) {
                stringResource(
                    R.string.data_import_raw_summary,
                    state.result.rawDataInserted,
                    state.result.rawDataSkipped
                )
            } else {
                ""
            }
            AlertDialog(
                onDismissRequest = viewModel::dismissResult,
                title = { Text(stringResource(R.string.data_import_success_title)) },
                text = { Text(baseMessage + rawSummary) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissResult) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            )
        }
        is DataBackupOperationState.ClearSuccess -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissResult,
                title = { Text(stringResource(R.string.data_clear_success_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.data_clear_success_message,
                            state.result.recordCount,
                            state.result.snapshotCount,
                            state.result.rawDataCount
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissResult) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            )
        }
        is DataBackupOperationState.Error -> {
            val errorText = when (val err = state.error) {
                is DataBackupError.Backup -> when (err.reason) {
                    BackupErrorReason.INVALID_ARCHIVE -> stringResource(R.string.data_error_invalid_archive)
                    BackupErrorReason.UNSUPPORTED_VERSION -> stringResource(R.string.data_error_unsupported_version)
                    BackupErrorReason.CORRUPT_DATA -> stringResource(R.string.data_error_corrupt_data)
                }
                is DataBackupError.Io -> stringResource(R.string.data_error_io, err.message)
            }
            AlertDialog(
                onDismissRequest = viewModel::dismissResult,
                title = { Text(stringResource(R.string.data_operation_failed_title)) },
                text = { Text(errorText) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissResult) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            )
        }
        else -> Unit
    }
}

@Composable
private fun DataActionRow(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun DangerWarningActionCard(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
