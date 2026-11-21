package com.melonhu.indexrecord.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.BuildConfig
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.markdown.MarkdownContentFormat
import com.melonhu.indexrecord.markdown.RenderMarkdownText

@Composable
fun AppUpdateDialogHost(viewModel: AppUpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val updateCheckState by viewModel.updateCheckState.collectAsState()
    val pendingInstallApk by viewModel.pendingInstallApk.collectAsState()
    val downloadError by viewModel.downloadError.collectAsState()
    val useGithubProxy by viewModel.useGithubProxy.collectAsState()

    val requestInstallPermission = {
        Toast.makeText(
            context,
            context.getString(R.string.update_install_permission_required),
            Toast.LENGTH_LONG
        ).show()
        val permIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(permIntent)
    }

    LaunchedEffect(updateCheckState) {
        when (val state = updateCheckState) {
            is UpdateCheckState.UpToDate -> {
                Toast.makeText(context, context.getString(R.string.update_up_to_date), Toast.LENGTH_SHORT).show()
                viewModel.dismissUpdateDialog()
            }
            is UpdateCheckState.Failed -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.update_check_failed, state.message),
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.dismissUpdateDialog()
            }
            else -> Unit
        }
    }

    LaunchedEffect(downloadError) {
        val message = downloadError ?: return@LaunchedEffect
        Toast.makeText(
            context,
            context.getString(R.string.update_download_failed, message),
            Toast.LENGTH_SHORT
        ).show()
        viewModel.consumeDownloadError()
    }

    LaunchedEffect(pendingInstallApk) {
        val apkFile = pendingInstallApk ?: return@LaunchedEffect
        viewModel.consumePendingInstallApk()
        if (!context.packageManager.canRequestPackageInstalls()) {
            requestInstallPermission()
            return@LaunchedEffect
        }
        val apkUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.dismissUpdateDialog()
        context.startActivity(installIntent)
    }

    val availableState = updateCheckState as? UpdateCheckState.Available ?: return
    val info = availableState.info
    val progress = availableState.downloadProgress
    val isDownloading = progress != null

    AlertDialog(
        onDismissRequest = { if (!isDownloading) viewModel.dismissUpdateDialog() },
        title = { Text(stringResource(R.string.update_available_title, info.versionName)) },
        text = {
            RenderMarkdownText(
                markdown = info.changelog.ifBlank { stringResource(R.string.update_no_changelog) },
                contentFormat = MarkdownContentFormat.MARKDOWN,
                modifier = Modifier.heightIn(max = 320.dp)
            )
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !isDownloading,
                    onClick = { viewModel.skipCurrentUpdate() }
                ) {
                    Text(stringResource(R.string.update_skip_version))
                }
                TextButton(
                    enabled = !isDownloading,
                    onClick = {
                        viewModel.dismissUpdateDialog()
                        val manualUrl = if (useGithubProxy) info.proxyDownloadUrl else info.directDownloadUrl
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(manualUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                ) {
                    Text(stringResource(R.string.update_manual))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isDownloading,
                onClick = {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        requestInstallPermission()
                    } else {
                        viewModel.downloadUpdate()
                    }
                }
            ) {
                val label = when {
                    progress == null -> stringResource(R.string.update_auto)
                    progress >= 0 -> "$progress%"
                    else -> stringResource(R.string.update_downloading)
                }
                Text(label)
            }
        }
    )
}
