package com.example.cuktech.automation

import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.db.room.DeadlineTaskEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import kotlin.math.abs
import kotlin.math.roundToInt

data class AutomationConflictItem(
    val severity: String, // CRITICAL | WARNING | INFO
    val code: String,
    val ruleNames: List<String>,
    val detail: String
)

data class AutomationSafetyReport(
    val conflicts: List<AutomationConflictItem> = emptyList(),
    val enabledCount: Int = 0
) {
    val hasCritical: Boolean get() = conflicts.any { it.severity == "CRITICAL" }
}

data class AutomationSimulationEvent(
    val timestampMs: Long,
    val ruleId: Long,
    val ruleName: String,
    val actionSummary: String
)

data class AutomationSimulationReport(
    val evaluatedSamples: Int = 0,
    val skippedStaleGaps: Int = 0,
    val triggerCountsByRuleId: Map<Long, Int> = emptyMap(),
    val events: List<AutomationSimulationEvent> = emptyList()
)

enum class DeadlineReadinessStatus {
    COMPLETED,
    ON_TRACK,
    AT_RISK,
    WILL_MISS,
    PAUSED_NO_LOAD
}

data class DeadlineReadinessItem(
    val task: DeadlineTaskEntity,
    val currentPortWatts: Double,
    val deliveredWh: Double,
    val remainingWh: Double,
    val estimatedMinutesLeft: Int?,
    val availableMinutesLeft: Int,
    val marginMinutes: Int?,
    val status: DeadlineReadinessStatus
)

data class NfcActionCommand(
    val uri: String,
    val command: ChargerCommand,
    val description: String
)

/**
 * Stage 6 pure-Kotlin domain engine:
 * - Rule conflict, feedback-cycle, and Power-Guard software safety analyzer (#165, #167, #168, #195)
 * - Dry-run simulator against historical telemetry samples (#169, #190)
 * - Priority Queue & Deadline Charging readiness calculator (#23, #24, #170-#172, #289)
 * - NFC URI action parser (`cuktech://action/...`) routed solely to [CommandDispatcher] (#179, #265)
 */
object AutomationSafetyAndDeadlineEngine {

    fun detectConflictsAndSafety(rules: List<AutomationRuleEntity>): AutomationSafetyReport {
        val enabled = rules.filter { it.enabled }
        val conflicts = mutableListOf<AutomationConflictItem>()

        // 1. Pairwise conflicts
        for (i in enabled.indices) {
            for (j in (i + 1) until enabled.size) {
                val a = enabled[i]
                val b = enabled[j]

                // Direct schedule overlap with opposing scene modes or port-off collision
                if (a.triggerType == LocalAutomationEngine.TRIGGER_DAILY_SCHEDULE &&
                    b.triggerType == LocalAutomationEngine.TRIGGER_DAILY_SCHEDULE &&
                    (a.daysMask and b.daysMask) != 0 &&
                    abs(a.minuteOfDay - b.minuteOfDay) <= 2
                ) {
                    if (a.actionType == LocalAutomationEngine.ACTION_SET_SCENE_MODE &&
                        b.actionType == LocalAutomationEngine.ACTION_SET_SCENE_MODE &&
                        a.actionSceneMode != b.actionSceneMode
                    ) {
                        conflicts += AutomationConflictItem(
                            severity = "CRITICAL",
                            code = "SCHEDULE_SCENE_CONFLICT",
                            ruleNames = listOf(a.name, b.name),
                            detail = "P${a.priority} vs P${b.priority}: Scene ${a.actionSceneMode} vs ${b.actionSceneMode}"
                        )
                    }
                }

                // Overlapping power thresholds on the same port (e.g., low-power cutoff >= high-load or power guard)
                if (a.portId == b.portId) {
                    val low = listOf(a, b).firstOrNull { it.triggerType == LocalAutomationEngine.TRIGGER_LOW_POWER_CUTOFF }
                    val high = listOf(a, b).firstOrNull {
                        it.triggerType == LocalAutomationEngine.TRIGGER_HIGH_LOAD_SCENE ||
                            it.triggerType == LocalAutomationEngine.TRIGGER_POWER_GUARD
                    }
                    if (low != null && high != null && low.thresholdWatts >= high.thresholdWatts) {
                        conflicts += AutomationConflictItem(
                            severity = "CRITICAL",
                            code = "OPPOSING_WATT_BAND",
                            ruleNames = listOf(low.name, high.name),
                            detail = "Port ${low.portId}: <=${low.thresholdWatts}W overlaps >=${high.thresholdWatts}W"
                        )
                    }

                    // Oscillation risk: rapid scene cycling rules on the same port
                    if (a.actionType == LocalAutomationEngine.ACTION_SET_SCENE_MODE &&
                        b.actionType == LocalAutomationEngine.ACTION_SET_SCENE_MODE &&
                        a.actionSceneMode != b.actionSceneMode &&
                        (a.cooldownMinutes <= 5 || b.cooldownMinutes <= 5)
                    ) {
                        conflicts += AutomationConflictItem(
                            severity = "WARNING",
                            code = "SCENE_CYCLE_RISK",
                            ruleNames = listOf(a.name, b.name),
                            detail = "Port ${a.portId}: cooldown <= 5m may oscillate scenes"
                        )
                    }
                }
            }
        }

        // 2. Per-rule software safety sanity warnings
        for (rule in enabled) {
            if (rule.triggerType == LocalAutomationEngine.TRIGGER_POWER_GUARD && rule.holdSeconds < 5) {
                conflicts += AutomationConflictItem(
                    severity = "WARNING",
                    code = "POWER_GUARD_SHORT_HOLD",
                    ruleNames = listOf(rule.name),
                    detail = "Software Power Guard (<5s hold) may trip on normal USB-PD handshake spikes."
                )
            }
            if (rule.triggerType == LocalAutomationEngine.TRIGGER_LOW_POWER_CUTOFF && rule.holdSeconds < 30) {
                conflicts += AutomationConflictItem(
                    severity = "WARNING",
                    code = "LOW_POWER_SHORT_HOLD",
                    ruleNames = listOf(rule.name),
                    detail = "Hold <30s may cut off devices during intermittent screen/battery pauses."
                )
            }
        }

        return AutomationSafetyReport(
            conflicts = conflicts,
            enabledCount = enabled.size
        )
    }

    fun simulateRulesOnHistory(
        rules: List<AutomationRuleEntity>,
        telemetrySamples: List<TelemetrySampleEntity>,
        initialSceneMode: Int = 1,
        phoneSocPct: Int? = null,
        phoneIsCharging: Boolean = false
    ): AutomationSimulationReport {
        val enabled = rules.filter { it.enabled }.map { it.copy(lastTriggeredMs = 0L) }.toMutableList()
        if (enabled.isEmpty() || telemetrySamples.isEmpty()) {
            return AutomationSimulationReport(evaluatedSamples = telemetrySamples.size)
        }

        val engine = LocalAutomationEngine()
        var currentSettings = ChargerSettingsState(sceneMode = initialSceneMode)
        val portStates = mutableMapOf(
            1 to PortTelemetry(1, "USB-C1", enabled = true),
            2 to PortTelemetry(2, "USB-C2", enabled = true),
            3 to PortTelemetry(3, "USB-C3", enabled = true),
            4 to PortTelemetry(4, "USB-A", enabled = true)
        )

        val buckets = telemetrySamples
            .groupBy { (it.timestampMs / 2000L) * 2000L }
            .entries
            .sortedBy { it.key }

        val counts = mutableMapOf<Long, Int>()
        val events = mutableListOf<AutomationSimulationEvent>()
        var staleGapSkips = 0
        var prevTs = 0L

        for ((bucketTs, rows) in buckets) {
            if (prevTs > 0L && (bucketTs - prevTs) > LocalAutomationEngine.STALE_TELEMETRY_MAX_AGE_MS) {
                staleGapSkips++
                // Inject a stale-age check so engine clears transient hysteresis timers across BLE gaps
                engine.evaluate(
                    rules = enabled,
                    ports = portStates,
                    settings = currentSettings,
                    isAuthenticated = true,
                    nowMs = bucketTs - 1L,
                    lastTelemetryReceivedMs = prevTs,
                    phoneBatterySocPct = phoneSocPct,
                    phoneIsCharging = phoneIsCharging
                )
            }
            for (r in rows) {
                if (r.portId in 1..4) {
                    portStates[r.portId] = PortTelemetry(
                        portId = r.portId,
                        portLabel = portStates[r.portId]?.portLabel ?: "P${r.portId}",
                        voltageV = r.voltageV,
                        currentA = r.currentA,
                        powerW = r.powerW,
                        protocolName = r.protocolName,
                        cumulativeWh = r.cumulativeWh,
                        enabled = portStates[r.portId]?.enabled ?: true
                    )
                }
            }

            val fired = engine.evaluate(
                rules = enabled,
                ports = portStates,
                settings = currentSettings,
                isAuthenticated = true,
                nowMs = bucketTs,
                lastTelemetryReceivedMs = bucketTs,
                phoneBatterySocPct = phoneSocPct,
                phoneIsCharging = phoneIsCharging
            )

            for (act in fired) {
                counts[act.rule.id] = (counts[act.rule.id] ?: 0) + 1
                val idx = enabled.indexOfFirst { it.id == act.rule.id }
                if (idx >= 0) {
                    enabled[idx] = enabled[idx].copy(lastTriggeredMs = bucketTs)
                }
                when (val cmd = act.command) {
                    is ChargerCommand.SetSceneMode -> currentSettings = currentSettings.copy(sceneMode = cmd.mode)
                    is ChargerCommand.SetPortEnabled -> {
                        portStates[cmd.portId]?.let { p ->
                            portStates[cmd.portId] = p.copy(enabled = cmd.enabled, powerW = if (cmd.enabled) p.powerW else 0.0)
                        }
                    }
                    else -> {}
                }
                if (events.size < 40) {
                    events += AutomationSimulationEvent(
                        timestampMs = bucketTs,
                        ruleId = act.rule.id,
                        ruleName = act.rule.name,
                        actionSummary = act.command.javaClass.simpleName
                    )
                }
            }
            prevTs = bucketTs
        }

        return AutomationSimulationReport(
            evaluatedSamples = telemetrySamples.size,
            skippedStaleGaps = staleGapSkips,
            triggerCountsByRuleId = counts,
            events = events
        )
    }

    fun evaluateDeadlineQueue(
        tasks: List<DeadlineTaskEntity>,
        ports: Map<Int, PortTelemetry>,
        activeSessions: Map<Int, ChargeSessionRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): List<DeadlineReadinessItem> {
        return tasks
            .sortedWith(compareBy<DeadlineTaskEntity> { !it.enabled }.thenBy { it.priority }.thenBy { it.deadlineEpochMs })
            .map { task ->
                val p = ports[task.portId]
                val watts = if (p != null && p.enabled) p.powerW else 0.0
                val delivered = activeSessions[task.portId]?.whAccumulated
                    ?: p?.cumulativeWh
                    ?: 0.0
                val remaining = (task.targetWh - delivered).coerceAtLeast(0.0)
                val availableMin = ((task.deadlineEpochMs - nowMs) / 60_000L).toInt().coerceAtLeast(0)

                val (etaMin, marginMin, status) = when {
                    remaining <= 0.05 -> Triple(0, availableMin, DeadlineReadinessStatus.COMPLETED)
                    watts < 0.8 -> Triple(null, null, DeadlineReadinessStatus.PAUSED_NO_LOAD)
                    else -> {
                        val eta = ((remaining / watts) * 60.0).roundToInt().coerceAtLeast(1)
                        val margin = availableMin - eta
                        val st = when {
                            margin >= 15 -> DeadlineReadinessStatus.ON_TRACK
                            margin >= 0 -> DeadlineReadinessStatus.AT_RISK
                            else -> DeadlineReadinessStatus.WILL_MISS
                        }
                        Triple(eta, margin, st)
                    }
                }

                DeadlineReadinessItem(
                    task = task,
                    currentPortWatts = watts,
                    deliveredWh = delivered,
                    remainingWh = remaining,
                    estimatedMinutesLeft = etaMin,
                    availableMinutesLeft = availableMin,
                    marginMinutes = marginMin,
                    status = status
                )
            }
    }

    fun parseNfcActionUri(
        rawUri: String?,
        currentPorts: Map<Int, PortTelemetry> = emptyMap()
    ): NfcActionCommand? {
        if (rawUri.isNullOrBlank()) return null
        val trimmed = rawUri.trim()
        if (!trimmed.startsWith("cuktech://action/", ignoreCase = true)) return null
        val segments = trimmed.substringAfter("cuktech://action/", "")
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        return when (segments[0].lowercase()) {
            "scene" -> {
                val mode = segments.getOrNull(1)?.toIntOrNull() ?: return null
                if (mode !in 1..4) return null
                NfcActionCommand(trimmed, ChargerCommand.SetSceneMode(mode), "Scene $mode")
            }
            "port" -> {
                val portId = segments.getOrNull(1)?.toIntOrNull() ?: return null
                if (portId !in 1..4) return null
                val verb = segments.getOrNull(2)?.lowercase() ?: "toggle"
                val enabled = when (verb) {
                    "on", "enable", "1", "true" -> true
                    "off", "disable", "0", "false" -> false
                    "toggle" -> !(currentPorts[portId]?.enabled ?: true)
                    else -> return null
                }
                NfcActionCommand(trimmed, ChargerCommand.SetPortEnabled(portId, enabled), "Port $portId -> $verb")
            }
            "usb_a_trickle" -> {
                val verb = segments.getOrNull(1)?.lowercase() ?: "on"
                val enable = verb != "off" && verb != "false" && verb != "0"
                NfcActionCommand(trimmed, ChargerCommand.SetUsbALowCurrent(enable), "USB-A Trickle $verb")
            }
            "night_display" -> {
                NfcActionCommand(trimmed, ChargerCommand.SetIdleScreenOff(true), "Night Display Off")
            }
            "killswitch_restore" -> {
                NfcActionCommand(trimmed, ChargerCommand.RestoreKillswitch, "Restore Killswitch")
            }
            else -> null
        }
    }
}
