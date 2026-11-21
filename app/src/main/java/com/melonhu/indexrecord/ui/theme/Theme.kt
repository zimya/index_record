package com.melonhu.indexrecord.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

enum class ThemeColor(val swatchColor: Color) {
    INDEX_BLUE(IndexBlue),
    INDEX_GREEN(Color(0xFF2E9E6B)),
    PINK(Color(0xFFEC709B)),
    BLUE(Color(0xFF3B82F6)),
    PURPLE(Color(0xFF8B5CF6)),
    ORANGE(Color(0xFFF97316)),
    MINT_GREEN(Color(0xFF3EB489)),
    CYAN(Color(0xFF06B6D4)),
    ICE_BLUE(Color(0xFF7DD3FC))
}

private data class ThemePalette(
    val lightPrimary: Color,
    val lightPrimaryContainer: Color,
    val lightOnPrimaryContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkOnPrimaryContainer: Color
)

private fun ThemeColor.toPalette(): ThemePalette = when (this) {
    ThemeColor.INDEX_BLUE -> ThemePalette(
        lightPrimary = IndexDarkBlue,
        lightPrimaryContainer = Color(0xFFD6EAF8),
        lightOnPrimaryContainer = Color(0xFF0F3554),
        darkPrimary = IndexBlue,
        darkOnPrimary = Color(0xFF0B2D42),
        darkPrimaryContainer = Color(0xFF1F4B6E),
        darkOnPrimaryContainer = Color(0xFFD6EAF8)
    )
    ThemeColor.INDEX_GREEN -> ThemePalette(
        lightPrimary = Color(0xFF1F7A52),
        lightPrimaryContainer = Color(0xFFD0F2E0),
        lightOnPrimaryContainer = Color(0xFF0B3823),
        darkPrimary = Color(0xFF6CD6A4),
        darkOnPrimary = Color(0xFF0A3823),
        darkPrimaryContainer = Color(0xFF175237),
        darkOnPrimaryContainer = Color(0xFFD0F2E0)
    )
    ThemeColor.PINK -> ThemePalette(
        lightPrimary = Color(0xFFC24372),
        lightPrimaryContainer = Color(0xFFFFD9E4),
        lightOnPrimaryContainer = Color(0xFF4A0D25),
        darkPrimary = Color(0xFFFFB1C8),
        darkOnPrimary = Color(0xFF54122B),
        darkPrimaryContainer = Color(0xFF7A2444),
        darkOnPrimaryContainer = Color(0xFFFFD9E4)
    )
    ThemeColor.BLUE -> ThemePalette(
        lightPrimary = Color(0xFF1D63D8),
        lightPrimaryContainer = Color(0xFFD8E5FF),
        lightOnPrimaryContainer = Color(0xFF082966),
        darkPrimary = Color(0xFF9ECAFF),
        darkOnPrimary = Color(0xFF0A306B),
        darkPrimaryContainer = Color(0xFF154696),
        darkOnPrimaryContainer = Color(0xFFD8E5FF)
    )
    ThemeColor.PURPLE -> ThemePalette(
        lightPrimary = Color(0xFF6750A4),
        lightPrimaryContainer = Color(0xFFE9DDFF),
        lightOnPrimaryContainer = Color(0xFF22005D),
        darkPrimary = Color(0xFFD0BCFF),
        darkOnPrimary = Color(0xFF381E72),
        darkPrimaryContainer = Color(0xFF4F378B),
        darkOnPrimaryContainer = Color(0xFFE9DDFF)
    )
    ThemeColor.ORANGE -> ThemePalette(
        lightPrimary = Color(0xFFC25100),
        lightPrimaryContainer = Color(0xFFFFDCC8),
        lightOnPrimaryContainer = Color(0xFF401700),
        darkPrimary = Color(0xFFFFB68B),
        darkOnPrimary = Color(0xFF522000),
        darkPrimaryContainer = Color(0xFF783100),
        darkOnPrimaryContainer = Color(0xFFFFDCC8)
    )
    ThemeColor.MINT_GREEN -> ThemePalette(
        lightPrimary = Color(0xFF00795A),
        lightPrimaryContainer = Color(0xFFC8F7E5),
        lightOnPrimaryContainer = Color(0xFF00291D),
        darkPrimary = Color(0xFF72E2BA),
        darkOnPrimary = Color(0xFF003828),
        darkPrimaryContainer = Color(0xFF00523C),
        darkOnPrimaryContainer = Color(0xFFC8F7E5)
    )
    ThemeColor.CYAN -> ThemePalette(
        lightPrimary = Color(0xFF006879),
        lightPrimaryContainer = Color(0xFFB8EEFA),
        lightOnPrimaryContainer = Color(0xFF002026),
        darkPrimary = Color(0xFF67D8EE),
        darkOnPrimary = Color(0xFF003640),
        darkPrimaryContainer = Color(0xFF004E5C),
        darkOnPrimaryContainer = Color(0xFFB8EEFA)
    )
    ThemeColor.ICE_BLUE -> ThemePalette(
        lightPrimary = Color(0xFF006494),
        lightPrimaryContainer = Color(0xFFCDE9FF),
        lightOnPrimaryContainer = Color(0xFF001E30),
        darkPrimary = Color(0xFF99CBFF),
        darkOnPrimary = Color(0xFF003350),
        darkPrimaryContainer = Color(0xFF004B72),
        darkOnPrimaryContainer = Color(0xFFCDE9FF)
    )
}

val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun isAppInDarkTheme(): Boolean {
    return LocalIsDarkTheme.current || (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
}

private fun buildDarkColorScheme(themeColor: ThemeColor): ColorScheme {
    val palette = themeColor.toPalette()
    val darkSurface = Color(0xFF121316)
    return darkColorScheme(
        primary = palette.darkPrimary,
        onPrimary = palette.darkOnPrimary,
        primaryContainer = palette.darkPrimaryContainer,
        onPrimaryContainer = palette.darkOnPrimaryContainer,
        secondary = IndexGold,
        secondaryContainer = palette.darkPrimaryContainer,
        onSecondaryContainer = palette.darkOnPrimaryContainer,
        tertiary = IndexPink,
        background = darkSurface,
        surface = darkSurface,
        surfaceContainerLow = lerp(darkSurface, palette.darkPrimary, 0.05f),
        surfaceContainer = lerp(darkSurface, palette.darkPrimary, 0.08f),
        surfaceContainerHigh = lerp(darkSurface, palette.darkPrimary, 0.11f),
        surfaceContainerHighest = lerp(darkSurface, palette.darkPrimary, 0.14f),
        surfaceVariant = lerp(darkSurface, palette.darkPrimary, 0.14f)
    )
}

private fun buildLightColorScheme(themeColor: ThemeColor): ColorScheme {
    val palette = themeColor.toPalette()
    return lightColorScheme(
        primary = palette.lightPrimary,
        onPrimary = IndexWhite,
        primaryContainer = palette.lightPrimaryContainer,
        onPrimaryContainer = palette.lightOnPrimaryContainer,
        secondary = IndexGold,
        secondaryContainer = palette.lightPrimaryContainer,
        onSecondaryContainer = palette.lightOnPrimaryContainer,
        tertiary = IndexPink,
        background = IndexWhite,
        surface = IndexWhite,
        surfaceContainerLow = lerp(IndexWhite, palette.lightPrimary, 0.03f),
        surfaceContainer = lerp(IndexWhite, palette.lightPrimary, 0.06f),
        surfaceContainerHigh = lerp(IndexWhite, palette.lightPrimary, 0.09f),
        surfaceContainerHighest = lerp(IndexWhite, palette.lightPrimary, 0.12f),
        surfaceVariant = lerp(IndexWhite, palette.lightPrimary, 0.10f),
        onSecondary = IndexWhite,
        onTertiary = IndexWhite,
        onBackground = Color(0xFF1C1B1F),
        onSurface = Color(0xFF1C1B1F)
    )
}

@Composable
fun IndexRecordTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeColor: ThemeColor = ThemeColor.INDEX_BLUE,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> buildDarkColorScheme(themeColor)
        else -> buildLightColorScheme(themeColor)
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
