package com.melonhu.indexrecord.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.settings.AppLanguage
import com.melonhu.indexrecord.settings.RedditRecordMode
import com.melonhu.indexrecord.settings.TweetRecordMode
import com.melonhu.indexrecord.settings.WeiboRecordMode
import com.melonhu.indexrecord.settings.ThreadsRecordMode
import com.melonhu.indexrecord.ui.components.SyncPullRefreshContainer
import com.melonhu.indexrecord.ui.theme.ThemeMode
import com.melonhu.indexrecord.ui.util.rememberAppHapticFeedback
import com.melonhu.indexrecord.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val themeMode by viewModel.themeMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val tweetRecordMode by viewModel.tweetRecordMode.collectAsState()
    val weiboRecordMode by viewModel.weiboRecordMode.collectAsState()
    val threadsRecordMode by viewModel.threadsRecordMode.collectAsState()
    val redditRecordMode by viewModel.redditRecordMode.collectAsState()
    val collapseMultipleItems by viewModel.collapseMultipleItems.collectAsState()
    val includeRepliesAndCommentsInStats by viewModel.includeRepliesAndCommentsInStats.collectAsState()
    val lsposedStatus by viewModel.lsposedStatus.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        }
    ) { padding ->
        SyncPullRefreshContainer(
            rawPendingCount = 0L,
            syncProgress = syncProgress,
            onRefresh = { viewModel.refreshData() },
            modifier = Modifier.padding(padding),
            showSyncStatusBanner = false,
            showResultHint = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
            Spacer(modifier = Modifier.height(12.dp))
            LsposedStatusCard(status = lsposedStatus)
            Spacer(modifier = Modifier.height(12.dp))
            SettingsSectionTitle(stringResource(R.string.settings_section_appearance))
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

            Spacer(modifier = Modifier.height(24.dp))
            SettingsSectionTitle(stringResource(R.string.settings_section_stats))
            SettingsSwitchRow(
                title = stringResource(R.string.settings_include_replies_stats),
                description = stringResource(R.string.settings_include_replies_stats_desc),
                checked = includeRepliesAndCommentsInStats,
                onCheckedChange = viewModel::setIncludeRepliesAndCommentsInStats
            )

            Spacer(modifier = Modifier.height(24.dp))
            SettingsSectionTitle(stringResource(R.string.settings_section_record))
            val modePushLabel = stringResource(R.string.settings_mode_push)
            val modeReadLabel = stringResource(R.string.settings_mode_read)
            SettingsDropdownRow(
                title = stringResource(R.string.settings_tweet_record_mode),
                selected = tweetRecordMode,
                options = listOf(TweetRecordMode.PUSH, TweetRecordMode.READ),
                label = { if (it == TweetRecordMode.PUSH) modePushLabel else modeReadLabel },
                onSelect = viewModel::setTweetRecordMode
            )
            SettingsDropdownRow(
                title = stringResource(R.string.settings_weibo_record_mode),
                selected = weiboRecordMode,
                options = listOf(WeiboRecordMode.PUSH, WeiboRecordMode.READ),
                label = { if (it == WeiboRecordMode.PUSH) modePushLabel else modeReadLabel },
                onSelect = viewModel::setWeiboRecordMode
            )
            SettingsDropdownRow(
                title = stringResource(R.string.settings_threads_record_mode),
                selected = threadsRecordMode,
                options = listOf(ThreadsRecordMode.PUSH, ThreadsRecordMode.READ),
                label = { if (it == ThreadsRecordMode.PUSH) modePushLabel else modeReadLabel },
                onSelect = viewModel::setThreadsRecordMode
            )
            SettingsDropdownRow(
                title = stringResource(R.string.settings_reddit_record_mode),
                selected = redditRecordMode,
                options = listOf(RedditRecordMode.PUSH, RedditRecordMode.READ),
                label = { if (it == RedditRecordMode.PUSH) modePushLabel else modeReadLabel },
                onSelect = viewModel::setRedditRecordMode
            )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    val appHaptics = rememberAppHapticFeedback()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { newValue ->
                    appHaptics.toggle(newValue)
                    onCheckedChange(newValue)
                }
            )
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (description != null) Modifier.padding(end = 12.dp) else Modifier)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun <T> SettingsDropdownRow(
    title: String,
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = label(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    modifier = Modifier.background(
                        if (option == selected) MaterialTheme.colorScheme.surfaceVariant
                        else Color.Transparent
                    )
                )
            }
        }
    }
}
