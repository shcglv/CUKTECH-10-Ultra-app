package com.example.ui.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

fun resolveLocale(languagePref: String): Locale {
    return when (languagePref.lowercase(Locale.ROOT)) {
        "ru" -> Locale.forLanguageTag("ru-RU")
        "en" -> Locale.ENGLISH
        else -> Locale.getDefault()
    }
}

fun Context.withAppLocale(languagePref: String): Context {
    if (languagePref == "system") return this
    val targetLocale = resolveLocale(languagePref)
    val config = Configuration(resources.configuration)
    config.setLocale(targetLocale)
    val resContext = createConfigurationContext(config)
    return LocalizedContextWrapper(this, resContext.resources)
}

/**
 * Preserves the original base [Context] (including [androidx.activity.ComponentActivity] for
 * ActivityResultRegistry, OnBackPressedDispatcher, and Theme/Window lookups) while overriding
 * [Resources] with the selected locale configuration.
 */
private class LocalizedContextWrapper(
    base: Context,
    private val localizedResources: Resources
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResources
    override fun getAssets(): AssetManager = localizedResources.assets
}

/**
 * Dynamically provides localized Android [Context] and [Configuration] inside Compose
 * whenever `appLanguage` ("system" | "en" | "ru") changes in DataStore,
 * without destroying Activity state, ActivityResult launchers, or active BLE sessions.
 */
@Composable
fun LocalizedAppContent(
    appLanguage: String,
    content: @Composable () -> Unit
) {
    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val activityResultOwner = LocalActivityResultRegistryOwner.current
    val backPressedOwner = LocalOnBackPressedDispatcherOwner.current

    val (localizedContext, localizedConfig) = remember(appLanguage, baseConfig) {
        if (appLanguage == "system") {
            baseContext to baseConfig
        } else {
            val targetLocale = resolveLocale(appLanguage)
            val updatedConfig = Configuration(baseConfig).apply {
                setLocale(targetLocale)
            }
            val resContext = baseContext.createConfigurationContext(updatedConfig)
            val wrapped = LocalizedContextWrapper(baseContext, resContext.resources)
            wrapped to updatedConfig
        }
    }

    val providedLocals = buildList {
        add(LocalContext provides localizedContext)
        add(LocalConfiguration provides localizedConfig)
        if (activityResultOwner != null) {
            add(LocalActivityResultRegistryOwner provides activityResultOwner)
        }
        if (backPressedOwner != null) {
            add(LocalOnBackPressedDispatcherOwner provides backPressedOwner)
        }
    }.toTypedArray()

    CompositionLocalProvider(*providedLocals, content = content)
}
