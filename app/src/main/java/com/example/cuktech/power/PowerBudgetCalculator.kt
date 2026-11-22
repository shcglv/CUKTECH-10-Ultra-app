package com.example.cuktech.power

import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.protocol.PortTelemetryDecoder

enum class BudgetStatusLevel {
    IDLE,
    BALANCED,
    HIGH_LOAD,
    NEAR_CEILING
}

data class PortBudgetAllocation(
    val portId: Int,
    val portLabel: String,
    val liveWatts: Double,
    val hardwareMaxWatts: Int,
    val totalFraction: Float, // relative to 120W total block budget
    val portFraction: Float,  // relative to individual port's rated ceiling
    val isActive: Boolean,
    val isSharedC3ABus: Boolean = false
)

data class PowerBudgetSnapshot(
    val totalWatts: Double,
    val ratedMaxWatts: Double,
    val headroomWatts: Double,
    val utilizationFraction: Float,
    val sceneMode: Int,
    val statusLevel: BudgetStatusLevel,
    val allocations: List<PortBudgetAllocation>,
    val isSharedC3ABusActive: Boolean = false
)

/**
 * Pure domain calculator for CUKTECH 10 Ultra 120W GaN power budget allocation & headroom.
 */
object PowerBudgetCalculator {
    const val CHARGER_MAX_WATTS = 120.0

    fun calculate(
        ports: Map<Int, PortTelemetry>,
        sceneMode: Int
    ): PowerBudgetSnapshot {
        val c3aState = PortTelemetryDecoder.resolveC3ASharedBus(ports)
        val rawTotal = PortTelemetryDecoder.computeEffectiveTotalWatts(ports)
        val effectiveTotal = rawTotal.coerceAtMost(CHARGER_MAX_WATTS * 1.15)
        val headroom = (CHARGER_MAX_WATTS - rawTotal).coerceAtLeast(0.0)
        val utilization = (rawTotal / CHARGER_MAX_WATTS).toFloat().coerceIn(0f, 1f)

        val status = when {
            rawTotal < 0.3 -> BudgetStatusLevel.IDLE
            utilization >= 0.85f -> BudgetStatusLevel.NEAR_CEILING
            utilization >= 0.55f -> BudgetStatusLevel.HIGH_LOAD
            else -> BudgetStatusLevel.BALANCED
        }

        val allocations = mutableListOf<PortBudgetAllocation>()
        for (pid in listOf(1, 2)) {
            val p = ports[pid] ?: PortTelemetry(pid, defaultPortLabel(pid))
            val live = p.powerW.coerceAtLeast(0.0)
            val hwMax = p.maxPowerW.coerceAtLeast(1)
            allocations.add(
                PortBudgetAllocation(
                    portId = pid,
                    portLabel = p.portLabel,
                    liveWatts = live,
                    hardwareMaxWatts = hwMax,
                    totalFraction = (live / CHARGER_MAX_WATTS).toFloat().coerceIn(0f, 1f),
                    portFraction = (live / hwMax.toDouble()).toFloat().coerceIn(0f, 1f),
                    isActive = live >= 0.3
                )
            )
        }

        if (c3aState.isSharedActive) {
            allocations.add(
                PortBudgetAllocation(
                    portId = 3,
                    portLabel = "USB-C3 + USB-A",
                    liveWatts = c3aState.sharedPowerW,
                    hardwareMaxWatts = c3aState.maxWatts,
                    totalFraction = (c3aState.sharedPowerW / CHARGER_MAX_WATTS).toFloat().coerceIn(0f, 1f),
                    portFraction = (c3aState.sharedPowerW / c3aState.maxWatts.toDouble()).toFloat().coerceIn(0f, 1f),
                    isActive = true,
                    isSharedC3ABus = true
                )
            )
        } else {
            for (pid in listOf(3, 4)) {
                val p = ports[pid] ?: PortTelemetry(pid, defaultPortLabel(pid))
                val live = p.powerW.coerceAtLeast(0.0)
                val hwMax = p.maxPowerW.coerceAtLeast(1)
                allocations.add(
                    PortBudgetAllocation(
                        portId = pid,
                        portLabel = p.portLabel,
                        liveWatts = live,
                        hardwareMaxWatts = hwMax,
                        totalFraction = (live / CHARGER_MAX_WATTS).toFloat().coerceIn(0f, 1f),
                        portFraction = (live / hwMax.toDouble()).toFloat().coerceIn(0f, 1f),
                        isActive = live >= 0.3
                    )
                )
            }
        }

        return PowerBudgetSnapshot(
            totalWatts = effectiveTotal,
            ratedMaxWatts = CHARGER_MAX_WATTS,
            headroomWatts = headroom,
            utilizationFraction = utilization,
            sceneMode = sceneMode,
            statusLevel = status,
            allocations = allocations,
            isSharedC3ABusActive = c3aState.isSharedActive
        )
    }

    private fun defaultPortLabel(portId: Int): String = when (portId) {
        1 -> "USB-C1"
        2 -> "USB-C2"
        3 -> "USB-C3"
        else -> "USB-A"
    }
}
