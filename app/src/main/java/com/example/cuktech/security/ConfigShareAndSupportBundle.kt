package com.example.cuktech.security

import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.db.room.BleLinkEventEntity
import com.example.cuktech.db.room.ChargingProfileEntity
import com.example.cuktech.db.room.CommandAuditEntity
import com.example.cuktech.db.room.PortDevicePresetEntity
import com.example.cuktech.power.CustomKpiAndWorkspaceEngine
import com.example.cuktech.power.CustomKpiDefinition
import com.example.cuktech.power.PowerDoctorReport
import com.example.cuktech.prefs.AppPreferences
import com.example.cuktech.telemetry.TelemetryIntegritySnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ShareableConfigImportSelection(
    val includePreferences: Boolean = true,
    val includeProfiles: Boolean = true,
    val includeRules: Boolean = true,
    val includePresets: Boolean = true,
    val includeKpis: Boolean = true
)

data class ShareableConfigBundle(
    val schemaVersion: Int = 1,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val preferencesJson: JSONObject = JSONObject(),
    val profiles: List<ChargingProfileEntity> = emptyList(),
    val rules: List<AutomationRuleEntity> = emptyList(),
    val presets: List<PortDevicePresetEntity> = emptyList(),
    val kpis: List<CustomKpiDefinition> = emptyList()
)

/**
 * Generates and imports sanitized shareable JSON config bundles (never containing MACs,
 * MiOT tokens, BLE keys, URLs, or passwords) and builds redacted Diagnostic Support Bundles.
 */
object ConfigShareAndSupportBundle {

    private const val CONFIG_SCHEMA_ID = "cuktech10_share_config"
    private const val CONFIG_SCHEMA_VERSION = 1

    fun buildShareableConfigJson(
        preferences: AppPreferences,
        profiles: List<ChargingProfileEntity>,
        rules: List<AutomationRuleEntity>,
        presets: List<PortDevicePresetEntity>,
        kpis: List<CustomKpiDefinition>
    ): String {
        val safePrefs = JSONObject().apply {
            put("themeMode", preferences.themeMode)
            put("useDynamicColor", preferences.useDynamicColor)
            put("darkPalette", preferences.darkPalette)
            put("retentionDays", preferences.retentionDays)
            put("sessionStitchGraceSec", preferences.sessionStitchGraceSec)
            put("tariffRatePerKwh", preferences.tariffRatePerKwh)
            put("touEnabled", preferences.touEnabled)
            put("tariffNightRatePerKwh", preferences.tariffNightRatePerKwh)
            put("tariffDayStartMinute", preferences.tariffDayStartMinute)
            put("tariffNightStartMinute", preferences.tariffNightStartMinute)
            put("currencyCode", preferences.currencyCode)
            put("completionHysteresisSec", preferences.completionHysteresisSec)
            put("completionPowerThresholdW", preferences.completionPowerThresholdW)
            put("quietHoursEnabled", preferences.quietHoursEnabled)
            put("quietHoursStartMinute", preferences.quietHoursStartMinute)
            put("quietHoursEndMinute", preferences.quietHoursEndMinute)
            put("thresholdAlertsEnabled", preferences.thresholdAlertsEnabled)
            put("highLoadAlertWatts", preferences.highLoadAlertWatts)
            put("lowPowerDropAlertWatts", preferences.lowPowerDropAlertWatts)
            put("bleExitAlertEnabled", preferences.bleExitAlertEnabled)
            put("bleExitRssiThreshold", preferences.bleExitRssiThreshold)
            put("activeWorkspaceId", preferences.activeWorkspaceId)
        }

        val profilesArr = JSONArray()
        for (p in profiles.filter { !it.isBuiltIn }) {
            profilesArr.put(
                JSONObject().apply {
                    put("name", LogSanitizer.sanitize(p.name, maskNetworkIdentifiers = true))
                    put("sceneMode", p.sceneMode)
                    put("portSwitchBitmap", p.portSwitchBitmap)
                    put("screenTimeout", p.screenTimeout)
                    put("usbALowCurrent", p.usbALowCurrent)
                    put("idleScreenOff", p.idleScreenOff)
                    put("orientationLock", p.orientationLock)
                    put("whLimitPort1", p.whLimitPort1)
                    put("whLimitPort2", p.whLimitPort2)
                    put("whLimitPort3", p.whLimitPort3)
                    put("whLimitPort4", p.whLimitPort4)
                }
            )
        }

        val rulesArr = JSONArray()
        for (r in rules) {
            rulesArr.put(
                JSONObject().apply {
                    put("name", LogSanitizer.sanitize(r.name, maskNetworkIdentifiers = true))
                    put("enabled", r.enabled)
                    put("triggerType", r.triggerType)
                    put("portId", r.portId)
                    put("thresholdWatts", r.thresholdWatts)
                    put("holdSeconds", r.holdSeconds)
                    put("minuteOfDay", r.minuteOfDay)
                    put("daysMask", r.daysMask)
                    put("actionType", r.actionType)
                    put("actionSceneMode", r.actionSceneMode)
                    put("cooldownMinutes", r.cooldownMinutes)
                    put("priority", r.priority)
                    put("targetPhoneSocPct", r.targetPhoneSocPct)
                    put("actionOrientationLock", r.actionOrientationLock)
                    put("actionIdleScreenOff", r.actionIdleScreenOff)
                    put("actionScreenTimeout", r.actionScreenTimeout)
                }
            )
        }

        val presetsArr = JSONArray()
        for (pr in presets) {
            presetsArr.put(
                JSONObject().apply {
                    put("portId", pr.portId)
                    put("presetType", pr.presetType)
                    // Replace potentially identifying user label with generic port slot label
                    put("deviceLabel", "Port ${pr.portId} Preset")
                    put("targetWh", pr.targetWh)
                    put("initialSocPct", pr.initialSocPct)
                    put("capacityMah", pr.capacityMah)
                    put("cellSeriesCount", pr.cellSeriesCount)
                }
            )
        }

        val root = JSONObject().apply {
            put("schema", CONFIG_SCHEMA_ID)
            put("schema_version", CONFIG_SCHEMA_VERSION)
            put("exported_at_ms", System.currentTimeMillis())
            put("sanitized_no_secrets", true)
            put("preferences", safePrefs)
            put("profiles", profilesArr)
            put("automation_rules", rulesArr)
            put("port_presets", presetsArr)
            put("custom_kpis", JSONArray(CustomKpiAndWorkspaceEngine.serializeKpis(kpis)))
        }
        return root.toString(2)
    }

    fun parseShareableConfigJson(rawJson: String): Result<ShareableConfigBundle> = runCatching {
        val trimmed = rawJson.trim()
        require(trimmed.isNotBlank()) { "Config JSON is empty" }
        val root = JSONObject(trimmed)
        require(root.optString("schema") == CONFIG_SCHEMA_ID) { "Unrecognized config schema" }
        val ver = root.optInt("schema_version", -1)
        require(ver == CONFIG_SCHEMA_VERSION) { "Unsupported config schema version: $ver" }

        val rawPrefs = root.optJSONObject("preferences") ?: JSONObject()
        // Strip any unexpected sensitive keys before passing to AppPreferencesRepository
        val safePrefs = JSONObject()
        val allowedPrefKeys = setOf(
            "themeMode", "useDynamicColor", "darkPalette", "retentionDays", "sessionStitchGraceSec",
            "tariffRatePerKwh", "touEnabled", "tariffNightRatePerKwh", "tariffDayStartMinute",
            "tariffNightStartMinute", "currencyCode", "completionHysteresisSec",
            "completionPowerThresholdW", "quietHoursEnabled", "quietHoursStartMinute",
            "quietHoursEndMinute", "thresholdAlertsEnabled", "highLoadAlertWatts",
            "lowPowerDropAlertWatts", "bleExitAlertEnabled", "bleExitRssiThreshold",
            "activeWorkspaceId"
        )
        for (key in allowedPrefKeys) {
            if (rawPrefs.has(key)) {
                safePrefs.put(key, rawPrefs.get(key))
            }
        }

        val profilesArr = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = buildList {
            for (i in 0 until profilesArr.length()) {
                val obj = profilesArr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isBlank()) continue
                add(
                    ChargingProfileEntity(
                        name = name,
                        sceneMode = obj.optInt("sceneMode", 1).coerceIn(1, 4),
                        portSwitchBitmap = obj.optInt("portSwitchBitmap", 0x0F),
                        screenTimeout = obj.optInt("screenTimeout", 1),
                        usbALowCurrent = obj.optBoolean("usbALowCurrent", false),
                        idleScreenOff = obj.optBoolean("idleScreenOff", false),
                        orientationLock = obj.optBoolean("orientationLock", false),
                        whLimitPort1 = obj.optDouble("whLimitPort1", 0.0),
                        whLimitPort2 = obj.optDouble("whLimitPort2", 0.0),
                        whLimitPort3 = obj.optDouble("whLimitPort3", 0.0),
                        whLimitPort4 = obj.optDouble("whLimitPort4", 0.0),
                        isBuiltIn = false,
                        createdAtMs = System.currentTimeMillis()
                    )
                )
            }
        }

        val rulesArr = root.optJSONArray("automation_rules") ?: JSONArray()
        val rules = buildList {
            for (i in 0 until rulesArr.length()) {
                val obj = rulesArr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val trigger = obj.optString("triggerType", "").trim()
                if (name.isBlank() || trigger.isBlank()) continue
                add(
                    AutomationRuleEntity(
                        name = name,
                        enabled = obj.optBoolean("enabled", true),
                        triggerType = trigger,
                        portId = obj.optInt("portId", 1).coerceIn(1, 4),
                        thresholdWatts = obj.optDouble("thresholdWatts", 1.5),
                        holdSeconds = obj.optInt("holdSeconds", 180),
                        minuteOfDay = obj.optInt("minuteOfDay", 23 * 60).coerceIn(0, 1439),
                        daysMask = obj.optInt("daysMask", 0b1111111),
                        actionType = obj.optString("actionType", "PORT_OFF"),
                        actionSceneMode = obj.optInt("actionSceneMode", 1).coerceIn(1, 4),
                        cooldownMinutes = obj.optInt("cooldownMinutes", 15).coerceAtLeast(1),
                        createdAtMs = System.currentTimeMillis(),
                        priority = obj.optInt("priority", 10),
                        targetPhoneSocPct = obj.optInt("targetPhoneSocPct", 85),
                        actionOrientationLock = obj.optBoolean("actionOrientationLock", false),
                        actionIdleScreenOff = obj.optBoolean("actionIdleScreenOff", false),
                        actionScreenTimeout = obj.optInt("actionScreenTimeout", 1)
                    )
                )
            }
        }

        val presetsArr = root.optJSONArray("port_presets") ?: JSONArray()
        val presets = buildList {
            for (i in 0 until presetsArr.length()) {
                val obj = presetsArr.optJSONObject(i) ?: continue
                val portId = obj.optInt("portId", -1)
                if (portId !in 1..4) continue
                add(
                    PortDevicePresetEntity(
                        portId = portId,
                        presetType = obj.optString("presetType", "CUSTOM"),
                        deviceLabel = obj.optString("deviceLabel", "Port $portId Preset"),
                        targetWh = obj.optDouble("targetWh", 19.25).coerceIn(1.0, 500.0),
                        initialSocPct = obj.optInt("initialSocPct", 20).coerceIn(0, 99),
                        capacityMah = obj.optInt("capacityMah", 5000).coerceIn(100, 50_000),
                        cellSeriesCount = obj.optInt("cellSeriesCount", 1).coerceIn(1, 3),
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
        }

        val kpis = CustomKpiAndWorkspaceEngine.deserializeKpis(
            root.optJSONArray("custom_kpis")?.toString() ?: "[]"
        )

        ShareableConfigBundle(
            schemaVersion = ver,
            exportedAtMs = root.optLong("exported_at_ms", System.currentTimeMillis()),
            preferencesJson = safePrefs,
            profiles = profiles,
            rules = rules,
            presets = presets,
            kpis = kpis
        )
    }

    fun buildAnonymizedSupportBundle(
        stackDiagnosticsText: String,
        doctorReport: PowerDoctorReport,
        integrity: TelemetryIntegritySnapshot,
        linkEvents: List<BleLinkEventEntity>,
        commandAudit: List<CommandAuditEntity>,
        recentLogLines: List<String>
    ): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("=== CUKTECH 10 ULTRA ANONYMIZED SUPPORT BUNDLE ===")
        sb.appendLine("Generated: ${fmt.format(Date())}")
        sb.appendLine("Privacy: MAC addresses, LAN IPs, tokens, and secrets stripped.")
        sb.appendLine()
        sb.appendLine("--- POWER DOCTOR SUMMARY ---")
        sb.appendLine("Overall Score: ${doctorReport.overallScore}/100 (${doctorReport.grade.name})")
        for (cat in doctorReport.categories) {
            sb.appendLine("  * ${cat.id} (${cat.weightPct}%): ${cat.score}/100 [${cat.detailText}]")
        }
        for (f in doctorReport.findings) {
            sb.appendLine("  ! [${f.severity}] ${f.titleKey}: ${f.metricDetail}")
        }
        sb.appendLine()
        sb.appendLine("--- TELEMETRY INTEGRITY ---")
        sb.appendLine("Confidence: ${integrity.confidenceScore}% (${integrity.confidenceReasonKey})")
        sb.appendLine("Cadence: ${integrity.avgSampleIntervalMs}ms · Jitter: ${integrity.jitterMs}ms · 24h Uptime: ${String.format(Locale.US, "%.1f", integrity.uptimePct24h)}%")
        sb.appendLine("Direct samples: ${integrity.directSamplesCount} · Bridge samples: ${integrity.bridgeSamplesCount} · Open blind gaps: ${integrity.openBlindGapsCount}")
        sb.appendLine()
        if (stackDiagnosticsText.isNotBlank()) {
            sb.appendLine("--- STACK DIAGNOSTICS ---")
            sb.appendLine(stackDiagnosticsText)
            sb.appendLine()
        }
        sb.appendLine("--- RECENT BLE LINK EVENTS (${linkEvents.size.coerceAtMost(20)}) ---")
        for (ev in linkEvents.take(20)) {
            sb.appendLine("${fmt.format(Date(ev.timestampMs))} | ${ev.eventType} | RSSI=${ev.rssiDbm} | ${ev.detail}")
        }
        sb.appendLine()
        sb.appendLine("--- RECENT COMMAND AUDIT (${commandAudit.size.coerceAtMost(20)}) ---")
        for (cmd in commandAudit.take(20)) {
            sb.appendLine("${fmt.format(Date(cmd.timestampMs))} | ${cmd.source} | ${cmd.commandType} P${cmd.targetPortId} | accepted=${cmd.accepted} (${cmd.latencyMs}ms)")
        }
        if (recentLogLines.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- RECENT CONSOLE LOGS (${recentLogLines.size.coerceAtMost(30)}) ---")
            for (line in recentLogLines.takeLast(30)) {
                sb.appendLine(line)
            }
        }

        return LogSanitizer.sanitize(sb.toString().trim(), maskNetworkIdentifiers = true)
    }
}
