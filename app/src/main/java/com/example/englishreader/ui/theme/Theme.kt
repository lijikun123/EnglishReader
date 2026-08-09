package com.example.englishreader.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.englishreader.data.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = Color(0xFF001B3D),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF00315B),
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = LightPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
)

// Sepia 基于浅色方案，换成暖色背景，适合长时间阅读。
private val SepiaColors = lightColorScheme(
    primary = SepiaPrimary,
    onPrimary = Color.White,
    primaryContainer = SepiaPrimaryContainer,
    onPrimaryContainer = Color(0xFF2E1A07),
    background = SepiaBackground,
    onBackground = SepiaOnSurface,
    surface = SepiaSurface,
    onSurface = SepiaOnSurface,
    surfaceVariant = SepiaSurfaceVariant,
    onSurfaceVariant = SepiaOnSurfaceVariant,
)

@Suppress("DEPRECATION") // window.statusBarColor: keep theme-matched status bar on this minSdk
@Composable
fun EnglishReaderTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeMode) {
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.SEPIA -> SepiaColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                themeMode != ThemeMode.DARK
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
