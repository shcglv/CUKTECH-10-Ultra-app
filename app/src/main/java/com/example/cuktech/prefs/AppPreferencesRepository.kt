package com.example.cuktech.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

enum class StartDestination {
    OVERVIEW,
    PORTS,
    ANALYTICS,
    SETTINGS,
    GATEWAY
}

data class AppPreferences(
    val startDestination: StartDestination = StartDestination.OVERVIEW,
    val appLanguage: String = "system", // "system", "en", "ru"
    val themeMode: String = "system",   // "dark", "light", "system"
    val useDynamicColor: Boolean = true,
    val darkPalette: String = "CUKTECH_CYAN",
    val hasCompletedOnboarding: Boolean = false,
    val retentionDays: Int = 30,        // Default 30 days before auto-archiving old sessions
    val sessionStitchGraceSec: Int = 120,
    val tariffRatePerKwh: Double = 0.0,
    val touEnabled: Boolean = false,
    val tariffNightRatePerKwh: Double = 0.0,
    val tariffDayStartMinute: Int = 7 * 60,
    val tariffNightStartMinute: Int = 23 * 60,
    val currencyCode: String = "USD",
    val completionHysteresisSec: Int = 60,
    val completionPowerThresholdW: Double = 1.0,
    val completionSoundEnabled: Boolean = true,
    val completionVibrationEnabled: Boolean = true,
    val completionRepeatEnabled: Boolean = false,
    val completionRepeatIntervalMin: Int = 5,
    val completionMaxRepeats: Int = 2,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinute: Int = 23 * 60,
    val quietHoursEndMinute: Int = 7 * 60,
    val liveUpdatesEnabled: Boolean = true,
    val consoleLogLevel: String = "LEVEL1",
    val thresholdAlertsEnabled: Boolean = false,
    val highLoadAlertWatts: Double = 105.0,
    val lowPowerDropAlertWatts: Double = 1.5,
    val bridgeSyncEnabled: Boolean = false,
    val bridgeEndpointUrl: String = "",
    val bleExitAlertEnabled: Boolean = true,
    val bleExitRssiThreshold: Int = -86,
    val activeWorkspaceId: String = "ALL",
    val customKpisJson: String = "[]",
    val wifiContextEnabled: Boolean = false,
    val boundWifiKey: String = "",
    val boundWifiLabel: String = "",
    val wifiBleOnlyAtHome: Boolean = false,
    val wifiLeaveLoadAlertEnabled: Boolean = true,
    val wifiLeaveLoadThresholdWatts: Double = 15.0,
    val hiddenAnalyticsCardsCsv: String = "",
    val completionAlertsEnabled: Boolean = true,
    val anomalyAlertsEnabled: Boolean = true,
    val anomalySuppressAfterCompletion: Boolean = true,
    val thresholdPushEnabled: Boolean = true
) {
    fun hiddenAnalyticsCardSet(): Set<String> =
        hiddenAnalyticsCardsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun isWithinQuietHours(minuteOfDay: Int = currentLocalMinuteOfDay()): Boolean {
        if (!quietHoursEnabled) return false
        return isMinuteInRange(minuteOfDay, quietHoursStartMinute, quietHoursEndMinute)
    }

    fun isWithinNightTariff(minuteOfDay: Int = currentLocalMinuteOfDay()): Boolean {
        if (!touEnabled) return false
        return isMinuteInRange(minuteOfDay, tariffNightStartMinute, tariffDayStartMinute)
    }

    fun resolveEffectiveTariffPerKwh(minuteOfDay: Int = currentLocalMinuteOfDay()): Double {
        return if (touEnabled && isWithinNightTariff(minuteOfDay)) {
            tariffNightRatePerKwh
        } else {
            tariffRatePerKwh
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("startDestination", startDestination.name)
        put("appLanguage", appLanguage)
        put("themeMode", themeMode)
        put("useDynamicColor", useDynamicColor)
        put("darkPalette", darkPalette)
        put("hasCompletedOnboarding", hasCompletedOnboarding)
        put("retentionDays", retentionDays)
        put("sessionStitchGraceSec", sessionStitchGraceSec)
        put("tariffRatePerKwh", tariffRatePerKwh)
        put("touEnabled", touEnabled)
        put("tariffNightRatePerKwh", tariffNightRatePerKwh)
        put("tariffDayStartMinute", tariffDayStartMinute)
        put("tariffNightStartMinute", tariffNightStartMinute)
        put("currencyCode", currencyCode)
        put("completionHysteresisSec", completionHysteresisSec)
        put("completionPowerThresholdW", completionPowerThresholdW)
        put("completionSoundEnabled", completionSoundEnabled)
        put("completionVibrationEnabled", completionVibrationEnabled)
        put("completionRepeatEnabled", completionRepeatEnabled)
        put("completionRepeatIntervalMin", completionRepeatIntervalMin)
        put("completionMaxRepeats", completionMaxRepeats)
        put("quietHoursEnabled", quietHoursEnabled)
        put("quietHoursStartMinute", quietHoursStartMinute)
        put("quietHoursEndMinute", quietHoursEndMinute)
        put("liveUpdatesEnabled", liveUpdatesEnabled)
        put("consoleLogLevel", consoleLogLevel)
        put("thresholdAlertsEnabled", thresholdAlertsEnabled)
        put("highLoadAlertWatts", highLoadAlertWatts)
        put("lowPowerDropAlertWatts", lowPowerDropAlertWatts)
        put("bridgeSyncEnabled", bridgeSyncEnabled)
        put("bridgeEndpointUrl", bridgeEndpointUrl)
        put("bleExitAlertEnabled", bleExitAlertEnabled)
        put("bleExitRssiThreshold", bleExitRssiThreshold)
        put("activeWorkspaceId", activeWorkspaceId)
        put("customKpisJson", customKpisJson)
        put("wifiContextEnabled", wifiContextEnabled)
        put("boundWifiKey", boundWifiKey)
        put("boundWifiLabel", boundWifiLabel)
        put("wifiBleOnlyAtHome", wifiBleOnlyAtHome)
        put("wifiLeaveLoadAlertEnabled", wifiLeaveLoadAlertEnabled)
        put("wifiLeaveLoadThresholdWatts", wifiLeaveLoadThresholdWatts)
        put("hiddenAnalyticsCardsCsv", hiddenAnalyticsCardsCsv)
        put("completionAlertsEnabled", completionAlertsEnabled)
        put("anomalyAlertsEnabled", anomalyAlertsEnabled)
        put("anomalySuppressAfterCompletion", anomalySuppressAfterCompletion)
        put("thresholdPushEnabled", thresholdPushEnabled)
    }

    companion object {
        fun isMinuteInRange(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean {
            val m = minuteOfDay.coerceIn(0, 1439)
            val s = startMin.coerceIn(0, 1439)
            val e = endMin.coerceIn(0, 1439)
            if (s == e) return false
            return if (s < e) {
                m in s until e
            } else {
                m >= s || m < e
            }
        }

        fun currentLocalMinuteOfDay(): Int {
            val cal = Calendar.getInstance()
            return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        }
    }
}

private val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cuktech_app_preferences"
)

/**
 * DataStore-backed typed preferences repository with one-time legacy SharedPreferences migration,
 * ToU electricity tariff resolution, quiet-hours awareness, and JSON backup import/export.
 */
class AppPreferencesRepository(context: Context) {

    private val appCtx = context.applicationContext
    private val dataStore = appCtx.appPreferencesDataStore
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _preferences = MutableStateFlow(AppPreferences())
    val preferences: StateFlow<AppPreferences> = _preferences.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        scope.launch {
            migrateLegacyPrefsIfNeeded()
            dataStore.data.collect { prefs ->
                _preferences.value = mapFromDataStore(prefs)
                _isLoaded.value = true
            }
        }
    }

    suspend fun awaitLoaded(): AppPreferences {
        migrateLegacyPrefsIfNeeded()
        val mapped = mapFromDataStore(dataStore.data.first())
        _preferences.value = mapped
        return mapped
    }

    private suspend fun migrateLegacyPrefsIfNeeded() {
        val current = dataStore.data.first()
        if (current[KEY_MIGRATED_LEGACY] == true) return
        val legacy = appCtx.getSharedPreferences("ble_cuktech_prefs", Context.MODE_PRIVATE)
        val palette = legacy.getString("dark_palette", "CUKTECH_CYAN") ?: "CUKTECH_CYAN"
        val logLvl = legacy.getString("console_log_level", "LEVEL1") ?: "LEVEL1"
        dataStore.edit { edit ->
            if (edit[KEY_MIGRATED_LEGACY] != true) {
                edit[KEY_DARK_PALETTE] = palette
                edit[KEY_CONSOLE_LOG_LEVEL] = logLvl
                edit[KEY_RETENTION_DAYS] = 30
                edit[KEY_STITCH_GRACE_SEC] = 120
                edit[KEY_MIGRATED_LEGACY] = true
            }
        }
    }

    private fun mapFromDataStore(prefs: Preferences): AppPreferences {
        val startName = prefs[KEY_START_DESTINATION] ?: StartDestination.OVERVIEW.name
        val startDest = runCatching { StartDestination.valueOf(startName) }.getOrDefault(StartDestination.OVERVIEW)
        return AppPreferences(
            startDestination = startDest,
            appLanguage = prefs[KEY_APP_LANGUAGE] ?: "system",
            themeMode = prefs[KEY_THEME_MODE] ?: "system",
            useDynamicColor = prefs[KEY_USE_DYNAMIC_COLOR] ?: true,
            darkPalette = prefs[KEY_DARK_PALETTE] ?: "CUKTECH_CYAN",
            hasCompletedOnboarding = prefs[KEY_ONBOARDING_COMPLETED] ?: false,
            retentionDays = (prefs[KEY_RETENTION_DAYS] ?: 30).coerceIn(1, 3650),
            sessionStitchGraceSec = (prefs[KEY_STITCH_GRACE_SEC] ?: 120).coerceIn(0, 600),
            tariffRatePerKwh = (prefs[KEY_TARIFF_PER_KWH] ?: 0.0).coerceAtLeast(0.0),
            touEnabled = prefs[KEY_TOU_ENABLED] ?: false,
            tariffNightRatePerKwh = (prefs[KEY_TARIFF_NIGHT_PER_KWH] ?: 0.0).coerceAtLeast(0.0),
            tariffDayStartMinute = (prefs[KEY_TARIFF_DAY_START_MIN] ?: (7 * 60)).coerceIn(0, 1439),
            tariffNightStartMinute = (prefs[KEY_TARIFF_NIGHT_START_MIN] ?: (23 * 60)).coerceIn(0, 1439),
            currencyCode = (prefs[KEY_CURRENCY_CODE] ?: "USD").take(6).ifBlank { "USD" },
            completionHysteresisSec = (prefs[KEY_COMPLETION_HYSTERESIS_SEC] ?: 60).coerceIn(10, 600),
            completionPowerThresholdW = (prefs[KEY_COMPLETION_POWER_W] ?: 1.0).coerceIn(0.2, 10.0),
            completionSoundEnabled = prefs[KEY_COMPLETION_SOUND] ?: true,
            completionVibrationEnabled = prefs[KEY_COMPLETION_VIBRATION] ?: true,
            completionRepeatEnabled = prefs[KEY_COMPLETION_REPEAT] ?: false,
            completionRepeatIntervalMin = (prefs[KEY_COMPLETION_REPEAT_INTERVAL_MIN] ?: 5).coerceIn(1, 60),
            completionMaxRepeats = (prefs[KEY_COMPLETION_MAX_REPEATS] ?: 2).coerceIn(1, 5),
            quietHoursEnabled = prefs[KEY_QUIET_HOURS_ENABLED] ?: false,
            quietHoursStartMinute = (prefs[KEY_QUIET_START_MIN] ?: (23 * 60)).coerceIn(0, 1439),
            quietHoursEndMinute = (prefs[KEY_QUIET_END_MIN] ?: (7 * 60)).coerceIn(0, 1439),
            liveUpdatesEnabled = prefs[KEY_LIVE_UPDATES_ENABLED] ?: true,
            consoleLogLevel = prefs[KEY_CONSOLE_LOG_LEVEL] ?: "LEVEL1",
            thresholdAlertsEnabled = prefs[KEY_THRESHOLD_ALERTS_ENABLED] ?: false,
            highLoadAlertWatts = (prefs[KEY_HIGH_LOAD_ALERT_W] ?: 105.0).coerceIn(40.0, 120.0),
            lowPowerDropAlertWatts = (prefs[KEY_LOW_POWER_DROP_ALERT_W] ?: 1.5).coerceIn(0.5, 15.0),
            bridgeSyncEnabled = prefs[KEY_BRIDGE_SYNC_ENABLED] ?: false,
            bridgeEndpointUrl = prefs[KEY_BRIDGE_ENDPOINT_URL] ?: "",
            bleExitAlertEnabled = prefs[KEY_BLE_EXIT_ALERT_ENABLED] ?: true,
            bleExitRssiThreshold = (prefs[KEY_BLE_EXIT_RSSI_THRESHOLD] ?: -86).coerceIn(-96, -65),
            activeWorkspaceId = (prefs[KEY_ACTIVE_WORKSPACE_ID] ?: "ALL").ifBlank { "ALL" },
            customKpisJson = (prefs[KEY_CUSTOM_KPIS_JSON] ?: "[]").ifBlank { "[]" },
            wifiContextEnabled = prefs[KEY_WIFI_CONTEXT_ENABLED] ?: false,
            boundWifiKey = prefs[KEY_BOUND_WIFI_KEY] ?: "",
            boundWifiLabel = prefs[KEY_BOUND_WIFI_LABEL] ?: "",
            wifiBleOnlyAtHome = prefs[KEY_WIFI_BLE_ONLY_AT_HOME] ?: false,
            wifiLeaveLoadAlertEnabled = prefs[KEY_WIFI_LEAVE_LOAD_ALERT] ?: true,
            wifiLeaveLoadThresholdWatts = (prefs[KEY_WIFI_LEAVE_LOAD_W] ?: 15.0).coerceIn(5.0, 100.0),
            hiddenAnalyticsCardsCsv = prefs[KEY_HIDDEN_ANALYTICS_CARDS] ?: "",
            completionAlertsEnabled = prefs[KEY_COMPLETION_ALERTS_ENABLED] ?: true,
            anomalyAlertsEnabled = prefs[KEY_ANOMALY_ALERTS_ENABLED] ?: true,
            anomalySuppressAfterCompletion = prefs[KEY_ANOMALY_SUPPRESS_AFTER_COMPLETION] ?: true,
            thresholdPushEnabled = prefs[KEY_THRESHOLD_PUSH_ENABLED] ?: true
        )
    }

    suspend fun updateStartDestination(dest: StartDestination) {
        dataStore.edit { it[KEY_START_DESTINATION] = dest.name }
    }

    suspend fun updateLanguage(lang: String) {
        val valid = if (lang in setOf("system", "en", "ru")) lang else "system"
        dataStore.edit { it[KEY_APP_LANGUAGE] = valid }
    }

    suspend fun updateTheme(themeMode: String, darkPalette: String? = null) {
        dataStore.edit {
            it[KEY_THEME_MODE] = if (themeMode in setOf("dark", "light", "system")) themeMode else "system"
            if (darkPalette != null) {
                it[KEY_DARK_PALETTE] = darkPalette
            }
        }
    }

    suspend fun updateDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_USE_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun updateRetentionDays(days: Int) {
        dataStore.edit { it[KEY_RETENTION_DAYS] = days.coerceIn(1, 3650) }
    }

    suspend fun updateSessionStitchGraceSec(seconds: Int) {
        dataStore.edit { it[KEY_STITCH_GRACE_SEC] = seconds.coerceIn(0, 600) }
    }

    suspend fun updateTariff(ratePerKwh: Double, currencyCode: String) {
        dataStore.edit {
            it[KEY_TARIFF_PER_KWH] = ratePerKwh.coerceAtLeast(0.0)
            it[KEY_CURRENCY_CODE] = currencyCode.trim().uppercase().take(6).ifBlank { "USD" }
        }
    }

    suspend fun updateTouTariff(
        touEnabled: Boolean,
        dayRatePerKwh: Double,
        nightRatePerKwh: Double,
        currencyCode: String,
        dayStartMinute: Int,
        nightStartMinute: Int
    ) {
        dataStore.edit {
            it[KEY_TOU_ENABLED] = touEnabled
            it[KEY_TARIFF_PER_KWH] = dayRatePerKwh.coerceAtLeast(0.0)
            it[KEY_TARIFF_NIGHT_PER_KWH] = nightRatePerKwh.coerceAtLeast(0.0)
            it[KEY_CURRENCY_CODE] = currencyCode.trim().uppercase().take(6).ifBlank { "USD" }
            it[KEY_TARIFF_DAY_START_MIN] = dayStartMinute.coerceIn(0, 1439)
            it[KEY_TARIFF_NIGHT_START_MIN] = nightStartMinute.coerceIn(0, 1439)
        }
    }

    suspend fun updateNotificationPolicy(
        hysteresisSec: Int,
        thresholdW: Double,
        quietHoursEnabled: Boolean,
        quietStartMin: Int,
        quietEndMin: Int,
        liveUpdatesEnabled: Boolean,
        soundEnabled: Boolean = _preferences.value.completionSoundEnabled,
        vibrationEnabled: Boolean = _preferences.value.completionVibrationEnabled,
        repeatEnabled: Boolean = _preferences.value.completionRepeatEnabled,
        repeatIntervalMin: Int = _preferences.value.completionRepeatIntervalMin,
        maxRepeats: Int = _preferences.value.completionMaxRepeats
    ) {
        dataStore.edit {
            it[KEY_COMPLETION_HYSTERESIS_SEC] = hysteresisSec.coerceIn(10, 600)
            it[KEY_COMPLETION_POWER_W] = thresholdW.coerceIn(0.2, 10.0)
            it[KEY_QUIET_HOURS_ENABLED] = quietHoursEnabled
            it[KEY_QUIET_START_MIN] = quietStartMin.coerceIn(0, 1439)
            it[KEY_QUIET_END_MIN] = quietEndMin.coerceIn(0, 1439)
            it[KEY_LIVE_UPDATES_ENABLED] = liveUpdatesEnabled
            it[KEY_COMPLETION_SOUND] = soundEnabled
            it[KEY_COMPLETION_VIBRATION] = vibrationEnabled
            it[KEY_COMPLETION_REPEAT] = repeatEnabled
            it[KEY_COMPLETION_REPEAT_INTERVAL_MIN] = repeatIntervalMin.coerceIn(1, 60)
            it[KEY_COMPLETION_MAX_REPEATS] = maxRepeats.coerceIn(1, 5)
        }
    }

    suspend fun updateAlertChannelsPolicy(
        completionAlertsEnabled: Boolean,
        anomalyAlertsEnabled: Boolean,
        anomalySuppressAfterCompletion: Boolean,
        thresholdPushEnabled: Boolean
    ) {
        _preferences.value = _preferences.value.copy(
            completionAlertsEnabled = completionAlertsEnabled,
            anomalyAlertsEnabled = anomalyAlertsEnabled,
            anomalySuppressAfterCompletion = anomalySuppressAfterCompletion,
            thresholdPushEnabled = thresholdPushEnabled
        )
        val updated = dataStore.edit {
            it[KEY_COMPLETION_ALERTS_ENABLED] = completionAlertsEnabled
            it[KEY_ANOMALY_ALERTS_ENABLED] = anomalyAlertsEnabled
            it[KEY_ANOMALY_SUPPRESS_AFTER_COMPLETION] = anomalySuppressAfterCompletion
            it[KEY_THRESHOLD_PUSH_ENABLED] = thresholdPushEnabled
        }
        _preferences.value = mapFromDataStore(updated)
    }

    suspend fun updateConsoleLogLevel(levelName: String) {
        dataStore.edit { it[KEY_CONSOLE_LOG_LEVEL] = levelName }
    }

    suspend fun updateThresholdAlerts(
        enabled: Boolean,
        highLoadWatts: Double,
        lowPowerDropWatts: Double
    ) {
        val clampedHigh = highLoadWatts.coerceIn(40.0, 120.0)
        val clampedLow = lowPowerDropWatts.coerceIn(0.5, 15.0)
        _preferences.value = _preferences.value.copy(
            thresholdAlertsEnabled = enabled,
            highLoadAlertWatts = clampedHigh,
            lowPowerDropAlertWatts = clampedLow
        )
        val updated = dataStore.edit {
            it[KEY_THRESHOLD_ALERTS_ENABLED] = enabled
            it[KEY_HIGH_LOAD_ALERT_W] = clampedHigh
            it[KEY_LOW_POWER_DROP_ALERT_W] = clampedLow
        }
        _preferences.value = mapFromDataStore(updated)
    }

    suspend fun updateBridgeSettings(enabled: Boolean, endpointUrl: String) {
        val cleaned = endpointUrl.trim().take(240)
        val updated = dataStore.edit {
            it[KEY_BRIDGE_SYNC_ENABLED] = enabled
            it[KEY_BRIDGE_ENDPOINT_URL] = cleaned
        }
        _preferences.value = mapFromDataStore(updated)
    }

    suspend fun updateBleExitAlertPolicy(enabled: Boolean, rssiThreshold: Int) {
        val clamped = rssiThreshold.coerceIn(-96, -65)
        val updated = dataStore.edit {
            it[KEY_BLE_EXIT_ALERT_ENABLED] = enabled
            it[KEY_BLE_EXIT_RSSI_THRESHOLD] = clamped
        }
        _preferences.value = mapFromDataStore(updated)
    }

    suspend fun updateActiveWorkspace(workspaceId: String) {
        val clean = workspaceId.trim().uppercase().take(24).ifBlank { "ALL" }
        val updated = dataStore.edit {
            it[KEY_ACTIVE_WORKSPACE_ID] = clean
        }
        _preferences.value = mapFromDataStore(updated)
    }

    suspend fun updateCustomKpisJson(json: String) {
        val clean = json.trim().ifBlank { "[]" }
        val updated = dataStore.edit {
            it[KEY_CUSTOM_KPIS_JSON] = clean
        }
        _preferences.value = mapFromDataStore(updated)
    }

    suspend fun bindHomeWifi(key: String, label: String) {
        val updated = dataStore.edit {
            it[KEY_WIFI_CONTEXT_ENABLED] = true
            it[KEY_BOUND_WIFI_KEY] = key.trim().take(80)
            it[KEY_BOUND_WIFI_LABEL] = label.trim().take(64)
        }
        _preferences.value = mapFromDataStore(updated)
    }

    suspend fun clearHomeWifiBinding() {
        val updated = dataStore.edit {
            it[KEY_WIFI_CONTEXT_ENABLED] = false
            it[KEY_BOUND_WIFI_KEY] = ""
            it[KEY_BOUND_WIFI_LABEL] = ""
            it[KEY_WIFI_BLE_ONLY_AT_HOME] = false
        }
        _preferences.value = mapFromDataStore(updated)
    }

    suspend fun updateWifiContextPolicy(
        contextEnabled: Boolean,
        bleOnlyAtHome: Boolean,
        leaveAlertEnabled: Boolean,
        leaveThresholdWatts: Double
    ) {
        val updated = dataStore.edit {
            it[KEY_WIFI_CONTEXT_ENABLED] = contextEnabled
            it[KEY_WIFI_BLE_ONLY_AT_HOME] = bleOnlyAtHome
            it[KEY_WIFI_LEAVE_LOAD_ALERT] = leaveAlertEnabled
            it[KEY_WIFI_LEAVE_LOAD_W] = leaveThresholdWatts.coerceIn(5.0, 100.0)
        }
        _preferences.value = mapFromDataStore(updated)
    }

    suspend fun updateHiddenAnalyticsCards(hiddenIds: Set<String>) {
        val cleanCsv = hiddenIds.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
        val updated = dataStore.edit {
            it[KEY_HIDDEN_ANALYTICS_CARDS] = cleanCsv
        }
        _preferences.value = mapFromDataStore(updated)
    }

    suspend fun applyFromBackupJson(json: JSONObject) {
        if (json.length() == 0) return
        dataStore.edit { edit ->
            if (json.has("startDestination")) {
                val sd = runCatching { StartDestination.valueOf(json.optString("startDestination")) }
                    .getOrDefault(StartDestination.OVERVIEW)
                edit[KEY_START_DESTINATION] = sd.name
            }
            if (json.has("appLanguage")) {
                val l = json.optString("appLanguage", "system")
                if (l in setOf("system", "en", "ru")) edit[KEY_APP_LANGUAGE] = l
            }
            if (json.has("themeMode")) {
                val t = json.optString("themeMode", "system")
                if (t in setOf("dark", "light", "system")) edit[KEY_THEME_MODE] = t
            }
            if (json.has("useDynamicColor")) {
                edit[KEY_USE_DYNAMIC_COLOR] = json.optBoolean("useDynamicColor", true)
            }
            if (json.has("darkPalette")) {
                edit[KEY_DARK_PALETTE] = json.optString("darkPalette", "CUKTECH_CYAN")
            }
            if (json.has("hasCompletedOnboarding")) {
                edit[KEY_ONBOARDING_COMPLETED] = json.optBoolean("hasCompletedOnboarding", false)
            }
            if (json.has("retentionDays")) {
                edit[KEY_RETENTION_DAYS] = json.optInt("retentionDays", 30).coerceIn(1, 3650)
            }
            if (json.has("sessionStitchGraceSec")) {
                edit[KEY_STITCH_GRACE_SEC] = json.optInt("sessionStitchGraceSec", 120).coerceIn(0, 600)
            }
            if (json.has("tariffRatePerKwh")) {
                edit[KEY_TARIFF_PER_KWH] = json.optDouble("tariffRatePerKwh", 0.0).coerceAtLeast(0.0)
            }
            if (json.has("touEnabled")) {
                edit[KEY_TOU_ENABLED] = json.optBoolean("touEnabled", false)
            }
            if (json.has("tariffNightRatePerKwh")) {
                edit[KEY_TARIFF_NIGHT_PER_KWH] = json.optDouble("tariffNightRatePerKwh", 0.0).coerceAtLeast(0.0)
            }
            if (json.has("tariffDayStartMinute")) {
                edit[KEY_TARIFF_DAY_START_MIN] = json.optInt("tariffDayStartMinute", 7 * 60).coerceIn(0, 1439)
            }
            if (json.has("tariffNightStartMinute")) {
                edit[KEY_TARIFF_NIGHT_START_MIN] = json.optInt("tariffNightStartMinute", 23 * 60).coerceIn(0, 1439)
            }
            if (json.has("currencyCode")) {
                edit[KEY_CURRENCY_CODE] = json.optString("currencyCode", "USD").trim().uppercase().take(6).ifBlank { "USD" }
            }
            if (json.has("completionHysteresisSec")) {
                edit[KEY_COMPLETION_HYSTERESIS_SEC] = json.optInt("completionHysteresisSec", 60).coerceIn(10, 600)
            }
            if (json.has("completionPowerThresholdW")) {
                edit[KEY_COMPLETION_POWER_W] = json.optDouble("completionPowerThresholdW", 1.0).coerceIn(0.2, 10.0)
            }
            if (json.has("completionSoundEnabled")) {
                edit[KEY_COMPLETION_SOUND] = json.optBoolean("completionSoundEnabled", true)
            }
            if (json.has("completionVibrationEnabled")) {
                edit[KEY_COMPLETION_VIBRATION] = json.optBoolean("completionVibrationEnabled", true)
            }
            if (json.has("completionRepeatEnabled")) {
                edit[KEY_COMPLETION_REPEAT] = json.optBoolean("completionRepeatEnabled", false)
            }
            if (json.has("completionRepeatIntervalMin")) {
                edit[KEY_COMPLETION_REPEAT_INTERVAL_MIN] = json.optInt("completionRepeatIntervalMin", 5).coerceIn(1, 60)
            }
            if (json.has("completionMaxRepeats")) {
                edit[KEY_COMPLETION_MAX_REPEATS] = json.optInt("completionMaxRepeats", 2).coerceIn(1, 5)
            }
            if (json.has("quietHoursEnabled")) {
                edit[KEY_QUIET_HOURS_ENABLED] = json.optBoolean("quietHoursEnabled", false)
            }
            if (json.has("quietHoursStartMinute")) {
                edit[KEY_QUIET_START_MIN] = json.optInt("quietHoursStartMinute", 23 * 60).coerceIn(0, 1439)
            }
            if (json.has("quietHoursEndMinute")) {
                edit[KEY_QUIET_END_MIN] = json.optInt("quietHoursEndMinute", 7 * 60).coerceIn(0, 1439)
            }
            if (json.has("liveUpdatesEnabled")) {
                edit[KEY_LIVE_UPDATES_ENABLED] = json.optBoolean("liveUpdatesEnabled", true)
            }
            if (json.has("consoleLogLevel")) {
                edit[KEY_CONSOLE_LOG_LEVEL] = json.optString("consoleLogLevel", "LEVEL1")
            }
            if (json.has("thresholdAlertsEnabled")) {
                edit[KEY_THRESHOLD_ALERTS_ENABLED] = json.optBoolean("thresholdAlertsEnabled", false)
            }
            if (json.has("highLoadAlertWatts")) {
                edit[KEY_HIGH_LOAD_ALERT_W] = json.optDouble("highLoadAlertWatts", 105.0).coerceIn(40.0, 120.0)
            }
            if (json.has("lowPowerDropAlertWatts")) {
                edit[KEY_LOW_POWER_DROP_ALERT_W] = json.optDouble("lowPowerDropAlertWatts", 1.5).coerceIn(0.5, 15.0)
            }
            if (json.has("bridgeSyncEnabled")) {
                edit[KEY_BRIDGE_SYNC_ENABLED] = json.optBoolean("bridgeSyncEnabled", false)
            }
            if (json.has("bridgeEndpointUrl")) {
                edit[KEY_BRIDGE_ENDPOINT_URL] = json.optString("bridgeEndpointUrl", "").trim().take(240)
            }
            if (json.has("bleExitAlertEnabled")) {
                edit[KEY_BLE_EXIT_ALERT_ENABLED] = json.optBoolean("bleExitAlertEnabled", true)
            }
            if (json.has("bleExitRssiThreshold")) {
                edit[KEY_BLE_EXIT_RSSI_THRESHOLD] = json.optInt("bleExitRssiThreshold", -86).coerceIn(-96, -65)
            }
            if (json.has("activeWorkspaceId")) {
                edit[KEY_ACTIVE_WORKSPACE_ID] = json.optString("activeWorkspaceId", "ALL").trim().uppercase().take(24).ifBlank { "ALL" }
            }
            if (json.has("customKpisJson")) {
                edit[KEY_CUSTOM_KPIS_JSON] = json.optString("customKpisJson", "[]").ifBlank { "[]" }
            }
            if (json.has("wifiContextEnabled")) {
                edit[KEY_WIFI_CONTEXT_ENABLED] = json.optBoolean("wifiContextEnabled", false)
            }
            if (json.has("boundWifiKey")) {
                edit[KEY_BOUND_WIFI_KEY] = json.optString("boundWifiKey", "").take(80)
            }
            if (json.has("boundWifiLabel")) {
                edit[KEY_BOUND_WIFI_LABEL] = json.optString("boundWifiLabel", "").take(64)
            }
            if (json.has("wifiBleOnlyAtHome")) {
                edit[KEY_WIFI_BLE_ONLY_AT_HOME] = json.optBoolean("wifiBleOnlyAtHome", false)
            }
            if (json.has("wifiLeaveLoadAlertEnabled")) {
                edit[KEY_WIFI_LEAVE_LOAD_ALERT] = json.optBoolean("wifiLeaveLoadAlertEnabled", true)
            }
            if (json.has("wifiLeaveLoadThresholdWatts")) {
                edit[KEY_WIFI_LEAVE_LOAD_W] = json.optDouble("wifiLeaveLoadThresholdWatts", 15.0).coerceIn(5.0, 100.0)
            }
            if (json.has("hiddenAnalyticsCardsCsv")) {
                edit[KEY_HIDDEN_ANALYTICS_CARDS] = json.optString("hiddenAnalyticsCardsCsv", "")
            }
            if (json.has("completionAlertsEnabled")) {
                edit[KEY_COMPLETION_ALERTS_ENABLED] = json.optBoolean("completionAlertsEnabled", true)
            }
            if (json.has("anomalyAlertsEnabled")) {
                edit[KEY_ANOMALY_ALERTS_ENABLED] = json.optBoolean("anomalyAlertsEnabled", true)
            }
            if (json.has("anomalySuppressAfterCompletion")) {
                edit[KEY_ANOMALY_SUPPRESS_AFTER_COMPLETION] = json.optBoolean("anomalySuppressAfterCompletion", true)
            }
            if (json.has("thresholdPushEnabled")) {
                edit[KEY_THRESHOLD_PUSH_ENABLED] = json.optBoolean("thresholdPushEnabled", true)
            }
            edit[KEY_MIGRATED_LEGACY] = true
        }
        val refreshed = mapFromDataStore(dataStore.data.first())
        _preferences.value = refreshed
    }

    companion object {
        private val KEY_MIGRATED_LEGACY = booleanPreferencesKey("migrated_legacy_prefs_v1")
        private val KEY_START_DESTINATION = stringPreferencesKey("start_destination")
        private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        private val KEY_DARK_PALETTE = stringPreferencesKey("dark_palette")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("has_completed_onboarding")
        private val KEY_RETENTION_DAYS = intPreferencesKey("retention_days")
        private val KEY_STITCH_GRACE_SEC = intPreferencesKey("session_stitch_grace_sec")
        private val KEY_TARIFF_PER_KWH = doublePreferencesKey("tariff_per_kwh")
        private val KEY_TOU_ENABLED = booleanPreferencesKey("tou_tariff_enabled")
        private val KEY_TARIFF_NIGHT_PER_KWH = doublePreferencesKey("tariff_night_per_kwh")
        private val KEY_TARIFF_DAY_START_MIN = intPreferencesKey("tariff_day_start_min")
        private val KEY_TARIFF_NIGHT_START_MIN = intPreferencesKey("tariff_night_start_min")
        private val KEY_CURRENCY_CODE = stringPreferencesKey("currency_code")
        private val KEY_COMPLETION_HYSTERESIS_SEC = intPreferencesKey("completion_hysteresis_sec")
        private val KEY_COMPLETION_POWER_W = doublePreferencesKey("completion_power_threshold_w")
        private val KEY_COMPLETION_SOUND = booleanPreferencesKey("completion_sound_enabled")
        private val KEY_COMPLETION_VIBRATION = booleanPreferencesKey("completion_vibration_enabled")
        private val KEY_COMPLETION_REPEAT = booleanPreferencesKey("completion_repeat_enabled")
        private val KEY_COMPLETION_REPEAT_INTERVAL_MIN = intPreferencesKey("completion_repeat_interval_min")
        private val KEY_COMPLETION_MAX_REPEATS = intPreferencesKey("completion_max_repeats")
        private val KEY_QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        private val KEY_QUIET_START_MIN = intPreferencesKey("quiet_hours_start_min")
        private val KEY_QUIET_END_MIN = intPreferencesKey("quiet_hours_end_min")
        private val KEY_LIVE_UPDATES_ENABLED = booleanPreferencesKey("live_updates_enabled")
        private val KEY_CONSOLE_LOG_LEVEL = stringPreferencesKey("console_log_level")
        private val KEY_THRESHOLD_ALERTS_ENABLED = booleanPreferencesKey("threshold_alerts_enabled")
        private val KEY_HIGH_LOAD_ALERT_W = doublePreferencesKey("high_load_alert_watts")
        private val KEY_LOW_POWER_DROP_ALERT_W = doublePreferencesKey("low_power_drop_alert_watts")
        private val KEY_BRIDGE_SYNC_ENABLED = booleanPreferencesKey("bridge_sync_enabled")
        private val KEY_BRIDGE_ENDPOINT_URL = stringPreferencesKey("bridge_endpoint_url")
        private val KEY_BLE_EXIT_ALERT_ENABLED = booleanPreferencesKey("ble_exit_alert_enabled")
        private val KEY_BLE_EXIT_RSSI_THRESHOLD = intPreferencesKey("ble_exit_rssi_threshold")
        private val KEY_ACTIVE_WORKSPACE_ID = stringPreferencesKey("active_workspace_id")
        private val KEY_CUSTOM_KPIS_JSON = stringPreferencesKey("custom_kpis_json")
        private val KEY_WIFI_CONTEXT_ENABLED = booleanPreferencesKey("wifi_context_enabled")
        private val KEY_BOUND_WIFI_KEY = stringPreferencesKey("bound_wifi_key")
        private val KEY_BOUND_WIFI_LABEL = stringPreferencesKey("bound_wifi_label")
        private val KEY_WIFI_BLE_ONLY_AT_HOME = booleanPreferencesKey("wifi_ble_only_at_home")
        private val KEY_WIFI_LEAVE_LOAD_ALERT = booleanPreferencesKey("wifi_leave_load_alert")
        private val KEY_WIFI_LEAVE_LOAD_W = doublePreferencesKey("wifi_leave_load_watts")
        private val KEY_HIDDEN_ANALYTICS_CARDS = stringPreferencesKey("hidden_analytics_cards")
        private val KEY_COMPLETION_ALERTS_ENABLED = booleanPreferencesKey("completion_alerts_enabled")
        private val KEY_ANOMALY_ALERTS_ENABLED = booleanPreferencesKey("anomaly_alerts_enabled")
        private val KEY_ANOMALY_SUPPRESS_AFTER_COMPLETION = booleanPreferencesKey("anomaly_suppress_after_completion")
        private val KEY_THRESHOLD_PUSH_ENABLED = booleanPreferencesKey("threshold_push_enabled")

        const val CARD_CHART = "CHART"
        const val CARD_PERIOD_SUMMARY = "PERIOD_SUMMARY"
        const val CARD_FLEET = "FLEET"
        const val CARD_DAILY_BARS = "DAILY_BARS"
        const val CARD_TIMELINE_24H = "TIMELINE_24H"
        const val CARD_ENERGY_BREAKDOWN = "ENERGY_BREAKDOWN"
    }
}
