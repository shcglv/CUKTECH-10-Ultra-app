package com.example.cuktech.telemetry

import com.example.cuktech.db.room.SessionGapEntity
import com.example.cuktech.protocol.PortTelemetry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW
}

enum class DisconnectRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class RssiPoint(
    val timestampMs: Long,
    val rssiDbm: Int
)

data class TelemetryIntegritySnapshot(
    val confidenceScore: Int = 0,
    val confidenceLevel: ConfidenceLevel = ConfidenceLevel.LOW,
    val confidenceReasonKey: String = "ble_disconnected",
    val avgSampleIntervalMs: Long = 0L,
    val jitterMs: Long = 0L,
    val lastSampleAgeMs: Long = Long.MAX_VALUE,
    val isStale: Boolean = true,
    val uptimePct24h: Double = 0.0,
    val reconnectCount24h: Int = 0,
    val directSamplesCount: Int = 0,
    val bridgeSamplesCount: Int = 0,
    val openBlindGapsCount: Int = 0,
    val totalBlindMinutes24h: Double = 0.0,
    val backfilledGapsCount: Int = 0,
    val currentRssiDbm: Int = 0,
    val disconnectRisk: DisconnectRiskLevel = DisconnectRiskLevel.LOW,
    val bleGapSafeFlag: Boolean = false,
    val lastBridgeSyncMs: Long = 0L,
    val lastBridgeAddedSamples: Int = 0
)

data class ConnectionWizardStep(
    val stepIndex: Int,
    val titleKey: String,
    val passed: Boolean,
    val active: Boolean,
    val detail: String = ""
)

data class BleExitRiskResult(
    val shouldAlert: Boolean,
    val rssiDbm: Int,
    val riskLevel: DisconnectRiskLevel
)

enum class AwayPortDeltaType {
    STOPPED,
    STARTED,
    STILL_ACTIVE
}

data class AwayPortDelta(
    val portId: Int,
    val portLabel: String,
    val beforeWatts: Double,
    val afterWatts: Double,
    val deltaType: AwayPortDeltaType
)

data class AwayDeltaSummary(
    val awayDurationSec: Long,
    val deltas: List<AwayPortDelta>,
    val bridgeSamplesSynced: Int = 0,
    val reconnectedAtMs: Long = System.currentTimeMillis()
)

fun computeAwayDeltaSummary(
    preDisconnectPorts: Map<Int, PortTelemetry>,
    postReconnectPorts: Map<Int, PortTelemetry>,
    awayDurationSec: Long,
    bridgeSamplesSynced: Int = 0,
    reconnectedAtMs: Long = System.currentTimeMillis()
): AwayDeltaSummary? {
    if (awayDurationSec < 60L) return null
    val deltas = (1..4).mapNotNull { pid ->
        val before = preDisconnectPorts[pid]
        val after = postReconnectPorts[pid]
        val beforeW = (before?.powerW ?: 0.0).coerceAtLeast(0.0)
        val afterW = (after?.powerW ?: 0.0).coerceAtLeast(0.0)
        val wasActive = beforeW >= 1.0
        val isActive = afterW >= 1.0
        val label = after?.portLabel ?: before?.portLabel ?: when (pid) {
            1 -> "USB-C1"
            2 -> "USB-C2"
            3 -> "USB-C3"
            else -> "USB-A"
        }
        when {
            wasActive && !isActive -> AwayPortDelta(pid, label, beforeW, afterW, AwayPortDeltaType.STOPPED)
            !wasActive && isActive -> AwayPortDelta(pid, label, beforeW, afterW, AwayPortDeltaType.STARTED)
            wasActive && isActive -> AwayPortDelta(pid, label, beforeW, afterW, AwayPortDeltaType.STILL_ACTIVE)
            else -> null
        }
    }
    if (deltas.isEmpty() && bridgeSamplesSynced <= 0) return null
    return AwayDeltaSummary(
        awayDurationSec = awayDurationSec,
        deltas = deltas,
        bridgeSamplesSynced = bridgeSamplesSynced,
        reconnectedAtMs = reconnectedAtMs
    )
}

/**
 * Pure thread-safe telemetry continuity, cadence jitter, BLE RSSI proximity radar,
 * and blind-gap confidence tracker.
 */
class TelemetryIntegrityEngine {

    private val lock = Any()
    private val sampleTimestamps = ArrayDeque<Long>(64)
    private val rssiHistory = ArrayDeque<RssiPoint>(96)
    private val reconnectTimestamps = ArrayDeque<Long>(128)

    private var lastSampleMs: Long = 0L
    private var lastDisconnectMs: Long = 0L
    private var lastBridgeSyncMs: Long = 0L
    private var lastBridgeAddedCount: Int = 0

    private var consecutiveWeakRssiCount: Int = 0
    private var lastBleExitAlertMs: Long = 0L

    fun onTelemetrySampleReceived(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (lastSampleMs > 0L && nowMs > lastSampleMs) {
                val interval = nowMs - lastSampleMs
                // Ignore long reconnect jumps when computing live cadence jitter
                if (interval in 100L..15_000L) {
                    if (sampleTimestamps.size >= 40) sampleTimestamps.removeFirst()
                    sampleTimestamps.addLast(interval)
                }
            }
            lastSampleMs = nowMs
        }
    }

    fun onRssiSample(rssiDbm: Int, nowMs: Long = System.currentTimeMillis()) {
        if (rssiDbm == 0 || rssiDbm < -120 || rssiDbm > -10) return
        synchronized(lock) {
            if (rssiHistory.size >= 90) rssiHistory.removeFirst()
            rssiHistory.addLast(RssiPoint(timestampMs = nowMs, rssiDbm = rssiDbm))
        }
    }

    fun getRssiHistory(): List<RssiPoint> = synchronized(lock) { rssiHistory.toList() }

    fun onBleDisconnected(nowMs: Long = System.currentTimeMillis(), wasManual: Boolean) {
        synchronized(lock) {
            if (!wasManual) {
                lastDisconnectMs = nowMs
            }
            consecutiveWeakRssiCount = 0
        }
    }

    fun onBleReconnect(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val cutoff = nowMs - 86_400_000L
            while (reconnectTimestamps.isNotEmpty() && reconnectTimestamps.first() < cutoff) {
                reconnectTimestamps.removeFirst()
            }
            reconnectTimestamps.addLast(nowMs)
            consecutiveWeakRssiCount = 0
        }
    }

    fun onBridgeSyncCompleted(addedSamples: Int, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            lastBridgeSyncMs = nowMs
            lastBridgeAddedCount = addedSamples
        }
    }

    fun evaluateBleExitRisk(
        rssiDbm: Int,
        totalWatts: Double,
        hasActiveWhLimit: Boolean,
        thresholdDbm: Int,
        enabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = 180_000L
    ): BleExitRiskResult {
        if (rssiDbm == 0 || rssiDbm < -120) {
            return BleExitRiskResult(false, rssiDbm, DisconnectRiskLevel.LOW)
        }
        val risk = classifyRisk(rssiDbm, thresholdDbm)
        synchronized(lock) {
            if (rssiDbm <= thresholdDbm) {
                consecutiveWeakRssiCount++
            } else {
                consecutiveWeakRssiCount = 0
            }
            val significantLoad = totalWatts >= 5.0 || hasActiveWhLimit
            val shouldAlert = enabled &&
                significantLoad &&
                consecutiveWeakRssiCount >= 2 &&
                (nowMs - lastBleExitAlertMs >= cooldownMs)
            if (shouldAlert) {
                lastBleExitAlertMs = nowMs
            }
            return BleExitRiskResult(
                shouldAlert = shouldAlert,
                rssiDbm = rssiDbm,
                riskLevel = risk
            )
        }
    }

    fun buildSnapshot(
        nowMs: Long = System.currentTimeMillis(),
        isAuthenticated: Boolean,
        gapsLast24h: List<SessionGapEntity>,
        directSamples24h: Int,
        bridgeSamples24h: Int,
        rssiThresholdDbm: Int = -86,
        windowStartMs: Long = nowMs - 86_400_000L
    ): TelemetryIntegritySnapshot = synchronized(lock) {
        val intervals = sampleTimestamps.toList()
        val avgInterval = if (intervals.isNotEmpty()) intervals.average().roundToInt().toLong() else 0L
        val jitter = if (intervals.size >= 2) {
            val mean = intervals.average()
            val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
            sqrt(variance).roundToInt().toLong()
        } else {
            0L
        }

        val lastAge = if (lastSampleMs > 0L) max(0L, nowMs - lastSampleMs) else Long.MAX_VALUE
        val stale = isAuthenticated && lastSampleMs > 0L && lastAge > 8_000L

        val cutoff = nowMs - 86_400_000L
        val reconnects = reconnectTimestamps.count { it >= cutoff }

        val openGaps = gapsLast24h.filter { !it.wasBackfilledByBridge }
        val backfilledGaps = gapsLast24h.filter { it.wasBackfilledByBridge }
        val effectiveWindowMs = max(1L, nowMs - windowStartMs)
        val unbackfilledGapMs = openGaps.sumOf { g ->
            val s = max(g.gapStartMs, windowStartMs)
            val e = min(g.gapEndMs, nowMs)
            max(0L, e - s)
        }
        val uptimePct = (((effectiveWindowMs - unbackfilledGapMs).toDouble() / effectiveWindowMs.toDouble()) * 100.0)
            .coerceIn(0.0, 100.0)
        val blindMins = unbackfilledGapMs / 60_000.0

        val latestRssi = rssiHistory.lastOrNull()?.rssiDbm ?: 0
        val risk = classifyRisk(latestRssi, rssiThresholdDbm)

        var score = 100
        if (!isAuthenticated) score -= 40
        if (stale) score -= 25
        if (jitter > 1_200L) score -= min(15, ((jitter - 1_200L) / 200L).toInt())
        if (risk == DisconnectRiskLevel.HIGH) score -= 20 else if (risk == DisconnectRiskLevel.MEDIUM) score -= 8
        if (openGaps.isNotEmpty() && bridgeSamples24h == 0) {
            score -= min(20, openGaps.size * 5)
        } else if (backfilledGaps.isNotEmpty() && openGaps.isEmpty()) {
            score = max(score, 88)
        }
        score = score.coerceIn(0, 100)

        val level = when {
            !isAuthenticated && bridgeSamples24h == 0 -> ConfidenceLevel.LOW
            score >= 80 -> ConfidenceLevel.HIGH
            score >= 50 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

        val reasonKey = when {
            !isAuthenticated && backfilledGaps.isNotEmpty() -> "bridge_backfilled"
            !isAuthenticated -> "ble_disconnected"
            stale -> "telemetry_stale"
            risk == DisconnectRiskLevel.HIGH -> "weak_signal"
            openGaps.isNotEmpty() -> "blind_gap_open"
            bridgeSamples24h > 0 && openGaps.isEmpty() -> "bridge_backfilled"
            else -> "live_direct"
        }

        val bleGapSafe = openGaps.any { abs(nowMs - it.gapEndMs) <= 30_000L } || !isAuthenticated

        TelemetryIntegritySnapshot(
            confidenceScore = score,
            confidenceLevel = level,
            confidenceReasonKey = reasonKey,
            avgSampleIntervalMs = avgInterval,
            jitterMs = jitter,
            lastSampleAgeMs = lastAge,
            isStale = stale,
            uptimePct24h = uptimePct,
            reconnectCount24h = reconnects,
            directSamplesCount = directSamples24h,
            bridgeSamplesCount = bridgeSamples24h,
            openBlindGapsCount = openGaps.size,
            totalBlindMinutes24h = blindMins,
            backfilledGapsCount = backfilledGaps.size,
            currentRssiDbm = latestRssi,
            disconnectRisk = risk,
            bleGapSafeFlag = bleGapSafe,
            lastBridgeSyncMs = lastBridgeSyncMs,
            lastBridgeAddedSamples = lastBridgeAddedCount
        )
    }

    fun buildConnectionWizardSteps(
        hasBtPerms: Boolean,
        btEnabled: Boolean,
        discoveredCount: Int,
        hasActiveCred: Boolean,
        authStateName: String
    ): List<ConnectionWizardStep> {
        val gattConnected = authStateName in setOf(
            "DISCOVERING_SERVICES", "PHASE_A_INIT_A4", "PHASE_B_HANDSHAKE", "AUTHENTICATED"
        )
        val a4Done = authStateName in setOf("PHASE_B_HANDSHAKE", "AUTHENTICATED")
        val authenticated = authStateName == "AUTHENTICATED"

        val steps = listOf(
            ConnectionWizardStep(1, "wizard_bt_perm", hasBtPerms, !hasBtPerms, ""),
            ConnectionWizardStep(2, "wizard_bt_adapter", btEnabled, hasBtPerms && !btEnabled, ""),
            ConnectionWizardStep(
                3,
                "wizard_ble_scan",
                discoveredCount > 0 || gattConnected,
                btEnabled && discoveredCount == 0 && !gattConnected,
                "$discoveredCount"
            ),
            ConnectionWizardStep(
                4,
                "wizard_creds_ready",
                hasActiveCred,
                hasBtPerms && btEnabled && !hasActiveCred,
                ""
            ),
            ConnectionWizardStep(
                5,
                "wizard_gatt_a4",
                a4Done,
                gattConnected && !a4Done,
                authStateName
            ),
            ConnectionWizardStep(
                6,
                "wizard_miot_session",
                authenticated,
                a4Done && !authenticated,
                authStateName
            )
        )
        return steps
    }

    private fun classifyRisk(rssiDbm: Int, thresholdDbm: Int): DisconnectRiskLevel {
        if (rssiDbm == 0) return DisconnectRiskLevel.LOW
        return when {
            rssiDbm <= thresholdDbm -> DisconnectRiskLevel.HIGH
            rssiDbm <= thresholdDbm + 8 -> DisconnectRiskLevel.MEDIUM
            else -> DisconnectRiskLevel.LOW
        }
    }
}
