package com.melonhu.indexrecord.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.settings.AppLanguage
import com.melonhu.indexrecord.ui.theme.ThemeMode
import com.melonhu.indexrecord.viewmodel.SettingsViewModel

@Composable
internal fun AppearanceSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val collapseMultipleItems by viewModel.collapseMultipleItems.collectAsState()

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_section_appearance),
        onBack = onBack
    ) {
        SettingsDropdownRow(
            title = stringResource(R.string.settings_theme),
            selected = themeMode,
            options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
            label = {
                when (it) {
                    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                }
            },
            onSelect = viewModel::setThemeMode
        )
        SettingsDropdownRow(
            title = stringResource(R.string.settings_language),
            selected = appLanguage,
            options = listOf(
                AppLanguage.SYSTEM,
                AppLanguage.SIMPLIFIED_CHINESE,
                AppLanguage.TRADITIONAL_CHINESE,
                AppLanguage.ENGLISH
            ),
            label = {
                when (it) {
                    AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
                    AppLanguage.SIMPLIFIED_CHINESE -> stringResource(R.string.settings_language_zh_cn)
                    AppLanguage.TRADITIONAL_CHINESE -> stringResource(R.string.settings_language_zh_tw)
                    AppLanguage.ENGLISH -> stringResource(R.string.settings_language_en)
                }
            },
            onSelect = viewModel::setAppLanguage
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_collapse_multiple),
            checked = collapseMultipleItems,
            onCheckedChange = viewModel::setCollapseMultipleItems
        )
    }
}
