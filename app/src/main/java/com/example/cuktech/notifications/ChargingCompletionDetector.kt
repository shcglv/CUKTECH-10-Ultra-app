package com.example.cuktech.notifications

import com.example.cuktech.prefs.AppPreferences

data class CompletionAlertEvent(
    val portId: Int,
    val portLabel: String,
    val sessionWh: Double,
    val peakWatts: Double,
    val completedAtMs: Long,
    val repeatIndex: Int,
    val suppressedByQuietHours: Boolean
)

/**
 * Pure state machine detecting genuine charging completion per port using hysteresis +
 * sustained low-draw plateau while distinguishing completion from:
 * - Immediate physical cable unplugging (cliff drop straight to <= 0.15W)
 * - BLE disconnects
 * - Short load dips / renegotiation pauses (power rebounds above hysteresis band)
 * - Manual port OFF, Wh cutoff limits, countdown expiry, and Soft Reset
 */
class ChargingCompletionDetector {

    private data class PortPhase(
        var wasMeaningfullyCharging: Boolean = false,
        var peakWatts: Double = 0.0,
        var lastWatts: Double = 0.0,
        var trickleCandidateSinceMs: Long? = null,
        var completedNotifiedAtMs: Long? = null,
        var repeatsSent: Int = 0,
        var lastAlertSentMs: Long = 0L,
        var suppressedUntilReplug: Boolean = false
    )

    private val phases = mapOf(
        1 to PortPhase(),
        2 to PortPhase(),
        3 to PortPhase(),
        4 to PortPhase()
    )

    fun onTelemetrySample(
        portId: Int,
        portLabel: String,
        powerW: Double,
        enabled: Boolean,
        sessionWh: Double,
        nowMs: Long,
        prefs: AppPreferences,
        minuteOfDay: Int = AppPreferences.currentLocalMinuteOfDay()
    ): CompletionAlertEvent? {
        val state = phases[portId] ?: return null
        val thresholdW = prefs.completionPowerThresholdW.coerceIn(0.2, 10.0)
        val resumeBandW = (thresholdW * 1.4).coerceAtLeast(thresholdW + 0.5)
        val hysteresisMs = prefs.completionHysteresisSec.coerceIn(10, 600) * 1000L

        if (!enabled || state.suppressedUntilReplug) {
            if (!enabled) {
                resetPort(portId)
            } else if (powerW >= MEANINGFUL_CHARGE_W) {
                // New real load started -> re-arm detector
                state.suppressedUntilReplug = false
                state.wasMeaningfullyCharging = true
                state.peakWatts = powerW
                state.trickleCandidateSinceMs = null
                state.completedNotifiedAtMs = null
                state.repeatsSent = 0
                state.lastAlertSentMs = 0L
            }
            state.lastWatts = powerW
            return null
        }

        // 1. Active charging load (>= resumeBandW): arm detector and clear trickle/completed timers
        if (powerW >= resumeBandW) {
            if (powerW >= MEANINGFUL_CHARGE_W || state.wasMeaningfullyCharging) {
                if (state.completedNotifiedAtMs != null) {
                    // Device began drawing significant power again (e.g. second stage or new device)
                    state.completedNotifiedAtMs = null
                    state.repeatsSent = 0
                    state.lastAlertSentMs = 0L
                }
                state.wasMeaningfullyCharging = true
                if (powerW > state.peakWatts) state.peakWatts = powerW
            }
            state.trickleCandidateSinceMs = null
            state.lastWatts = powerW
            return null
        }

        // 2. If never meaningfully charged yet, nothing to complete
        if (!state.wasMeaningfullyCharging) {
            state.lastWatts = powerW
            return null
        }

        // 3. Cliff drop directly from high active load (> 5.0W or > threshold * 2.5) down to near-zero (<= 0.15W)
        // means the user physically unplugged the cable -> do NOT confuse with completed trickle plateau.
        val abruptUnplugCutoff = maxOf(5.0, thresholdW * 2.5)
        if (powerW <= CABLE_UNPLUG_WATTS && state.trickleCandidateSinceMs == null && state.lastWatts >= abruptUnplugCutoff) {
            resetPort(portId)
            return null
        }

        // Also if completed previously and cable is now physically unplugged (0W steady or <= 0.15W after notification)
        if (powerW <= CABLE_UNPLUG_WATTS && state.completedNotifiedAtMs != null) {
            resetPort(portId)
            return null
        }

        // 4. Inside trickle/low-draw band (powerW < resumeBandW)
        if (powerW <= thresholdW) {
            if (state.completedNotifiedAtMs == null) {
                val startMs = state.trickleCandidateSinceMs ?: nowMs.also {
                    state.trickleCandidateSinceMs = it
                }
                state.lastWatts = powerW
                if (nowMs - startMs >= hysteresisMs) {
                    state.completedNotifiedAtMs = nowMs
                    state.lastAlertSentMs = nowMs
                    return CompletionAlertEvent(
                        portId = portId,
                        portLabel = portLabel,
                        sessionWh = sessionWh,
                        peakWatts = state.peakWatts,
                        completedAtMs = nowMs,
                        repeatIndex = 0,
                        suppressedByQuietHours = prefs.isWithinQuietHours(minuteOfDay)
                    )
                }
            } else if (prefs.completionRepeatEnabled && state.repeatsSent < prefs.completionMaxRepeats) {
                val intervalMs = prefs.completionRepeatIntervalMin.coerceIn(1, 60) * 60_000L
                if (nowMs - state.lastAlertSentMs >= intervalMs) {
                    state.repeatsSent += 1
                    state.lastAlertSentMs = nowMs
                    state.lastWatts = powerW
                    return CompletionAlertEvent(
                        portId = portId,
                        portLabel = portLabel,
                        sessionWh = sessionWh,
                        peakWatts = state.peakWatts,
                        completedAtMs = nowMs,
                        repeatIndex = state.repeatsSent,
                        suppressedByQuietHours = prefs.isWithinQuietHours(minuteOfDay)
                    )
                }
            }
        }

        state.lastWatts = powerW
        return null
    }

    fun onTick(
        portId: Int,
        portLabel: String,
        sessionWh: Double,
        nowMs: Long,
        prefs: AppPreferences,
        minuteOfDay: Int = AppPreferences.currentLocalMinuteOfDay()
    ): CompletionAlertEvent? {
        val state = phases[portId] ?: return null
        if (!state.wasMeaningfullyCharging || state.suppressedUntilReplug) return null
        val startMs = state.trickleCandidateSinceMs
        val hysteresisMs = prefs.completionHysteresisSec.coerceIn(10, 600) * 1000L
        if (state.completedNotifiedAtMs == null && startMs != null && nowMs - startMs >= hysteresisMs) {
            state.completedNotifiedAtMs = nowMs
            state.lastAlertSentMs = nowMs
            return CompletionAlertEvent(
                portId = portId,
                portLabel = portLabel,
                sessionWh = sessionWh,
                peakWatts = state.peakWatts,
                completedAtMs = nowMs,
                repeatIndex = 0,
                suppressedByQuietHours = prefs.isWithinQuietHours(minuteOfDay)
            )
        }
        if (state.completedNotifiedAtMs != null && prefs.completionRepeatEnabled &&
            state.repeatsSent < prefs.completionMaxRepeats
        ) {
            val intervalMs = prefs.completionRepeatIntervalMin.coerceIn(1, 60) * 60_000L
            if (nowMs - state.lastAlertSentMs >= intervalMs) {
                state.repeatsSent += 1
                state.lastAlertSentMs = nowMs
                return CompletionAlertEvent(
                    portId = portId,
                    portLabel = portLabel,
                    sessionWh = sessionWh,
                    peakWatts = state.peakWatts,
                    completedAtMs = nowMs,
                    repeatIndex = state.repeatsSent,
                    suppressedByQuietHours = prefs.isWithinQuietHours(minuteOfDay)
                )
            }
        }
        return null
    }

    fun notifyBleDisconnected() {
        for (portId in 1..4) {
            resetPort(portId)
        }
    }

    fun notifyManualStop(portId: Int) {
        resetPort(portId)
    }

    fun notifySafetyCutoff(portId: Int) {
        phases[portId]?.apply {
            trickleCandidateSinceMs = null
            wasMeaningfullyCharging = false
            suppressedUntilReplug = true
        }
    }

    fun notifySoftResetAll() {
        for (portId in 1..4) {
            resetPort(portId)
        }
    }

    fun resetPort(portId: Int) {
        phases[portId]?.let {
            it.wasMeaningfullyCharging = false
            it.peakWatts = 0.0
            it.lastWatts = 0.0
            it.trickleCandidateSinceMs = null
            it.completedNotifiedAtMs = null
            it.repeatsSent = 0
            it.lastAlertSentMs = 0L
            it.suppressedUntilReplug = false
        }
    }

    companion object {
        const val MEANINGFUL_CHARGE_W = 2.5
        const val CABLE_UNPLUG_WATTS = 0.15
    }
}
