package com.example.cuktech.power

import com.example.cuktech.db.room.SessionGapEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.energy.ChargeSessionRecord
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PortLifecycleStats(
    val portId: Int,
    val connectionCount: Int,
    val avgDurationSec: Long,
    val minDurationSec: Long,
    val maxDurationSec: Long,
    val occupancyPct: Double,
    val utilizationPct: Double,
    val efficiencyRatioPct: Double,
    val idleAttachedMinutes: Double,
    val idleEnergyWh: Double,
    val rapidReconnectCount: Int,
    val unexpectedDisconnectCount: Int,
    val popularityRank: Int,
    val totalWh: Double,
    val peakWatts: Double
)

data class SimultaneousMultiportStats(
    val minutes1Port: Double = 0.0,
    val minutes2Ports: Double = 0.0,
    val minutes3Ports: Double = 0.0,
    val minutes4Ports: Double = 0.0,
    val maxConcurrentPorts: Int = 0
)

data class PowerBandBucket(
    val label: String,
    val minW: Double,
    val maxW: Double,
    val minutes: Double,
    val sharePct: Double,
    val energyWh: Double
)

data class ElectricalHistogramBucket(
    val label: String,
    val minutes: Double,
    val sharePct: Double
)

data class PowerExtremumEvent(
    val timestampMs: Long,
    val portId: Int,
    val powerW: Double,
    val voltageV: Double,
    val currentA: Double,
    val protocolName: String,
    val isPeak: Boolean
)

data class ProtocolStatsEntry(
    val protocolName: String,
    val minutes: Double,
    val sharePct: Double,
    val energyWh: Double,
    val transitionCount: Int,
    val avgWatts: Double,
    val peakWatts: Double,
    val exactHardwareCount: Int,
    val heuristicCount: Int
)

data class ComprehensivePortAnalytics(
    val portStats: List<PortLifecycleStats> = emptyList(),
    val simultaneousMultiport: SimultaneousMultiportStats = SimultaneousMultiportStats(),
    val powerBands: List<PowerBandBucket> = emptyList(),
    val voltageHistogram: List<ElectricalHistogramBucket> = emptyList(),
    val currentHistogram: List<ElectricalHistogramBucket> = emptyList(),
    val weeklyHeatmap: List<List<Double>> = List(7) { List(6) { 0.0 } }, // [Mon..Sun][6 x 4h blocks]
    val hourlyWhProfile: List<Double> = List(24) { 0.0 },
    val peakHoldMinutes: Double = 0.0,
    val plateausCount: Int = 0,
    val medianWatts: Double = 0.0,
    val whPerHourRate: Double = 0.0,
    val totalEnergyWh: Double = 0.0,
    val topPeaks: List<PowerExtremumEvent> = emptyList(),
    val topDips: List<PowerExtremumEvent> = emptyList(),
    val protocolStats: List<ProtocolStatsEntry> = emptyList(),
    val monitoredHours: Double = 0.0,
    val directSamplesCount: Int = 0,
    val bridgeSamplesCount: Int = 0
)

/**
 * Pure Stage 2 analytics calculator deriving real per-port lifecycle metrics,
 * multi-port concurrency, power/voltage/current distributions, 7x6 weekly heatmap,
 * 24h profile, plateaus, peaks/dips, and honest protocol attribution (C1/C2 exact PIID 17
 * vs C3/A heuristic PIID 18 / V·A). Never fabricates Wh across unmeasured BLE gaps.
 */
object PortLifecycleAnalyzer {

    private const val MAX_VALID_STEP_MS = 15_000L
    private const val ACTIVE_POWER_W = 0.5
    private const val ATTACHED_VOLTAGE_V = 4.0
    private const val BLE_GAP_SAFE_CADENCE_MS = 10_000L

    private val POWER_BANDS = listOf(
        Triple("0-1W", 0.0, 1.0),
        Triple("1-3W", 1.0, 3.0),
        Triple("3-5W", 3.0, 5.0),
        Triple("5-15W", 5.0, 15.0),
        Triple("15-30W", 15.0, 30.0),
        Triple("30-60W", 30.0, 60.0),
        Triple("60W+", 60.0, Double.MAX_VALUE)
    )

    private val VOLTAGE_LABELS = listOf("5V", "9V", "12V", "15V", "20V", "PPS", "28V+")
    private val CURRENT_LABELS = listOf("<0.5A", "0.5-1.5A", "1.5-3.0A", "3.0A+")

    fun analyze(
        samples: List<TelemetrySampleEntity>,
        sessions: List<ChargeSessionRecord>,
        gaps: List<SessionGapEntity>,
        windowStartMs: Long,
        windowEndMs: Long,
        deviceMacFilter: String? = null
    ): ComprehensivePortAnalytics {
        val normMac = deviceMacFilter?.trim()?.uppercase().orEmpty()

        val filteredSamples = samples.filter { s ->
            s.timestampMs in windowStartMs..windowEndMs &&
                (normMac.isBlank() || s.deviceMac.isBlank() || s.deviceMac.equals(normMac, ignoreCase = true))
        }.sortedBy { it.timestampMs }

        val filteredSessions = sessions.filter { sess ->
            val startMs = (sess.startTimeSec * 1000.0).toLong()
            val endMs = ((sess.endTimeSec ?: sess.lastSampleTimeSec) * 1000.0).toLong()
            endMs >= windowStartMs && startMs <= windowEndMs &&
                (normMac.isBlank() || sess.deviceMac.isBlank() || sess.deviceMac.equals(normMac, ignoreCase = true))
        }

        val filteredGaps = gaps.filter { g ->
            g.gapEndMs >= windowStartMs && g.gapStartMs <= windowEndMs &&
                (normMac.isBlank() || g.deviceMac.isBlank() || g.deviceMac.equals(normMac, ignoreCase = true))
        }

        if (filteredSamples.isEmpty() && filteredSessions.isEmpty()) {
            return ComprehensivePortAnalytics()
        }

        val directCount = filteredSamples.count { it.sourceMode != "BRIDGE_SYNC" }
        val bridgeCount = filteredSamples.count { it.sourceMode == "BRIDGE_SYNC" }

        // Group samples by timestamp bucket (nearest 2s) for multi-port & whole-charger power metrics
        val timeSlots = filteredSamples.groupBy { (it.timestampMs / 2000L) * 2000L }.toSortedMap()
        val slotTimes = timeSlots.keys.toList()

        var monitoredMs = 0L
        var mins1 = 0.0
        var mins2 = 0.0
        var mins3 = 0.0
        var mins4 = 0.0
        var maxConcurrent = 0

        val activeTotalWattsList = ArrayList<Double>(slotTimes.size)
        val slotMetrics = ArrayList<Triple<Long, Double, Double>>(slotTimes.size) // (tsMs, totalW, dtMs)

        val hourlyWh = DoubleArray(24)
        val heatmapRaw = Array(7) { DoubleArray(6) }
        val cal = Calendar.getInstance()

        for (i in slotTimes.indices) {
            val ts = slotTimes[i]
            val slotSamples = timeSlots[ts].orEmpty()
            val dtMs = if (i > 0) {
                val raw = ts - slotTimes[i - 1]
                if (raw in 1L..MAX_VALID_STEP_MS) raw else 5_000L
            } else {
                5_000L
            }
            monitoredMs += dtMs
            val dtMin = dtMs / 60_000.0
            val dtHours = dtMs / 3_600_000.0

            val activePortsHere = slotSamples.filter { it.powerW >= ACTIVE_POWER_W }.map { it.portId }.distinct().size
            if (activePortsHere > maxConcurrent) maxConcurrent = activePortsHere
            when (activePortsHere) {
                1 -> mins1 += dtMin
                2 -> mins2 += dtMin
                3 -> mins3 += dtMin
                in 4..10 -> mins4 += dtMin
            }

            val totalW = slotSamples.sumOf { it.powerW }
            if (totalW >= ACTIVE_POWER_W) {
                activeTotalWattsList.add(totalW)
            }
            slotMetrics.add(Triple(ts, totalW, dtMs.toDouble()))

            val slotWh = totalW * dtHours
            if (slotWh > 0.0) {
                cal.timeInMillis = ts
                val hour = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
                hourlyWh[hour] += slotWh
                val dowMonZero = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7).coerceIn(0, 6)
                val block = (hour / 4).coerceIn(0, 5)
                heatmapRaw[dowMonZero][block] += slotWh
            }
        }

        val maxHeat = heatmapRaw.maxOfOrNull { row -> row.maxOrNull() ?: 0.0 } ?: 0.0
        val weeklyHeatmap = heatmapRaw.map { row ->
            row.map { v -> if (maxHeat > 0.0) (v / maxHeat).coerceIn(0.0, 1.0) else 0.0 }
        }

        // Total energy across all samples (gap-safe trapezoidal step per port)
        val samplesByPort = filteredSamples.groupBy { it.portId }
        val portTotalsWh = HashMap<Int, Double>()
        val portOccupiedMs = HashMap<Int, Long>()
        val portActiveMs = HashMap<Int, Long>()
        val portIdleAttachedMs = HashMap<Int, Long>()
        val portIdleWh = HashMap<Int, Double>()
        val portPeakW = HashMap<Int, Double>()

        val powerBandMins = DoubleArray(POWER_BANDS.size)
        val powerBandWh = DoubleArray(POWER_BANDS.size)
        val voltageMins = DoubleArray(VOLTAGE_LABELS.size)
        val currentMins = DoubleArray(CURRENT_LABELS.size)

        // Protocol tracking per port
        data class ProtoAcc(
            var ms: Long = 0L,
            var wh: Double = 0.0,
            var transitions: Int = 0,
            var wattsSum: Double = 0.0,
            var count: Int = 0,
            var peakW: Double = 0.0,
            var exactCount: Int = 0,
            var heuristicCount: Int = 0
        )
        val protoMap = HashMap<String, ProtoAcc>()

        for ((portId, pList) in samplesByPort) {
            if (portId !in 1..4) continue
            var pWh = 0.0
            var occMs = 0L
            var actMs = 0L
            var idleAttMs = 0L
            var idleW = 0.0
            var peak = 0.0
            var lastProto: String? = null

            for (idx in pList.indices) {
                val cur = pList[idx]
                if (cur.powerW > peak) peak = cur.powerW
                val prev = pList.getOrNull(idx - 1)
                val stepMs = if (prev != null) {
                    val d = cur.timestampMs - prev.timestampMs
                    if (d in 1L..MAX_VALID_STEP_MS) d else 5_000L
                } else {
                    5_000L
                }
                val stepMin = stepMs / 60_000.0
                val stepHr = stepMs / 3_600_000.0
                val avgW = if (prev != null && (cur.timestampMs - prev.timestampMs) <= MAX_VALID_STEP_MS) {
                    0.5 * (prev.powerW + cur.powerW)
                } else {
                    cur.powerW
                }
                val dWh = avgW * stepHr
                pWh += dWh

                val attached = cur.powerW > 0.1 || cur.voltageV >= ATTACHED_VOLTAGE_V
                if (attached) {
                    occMs += stepMs
                }
                if (cur.powerW >= ACTIVE_POWER_W) {
                    actMs += stepMs
                    // Band & Histograms only for active or idle attached samples
                    val bandIdx = POWER_BANDS.indexOfFirst { (_, lo, hi) -> cur.powerW >= lo && cur.powerW < hi }
                        .takeIf { it >= 0 } ?: (POWER_BANDS.lastIndex)
                    powerBandMins[bandIdx] += stepMin
                    powerBandWh[bandIdx] += dWh

                    val vIdx = classifyVoltageBucket(cur.voltageV, cur.protocolName)
                    voltageMins[vIdx] += stepMin

                    val cIdx = classifyCurrentBucket(cur.currentA)
                    currentMins[cIdx] += stepMin

                    val protoKey = normalizeProtocolName(cur.protocolName)
                    val acc = protoMap.getOrPut(protoKey) { ProtoAcc() }
                    acc.ms += stepMs
                    acc.wh += dWh
                    acc.wattsSum += cur.powerW
                    acc.count += 1
                    if (cur.powerW > acc.peakW) acc.peakW = cur.powerW
                    if (portId == 1 || portId == 2) acc.exactCount += 1 else acc.heuristicCount += 1
                    if (lastProto != null && lastProto != protoKey) {
                        acc.transitions += 1
                    }
                    lastProto = protoKey
                } else if (cur.voltageV >= ATTACHED_VOLTAGE_V && cur.powerW in 0.05..ACTIVE_POWER_W) {
                    idleAttMs += stepMs
                    idleW += dWh
                    powerBandMins[0] += stepMin
                    powerBandWh[0] += dWh
                    lastProto = null
                } else {
                    lastProto = null
                }
            }
            portTotalsWh[portId] = pWh
            portOccupiedMs[portId] = occMs
            portActiveMs[portId] = actMs
            portIdleAttachedMs[portId] = idleAttMs
            portIdleWh[portId] = idleW
            portPeakW[portId] = peak
        }

        // Lifecycle per port integrating ChargeSessionRecord + BLE-gap-safe checks
        val rawPortStats = (1..4).map { portId ->
            val pSessions = filteredSessions.filter { it.portId == portId }.sortedBy { it.startTimeSec }
            val durations = pSessions.map { max(1L, it.durationSeconds) }
            val avgDur = if (durations.isNotEmpty()) durations.average().toLong() else 0L
            val minDur = durations.minOrNull() ?: 0L
            val maxDur = durations.maxOrNull() ?: 0L

            var rapidReconnects = 0
            for (i in 1 until pSessions.size) {
                val prevEnd = pSessions[i - 1].endTimeSec ?: pSessions[i - 1].lastSampleTimeSec
                val gapSec = pSessions[i].startTimeSec - prevEnd
                if (gapSec in 0.0..60.0) rapidReconnects++
            }

            // BLE-gap-safe unexpected disconnect verification (#39)
            val pSamples = samplesByPort[portId].orEmpty()
            var unexpectedDrops = 0
            for (sess in pSessions) {
                val reason = sess.endReason.orEmpty().uppercase()
                val looksAbrupt = reason.contains("DROP") || reason.contains("UNEXPECTED") || reason.contains("ERROR")
                if (!looksAbrupt) continue
                val endMs = ((sess.endTimeSec ?: sess.lastSampleTimeSec) * 1000.0).toLong()
                val overlappingBleGap = filteredGaps.any { g ->
                    endMs in (g.gapStartMs - 15_000L)..(g.gapEndMs + 15_000L)
                }
                val hasContinuousCadence = hasContinuousTelemetryAround(pSamples, endMs, BLE_GAP_SAFE_CADENCE_MS)
                if (!overlappingBleGap && hasContinuousCadence) {
                    unexpectedDrops++
                }
            }

            val denomMs = max(1L, monitoredMs)
            val occPct = ((portOccupiedMs[portId] ?: 0L).toDouble() / denomMs * 100.0).coerceIn(0.0, 100.0)
            val utilPct = ((portActiveMs[portId] ?: 0L).toDouble() / denomMs * 100.0).coerceIn(0.0, 100.0)
            val occ = portOccupiedMs[portId] ?: 0L
            val effPct = if (occ > 0L) {
                ((portActiveMs[portId] ?: 0L).toDouble() / occ * 100.0).coerceIn(0.0, 100.0)
            } else {
                0.0
            }
            val sessionWh = pSessions.sumOf { it.whAccumulated }
            val totalWh = max(portTotalsWh[portId] ?: 0.0, sessionWh)
            val peakW = max(portPeakW[portId] ?: 0.0, pSessions.maxOfOrNull { it.peakWatts } ?: 0.0)

            PortLifecycleStats(
                portId = portId,
                connectionCount = pSessions.size,
                avgDurationSec = avgDur,
                minDurationSec = minDur,
                maxDurationSec = maxDur,
                occupancyPct = occPct,
                utilizationPct = utilPct,
                efficiencyRatioPct = effPct,
                idleAttachedMinutes = (portIdleAttachedMs[portId] ?: 0L) / 60_000.0,
                idleEnergyWh = portIdleWh[portId] ?: 0.0,
                rapidReconnectCount = rapidReconnects,
                unexpectedDisconnectCount = unexpectedDrops,
                popularityRank = 4,
                totalWh = totalWh,
                peakWatts = peakW
            )
        }

        // Assign popularity rank 1..4 by energy Wh (tiebreak by connection count)
        val rankedIds = rawPortStats
            .sortedWith(compareByDescending<PortLifecycleStats> { it.totalWh }.thenByDescending { it.connectionCount })
            .mapIndexed { idx, s -> s.portId to (idx + 1) }
            .toMap()
        val finalPortStats = rawPortStats.map { it.copy(popularityRank = rankedIds[it.portId] ?: 4) }

        val totalBandMins = max(0.001, powerBandMins.sum())
        val powerBands = POWER_BANDS.mapIndexed { i, (label, lo, hi) ->
            PowerBandBucket(
                label = label,
                minW = lo,
                maxW = hi,
                minutes = powerBandMins[i],
                sharePct = (powerBandMins[i] / totalBandMins) * 100.0,
                energyWh = powerBandWh[i]
            )
        }

        val totalVMin = max(0.001, voltageMins.sum())
        val voltageHistogram = VOLTAGE_LABELS.mapIndexed { i, label ->
            ElectricalHistogramBucket(
                label = label,
                minutes = voltageMins[i],
                sharePct = (voltageMins[i] / totalVMin) * 100.0
            )
        }

        val totalAMin = max(0.001, currentMins.sum())
        val currentHistogram = CURRENT_LABELS.mapIndexed { i, label ->
            ElectricalHistogramBucket(
                label = label,
                minutes = currentMins[i],
                sharePct = (currentMins[i] / totalAMin) * 100.0
            )
        }

        // Peak hold & plateaus (>= 85% of window peak, continuous span >= 60s)
        val windowPeakW = activeTotalWattsList.maxOrNull() ?: 0.0
        var peakHoldMs = 0L
        var plateaus = 0
        var currentPlateauMs = 0L
        if (windowPeakW >= 5.0) {
            val thresholdW = windowPeakW * 0.85
            for ((_, totalW, dtMs) in slotMetrics) {
                if (totalW >= thresholdW) {
                    peakHoldMs += dtMs.toLong()
                    currentPlateauMs += dtMs.toLong()
                } else {
                    if (currentPlateauMs >= 60_000L) plateaus++
                    currentPlateauMs = 0L
                }
            }
            if (currentPlateauMs >= 60_000L) plateaus++
        }

        val sortedActiveW = activeTotalWattsList.sorted()
        val medianW = if (sortedActiveW.isEmpty()) 0.0 else sortedActiveW[sortedActiveW.size / 2]
        val totalWhAll = hourlyWh.sum()
        val activeTotalHours = max(0.001, (mins1 + mins2 + mins3 + mins4) / 60.0)
        val whRate = if ((mins1 + mins2 + mins3 + mins4) > 0.5) totalWhAll / activeTotalHours else 0.0

        // Top Peaks & Dips
        val topPeaks = filteredSamples
            .filter { it.powerW >= 1.0 }
            .sortedByDescending { it.powerW }
            .distinctBy { it.timestampMs / 30_000L }
            .take(5)
            .map {
                PowerExtremumEvent(
                    timestampMs = it.timestampMs,
                    portId = it.portId,
                    powerW = it.powerW,
                    voltageV = it.voltageV,
                    currentA = it.currentA,
                    protocolName = it.protocolName,
                    isPeak = true
                )
            }

        val dipEvents = ArrayList<PowerExtremumEvent>()
        for ((_, pList) in samplesByPort) {
            for (idx in 1 until pList.size) {
                val prev = pList[idx - 1]
                val cur = pList[idx]
                if ((cur.timestampMs - prev.timestampMs) <= 20_000L && (prev.powerW - cur.powerW) >= 8.0) {
                    dipEvents.add(
                        PowerExtremumEvent(
                            timestampMs = cur.timestampMs,
                            portId = cur.portId,
                            powerW = cur.powerW,
                            voltageV = cur.voltageV,
                            currentA = cur.currentA,
                            protocolName = cur.protocolName,
                            isPeak = false
                        )
                    )
                }
            }
        }
        val topDips = dipEvents
            .sortedByDescending { it.timestampMs }
            .distinctBy { it.timestampMs / 30_000L }
            .take(5)

        val totalProtoMs = max(1L, protoMap.values.sumOf { it.ms })
        val protocolStats = protoMap.entries.map { (name, acc) ->
            ProtocolStatsEntry(
                protocolName = name,
                minutes = acc.ms / 60_000.0,
                sharePct = (acc.ms.toDouble() / totalProtoMs) * 100.0,
                energyWh = acc.wh,
                transitionCount = acc.transitions,
                avgWatts = if (acc.count > 0) acc.wattsSum / acc.count else 0.0,
                peakWatts = acc.peakW,
                exactHardwareCount = acc.exactCount,
                heuristicCount = acc.heuristicCount
            )
        }.sortedByDescending { it.energyWh }

        return ComprehensivePortAnalytics(
            portStats = finalPortStats,
            simultaneousMultiport = SimultaneousMultiportStats(
                minutes1Port = mins1,
                minutes2Ports = mins2,
                minutes3Ports = mins3,
                minutes4Ports = mins4,
                maxConcurrentPorts = maxConcurrent
            ),
            powerBands = powerBands,
            voltageHistogram = voltageHistogram,
            currentHistogram = currentHistogram,
            weeklyHeatmap = weeklyHeatmap,
            hourlyWhProfile = hourlyWh.toList(),
            peakHoldMinutes = peakHoldMs / 60_000.0,
            plateausCount = plateaus,
            medianWatts = medianW,
            whPerHourRate = whRate,
            totalEnergyWh = totalWhAll,
            topPeaks = topPeaks,
            topDips = topDips,
            protocolStats = protocolStats,
            monitoredHours = monitoredMs / 3_600_000.0,
            directSamplesCount = directCount,
            bridgeSamplesCount = bridgeCount
        )
    }

    private fun hasContinuousTelemetryAround(
        samples: List<TelemetrySampleEntity>,
        targetMs: Long,
        maxCadenceGapMs: Long
    ): Boolean {
        if (samples.size < 2) return false
        val before = samples.lastOrNull { it.timestampMs <= targetMs } ?: return false
        if (abs(targetMs - before.timestampMs) > maxCadenceGapMs) return false
        val after = samples.firstOrNull { it.timestampMs > before.timestampMs }
        return if (after != null) {
            (after.timestampMs - before.timestampMs) <= maxCadenceGapMs * 2L
        } else {
            true
        }
    }

    private fun classifyVoltageBucket(voltageV: Double, protocolName: String): Int {
        if (protocolName.contains("PPS", ignoreCase = true) || protocolName.contains("UFCS", ignoreCase = true)) {
            return 5 // PPS / Variable
        }
        return when {
            voltageV < 7.0 -> 0       // 5V
            voltageV < 10.8 -> 1      // 9V
            voltageV < 13.8 -> 2      // 12V
            voltageV < 17.8 -> 3      // 15V
            voltageV < 23.0 -> 4      // 20V
            else -> 6                 // 28V+
        }
    }

    private fun classifyCurrentBucket(currentA: Double): Int = when {
        currentA < 0.5 -> 0
        currentA < 1.5 -> 1
        currentA < 3.0 -> 2
        else -> 3
    }

    private fun normalizeProtocolName(raw: String): String {
        val clean = raw.trim()
        return when {
            clean.isBlank() || clean.equals("Idle", ignoreCase = true) -> "Standard 5V"
            else -> clean
        }
    }
}
