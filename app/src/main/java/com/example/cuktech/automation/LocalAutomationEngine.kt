package com.example.cuktech.automation

import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Safe on-device automation rule evaluator with duration hysteresis, schedule day-bitmask matching,
 * per-rule cooldowns, and strict routing via [ChargerCommand] (`CommandSource.AUTOMATION_RULE`).
 */
class LocalAutomationEngine {

    companion object {
        const val TRIGGER_LOW_POWER_CUTOFF = "LOW_POWER_CUTOFF"
        const val TRIGGER_DAILY_SCHEDULE = "DAILY_SCHEDULE"
        const val TRIGGER_HIGH_LOAD_SCENE = "HIGH_LOAD_SCENE"

        const val ACTION_PORT_OFF = "PORT_OFF"
        const val ACTION_SET_SCENE_MODE = "SET_SCENE_MODE"

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
        val firedAtMs: Long
    )

    private val conditionSinceMs = ConcurrentHashMap<Long, Long>()
    private val portSawLoad = ConcurrentHashMap<Int, Boolean>()

    fun evaluate(
        rules: List<AutomationRuleEntity>,
        ports: Map<Int, PortTelemetry>,
        settings: ChargerSettingsState,
        isAuthenticated: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): List<TriggeredRuleAction> {
        if (!isAuthenticated || rules.isEmpty()) return emptyList()

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

        val triggered = mutableListOf<TriggeredRuleAction>()

        for (rule in rules) {
            if (!rule.enabled) {
                conditionSinceMs.remove(rule.id)
                continue
            }

            val cooldownMs = rule.cooldownMinutes.coerceAtLeast(1) * 60_000L
            if (rule.lastTriggeredMs > 0L && (nowMs - rule.lastTriggeredMs) < cooldownMs) {
                continue
            }

            when (rule.triggerType) {
                TRIGGER_LOW_POWER_CUTOFF -> {
                    val p = ports[rule.portId]
                    val armed = (portSawLoad[rule.portId] == true) || ((p?.powerW ?: 0.0) in 0.05..rule.thresholdWatts)
                    if (p != null && p.enabled && armed && p.powerW <= rule.thresholdWatts) {
                        val started = conditionSinceMs.getOrPut(rule.id) { nowMs }
                        val elapsedMs = nowMs - started
                        if (elapsedMs >= rule.holdSeconds.coerceAtLeast(10) * 1000L) {
                            conditionSinceMs.remove(rule.id)
                            buildCommand(rule)?.let { cmd ->
                                triggered.add(TriggeredRuleAction(rule, cmd, nowMs))
                            }
                        }
                    } else {
                        conditionSinceMs.remove(rule.id)
                    }
                }

                TRIGGER_DAILY_SCHEDULE -> {
                    val dayMatches = (rule.daysMask and (1 shl dayBit)) != 0
                    val minuteDiff = currentMinuteOfDay - rule.minuteOfDay
                    if (dayMatches && minuteDiff in 0..1) {
                        // Guard redundant scene mode write if already active
                        val cmd = buildCommand(rule)
                        val isNoOp = cmd is ChargerCommand.SetSceneMode && settings.sceneMode == cmd.mode ||
                            (cmd is ChargerCommand.SetPortEnabled && ports[cmd.portId]?.enabled == cmd.enabled)
                        if (cmd != null && !isNoOp) {
                            triggered.add(TriggeredRuleAction(rule, cmd, nowMs))
                        }
                    }
                }

                TRIGGER_HIGH_LOAD_SCENE -> {
                    val p = ports[rule.portId]
                    if (p != null && p.enabled && p.powerW >= rule.thresholdWatts) {
                        val started = conditionSinceMs.getOrPut(rule.id) { nowMs }
                        val elapsedMs = nowMs - started
                        if (elapsedMs >= rule.holdSeconds.coerceAtLeast(3) * 1000L) {
                            conditionSinceMs.remove(rule.id)
                            val cmd = buildCommand(rule)
                            val isNoOp = cmd is ChargerCommand.SetSceneMode && settings.sceneMode == cmd.mode
                            if (cmd != null && !isNoOp) {
                                triggered.add(TriggeredRuleAction(rule, cmd, nowMs))
                            }
                        }
                    } else {
                        conditionSinceMs.remove(rule.id)
                    }
                }
            }
        }
        return triggered
    }

    fun reset() {
        conditionSinceMs.clear()
        portSawLoad.clear()
    }

    private fun buildCommand(rule: AutomationRuleEntity): ChargerCommand? = when (rule.actionType) {
        ACTION_PORT_OFF -> ChargerCommand.SetPortEnabled(rule.portId.coerceIn(1, 4), false)
        ACTION_SET_SCENE_MODE -> ChargerCommand.SetSceneMode(rule.actionSceneMode.coerceIn(1, 4))
        else -> null
    }
}
