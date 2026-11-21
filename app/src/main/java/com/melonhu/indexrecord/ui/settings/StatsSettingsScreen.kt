package com.melonhu.indexrecord.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.viewmodel.SettingsViewModel

@Composable
internal fun StatsSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val includeRepliesAndCommentsInStats by viewModel.includeRepliesAndCommentsInStats.collectAsState()

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_section_stats),
        onBack = onBack
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_include_replies_stats),
            description = stringResource(R.string.settings_include_replies_stats_desc),
            checked = includeRepliesAndCommentsInStats,
            onCheckedChange = viewModel::setIncludeRepliesAndCommentsInStats
        )
    }
}
