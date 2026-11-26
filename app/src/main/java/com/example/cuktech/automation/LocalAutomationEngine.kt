package com.example.cuktech.automation

import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Safe on-device automation rule evaluator with duration hysteresis, schedule day-bitmask matching,
 * per-rule cooldowns, stale-BLE protection (>12s gap blocks execution), priority conflict arbitration,
 * and strict routing via [ChargerCommand] (`CommandSource.AUTOMATION_RULE`).
 */
class LocalAutomationEngine {

    companion object {
        const val TRIGGER_LOW_POWER_CUTOFF = "LOW_POWER_CUTOFF"
        const val TRIGGER_DAILY_SCHEDULE = "DAILY_SCHEDULE"
        const val TRIGGER_HIGH_LOAD_SCENE = "HIGH_LOAD_SCENE"
        const val TRIGGER_POWER_GUARD = "POWER_GUARD"
        const val TRIGGER_PHONE_SOC_CUTOFF = "PHONE_SOC_CUTOFF"

        const val ACTION_PORT_OFF = "PORT_OFF"
        const val ACTION_SET_SCENE_MODE = "SET_SCENE_MODE"
        const val ACTION_USB_A_LOW_CURRENT_ON = "USB_A_LOW_CURRENT_ON"
        const val ACTION_SMART_DISPLAY = "SMART_DISPLAY"

        const val STALE_TELEMETRY_MAX_AGE_MS = 12_000L

        fun dayBitForCalendar(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }
    }

    data class TriggeredRuleAction(
        val rule: AutomationRuleEntity,
        val command: ChargerCommand,
        val firedAtMs: Long,
        val extraCommands: List<ChargerCommand> = emptyList()
    )

    private val conditionSinceMs = ConcurrentHashMap<Long, Long>()
    private val portSawLoad = ConcurrentHashMap<Int, Boolean>()

    @Volatile
    var lastStaleSkipMs: Long = 0L
        private set

    @Volatile
    var lastConflictSuppressedCount: Int = 0
        private set

    fun evaluate(
        rules: List<AutomationRuleEntity>,
        ports: Map<Int, PortTelemetry>,
        settings: ChargerSettingsState,
        isAuthenticated: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        lastTelemetryReceivedMs: Long = nowMs,
        phoneBatterySocPct: Int? = null,
        phoneIsCharging: Boolean = false
    ): List<TriggeredRuleAction> {
        if (!isAuthenticated || rules.isEmpty()) return emptyList()

        // Stale BLE safety guard (#156, #193, #194): never fire rules on frozen/stale telemetry
        if (lastTelemetryReceivedMs > 0L && (nowMs - lastTelemetryReceivedMs) > STALE_TELEMETRY_MAX_AGE_MS) {
            lastStaleSkipMs = nowMs
            conditionSinceMs.clear()
            return emptyList()
        }

        // Track whether each port has actively charged >= 3W since enabling so empty ports don't immediately trip
        for (pid in 1..4) {
            val p = ports[pid]
            if (p == null || !p.enabled) {
                portSawLoad[pid] = false
            } else if (p.powerW >= 3.0) {
                portSawLoad[pid] = true
            }
        }

        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val currentMinuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dayBit = dayBitForCalendar(cal.get(Calendar.DAY_OF_WEEK))

        // Priority arbitration: lower integer priority number = higher priority
        val orderedRules = rules.sortedWith(compareBy<AutomationRuleEntity> { it.priority }.thenBy { it.id })
        val claimedPortOff = mutableSetOf<Int>()
        var claimedSceneWrite = false
        var claimedDisplayWrite = false
        var claimedUsbATrickle = false
        var suppressedCount = 0

        val triggered = mutableListOf<TriggeredRuleAction>()

        for (rule in orderedRules) {
            if (!rule.enabled) {
                conditionSinceMs.remove(rule.id)
                continue
            }

            val cooldownMs = rule.cooldownMinutes.coerceAtLeast(1) * 60_000L
            if (rule.lastTriggeredMs > 0L && (nowMs - rule.lastTriggeredMs) < cooldownMs) {
                continue
            }

            val shouldFire = when (rule.triggerType) {
                TRIGGER_LOW_POWER_CUTOFF -> {
                    val p = ports[rule.portId]
                    val armed = (portSawLoad[rule.portId] == true) || ((p?.powerW ?: 0.0) in 0.05..rule.thresholdWatts)
                    if (p != null && p.enabled && armed && p.powerW <= rule.thresholdWatts) {
                        val started = conditionSinceMs.getOrPut(rule.id) { nowMs }
                        val elapsedMs = nowMs - started
                        if (elapsedMs >= rule.holdSeconds.coerceAtLeast(10) * 1000L) {
                            conditionSinceMs.remove(rule.id)
                            true
                        } else false
                    } else {
                        conditionSinceMs.remove(rule.id)
                        false
                    }
                }

                TRIGGER_DAILY_SCHEDULE -> {
                    val dayMatches = (rule.daysMask and (1 shl dayBit)) != 0
                    val minuteDiff = currentMinuteOfDay - rule.minuteOfDay
                    dayMatches && minuteDiff in 0..1
                }

                TRIGGER_HIGH_LOAD_SCENE, TRIGGER_POWER_GUARD -> {
                    val p = ports[rule.portId]
                    val minHold = if (rule.triggerType == TRIGGER_POWER_GUARD) 2 else 3
                    if (p != null && p.enabled && p.powerW >= rule.thresholdWatts) {
                        val started = conditionSinceMs.getOrPut(rule.id) { nowMs }
                        val elapsedMs = nowMs - started
                        if (elapsedMs >= rule.holdSeconds.coerceAtLeast(minHold) * 1000L) {
                            conditionSinceMs.remove(rule.id)
                            true
                        } else false
                    } else {
                        conditionSinceMs.remove(rule.id)
                        false
                    }
                }

                TRIGGER_PHONE_SOC_CUTOFF -> {
                    val p = ports[rule.portId]
                    val targetSoc = rule.targetPhoneSocPct.coerceIn(50, 100)
                    if (p != null && p.enabled && phoneIsCharging && phoneBatterySocPct != null && phoneBatterySocPct >= targetSoc) {
                        val started = conditionSinceMs.getOrPut(rule.id) { nowMs }
                        val elapsedMs = nowMs - started
                        if (elapsedMs >= rule.holdSeconds.coerceAtLeast(10) * 1000L) {
                            conditionSinceMs.remove(rule.id)
                            true
                        } else false
                    } else {
                        conditionSinceMs.remove(rule.id)
                        false
                    }
                }

                else -> false
            }

            if (!shouldFire) continue

            val commands = buildCommands(rule)
            if (commands.isEmpty()) continue
            val primary = commands.first()

            // No-op guards
            val isNoOp = when (primary) {
                is ChargerCommand.SetSceneMode -> settings.sceneMode == primary.mode
                is ChargerCommand.SetPortEnabled -> ports[primary.portId]?.enabled == primary.enabled
                is ChargerCommand.SetUsbALowCurrent -> settings.usbALowCurrent == primary.enabled
                else -> false
            }
            if (isNoOp) continue

            // Per-tick priority conflict arbitration
            val conflictsWithHigher = when (primary) {
                is ChargerCommand.SetPortEnabled -> !primary.enabled && !claimedPortOff.add(primary.portId)
                is ChargerCommand.SetSceneMode -> {
                    if (claimedSceneWrite) true else {
                        claimedSceneWrite = true
                        false
                    }
                }
                is ChargerCommand.SetUsbALowCurrent -> {
                    if (claimedUsbATrickle) true else {
                        claimedUsbATrickle = true
                        false
                    }
                }
                is ChargerCommand.SetIdleScreenOff,
                is ChargerCommand.SetScreenTimeout,
                is ChargerCommand.SetOrientationLock -> {
                    if (claimedDisplayWrite) true else {
                        claimedDisplayWrite = true
                        false
                    }
                }
                else -> false
            }
            if (conflictsWithHigher) {
                suppressedCount++
                continue
            }

            triggered.add(
                TriggeredRuleAction(
                    rule = rule,
                    command = primary,
                    firedAtMs = nowMs,
                    extraCommands = commands.drop(1)
                )
            )
        }

        lastConflictSuppressedCount = suppressedCount
        return triggered
    }

    fun reset() {
        conditionSinceMs.clear()
        portSawLoad.clear()
    }

    private fun buildCommands(rule: AutomationRuleEntity): List<ChargerCommand> = when (rule.actionType) {
        ACTION_PORT_OFF -> listOf(ChargerCommand.SetPortEnabled(rule.portId.coerceIn(1, 4), false))
        ACTION_SET_SCENE_MODE -> listOf(ChargerCommand.SetSceneMode(rule.actionSceneMode.coerceIn(1, 4)))
        ACTION_USB_A_LOW_CURRENT_ON -> listOf(ChargerCommand.SetUsbALowCurrent(true))
        ACTION_SMART_DISPLAY -> listOf(
            ChargerCommand.SetIdleScreenOff(rule.actionIdleScreenOff),
            ChargerCommand.SetScreenTimeout(rule.actionScreenTimeout.coerceIn(0, 5)),
            ChargerCommand.SetOrientationLock(rule.actionOrientationLock)
        )
        else -> emptyList()
    }
}

