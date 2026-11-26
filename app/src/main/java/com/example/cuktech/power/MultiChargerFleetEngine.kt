package com.example.cuktech.power

import com.example.cuktech.db.room.ChargerNodeEntity
import com.example.cuktech.db.room.DailyEnergyAggregateEntity
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.protocol.PortTelemetryDecoder
import com.example.cuktech.security.CuktechDeviceCredentials
import java.util.Calendar
import kotlin.math.max

data class FleetNodeMetrics(
    val deviceMac: String,
    val name: String,
    val locationContext: String,
    val mainsVoltageMode: Int,
    val ratedMaxW: Double,
    val isActiveTarget: Boolean,
    val isOnline: Boolean,
    val liveWatts: Double,
    val whToday: Double,
    val totalHistoricalWh: Double,
    val totalHistoricalCost: Double,
    val sessionCount: Int,
    val lastRssiDbm: Int,
    val lastSeenMs: Long
)

data class FleetSummarySnapshot(
    val totalLiveWatts: Double = 0.0,
    val fleetWhToday: Double = 0.0,
    val fleetTotalWh: Double = 0.0,
    val fleetTotalCost: Double = 0.0,
    val currencyCode: String = "USD",
    val onlineCount: Int = 0,
    val nodesCount: Int = 0,
    val nodes: List<FleetNodeMetrics> = emptyList()
)

data class FleetBenchmarkComparison(
    val nodeA: FleetNodeMetrics,
    val nodeB: FleetNodeMetrics,
    val deltaLiveWatts: Double,
    val deltaWhToday: Double,
    val deltaTotalWh: Double,
    val deltaRatedMaxW: Double
)

/**
 * Aggregates live and historical metrics across all saved CUKTECH 10 Ultra chargers
 * and computes side-by-side multi-charger benchmarks (including 110V vs 220V mains ceilings).
 */
object MultiChargerFleetEngine {

    fun buildFleetSummary(
        savedDevices: List<CuktechDeviceCredentials>,
        chargerNodes: List<ChargerNodeEntity>,
        completedSessions: List<ChargeSessionRecord>,
        activeSessions: Map<Int, ChargeSessionRecord>,
        dailyAggregates: List<DailyEnergyAggregateEntity>,
        activeMac: String,
        activeLivePorts: Map<Int, PortTelemetry>,
        isActiveOnline: Boolean,
        currencyCode: String = "USD",
        nowMs: Long = System.currentTimeMillis()
    ): FleetSummarySnapshot {
        val normActiveMac = activeMac.uppercase().trim()
        val nodeByMac = chargerNodes.associateBy { it.deviceMac.uppercase().trim() }
        val credByMac = savedDevices.associateBy { it.mac.uppercase().trim() }

        val allMacs = linkedSetOf<String>()
        savedDevices.mapTo(allMacs) { it.mac.uppercase().trim() }
        chargerNodes.mapTo(allMacs) { it.deviceMac.uppercase().trim() }
        if (normActiveMac.isNotBlank() && normActiveMac != "--") {
            allMacs.add(normActiveMac)
        }
        allMacs.removeIf { it.isBlank() || it == "--" }

        val todayStartSec = startOfTodayMs(nowMs) / 1000.0
        val liveEffWatts = PortTelemetryDecoder.computeEffectiveTotalWatts(activeLivePorts)

        val nodes = allMacs.map { mac ->
            val node = nodeByMac[mac]
            val cred = credByMac[mac]
            val isTarget = mac.equals(normActiveMac, ignoreCase = true)
            val mains = node?.mainsVoltageMode ?: 220
            val ratedMax = if (mains == 110) 110.0 else 120.0

            val matchingCompleted = completedSessions.filter {
                it.deviceMac.equals(mac, ignoreCase = true) || (it.deviceMac.isBlank() && isTarget)
            }
            val matchingActive = if (isTarget) activeSessions.values.toList() else emptyList()
            val matchingAggs = dailyAggsForMac(dailyAggregates, mac, isTarget)

            val todayWhCompleted = matchingCompleted
                .filter { it.startTimeSec >= todayStartSec }
                .sumOf { max(0.0, it.whAccumulated) }
            val todayWhActive = matchingActive.sumOf { max(0.0, it.whAccumulated) }
            val whToday = max(todayWhCompleted + todayWhActive, node?.lastTotalWhToday ?: 0.0)

            val histWh = matchingCompleted.sumOf { max(0.0, it.whAccumulated) } +
                matchingActive.sumOf { max(0.0, it.whAccumulated) } +
                matchingAggs.sumOf { max(0.0, it.totalWh) }

            val histCost = matchingCompleted.sumOf { max(0.0, it.computedCost) } +
                matchingAggs.sumOf { max(0.0, it.totalCost) }

            val sessionCount = matchingCompleted.size + matchingActive.size + matchingAggs.sumOf { it.sessionCount }
            val liveW = if (isTarget && isActiveOnline) liveEffWatts else if (node?.isOnline == true) node.lastTotalWatts else 0.0
            val online = if (isTarget) isActiveOnline else (node?.isOnline == true && (nowMs - node.lastSeenMs) < 90_000L)

            FleetNodeMetrics(
                deviceMac = mac,
                name = node?.name?.takeIf { it.isNotBlank() } ?: cred?.name ?: "CUKTECH 10 Ultra",
                locationContext = node?.locationContext?.takeIf { it.isNotBlank() } ?: "Default",
                mainsVoltageMode = mains,
                ratedMaxW = ratedMax,
                isActiveTarget = isTarget,
                isOnline = online,
                liveWatts = liveW,
                whToday = whToday,
                totalHistoricalWh = histWh,
                totalHistoricalCost = histCost,
                sessionCount = sessionCount,
                lastRssiDbm = node?.lastRssiDbm ?: 0,
                lastSeenMs = node?.lastSeenMs ?: 0L
            )
        }.sortedWith(compareByDescending<FleetNodeMetrics> { it.isActiveTarget }.thenByDescending { it.isOnline }.thenByDescending { it.totalHistoricalWh })

        return FleetSummarySnapshot(
            totalLiveWatts = nodes.sumOf { it.liveWatts },
            fleetWhToday = nodes.sumOf { it.whToday },
            fleetTotalWh = nodes.sumOf { it.totalHistoricalWh },
            fleetTotalCost = nodes.sumOf { it.totalHistoricalCost },
            currencyCode = currencyCode,
            onlineCount = nodes.count { it.isOnline },
            nodesCount = nodes.size,
            nodes = nodes
        )
    }

    fun compareNodes(nodeA: FleetNodeMetrics?, nodeB: FleetNodeMetrics?): FleetBenchmarkComparison? {
        if (nodeA == null || nodeB == null || nodeA.deviceMac == nodeB.deviceMac) return null
        return FleetBenchmarkComparison(
            nodeA = nodeA,
            nodeB = nodeB,
            deltaLiveWatts = nodeA.liveWatts - nodeB.liveWatts,
            deltaWhToday = nodeA.whToday - nodeB.whToday,
            deltaTotalWh = nodeA.totalHistoricalWh - nodeB.totalHistoricalWh,
            deltaRatedMaxW = nodeA.ratedMaxW - nodeB.ratedMaxW
        )
    }

    private fun dailyAggsForMac(
        aggs: List<DailyEnergyAggregateEntity>,
        mac: String,
        isTarget: Boolean
    ): List<DailyEnergyAggregateEntity> = aggs.filter {
        it.deviceMac.equals(mac, ignoreCase = true) || (it.deviceMac.isBlank() && isTarget)
    }

    private fun startOfTodayMs(nowMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
