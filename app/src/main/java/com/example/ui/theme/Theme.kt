package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CuktechLightColorScheme = lightColorScheme(
    primary = MdLightPrimary,
    onPrimary = MdLightOnPrimary,
    primaryContainer = MdLightPrimaryContainer,
    onPrimaryContainer = MdLightOnPrimaryContainer,
    secondary = MdLightSecondary,
    onSecondary = MdLightOnSecondary,
    secondaryContainer = MdLightSecondaryContainer,
    onSecondaryContainer = MdLightOnSecondaryContainer,
    tertiary = MdLightTertiary,
    onTertiary = MdLightOnTertiary,
    tertiaryContainer = MdLightTertiaryContainer,
    onTertiaryContainer = MdLightOnTertiaryContainer,
    background = MdLightBackground,
    onBackground = MdLightOnBackground,
    surface = MdLightSurface,
    onSurface = MdLightOnSurface,
    surfaceVariant = MdLightSurfaceVariant,
    onSurfaceVariant = MdLightOnSurfaceVariant,
    surfaceContainerLowest = MdLightSurfaceContainerLowest,
    surfaceContainerLow = MdLightSurfaceContainerLow,
    surfaceContainer = MdLightSurfaceContainer,
    surfaceContainerHigh = MdLightSurfaceContainerHigh,
    surfaceContainerHighest = MdLightSurfaceContainerHighest,
    outline = MdLightOutline,
    outlineVariant = MdLightOutlineVariant,
    error = MdLightError,
    onError = MdLightOnError,
    errorContainer = MdLightErrorContainer,
    onErrorContainer = MdLightOnErrorContainer
)

private val CuktechDarkColorScheme = darkColorScheme(
    primary = MdDarkPrimary,
    onPrimary = MdDarkOnPrimary,
    primaryContainer = MdDarkPrimaryContainer,
    onPrimaryContainer = MdDarkOnPrimaryContainer,
    secondary = MdDarkSecondary,
    onSecondary = MdDarkOnSecondary,
    secondaryContainer = MdDarkSecondaryContainer,
    onSecondaryContainer = MdDarkOnSecondaryContainer,
    tertiary = MdDarkTertiary,
    onTertiary = MdDarkOnTertiary,
    tertiaryContainer = MdDarkTertiaryContainer,
    onTertiaryContainer = MdDarkOnTertiaryContainer,
    background = MdDarkBackground,
    onBackground = MdDarkOnBackground,
    surface = MdDarkSurface,
    onSurface = MdDarkOnSurface,
    surfaceVariant = MdDarkSurfaceVariant,
    onSurfaceVariant = MdDarkOnSurfaceVariant,
    surfaceContainerLowest = MdDarkSurfaceContainerLowest,
    surfaceContainerLow = MdDarkSurfaceContainerLow,
    surfaceContainer = MdDarkSurfaceContainer,
    surfaceContainerHigh = MdDarkSurfaceContainerHigh,
    surfaceContainerHighest = MdDarkSurfaceContainerHighest,
    outline = MdDarkOutline,
    outlineVariant = MdDarkOutlineVariant,
    error = MdDarkError,
    onError = MdDarkOnError,
    errorContainer = MdDarkErrorContainer,
    onErrorContainer = MdDarkOnErrorContainer
)

/**
 * Pixel-native Material 3 Expressive theme supporting System/Light/Dark mode,
 * Android 12+ Material You Dynamic Color, and optional Pure Black AMOLED surface override.
 */
@Composable
fun MyApplicationTheme(
    themeMode: String = "system", // "system" | "light" | "dark"
    useDynamicColor: Boolean = true,
    useAmoledBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode.lowercase()) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val context = LocalContext.current
    val dynamicActive = useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val baseScheme = when {
        dynamicActive -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> CuktechDarkColorScheme
        else -> CuktechLightColorScheme
    }

    val colorScheme = if (isDark && useAmoledBlack) {
        baseScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0A0C10),
            surfaceContainer = Color(0xFF11141A),
            surfaceContainerHigh = Color(0xFF181C23),
            surfaceContainerHighest = Color(0xFF20252E)
        )
    } else {
        baseScheme
    }

    val semanticColors = remember(colorScheme, isDark, dynamicActive) {
        CuktechSemanticColors.fromColorScheme(
            colorScheme = colorScheme,
            isDark = isDark,
            dynamicEnabled = dynamicActive
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
            }
        }
    }

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = CuktechShapes,
            content = content
        )
    }
}
