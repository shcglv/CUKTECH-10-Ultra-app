package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light M3 Fallback Palette (Crisp CUKTECH Teal & Slate on clean off-white canvas)
val MdLightPrimary = Color(0xFF00677D)
val MdLightOnPrimary = Color(0xFFFFFFFF)
val MdLightPrimaryContainer = Color(0xFFB3EBFF)
val MdLightOnPrimaryContainer = Color(0xFF001F27)

val MdLightSecondary = Color(0xFF4B626B)
val MdLightOnSecondary = Color(0xFFFFFFFF)
val MdLightSecondaryContainer = Color(0xFFCEE7F1)
val MdLightOnSecondaryContainer = Color(0xFF061E26)

val MdLightTertiary = Color(0xFF565D7E)
val MdLightOnTertiary = Color(0xFFFFFFFF)
val MdLightTertiaryContainer = Color(0xFFDDE1FF)
val MdLightOnTertiaryContainer = Color(0xFF131937)

val MdLightBackground = Color(0xFFF6FAFC)
val MdLightOnBackground = Color(0xFF171C1E)
val MdLightSurface = Color(0xFFF6FAFC)
val MdLightOnSurface = Color(0xFF171C1E)
val MdLightSurfaceVariant = Color(0xFFDBE4E8)
val MdLightOnSurfaceVariant = Color(0xFF40484C)
val MdLightSurfaceContainerLowest = Color(0xFFFFFFFF)
val MdLightSurfaceContainerLow = Color(0xFFF0F4F6)
val MdLightSurfaceContainer = Color(0xFFEAEFF1)
val MdLightSurfaceContainerHigh = Color(0xFFE4E9EC)
val MdLightSurfaceContainerHighest = Color(0xFFDFE3E6)
val MdLightOutline = Color(0xFF70787C)
val MdLightOutlineVariant = Color(0xFFBFC8CC)
val MdLightError = Color(0xFFBA1A1A)
val MdLightOnError = Color(0xFFFFFFFF)
val MdLightErrorContainer = Color(0xFFFFDAD6)
val MdLightOnErrorContainer = Color(0xFF410002)

// Dark M3 Fallback Palette (Google Pixel slate-teal tones)
val MdDarkPrimary = Color(0xFF5DD5F4)
val MdDarkOnPrimary = Color(0xFF003642)
val MdDarkPrimaryContainer = Color(0xFF004E5F)
val MdDarkOnPrimaryContainer = Color(0xFFB3EBFF)

val MdDarkSecondary = Color(0xFFB2CBD4)
val MdDarkOnSecondary = Color(0xFF1D343B)
val MdDarkSecondaryContainer = Color(0xFF344A52)
val MdDarkOnSecondaryContainer = Color(0xFFCEE7F1)

val MdDarkTertiary = Color(0xFFBEC5EB)
val MdDarkOnTertiary = Color(0xFF282F4D)
val MdDarkTertiaryContainer = Color(0xFF3F4565)
val MdDarkOnTertiaryContainer = Color(0xFFDDE1FF)

val MdDarkBackground = Color(0xFF0F1417)
val MdDarkOnBackground = Color(0xFFDFE3E6)
val MdDarkSurface = Color(0xFF0F1417)
val MdDarkOnSurface = Color(0xFFDFE3E6)
val MdDarkSurfaceVariant = Color(0xFF40484C)
val MdDarkOnSurfaceVariant = Color(0xFFBFC8CC)
val MdDarkSurfaceContainerLowest = Color(0xFF0A0F12)
val MdDarkSurfaceContainerLow = Color(0xFF171C1F)
val MdDarkSurfaceContainer = Color(0xFF1B2023)
val MdDarkSurfaceContainerHigh = Color(0xFF252B2E)
val MdDarkSurfaceContainerHighest = Color(0xFF303639)
val MdDarkOutline = Color(0xFF899296)
val MdDarkOutlineVariant = Color(0xFF40484C)
val MdDarkError = Color(0xFFFFB4AB)
val MdDarkOnError = Color(0xFF690005)
val MdDarkErrorContainer = Color(0xFF93000A)
val MdDarkOnErrorContainer = Color(0xFFFFDAD6)

@Immutable
data class CuktechSemanticColors(
    val success: Color = Color(0xFF15803D),
    val onSuccess: Color = Color(0xFFFFFFFF),
    val successContainer: Color = Color(0xFFDCFCE7),
    val onSuccessContainer: Color = Color(0xFF052E16),
    val warning: Color = Color(0xFFB45309),
    val warningContainer: Color = Color(0xFFFEF3C7),
    val onWarningContainer: Color = Color(0xFF451A03),
    val portC1: Color = Color(0xFF0284C7),
    val portC2: Color = Color(0xFF0D9488),
    val portC3: Color = Color(0xFF6366F1),
    val portA: Color = Color(0xFFD97706),
    val chargerAccent: Color = Color(0xFF00B8D9),
    val isDark: Boolean = false
) {
    fun colorForPort(portId: Int): Color = when (portId) {
        1 -> portC1
        2 -> portC2
        3 -> portC3
        else -> portA
    }
}

val LightSemanticColors = CuktechSemanticColors(isDark = false)

val DarkSemanticColors = CuktechSemanticColors(
    success = Color(0xFF4ADE80),
    onSuccess = Color(0xFF052E16),
    successContainer = Color(0xFF0D3822),
    onSuccessContainer = Color(0xFFA7F3D0),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3A2807),
    onWarningContainer = Color(0xFFFDE68A),
    portC1 = Color(0xFF38BDF8),
    portC2 = Color(0xFF2DD4BF),
    portC3 = Color(0xFFA5B4FC),
    portA = Color(0xFFFBBF24),
    chargerAccent = Color(0xFF00E5FF),
    isDark = true
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }
