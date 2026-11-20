package com.example.cuktech.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AnomalyType {
    CABLE_RENEGOTIATION_INSTABILITY,
    POWER_COLLAPSE_LOOP,
    BLE_FLAP_LOOP
}

data class AnomalyAlert(
    val id: String,
    val type: AnomalyType,
    val portId: Int?,
    val portLabel: String?,
    val countInWindow: Int,
    val summary: String,
    val detectedAtMs: Long
)

/**
 * Rolling-window detector for unstable cables, repeated PD/PPS/UFCS renegotiation collapses
 * back to 5V Normal, and frequent unplanned BLE disconnect/reconnect flapping.
 */
class CableAnomalyDetector(
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class PortHistory(
        var prevPowerW: Double = 0.0,
        var prevVoltageV: Double = 0.0,
        var prevProtocol: String = "Normal",
        var prevEnabled: Boolean = true,
        val renegotiationDropsMs: ArrayDeque<Long> = ArrayDeque(),
        val collapseDropsMs: ArrayDeque<Long> = ArrayDeque(),
        var lastAlertedMs: Long = 0L
    )

    private val portHistories = mapOf(
        1 to PortHistory(),
        2 to PortHistory(),
        3 to PortHistory(),
        4 to PortHistory()
    )
    private val bleDisconnectEventsMs = ArrayDeque<Long>()
    private var lastBleFlapAlertMs = 0L

    private val _alerts = MutableStateFlow<List<AnomalyAlert>>(emptyList())
    val alerts: StateFlow<List<AnomalyAlert>> = _alerts.asStateFlow()

    fun onPortSample(
        portId: Int,
        portLabel: String,
        voltageV: Double,
        powerW: Double,
        protocolName: String,
        enabled: Boolean,
        nowMs: Long = clockMs()
    ): AnomalyAlert? {
        val hist = portHistories[portId] ?: return null
        if (!enabled) {
            hist.prevPowerW = powerW
            hist.prevVoltageV = voltageV
            hist.prevProtocol = protocolName
            hist.prevEnabled = false
            return null
        }

        var triggered: AnomalyAlert? = null

        val wasFastProtocol = hist.prevProtocol.contains("PD", ignoreCase = true) ||
            hist.prevProtocol.contains("PPS", ignoreCase = true) ||
            hist.prevProtocol.contains("UFCS", ignoreCase = true) ||
            hist.prevProtocol.contains("SCP", ignoreCase = true)
        val nowSlowFallback = protocolName.equals("Normal", ignoreCase = true) ||
            protocolName.contains("5V", ignoreCase = true)

        if (hist.prevEnabled && wasFastProtocol && hist.prevVoltageV >= 8.5 && voltageV in 3.5..5.7 && nowSlowFallback) {
            hist.renegotiationDropsMs.addLast(nowMs)
            pruneOld(hist.renegotiationDropsMs, nowMs, WINDOW_MS)
            if (hist.renegotiationDropsMs.size >= RENEGOTIATION_THRESHOLD &&
                (hist.lastAlertedMs == 0L || nowMs - hist.lastAlertedMs >= COOLDOWN_MS)
            ) {
                hist.lastAlertedMs = nowMs
                triggered = AnomalyAlert(
                    id = "reneg_port_$portId",
                    type = AnomalyType.CABLE_RENEGOTIATION_INSTABILITY,
                    portId = portId,
                    portLabel = portLabel,
                    countInWindow = hist.renegotiationDropsMs.size,
                    summary = "$portLabel: ${hist.renegotiationDropsMs.size}× PD/PPS/UFCS → 5V fallback in 2 min",
                    detectedAtMs = nowMs
                )
            }
        } else if (hist.prevEnabled && hist.prevPowerW >= 15.0 && powerW <= 0.5) {
            hist.collapseDropsMs.addLast(nowMs)
            pruneOld(hist.collapseDropsMs, nowMs, WINDOW_MS)
            if (hist.collapseDropsMs.size >= COLLAPSE_THRESHOLD &&
                (hist.lastAlertedMs == 0L || nowMs - hist.lastAlertedMs >= COOLDOWN_MS)
            ) {
                hist.lastAlertedMs = nowMs
                triggered = AnomalyAlert(
                    id = "collapse_port_$portId",
                    type = AnomalyType.POWER_COLLAPSE_LOOP,
                    portId = portId,
                    portLabel = portLabel,
                    countInWindow = hist.collapseDropsMs.size,
                    summary = "$portLabel: ${hist.collapseDropsMs.size}× sudden load collapses (≥15W → 0W)",
                    detectedAtMs = nowMs
                )
            }
        }

        hist.prevPowerW = powerW
        hist.prevVoltageV = voltageV
        hist.prevProtocol = protocolName
        hist.prevEnabled = true

        if (triggered != null) {
            upsertAlert(triggered)
        }
        return triggered
    }

    fun recordUnplannedBleDisconnect(nowMs: Long = clockMs()): AnomalyAlert? {
        bleDisconnectEventsMs.addLast(nowMs)
        pruneOld(bleDisconnectEventsMs, nowMs, BLE_WINDOW_MS)
        if (bleDisconnectEventsMs.size >= BLE_FLAP_THRESHOLD &&
            (lastBleFlapAlertMs == 0L || nowMs - lastBleFlapAlertMs >= COOLDOWN_MS)
        ) {
            lastBleFlapAlertMs = nowMs
            val alert = AnomalyAlert(
                id = "ble_flap_loop",
                type = AnomalyType.BLE_FLAP_LOOP,
                portId = null,
                portLabel = null,
                countInWindow = bleDisconnectEventsMs.size,
                summary = "BLE connection dropped ${bleDisconnectEventsMs.size}× within 3 min",
                detectedAtMs = nowMs
            )
            upsertAlert(alert)
            return alert
        }
        return null
    }

    fun dismissAlert(id: String) {
        _alerts.value = _alerts.value.filterNot { it.id == id }
    }

    fun clearAll() {
        _alerts.value = emptyList()
    }

    private fun upsertAlert(alert: AnomalyAlert) {
        val current = _alerts.value.filterNot { it.id == alert.id }.toMutableList()
        current.add(0, alert)
        _alerts.value = current.take(6)
    }

    private fun pruneOld(queue: ArrayDeque<Long>, nowMs: Long, windowMs: Long) {
        while (queue.isNotEmpty() && nowMs - queue.first() > windowMs) {
            queue.removeFirst()
        }
    }

    companion object {
        const val WINDOW_MS = 120_000L
        const val BLE_WINDOW_MS = 180_000L
        const val COOLDOWN_MS = 180_000L
        const val RENEGOTIATION_THRESHOLD = 3
        const val COLLAPSE_THRESHOLD = 3
        const val BLE_FLAP_THRESHOLD = 3
    }
}
