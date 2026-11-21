package com.melonhu.indexrecord.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.settings.AppLanguage
import com.melonhu.indexrecord.ui.theme.ThemeColor
import com.melonhu.indexrecord.ui.theme.ThemeMode
import com.melonhu.indexrecord.viewmodel.SettingsViewModel

@Composable
internal fun AppearanceSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val monetDynamicColor by viewModel.monetDynamicColor.collectAsState()
    val themeColor by viewModel.themeColor.collectAsState()
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
        SettingsSwitchRow(
            title = stringResource(R.string.settings_monet_dynamic_color),
            description = stringResource(R.string.settings_monet_dynamic_color_desc),
            checked = monetDynamicColor,
            onCheckedChange = viewModel::setMonetDynamicColor
        )
        SettingsDropdownRow(
            title = stringResource(R.string.settings_theme_color),
            selected = themeColor,
            options = ThemeColor.entries,
            enabled = !monetDynamicColor,
            label = {
                when (it) {
                    ThemeColor.INDEX_BLUE -> stringResource(R.string.settings_theme_color_index_blue)
                    ThemeColor.INDEX_GREEN -> stringResource(R.string.settings_theme_color_index_green)
                    ThemeColor.PINK -> stringResource(R.string.settings_theme_color_pink)
                    ThemeColor.BLUE -> stringResource(R.string.settings_theme_color_blue)
                    ThemeColor.PURPLE -> stringResource(R.string.settings_theme_color_purple)
                    ThemeColor.ORANGE -> stringResource(R.string.settings_theme_color_orange)
                    ThemeColor.MINT_GREEN -> stringResource(R.string.settings_theme_color_mint_green)
                    ThemeColor.CYAN -> stringResource(R.string.settings_theme_color_cyan)
                    ThemeColor.ICE_BLUE -> stringResource(R.string.settings_theme_color_ice_blue)
                }
            },
            optionLeading = { ColorSwatchCircle(it.swatchColor) },
            selectedTrailing = { ColorSwatchCircle(it.swatchColor) },
            onSelect = viewModel::setThemeColor
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
