package com.example.cuktech.power

import com.example.cuktech.db.room.DailyEnergyAggregateEntity
import com.example.cuktech.energy.ChargeSessionRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class SummaryPeriodMode {
    WEEK,
    MONTH
}

data class DailyWhBucket(
    val dateIso: String,
    val dayShortLabel: String,
    val totalWh: Double,
    val peakWatts: Double,
    val sessionCount: Int,
    val totalCost: Double
)

data class PeriodSummarySnapshot(
    val mode: SummaryPeriodMode,
    val periodOffset: Int,
    val hasData: Boolean,
    val startMs: Long,
    val endMs: Long,
    val totalWh: Double,
    val totalCost: Double,
    val currencyCode: String,
    val sessionCount: Int,
    val activeDays: Int,
    val peakWatts: Double,
    val avgWatts: Double,
    val fastBoostSharePct: Int,
    val topPortId: Int?,
    val qualityScore: Int,
    val stabilityPenalty: Int,
    val efficiencyBonus: Int
)

object ChargerSummaryCalculator {

    private val isoFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    fun computePeriodBounds(
        mode: SummaryPeriodMode,
        periodOffset: Int = 0,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return when (mode) {
            SummaryPeriodMode.WEEK -> {
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                val mondayOffset = if (dow == Calendar.SUNDAY) -6 else (Calendar.MONDAY - dow)
                cal.add(Calendar.DAY_OF_MONTH, mondayOffset + periodOffset * 7)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 7)
                val end = cal.timeInMillis - 1L
                start to end
            }
            SummaryPeriodMode.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, periodOffset)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                val end = cal.timeInMillis - 1L
                start to end
            }
        }
    }

    fun computeSummary(
        mode: SummaryPeriodMode,
        periodOffset: Int,
        completedSessions: List<ChargeSessionRecord>,
        activeSessions: Map<Int, ChargeSessionRecord>,
        dailyAggregates: List<DailyEnergyAggregateEntity>,
        anomalyCountInPeriod: Int = 0,
        defaultCurrency: String = "USD",
        nowMs: Long = System.currentTimeMillis()
    ): PeriodSummarySnapshot {
        val (startMs, endMs) = computePeriodBounds(mode, periodOffset, nowMs)
        val startSec = startMs / 1000.0
        val endSec = endMs / 1000.0
        val fmt = isoFormat.get()!!
        val startIso = fmt.format(Date(startMs))
        val endIso = fmt.format(Date(endMs))

        val combinedSessions = (completedSessions + activeSessions.values)
            .filter { it.startTimeSec in startSec..endSec }

        val periodAggs = dailyAggregates.filter { it.dateIso in startIso..endIso }

        if (combinedSessions.isEmpty() && periodAggs.isEmpty()) {
            return PeriodSummarySnapshot(
                mode = mode,
                periodOffset = periodOffset,
                hasData = false,
                startMs = startMs,
                endMs = endMs,
                totalWh = 0.0,
                totalCost = 0.0,
                currencyCode = defaultCurrency,
                sessionCount = 0,
                activeDays = 0,
                peakWatts = 0.0,
                avgWatts = 0.0,
                fastBoostSharePct = 0,
                topPortId = null,
                qualityScore = 0,
                stabilityPenalty = 0,
                efficiencyBonus = 0
            )
        }

        val sessionWh = combinedSessions.sumOf { it.whAccumulated }
        val aggWh = periodAggs.sumOf { it.totalWh }
        val totalWh = sessionWh + aggWh

        val sessionCost = combinedSessions.sumOf { it.computedCost }
        val aggCost = periodAggs.sumOf { it.totalCost }
        val totalCost = sessionCost + aggCost
        val currency = combinedSessions.firstOrNull { it.currencyCode.isNotBlank() }?.currencyCode
            ?: periodAggs.firstOrNull { it.currencyCode.isNotBlank() }?.currencyCode
            ?: defaultCurrency

        val sessionCount = combinedSessions.size + periodAggs.sumOf { it.sessionCount }
        val activeDaySet = mutableSetOf<String>()
        combinedSessions.forEach { activeDaySet += fmt.format(Date((it.startTimeSec * 1000.0).toLong())) }
        periodAggs.forEach { if (it.sessionCount > 0 || it.totalWh > 0.0) activeDaySet += it.dateIso }

        val peakWatts = max(
            combinedSessions.maxOfOrNull { it.peakWatts } ?: 0.0,
            periodAggs.maxOfOrNull { it.maxPeakWatts } ?: 0.0
        )

        val totalActiveSec = combinedSessions.sumOf { max(0L, it.durationSeconds) } +
            periodAggs.sumOf { max(0L, it.totalActiveSec) }
        val totalGapSec = combinedSessions.sumOf { max(0L, it.gapDurationSec) } +
            periodAggs.sumOf { max(0L, it.totalGapSec) }

        val avgWatts = if (totalActiveSec > 0L && totalWh > 0.0) {
            (totalWh * 3600.0) / totalActiveSec.toDouble()
        } else {
            val nonZero = combinedSessions.filter { it.avgWatts > 0.0 }
            if (nonZero.isNotEmpty()) nonZero.sumOf { it.avgWatts } / nonZero.size else 0.0
        }

        val boostSecSum = combinedSessions.sumOf { max(0L, it.fastBoostDurationSec) }
        val taperSecSum = combinedSessions.sumOf { max(0L, it.taperDurationSec) }
        val phaseTotal = boostSecSum + taperSecSum
        val fastBoostSharePct = if (phaseTotal > 0L) {
            ((boostSecSum.toDouble() / phaseTotal.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        } else if (avgWatts >= 18.0) {
            70
        } else {
            0
        }

        val whByPort = mutableMapOf<Int, Double>()
        combinedSessions.forEach { whByPort[it.portId] = (whByPort[it.portId] ?: 0.0) + it.whAccumulated }
        periodAggs.forEach { whByPort[it.portId] = (whByPort[it.portId] ?: 0.0) + it.totalWh }
        val topPortId = whByPort.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }?.key

        // Honest 0..100 Charging Quality Score:
        // Base 84 + efficiency bonus (up to +16 for healthy fast-boost ratio & complete deliveries)
        // minus stability penalties (BLE drop timeouts, gap ratio, anomalies).
        val dropTimeoutCount = combinedSessions.count { it.endReason == "BLE_DROP_TIMEOUT" }
        val gapRatio = if (totalActiveSec + totalGapSec > 0L) {
            totalGapSec.toDouble() / (totalActiveSec + totalGapSec).toDouble()
        } else 0.0
        val penalty = (dropTimeoutCount * 8 + (gapRatio * 35.0).roundToInt() + anomalyCountInPeriod * 6)
            .coerceIn(0, 60)

        val cleanCompletions = combinedSessions.count {
            it.endReason == "TRICKLE_COMPLETE" || it.endReason == "WH_LIMIT" || it.endReason == "UNPLUGGED"
        }
        val completionBonus = if (combinedSessions.isNotEmpty()) {
            ((cleanCompletions.toDouble() / combinedSessions.size.toDouble()) * 8.0).roundToInt()
        } else 4
        val speedBonus = if (fastBoostSharePct in 35..95) 8 else if (avgWatts >= 10.0) 5 else 2
        val bonus = (completionBonus + speedBonus).coerceIn(0, 16)
        val score = (84 + bonus - penalty).coerceIn(0, 100)

        return PeriodSummarySnapshot(
            mode = mode,
            periodOffset = periodOffset,
            hasData = totalWh > 0.001 || sessionCount > 0,
            startMs = startMs,
            endMs = endMs,
            totalWh = totalWh,
            totalCost = totalCost,
            currencyCode = currency,
            sessionCount = sessionCount,
            activeDays = activeDaySet.size,
            peakWatts = peakWatts,
            avgWatts = avgWatts,
            fastBoostSharePct = fastBoostSharePct,
            topPortId = topPortId,
            qualityScore = score,
            stabilityPenalty = penalty,
            efficiencyBonus = bonus
        )
    }

    fun computeRecentDailyBuckets(
        daysCount: Int = 10,
        completedSessions: List<ChargeSessionRecord>,
        activeSessions: Map<Int, ChargeSessionRecord>,
        dailyAggregates: List<DailyEnergyAggregateEntity>,
        locale: Locale = Locale.getDefault(),
        nowMs: Long = System.currentTimeMillis()
    ): List<DailyWhBucket> {
        val count = daysCount.coerceIn(1, 31)
        val isoFmt = isoFormat.get()!!
        val shortFmt = SimpleDateFormat("EE", locale)
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -(count - 1))
        }

        val sessionsByIso = (completedSessions + activeSessions.values)
            .groupBy { isoFmt.format(Date((it.startTimeSec * 1000.0).toLong())) }
        val aggsByIso = dailyAggregates.groupBy { it.dateIso }

        val result = ArrayList<DailyWhBucket>(count)
        repeat(count) {
            val dayDate = cal.time
            val iso = isoFmt.format(dayDate)
            val sess = sessionsByIso[iso].orEmpty()
            val aggs = aggsByIso[iso].orEmpty()
            val wh = sess.sumOf { it.whAccumulated } + aggs.sumOf { it.totalWh }
            val peak = max(
                sess.maxOfOrNull { it.peakWatts } ?: 0.0,
                aggs.maxOfOrNull { it.maxPeakWatts } ?: 0.0
            )
            val cnt = sess.size + aggs.sumOf { it.sessionCount }
            val cost = sess.sumOf { it.computedCost } + aggs.sumOf { it.totalCost }
            result += DailyWhBucket(
                dateIso = iso,
                dayShortLabel = shortFmt.format(dayDate).replace(".", "").take(3),
                totalWh = wh,
                peakWatts = peak,
                sessionCount = cnt,
                totalCost = cost
            )
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return result
    }
}
