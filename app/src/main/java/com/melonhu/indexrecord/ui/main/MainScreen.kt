package com.melonhu.indexrecord.ui.main

import com.melonhu.indexrecord.ui.home.HomeScreen
import com.melonhu.indexrecord.ui.settings.SettingsScreen
import com.melonhu.indexrecord.ui.stats.StatsScreen
import com.melonhu.indexrecord.ui.util.rememberAppHapticFeedback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.melonhu.indexrecord.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val saveableStateHolder = rememberSaveableStateHolder()
    val appHaptics = rememberAppHapticFeedback()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val navItemColors = NavigationBarItemDefaults.colors(
                selectedTextColor = MaterialTheme.colorScheme.onSurface
            )
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = {
                        if (selectedTab != 0) {
                            appHaptics.tabSwitch()
                            selectedTab = 0
                        }
                    },
                    colors = navItemColors,
                    icon = { androidx.compose.material3.Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home)) },
                    label = { androidx.compose.material3.Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        if (selectedTab != 1) {
                            appHaptics.tabSwitch()
                            selectedTab = 1
                        }
                    },
                    colors = navItemColors,
                    icon = { androidx.compose.material3.Icon(Icons.Default.BarChart, contentDescription = stringResource(R.string.nav_stats)) },
                    label = { androidx.compose.material3.Text(stringResource(R.string.nav_stats)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        if (selectedTab != 2) {
                            appHaptics.tabSwitch()
                            selectedTab = 2
                        }
                    },
                    colors = navItemColors,
                    icon = { androidx.compose.material3.Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings)) },
                    label = { androidx.compose.material3.Text(stringResource(R.string.nav_settings)) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            saveableStateHolder.SaveableStateProvider(key = selectedTab) {
                when (selectedTab) {
                    0 -> HomeScreen()
                    1 -> StatsScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }
}
