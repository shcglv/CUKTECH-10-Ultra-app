package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

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
    val portC1: Color = Color(0xFF00677D),
    val portC2: Color = Color(0xFF4B626B),
    val portC3: Color = Color(0xFF565D7E),
    val portA: Color = Color(0xFFB45309),
    val chargerAccent: Color = Color(0xFF00677D),
    val isDark: Boolean = false
) {
    fun colorForPort(portId: Int): Color = when (portId) {
        1 -> portC1
        2 -> portC2
        3 -> portC3
        else -> portA
    }

    companion object {
        /**
         * Derives semantic & per-port accents directly from the active Material 3 [ColorScheme]
         * so Material You Dynamic Color wallpapers flow cohesively across cards, charts, and faceplate.
         */
        fun fromColorScheme(colorScheme: ColorScheme, isDark: Boolean, dynamicEnabled: Boolean): CuktechSemanticColors {
            val baseSuccess = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
            val baseWarning = if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)

            val port1 = colorScheme.primary
            val port2 = if (dynamicEnabled) {
                colorScheme.tertiary
            } else {
                if (isDark) Color(0xFF2DD4BF) else Color(0xFF0D9488)
            }
            val port3 = if (dynamicEnabled) {
                lerp(colorScheme.secondary, colorScheme.primary, 0.35f)
            } else {
                colorScheme.tertiary
            }
            val port4 = if (dynamicEnabled) {
                lerp(colorScheme.tertiary, baseWarning, 0.30f)
            } else {
                baseWarning
            }

            return CuktechSemanticColors(
                success = if (dynamicEnabled) lerp(baseSuccess, colorScheme.primary, 0.14f) else baseSuccess,
                onSuccess = if (isDark) Color(0xFF052E16) else Color(0xFFFFFFFF),
                successContainer = if (isDark) {
                    lerp(Color(0xFF0D3822), colorScheme.primaryContainer, 0.22f)
                } else {
                    lerp(Color(0xFFDCFCE7), colorScheme.primaryContainer, 0.18f)
                },
                onSuccessContainer = if (isDark) Color(0xFFA7F3D0) else Color(0xFF052E16),
                warning = baseWarning,
                warningContainer = if (isDark) {
                    lerp(Color(0xFF3A2807), colorScheme.tertiaryContainer, 0.20f)
                } else {
                    lerp(Color(0xFFFEF3C7), colorScheme.tertiaryContainer, 0.15f)
                },
                onWarningContainer = if (isDark) Color(0xFFFDE68A) else Color(0xFF451A03),
                portC1 = port1,
                portC2 = port2,
                portC3 = port3,
                portA = port4,
                chargerAccent = colorScheme.primary,
                isDark = isDark
            )
        }
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
    portC1 = Color(0xFF5DD5F4),
    portC2 = Color(0xFF2DD4BF),
    portC3 = Color(0xFFBEC5EB),
    portA = Color(0xFFFBBF24),
    chargerAccent = Color(0xFF5DD5F4),
    isDark = true
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }
