package com.example.cuktech.power

import com.example.cuktech.notifications.AnomalyAlert
import com.example.cuktech.protocol.PortTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

enum class CableHealthRating {
    IDLE,
    EXCELLENT,
    MODERATE_SAG,
    UNSTABLE
}

enum class CableCurrentClass {
    UNKNOWN,
    STANDARD_3A,
    EMARKER_5A
}

data class PortCableDiagnostic(
    val portId: Int,
    val portLabel: String,
    val isActive: Boolean,
    val isVariablePps: Boolean,
    val nominalRailV: Double?,
    val measuredVoltageV: Double,
    val measuredCurrentA: Double,
    val voltageSagMv: Int, // Positive when measured < nominal fixed rail
    val estimatedResistanceMohm: Int?,
    val peakObservedCurrentA: Double,
    val peakObservedWatts: Double,
    val currentClass: CableCurrentClass,
    val renegotiationAlertCount: Int,
    val healthRating: CableHealthRating,
    val sampleCount: Int,
    val updatedAtMs: Long
)

/**
 * Real-time per-port Cable Health & Voltage-Drop Stress Diagnostic engine.
 * Evaluates fixed-rail voltage sag (ΔV mV), dynamic resistance delta under load steps,
 * peak observed current (>3.1A -> 5A E-Marker confirmation), and anomaly alerts.
 */
class CableDiagnosticEngine {

    private data class PortTracker(
        var peakCurrentA: Double = 0.0,
        var peakWatts: Double = 0.0,
        var baselineVoltageV: Double? = null,
        var baselineCurrentA: Double? = null,
        var estimatedResistanceMohm: Int? = null,
        var sampleCount: Int = 0
    )

    private val trackers = ConcurrentHashMap<Int, PortTracker>().apply {
        for (pid in 1..4) put(pid, PortTracker())
    }

    private val _diagnostics = MutableStateFlow(initialMap())
    val diagnostics: StateFlow<Map<Int, PortCableDiagnostic>> = _diagnostics.asStateFlow()

    fun onPortTelemetry(
        port: PortTelemetry,
        alerts: List<AnomalyAlert> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ): PortCableDiagnostic {
        val tracker = trackers.getOrPut(port.portId) { PortTracker() }
        val active = port.enabled && (port.powerW >= 0.4 || port.currentA >= 0.15)
        val pps = port.protocolName.contains("PPS", ignoreCase = true)

        if (!active) {
            tracker.baselineVoltageV = null
            tracker.baselineCurrentA = null
            val idleDiag = PortCableDiagnostic(
                portId = port.portId,
                portLabel = port.portLabel,
                isActive = false,
                isVariablePps = false,
                nominalRailV = null,
                measuredVoltageV = port.voltageV,
                measuredCurrentA = port.currentA,
                voltageSagMv = 0,
                estimatedResistanceMohm = tracker.estimatedResistanceMohm,
                peakObservedCurrentA = tracker.peakCurrentA,
                peakObservedWatts = tracker.peakWatts,
                currentClass = classifyCurrent(tracker.peakCurrentA),
                renegotiationAlertCount = alerts.count { it.portId == port.portId },
                healthRating = CableHealthRating.IDLE,
                sampleCount = tracker.sampleCount,
                updatedAtMs = nowMs
            )
            updateMap(port.portId, idleDiag)
            return idleDiag
        }

        tracker.sampleCount++
        if (port.currentA > tracker.peakCurrentA) tracker.peakCurrentA = port.currentA
        if (port.powerW > tracker.peakWatts) tracker.peakWatts = port.powerW

        val nominalV = if (!pps) inferFixedNominalRailV(port.voltageV) else null
        val sagMv = if (nominalV != null) {
            ((nominalV - port.voltageV) * 1000.0).roundToInt().coerceAtLeast(0)
        } else 0

        // Estimate dynamic loop resistance (ΔV / ΔI) if same fixed rail and current jumped >= 0.8A
        val baseV = tracker.baselineVoltageV
        val baseI = tracker.baselineCurrentA
        if (!pps && nominalV != null) {
            if (baseV == null || baseI == null || abs(baseV - nominalV) > 1.2) {
                if (port.currentA in 0.1..1.2) {
                    tracker.baselineVoltageV = port.voltageV
                    tracker.baselineCurrentA = port.currentA
                }
            } else {
                val deltaI = port.currentA - baseI
                val deltaV = baseV - port.voltageV
                if (deltaI >= 0.8 && deltaV in 0.03..2.0) {
                    tracker.estimatedResistanceMohm = ((deltaV / deltaI) * 1000.0)
                        .roundToInt()
                        .coerceIn(20, 2500)
                }
            }
        }

        val portAlertCount = alerts.filter { it.portId == port.portId }.sumOf { it.countInWindow }
        val health = when {
            portAlertCount > 0 -> CableHealthRating.UNSTABLE
            !pps && nominalV != null && sagMv >= 450 && port.currentA >= 1.5 -> CableHealthRating.UNSTABLE
            !pps && nominalV != null && sagMv >= 240 && port.currentA >= 1.0 -> CableHealthRating.MODERATE_SAG
            (tracker.estimatedResistanceMohm ?: 0) >= 420 -> CableHealthRating.MODERATE_SAG
            else -> CableHealthRating.EXCELLENT
        }

        val result = PortCableDiagnostic(
            portId = port.portId,
            portLabel = port.portLabel,
            isActive = true,
            isVariablePps = pps,
            nominalRailV = nominalV,
            measuredVoltageV = port.voltageV,
            measuredCurrentA = port.currentA,
            voltageSagMv = sagMv,
            estimatedResistanceMohm = tracker.estimatedResistanceMohm,
            peakObservedCurrentA = tracker.peakCurrentA,
            peakObservedWatts = tracker.peakWatts,
            currentClass = classifyCurrent(tracker.peakCurrentA),
            renegotiationAlertCount = portAlertCount,
            healthRating = health,
            sampleCount = tracker.sampleCount,
            updatedAtMs = nowMs
        )
        updateMap(port.portId, result)
        return result
    }

    fun resetPortStats(portId: Int) {
        trackers[portId] = PortTracker()
        val label = when (portId) {
            1 -> "USB-C1"
            2 -> "USB-C2"
            3 -> "USB-C3"
            else -> "USB-A"
        }
        updateMap(
            portId,
            PortCableDiagnostic(
                portId = portId,
                portLabel = label,
                isActive = false,
                isVariablePps = false,
                nominalRailV = null,
                measuredVoltageV = 0.0,
                measuredCurrentA = 0.0,
                voltageSagMv = 0,
                estimatedResistanceMohm = null,
                peakObservedCurrentA = 0.0,
                peakObservedWatts = 0.0,
                currentClass = CableCurrentClass.UNKNOWN,
                renegotiationAlertCount = 0,
                healthRating = CableHealthRating.IDLE,
                sampleCount = 0,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun updateMap(portId: Int, diag: PortCableDiagnostic) {
        val next = _diagnostics.value.toMutableMap()
        next[portId] = diag
        _diagnostics.value = next
    }

    companion object {
        private val FIXED_RAILS = listOf(5.0, 9.0, 12.0, 15.0, 20.0)

        fun inferFixedNominalRailV(voltageV: Double): Double? {
            if (voltageV < 4.0 || voltageV > 22.0) return null
            val nearest = FIXED_RAILS.minByOrNull { abs(it - voltageV) } ?: return null
            return if (abs(nearest - voltageV) <= 0.85) nearest else null
        }

        fun classifyCurrent(peakCurrentA: Double): CableCurrentClass = when {
            peakCurrentA >= 3.15 -> CableCurrentClass.EMARKER_5A
            peakCurrentA >= 1.2 -> CableCurrentClass.STANDARD_3A
            else -> CableCurrentClass.UNKNOWN
        }

        private fun initialMap(): Map<Int, PortCableDiagnostic> = mapOf(
            1 to PortCableDiagnostic(1, "USB-C1", false, false, null, 0.0, 0.0, 0, null, 0.0, 0.0, CableCurrentClass.UNKNOWN, 0, CableHealthRating.IDLE, 0, 0L),
            2 to PortCableDiagnostic(2, "USB-C2", false, false, null, 0.0, 0.0, 0, null, 0.0, 0.0, CableCurrentClass.UNKNOWN, 0, CableHealthRating.IDLE, 0, 0L),
            3 to PortCableDiagnostic(3, "USB-C3", false, false, null, 0.0, 0.0, 0, null, 0.0, 0.0, CableCurrentClass.UNKNOWN, 0, CableHealthRating.IDLE, 0, 0L),
            4 to PortCableDiagnostic(4, "USB-A", false, false, null, 0.0, 0.0, 0, null, 0.0, 0.0, CableCurrentClass.UNKNOWN, 0, CableHealthRating.IDLE, 0, 0L)
        )
    }
}
