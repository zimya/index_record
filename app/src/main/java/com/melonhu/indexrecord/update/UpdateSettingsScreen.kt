package com.melonhu.indexrecord.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.BuildConfig
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.ui.settings.SettingsDropdownRow
import com.melonhu.indexrecord.ui.settings.SettingsSubPageScaffold
import com.melonhu.indexrecord.ui.settings.SettingsSwitchRow

@Composable
internal fun UpdateSettingsScreen(
    onBack: () -> Unit,
    viewModel: AppUpdateViewModel = viewModel()
) {
    val updateCheckState by viewModel.updateCheckState.collectAsState()
    val updateCheckFrequency by viewModel.updateCheckFrequency.collectAsState()
    val useGithubProxy by viewModel.useGithubProxy.collectAsState()

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_section_update),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { viewModel.checkForUpdate(silent = false) }
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_check_update),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (updateCheckState is UpdateCheckState.Checking) {
                    stringResource(R.string.settings_checking_update)
                } else {
                    stringResource(R.string.settings_check_update_desc, BuildConfig.VERSION_NAME)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        SettingsDropdownRow(
            title = stringResource(R.string.settings_auto_update),
            selected = updateCheckFrequency,
            options = UpdateCheckFrequency.entries,
            label = { freq ->
                when (freq) {
                    UpdateCheckFrequency.EVERY_LAUNCH -> stringResource(R.string.update_freq_every_launch)
                    UpdateCheckFrequency.DAILY -> stringResource(R.string.update_freq_daily)
                    UpdateCheckFrequency.WEEKLY -> stringResource(R.string.update_freq_weekly)
                    UpdateCheckFrequency.OFF -> stringResource(R.string.update_freq_off)
                }
            },
            onSelect = viewModel::setUpdateCheckFrequency
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_use_github_proxy),
            checked = useGithubProxy,
            onCheckedChange = viewModel::setUseGithubProxy
        )
    }
}
