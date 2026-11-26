package com.example.cuktech.power

import com.example.cuktech.db.room.ScenarioObservationEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class ObservedVoltageBand(
    val label: String,
    val nominalV: Double,
    val observedMinutes: Double,
    val sharePct: Double,
    val matchesDeclaredPdo: Boolean
)

data class PdoPortExploration(
    val portId: Int,
    val hasHardwarePdo: Boolean,
    val declaredFixedVoltagesV: List<Double>,
    val ppsMaxVoltageV: Double?,
    val protocolPermissions: Map<String, Boolean>,
    val observedBands: List<ObservedVoltageBand>,
    val observedSampleCount: Int
)

data class SceneModeEmpiricalStats(
    val sceneMode: Int,
    val sampleCount: Int,
    val avgTotalW: Double,
    val peakTotalW: Double,
    val typicalCombinationLabel: String,
    val c3aSharedCount: Int
)

data class PortCombinationStat(
    val activeMask: Int,
    val label: String,
    val sampleCount: Int,
    val avgTotalW: Double,
    val peakTotalW: Double,
    val avgByPortW: Map<Int, Double>,
    val peakByPortW: Map<Int, Double>,
    val c3aSharedCount: Int
)

data class ProtocolTransitionEdge(
    val portId: Int,
    val fromProtocol: String,
    val toProtocol: String,
    val count: Int,
    val avgBeforeW: Double,
    val avgAfterW: Double,
    val avgDeltaW: Double,
    val isOscillationAlert: Boolean
)

data class LoadSimulationResult(
    val sceneMode: Int,
    val activeMask: Int,
    val combinationLabel: String,
    val ratedMainsMaxW: Double,
    val estimatedTotalCeilingW: Double,
    val estimatedHeadroomW: Double,
    val perPortCeilingW: Map<Int, Double>,
    val c3aSharedActive: Boolean,
    val empiricalSampleCount: Int,
    val basedOnHistory: Boolean,
    val confidence: String // "HIGH" | "MEDIUM" | "ESTIMATE"
)

data class ScenarioAndPdoSnapshot(
    val pdoPorts: List<PdoPortExploration> = emptyList(),
    val sceneStats: List<SceneModeEmpiricalStats> = emptyList(),
    val combinationMatrix: List<PortCombinationStat> = emptyList(),
    val protocolTransitions: List<ProtocolTransitionEdge> = emptyList(),
    val totalEmpiricalObservations: Int = 0
)

/**
 * Stage 3 domain engine:
 * - PDO Explorer (PIID 7/8 on C1/C2 + honest V/A fallback notice on C3/A)
 * - Empirical Scene Mode & Port Interaction Matrix (#1-#13, #300)
 * - Interactive Load Distribution Simulator
 * - Protocol Transition Graph & Correlation (#73, #74, #79, #84)
 */
object ScenarioAndPdoEngine {

    private const val MAX_STEP_MS = 15_000L

    fun formatMaskLabel(mask: Int): String {
        if (mask <= 0) return "Idle"
        val parts = mutableListOf<String>()
        if ((mask and 0x01) != 0) parts += "C1"
        if ((mask and 0x02) != 0) parts += "C2"
        val c3 = (mask and 0x04) != 0
        val a = (mask and 0x08) != 0
        when {
            c3 && a -> parts += "C3+A(5V)"
            c3 -> parts += "C3"
            a -> parts += "A"
        }
        return parts.joinToString("+")
    }

    fun buildMaskFromPorts(ports: Map<Int, PortTelemetry>): Int {
        var mask = 0
        if ((ports[1]?.powerW ?: 0.0) >= 0.5) mask = mask or 0x01
        if ((ports[2]?.powerW ?: 0.0) >= 0.5) mask = mask or 0x02
        if ((ports[3]?.powerW ?: 0.0) >= 0.5) mask = mask or 0x04
        if ((ports[4]?.powerW ?: 0.0) >= 0.5) mask = mask or 0x08
        return mask
    }

    fun analyze(
        ports: Map<Int, PortTelemetry>,
        settings: ChargerSettingsState,
        samples: List<TelemetrySampleEntity>,
        observations: List<ScenarioObservationEntity>
    ): ScenarioAndPdoSnapshot {
        val pdoList = (1..4).map { pid ->
            buildPortPdoExploration(pid, ports[pid], settings, samples)
        }

        val activeObs = observations.filter { it.activePortsMask > 0 }

        val sceneStats = (1..4).map { mode ->
            val list = activeObs.filter { it.sceneMode == mode }
            if (list.isEmpty()) {
                SceneModeEmpiricalStats(
                    sceneMode = mode,
                    sampleCount = 0,
                    avgTotalW = 0.0,
                    peakTotalW = 0.0,
                    typicalCombinationLabel = "--",
                    c3aSharedCount = 0
                )
            } else {
                val topMask = list.groupingBy { it.activePortsMask }.eachCount().maxByOrNull { it.value }?.key ?: 0
                SceneModeEmpiricalStats(
                    sceneMode = mode,
                    sampleCount = list.size,
                    avgTotalW = round1(list.map { it.effectiveTotalW }.average()),
                    peakTotalW = round1(list.maxOf { it.effectiveTotalW }),
                    typicalCombinationLabel = formatMaskLabel(topMask),
                    c3aSharedCount = list.count { it.c3aShared }
                )
            }
        }

        val matrix = activeObs
            .groupBy { it.activePortsMask }
            .entries
            .sortedByDescending { it.value.size }
            .take(12)
            .map { (mask, list) ->
                val avgMap = mapOf(
                    1 to round1(list.map { it.c1W }.average()),
                    2 to round1(list.map { it.c2W }.average()),
                    3 to round1(list.map { it.c3W }.average()),
                    4 to round1(list.map { it.aW }.average())
                )
                val peakMap = mapOf(
                    1 to round1(list.maxOf { it.c1W }),
                    2 to round1(list.maxOf { it.c2W }),
                    3 to round1(list.maxOf { it.c3W }),
                    4 to round1(list.maxOf { it.aW })
                )
                PortCombinationStat(
                    activeMask = mask,
                    label = formatMaskLabel(mask),
                    sampleCount = list.size,
                    avgTotalW = round1(list.map { it.effectiveTotalW }.average()),
                    peakTotalW = round1(list.maxOf { it.effectiveTotalW }),
                    avgByPortW = avgMap,
                    peakByPortW = peakMap,
                    c3aSharedCount = list.count { it.c3aShared }
                )
            }

        val transitions = computeProtocolTransitions(samples)

        return ScenarioAndPdoSnapshot(
            pdoPorts = pdoList,
            sceneStats = sceneStats,
            combinationMatrix = matrix,
            protocolTransitions = transitions,
            totalEmpiricalObservations = activeObs.size
        )
    }

    private fun buildPortPdoExploration(
        portId: Int,
        port: PortTelemetry?,
        settings: ChargerSettingsState,
        allSamples: List<TelemetrySampleEntity>
    ): PdoPortExploration {
        val hasHwPdo = portId == 1 || portId == 2
        val pdoCaps = if (hasHwPdo) port?.pdoCaps else null
        val declaredV = pdoCaps?.fixedVoltagesMv?.map { round1(it / 1000.0) }
            ?: if (hasHwPdo) listOf(5.0, 9.0, 12.0, 15.0, 20.0) else emptyList()
        val ppsMaxV = if (hasHwPdo) (pdoCaps?.ppsMaxVoltageMv?.let { round1(it / 1000.0) } ?: 21.0) else null

        val permissions = when (portId) {
            1 -> mapOf("PD" to settings.c1Pd, "PPS" to settings.c1Pps, "UFCS" to settings.c1Ufcs)
            2 -> mapOf("PD" to settings.c2Pd, "PPS" to settings.c2Pps, "UFCS" to settings.c2Ufcs)
            3 -> mapOf("UFCS" to settings.c3Ufcs, "SCP" to settings.c3Scp)
            else -> mapOf("UFCS" to settings.aUfcs, "SCP" to settings.aScp)
        }

        val portSamples = allSamples
            .filter { it.portId == portId && it.powerW >= 0.5 && it.voltageV >= 3.5 }
            .sortedBy { it.timestampMs }

        val bucketMinutes = linkedMapOf(
            5.0 to 0.0,
            9.0 to 0.0,
            12.0 to 0.0,
            15.0 to 0.0,
            20.0 to 0.0,
            28.0 to 0.0
        )
        var ppsMin = 0.0
        for (i in portSamples.indices) {
            val s = portSamples[i]
            val dtMs = if (i > 0) {
                val raw = s.timestampMs - portSamples[i - 1].timestampMs
                if (raw in 1L..MAX_STEP_MS) raw else 5_000L
            } else 5_000L
            val dtMin = dtMs / 60_000.0
            val v = s.voltageV
            val isPps = s.protocolName.contains("PPS", ignoreCase = true) ||
                s.protocolName.contains("Xiaomi", ignoreCase = true) ||
                (v in 5.8..21.2 && bucketMinutes.keys.none { abs(it - v) <= 0.65 })
            if (isPps) {
                ppsMin += dtMin
            } else {
                val nearest = bucketMinutes.keys.minByOrNull { abs(it - v) } ?: 5.0
                bucketMinutes[nearest] = (bucketMinutes[nearest] ?: 0.0) + dtMin
            }
        }

        val totalMin = (bucketMinutes.values.sum() + ppsMin).coerceAtLeast(0.0)
        val bands = mutableListOf<ObservedVoltageBand>()
        for ((nomV, mins) in bucketMinutes) {
            if (mins > 0.0 || declaredV.any { abs(it - nomV) <= 0.6 }) {
                val declaredMatch = hasHwPdo && declaredV.any { abs(it - nomV) <= 0.6 }
                bands += ObservedVoltageBand(
                    label = "${nomV.toInt()}V",
                    nominalV = nomV,
                    observedMinutes = round1(mins),
                    sharePct = if (totalMin > 0.0) round1((mins / totalMin) * 100.0) else 0.0,
                    matchesDeclaredPdo = declaredMatch
                )
            }
        }
        if (ppsMin > 0.0 || (hasHwPdo && ppsMaxV != null)) {
            bands += ObservedVoltageBand(
                label = if (ppsMaxV != null) "PPS ≤${ppsMaxV.toInt()}V" else "PPS/Var",
                nominalV = ppsMaxV ?: 11.0,
                observedMinutes = round1(ppsMin),
                sharePct = if (totalMin > 0.0) round1((ppsMin / totalMin) * 100.0) else 0.0,
                matchesDeclaredPdo = hasHwPdo && ppsMaxV != null
            )
        }

        return PdoPortExploration(
            portId = portId,
            hasHardwarePdo = hasHwPdo,
            declaredFixedVoltagesV = declaredV,
            ppsMaxVoltageV = ppsMaxV,
            protocolPermissions = permissions,
            observedBands = bands,
            observedSampleCount = portSamples.size
        )
    }

    private fun computeProtocolTransitions(samples: List<TelemetrySampleEntity>): List<ProtocolTransitionEdge> {
        data class EdgeKey(val portId: Int, val from: String, val to: String)
        class EdgeAcc {
            var count = 0
            var beforeWSum = 0.0
            var afterWSum = 0.0
            val timestamps = mutableListOf<Long>()
        }

        val accMap = linkedMapOf<EdgeKey, EdgeAcc>()
        val byPort = samples.groupBy { it.portId }
        for ((pid, list) in byPort) {
            val sorted = list.sortedBy { it.timestampMs }
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val cur = sorted[i]
                if (cur.timestampMs - prev.timestampMs > MAX_STEP_MS) continue
                val fromP = prev.protocolName.trim()
                val toP = cur.protocolName.trim()
                if (fromP.isBlank() || toP.isBlank() || fromP == toP) continue
                if (fromP.equals("Idle", true) && toP.equals("Idle", true)) continue
                val key = EdgeKey(pid, fromP, toP)
                val acc = accMap.getOrPut(key) { EdgeAcc() }
                acc.count++
                acc.beforeWSum += prev.powerW
                acc.afterWSum += cur.powerW
                acc.timestamps += cur.timestampMs
            }
        }

        return accMap.entries
            .sortedByDescending { it.value.count }
            .take(16)
            .map { (key, acc) ->
                val avgB = round1(acc.beforeWSum / acc.count)
                val avgA = round1(acc.afterWSum / acc.count)
                val osc = hasThreeInWindow(acc.timestamps, 120_000L)
                ProtocolTransitionEdge(
                    portId = key.portId,
                    fromProtocol = key.from,
                    toProtocol = key.to,
                    count = acc.count,
                    avgBeforeW = avgB,
                    avgAfterW = avgA,
                    avgDeltaW = round1(avgA - avgB),
                    isOscillationAlert = osc
                )
            }
    }

    private fun hasThreeInWindow(ts: List<Long>, windowMs: Long): Boolean {
        if (ts.size < 3) return false
        for (i in 0..ts.size - 3) {
            if (ts[i + 2] - ts[i] <= windowMs) return true
        }
        return false
    }

    fun simulateLoad(
        sceneMode: Int,
        activeMask: Int,
        ratedMainsMaxW: Double = 120.0,
        observations: List<ScenarioObservationEntity> = emptyList()
    ): LoadSimulationResult {
        val cleanMask = activeMask and 0x0F
        val c3 = (cleanMask and 0x04) != 0
        val a = (cleanMask and 0x08) != 0
        val c3aShared = c3 && a

        val exactMatches = observations.filter { it.sceneMode == sceneMode && it.activePortsMask == cleanMask }
        val maskMatches = if (exactMatches.size >= 3) exactMatches else observations.filter { it.activePortsMask == cleanMask }

        if (cleanMask != 0 && maskMatches.size >= 3) {
            val peakByPort = mapOf(
                1 to if ((cleanMask and 0x01) != 0) round1(maskMatches.maxOf { it.c1W }) else 0.0,
                2 to if ((cleanMask and 0x02) != 0) round1(maskMatches.maxOf { it.c2W }) else 0.0,
                3 to if ((cleanMask and 0x04) != 0) round1(maskMatches.maxOf { it.c3W }) else 0.0,
                4 to if ((cleanMask and 0x08) != 0) round1(maskMatches.maxOf { it.aW }) else 0.0
            )
            val estTotal = round1(maskMatches.maxOf { it.effectiveTotalW }.coerceAtMost(ratedMainsMaxW))
            return LoadSimulationResult(
                sceneMode = sceneMode,
                activeMask = cleanMask,
                combinationLabel = formatMaskLabel(cleanMask),
                ratedMainsMaxW = ratedMainsMaxW,
                estimatedTotalCeilingW = estTotal,
                estimatedHeadroomW = round1((ratedMainsMaxW - estTotal).coerceAtLeast(0.0)),
                perPortCeilingW = peakByPort,
                c3aSharedActive = c3aShared,
                empiricalSampleCount = maskMatches.size,
                basedOnHistory = true,
                confidence = if (exactMatches.size >= 15) "HIGH" else "MEDIUM"
            )
        }

        // Rated CUKTECH 10 Ultra hardware envelope fallback (honestly tagged ESTIMATE)
        val c1 = (cleanMask and 0x01) != 0
        val c2 = (cleanMask and 0x02) != 0
        val tailMax = when {
            c3aShared -> 15.0
            c3 -> 44.0
            a -> 33.0
            else -> 0.0
        }
        val remainingForC12 = (ratedMainsMaxW - tailMax).coerceAtLeast(0.0)
        var c1Ceil = 0.0
        var c2Ceil = 0.0
        when {
            c1 && c2 -> {
                when (sceneMode) {
                    4 -> { // Dual balanced
                        c1Ceil = minOf(60.0, remainingForC12 / 2.0)
                        c2Ceil = minOf(60.0, remainingForC12 / 2.0)
                    }
                    2 -> { // Hybrid priority C1
                        c1Ceil = minOf(100.0, remainingForC12 * 0.72)
                        c2Ceil = (remainingForC12 - c1Ceil).coerceAtLeast(0.0)
                    }
                    else -> { // AI / Single
                        c1Ceil = minOf(100.0, remainingForC12 * 0.65)
                        c2Ceil = (remainingForC12 - c1Ceil).coerceAtLeast(0.0)
                    }
                }
            }
            c1 -> c1Ceil = minOf(ratedMainsMaxW, remainingForC12)
            c2 -> c2Ceil = minOf(ratedMainsMaxW, remainingForC12)
        }

        val perPort = mapOf(
            1 to round1(c1Ceil),
            2 to round1(c2Ceil),
            3 to if (c3aShared) 7.5 else if (c3) 44.0 else 0.0,
            4 to if (c3aShared) 7.5 else if (a) 33.0 else 0.0
        )
        val estTotal = round1((c1Ceil + c2Ceil + tailMax).coerceAtMost(ratedMainsMaxW))
        return LoadSimulationResult(
            sceneMode = sceneMode,
            activeMask = cleanMask,
            combinationLabel = formatMaskLabel(cleanMask),
            ratedMainsMaxW = ratedMainsMaxW,
            estimatedTotalCeilingW = estTotal,
            estimatedHeadroomW = round1((ratedMainsMaxW - estTotal).coerceAtLeast(0.0)),
            perPortCeilingW = perPort,
            c3aSharedActive = c3aShared,
            empiricalSampleCount = maskMatches.size,
            basedOnHistory = false,
            confidence = "ESTIMATE"
        )
    }

    private fun round1(v: Double): Double = max(0.0, (v * 10.0).roundToInt() / 10.0)
}
