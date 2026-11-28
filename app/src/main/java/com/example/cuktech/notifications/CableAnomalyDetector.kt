package com.example.cuktech.notifications

import android.content.Context
import com.example.R
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
) {
    fun localizedSummary(context: Context): String = when (type) {
        AnomalyType.CABLE_RENEGOTIATION_INSTABILITY -> context.getString(
            R.string.anomaly_renegotiation_fmt,
            portLabel ?: "USB",
            countInWindow
        )
        AnomalyType.POWER_COLLAPSE_LOOP -> context.getString(
            R.string.anomaly_power_collapse_fmt,
            portLabel ?: "USB",
            countInWindow
        )
        AnomalyType.BLE_FLAP_LOOP -> context.getString(
            R.string.anomaly_ble_flap_fmt,
            countInWindow
        )
    }
}

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
    private val suppressedUntilMs = mutableMapOf<Int, Long>()
    private val bleDisconnectEventsMs = ArrayDeque<Long>()
    private var lastBleFlapAlertMs = 0L

    private val _alerts = MutableStateFlow<List<AnomalyAlert>>(emptyList())
    val alerts: StateFlow<List<AnomalyAlert>> = _alerts.asStateFlow()

    fun markPortCompleted(
        portId: Int,
        nowMs: Long = clockMs(),
        suppressDurationMs: Long = POST_COMPLETION_SUPPRESS_MS
    ) {
        suppressedUntilMs[portId] = nowMs + suppressDurationMs
        portHistories[portId]?.let {
            it.collapseDropsMs.clear()
            it.renegotiationDropsMs.clear()
        }
    }

    fun onPortSample(
        portId: Int,
        portLabel: String,
        voltageV: Double,
        powerW: Double,
        protocolName: String,
        enabled: Boolean,
        nowMs: Long = clockMs(),
        suppressAfterCompletion: Boolean = true
    ): AnomalyAlert? {
        val hist = portHistories[portId] ?: return null
        if (!enabled) {
            hist.prevPowerW = powerW
            hist.prevVoltageV = voltageV
            hist.prevProtocol = protocolName
            hist.prevEnabled = false
            return null
        }
        val untilMs = suppressedUntilMs[portId] ?: 0L
        if (suppressAfterCompletion && untilMs > nowMs) {
            hist.prevPowerW = powerW
            hist.prevVoltageV = voltageV
            hist.prevProtocol = protocolName
            hist.prevEnabled = true
            return null
        }

        var triggered: AnomalyAlert? = null

        val wasFastProtocol = hist.prevProtocol.contains("PD", ignoreCase = true) ||
            hist.prevProtocol.contains("PPS", ignoreCase = true) ||
            hist.prevProtocol.contains("UFCS", ignoreCase = true) ||
            hist.prevProtocol.contains("SCP", ignoreCase = true)
        val nowSlowFallback = protocolName.equals("Normal", ignoreCase = true) ||
            protocolName.contains("5V", ignoreCase = true)

        val voltageSagOrCollapse = voltageV < 4.5 || (hist.prevVoltageV - voltageV) >= 3.0
        val isGenuineLoadCollapse = hist.prevEnabled && powerW <= 0.5 && (
            hist.prevPowerW >= MIN_STEADY_VOLTAGE_COLLAPSE_W ||
                (hist.prevPowerW >= MIN_VOLTAGE_SAG_COLLAPSE_W && voltageSagOrCollapse)
            )

        if (hist.prevEnabled && wasFastProtocol && hist.prevVoltageV >= 8.5 && voltageV in 3.5..5.7 && nowSlowFallback) {
            hist.renegotiationDropsMs.addLast(nowMs)
            pruneOld(hist.renegotiationDropsMs, nowMs, WINDOW_MS)
            if (hist.renegotiationDropsMs.size >= RENEGOTIATION_THRESHOLD &&
                (hist.lastAlertedMs == 0L || nowMs - hist.lastAlertedMs >= COOLDOWN_MS)
            ) {
                val count = hist.renegotiationDropsMs.size
                hist.lastAlertedMs = nowMs
                hist.renegotiationDropsMs.clear()
                triggered = AnomalyAlert(
                    id = "reneg_port_$portId",
                    type = AnomalyType.CABLE_RENEGOTIATION_INSTABILITY,
                    portId = portId,
                    portLabel = portLabel,
                    countInWindow = count,
                    summary = "$portLabel: ${count}× PD/PPS/UFCS → 5V fallback in 2 min",
                    detectedAtMs = nowMs
                )
            }
        } else if (isGenuineLoadCollapse) {
            hist.collapseDropsMs.addLast(nowMs)
            pruneOld(hist.collapseDropsMs, nowMs, WINDOW_MS)
            if (hist.collapseDropsMs.size >= COLLAPSE_THRESHOLD &&
                (hist.lastAlertedMs == 0L || nowMs - hist.lastAlertedMs >= COOLDOWN_MS)
            ) {
                val count = hist.collapseDropsMs.size
                hist.lastAlertedMs = nowMs
                hist.collapseDropsMs.clear()
                triggered = AnomalyAlert(
                    id = "collapse_port_$portId",
                    type = AnomalyType.POWER_COLLAPSE_LOOP,
                    portId = portId,
                    portLabel = portLabel,
                    countInWindow = count,
                    summary = "$portLabel: ${count}× sudden load collapses (≥25W → 0W)",
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
            val count = bleDisconnectEventsMs.size
            lastBleFlapAlertMs = nowMs
            bleDisconnectEventsMs.clear()
            val alert = AnomalyAlert(
                id = "ble_flap_loop",
                type = AnomalyType.BLE_FLAP_LOOP,
                portId = null,
                portLabel = null,
                countInWindow = count,
                summary = "BLE connection dropped ${count}× within 3 min",
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
        suppressedUntilMs.clear()
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
        const val MIN_STEADY_VOLTAGE_COLLAPSE_W = 25.0
        const val MIN_VOLTAGE_SAG_COLLAPSE_W = 15.0
        const val POST_COMPLETION_SUPPRESS_MS = 10 * 60_000L
    }
}
