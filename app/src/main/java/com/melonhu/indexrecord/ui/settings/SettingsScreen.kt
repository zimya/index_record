package com.melonhu.indexrecord.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.R
import com.melonhu.indexrecord.ui.components.SyncPullRefreshContainer
import com.melonhu.indexrecord.update.UpdateSettingsScreen
import com.melonhu.indexrecord.viewmodel.SettingsViewModel

private enum class SettingsSubPage(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val icon: ImageVector
) {
    APPEARANCE(
        titleRes = R.string.settings_section_appearance,
        subtitleRes = R.string.settings_section_appearance_desc,
        icon = Icons.Outlined.Palette
    ),
    STATS(
        titleRes = R.string.settings_section_stats,
        subtitleRes = R.string.settings_section_stats_desc,
        icon = Icons.Default.BarChart
    ),
    RECORD(
        titleRes = R.string.settings_section_record,
        subtitleRes = R.string.settings_section_record_desc,
        icon = Icons.Outlined.Tune
    ),
    UPDATE(
        titleRes = R.string.settings_section_update,
        subtitleRes = R.string.settings_section_update_desc,
        icon = Icons.Outlined.SystemUpdate
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val lsposedStatus by viewModel.lsposedStatus.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    var selectedSubPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    var lastSubPage by remember { mutableStateOf<SettingsSubPage?>(null) }

    LaunchedEffect(selectedSubPage) {
        if (selectedSubPage != null) {
            lastSubPage = selectedSubPage
        }
    }

    BackHandler(enabled = selectedSubPage != null) {
        selectedSubPage = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsSubPage.entries.forEach { subPage ->
                        SettingsCategoryRow(
                            title = stringResource(subPage.titleRes),
                            subtitle = stringResource(subPage.subtitleRes),
                            icon = subPage.icon,
                            onClick = { selectedSubPage = subPage }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = selectedSubPage != null,
            enter = slideInHorizontally(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                initialOffsetX = { it }
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutHorizontally(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                targetOffsetX = { it }
            ) + fadeOut(animationSpec = tween(300))
        ) {
            val activeSubPage = selectedSubPage ?: lastSubPage
            if (activeSubPage != null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    shadowElevation = 8.dp
                ) {
                    val onBack = { selectedSubPage = null }
                    when (activeSubPage) {
                        SettingsSubPage.APPEARANCE -> AppearanceSettingsScreen(viewModel, onBack)
                        SettingsSubPage.STATS -> StatsSettingsScreen(viewModel, onBack)
                        SettingsSubPage.RECORD -> RecordSettingsScreen(viewModel, onBack)
                        SettingsSubPage.UPDATE -> UpdateSettingsScreen(onBack = onBack)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
