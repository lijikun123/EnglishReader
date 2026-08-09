package com.example.englishreader.ui

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.englishreader.ui.navigation.AppNavHost
import com.example.englishreader.ui.settings.SettingsViewModel
import com.example.englishreader.ui.theme.EnglishReaderTheme

/**
 * 应用根 Composable：
 *  - 从设置中读取主题（light / dark / sepia）并应用到整个 App
 *  - 根据 WindowSizeClass 把自适应导航交给 [AppNavHost]
 */
@Composable
fun AppRoot(windowSizeClass: WindowSizeClass) {
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val readingSettings by settingsViewModel.readingSettings.collectAsStateWithLifecycle()

    EnglishReaderTheme(themeMode = readingSettings.themeMode) {
        AppNavHost(windowSizeClass = windowSizeClass)
    }
}
