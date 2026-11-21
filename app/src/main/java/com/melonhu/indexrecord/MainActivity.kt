package com.melonhu.indexrecord

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.melonhu.indexrecord.settings.LocaleHelper
import com.melonhu.indexrecord.ui.main.MainScreen
import com.melonhu.indexrecord.ui.theme.IndexRecordTheme
import com.melonhu.indexrecord.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val themeMode by settingsViewModel.themeMode.collectAsState()
            val monetDynamicColor by settingsViewModel.monetDynamicColor.collectAsState()
            val themeColor by settingsViewModel.themeColor.collectAsState()
            val currentLanguage by settingsViewModel.appLanguage.collectAsState()
            val baseContext = LocalContext.current
            val localizedContext = remember(currentLanguage, baseContext) {
                LocaleHelper.getLocalizedContext(baseContext, currentLanguage)
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration
            ) {
                IndexRecordTheme(
                    themeMode = themeMode,
                    themeColor = themeColor,
                    dynamicColor = monetDynamicColor
                ) {
                    // Surface 是背景容器
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen()
                    }
                }
            }
        }
    }
}
