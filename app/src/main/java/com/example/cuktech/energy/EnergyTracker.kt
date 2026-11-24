package com.example.cuktech.energy

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class ChargeSessionRecord(
    val id: Long = 0L,
    val portId: Int,
    val portLabel: String,
    val startTimeSec: Double,
    val endTimeSec: Double? = null,
    val whAccumulated: Double = 0.0,
    val peakWatts: Double = 0.0,
    val avgWatts: Double = 0.0,
    val protocolUsed: String = "Idle",
    val isActive: Boolean = true,
    val deviceMac: String = "",
    val lastSampleTimeSec: Double = startTimeSec,
    val activeDurationSec: Long = 0L,
    val gapDurationSec: Long = 0L,
    val isPaused: Boolean = false,
    val pauseReason: String? = null,
    val endReason: String? = null,
    val tariffPerKwhSnapshot: Double = 0.0,
    val currencyCode: String = "USD",
    val computedCost: Double = 0.0,
    val isArchived: Boolean = false,
    val sampleCount: Int = 0,
    val wattsSum: Double = 0.0,
    val fastBoostDurationSec: Long = 0L,
    val taperDurationSec: Long = 0L
) {
    /**
     * Honest active charging duration in seconds, excluding BLE drop / pause gaps.
     */
    val durationSeconds: Long
        get() = if (activeDurationSec > 0L || gapDurationSec > 0L) {
            activeDurationSec
        } else {
            val end = endTimeSec ?: (System.currentTimeMillis() / 1000.0)
            max(0L, (end - startTimeSec).toLong())
        }

    val wallSpanSeconds: Long
        get() {
            val end = endTimeSec ?: (System.currentTimeMillis() / 1000.0)
            return max(0L, (end - startTimeSec).toLong())
        }
}

data class PortChargeLimit(
    val portId: Int,
    val whTarget: Double,
    val mode: String = "once",      // "once" or "always"
    val baselineWh: Double = 0.0,
    val enabled: Boolean = true,
    val triggered: Boolean = false
) {
    fun currentUsedWh(cumulativePortWh: Double): Double =
        max(0.0, cumulativePortWh - baselineWh)

    fun progressFraction(cumulativePortWh: Double): Float {
        if (whTarget <= 0.0) return 0f
        return min(1.0, currentUsedWh(cumulativePortWh) / whTarget).toFloat()
    }
}

data class IntegrationStep(
    val deltaWh: Double,
    val dtActiveSec: Double,
    val dtGapSec: Double,
    val wasGap: Boolean
)

/**
 * Gap-aware trapezoidal energy integrator (`0.5 * (lastW + curW) * dt / 3600.0`).
 *
 * Security & Telemetry Honesty Rules:
 * - Intervals where `dt > maxValidGapSec` are treated as unmeasured BLE/pause gaps:
 *   `0.0 Wh` and `0.0 activeSec` are integrated across the gap, eliminating phantom Wh inflation.
 * - Intervals where both endpoints are zero (`0.0W`) do not accumulate active seconds.
 */
class AdaptiveEnergyIntegrator(
    private val maxValidGapSec: Double = 6.0
) {
    private var lastTimestampSec: Double? = null
    private var lastWatts: Double = 0.0
    var totalWh: Double = 0.0
        private set

    fun updateWithDetail(
        watts: Double,
        timestampSec: Double = System.currentTimeMillis() / 1000.0
    ): IntegrationStep {
        val cleanW = if (watts < 0.15) 0.0 else watts
        val prevT = lastTimestampSec

        if (prevT == null || timestampSec <= prevT) {
            lastTimestampSec = timestampSec
            lastWatts = cleanW
            return IntegrationStep(deltaWh = 0.0, dtActiveSec = 0.0, dtGapSec = 0.0, wasGap = false)
        }

        val rawDt = timestampSec - prevT
        if (rawDt > maxValidGapSec) {
            // Interruption / telemetry gap: never fabricate Wh across an unmeasured window
            lastTimestampSec = timestampSec
            lastWatts = cleanW
            return IntegrationStep(deltaWh = 0.0, dtActiveSec = 0.0, dtGapSec = rawDt, wasGap = true)
        }

        if (lastWatts == 0.0 && cleanW == 0.0) {
            lastTimestampSec = timestampSec
            lastWatts = 0.0
            return IntegrationStep(deltaWh = 0.0, dtActiveSec = 0.0, dtGapSec = 0.0, wasGap = false)
        }

        val avgW = 0.5 * (lastWatts + cleanW)
        val deltaWh = (avgW * rawDt) / 3600.0
        totalWh += deltaWh
        lastTimestampSec = timestampSec
        lastWatts = cleanW
        return IntegrationStep(deltaWh = deltaWh, dtActiveSec = rawDt, dtGapSec = 0.0, wasGap = false)
    }

    fun update(watts: Double, timestampSec: Double = System.currentTimeMillis() / 1000.0): Double {
        return updateWithDetail(watts, timestampSec).deltaWh
    }

    fun reset(initialWh: Double = 0.0) {
        lastTimestampSec = null
        lastWatts = 0.0
        totalWh = max(0.0, initialWh)
    }

    fun restoreState(initialWh: Double, lastTimestampSec: Double?, lastWatts: Double = 0.0) {
        this.totalWh = max(0.0, initialWh)
        this.lastTimestampSec = lastTimestampSec
        this.lastWatts = if (lastWatts < 0.15) 0.0 else lastWatts
    }

    fun currentLastTimestampSec(): Double? = lastTimestampSec
}

/**
 * Median-filtered charging session detector supporting honest gap-aware pause & session stitching
 * within a configurable grace window (`stitchGraceWindowSec`, default 120s).
 */
class ChargeSessionDetector(
    val portId: Int,
    val portLabel: String
) {
    private val fastWindow = ArrayDeque<Double>(3)
    private val slowWindow = ArrayDeque<Double>(7)

    var activeSession: ChargeSessionRecord? = null
        private set

    private var lowPowerStartSec: Double? = null
    private var sampleSumWatts: Double = 0.0
    private var sampleCount: Int = 0
    private var activeSecondsDouble: Double = 0.0
    private var gapSecondsDouble: Double = 0.0
    private var fastBoostSecondsDouble: Double = 0.0
    private var taperSecondsDouble: Double = 0.0

    fun onSample(
        watts: Double,
        deltaWh: Double,
        protocolName: String,
        timestampSec: Double = System.currentTimeMillis() / 1000.0
    ): ChargeSessionRecord? {
        val current = activeSession
        val step = if (current == null) {
            IntegrationStep(deltaWh = deltaWh, dtActiveSec = 0.0, dtGapSec = 0.0, wasGap = false)
        } else {
            val dt = max(0.0, timestampSec - current.lastSampleTimeSec)
            IntegrationStep(
                deltaWh = deltaWh,
                dtActiveSec = if (watts >= UNPLUG_THRESHOLD_W) dt else 0.0,
                dtGapSec = 0.0,
                wasGap = false
            )
        }
        return onSample(
            watts = watts,
            step = step,
            protocolName = protocolName,
            timestampSec = timestampSec
        )
    }

    /**
     * Feeds a telemetry sample with an honest [IntegrationStep], optional stitching grace window,
     * tariff snapshot, and device MAC. Returns a completed [ChargeSessionRecord] if an active
     * session finished or expired; otherwise `null`.
     */
    fun onSample(
        watts: Double,
        step: IntegrationStep,
        protocolName: String,
        timestampSec: Double = System.currentTimeMillis() / 1000.0,
        deviceMac: String = "",
        stitchGraceWindowSec: Int = 120,
        tariffPerKwh: Double = 0.0,
        currencyCode: String = "USD"
    ): ChargeSessionRecord? {
        val current = activeSession

        // 1. Handle paused or gapped active session via stitching grace window
        if (current != null && (current.isPaused || step.wasGap)) {
            val elapsedSinceLastSample = max(0.0, timestampSec - current.lastSampleTimeSec)
            if (elapsedSinceLastSample > stitchGraceWindowSec.toDouble()) {
                val expired = finalizeAt(
                    record = current,
                    endTimeSec = current.lastSampleTimeSec,
                    endReason = "BLE_DROP_TIMEOUT"
                )
                reset()
                // After expiring the disconnected session, allow a brand new session to start immediately if charging resumed
                if (watts >= START_THRESHOLD_W) {
                    pushWindow(fastWindow, 3, watts)
                    pushWindow(slowWindow, 7, watts)
                    startNewSession(
                        watts = watts,
                        deltaWh = step.deltaWh,
                        protocolName = protocolName,
                        timestampSec = timestampSec,
                        deviceMac = deviceMac,
                        tariffPerKwh = tariffPerKwh,
                        currencyCode = currencyCode
                    )
                }
                return expired
            }

            // Still within stitch grace window: record gap duration honestly, 0 Wh & 0 activeSec during gap
            gapSecondsDouble += if (step.wasGap) step.dtGapSec else elapsedSinceLastSample
            fastWindow.clear()
            slowWindow.clear()
            pushWindow(fastWindow, 3, watts)
            pushWindow(slowWindow, 7, watts)
            lowPowerStartSec = if (watts < UNPLUG_THRESHOLD_W) timestampSec else null

            val cleanProto = pickProtocol(protocolName, current.protocolUsed)
            val pausedOrResumed = watts < UNPLUG_THRESHOLD_W
            activeSession = current.copy(
                deviceMac = deviceMac.ifBlank { current.deviceMac },
                lastSampleTimeSec = timestampSec,
                activeDurationSec = activeSecondsDouble.roundToLong(),
                gapDurationSec = gapSecondsDouble.roundToLong(),
                peakWatts = max(current.peakWatts, watts),
                avgWatts = computeHonestAvgWatts(current.whAccumulated),
                protocolUsed = cleanProto,
                isPaused = pausedOrResumed,
                pauseReason = if (pausedOrResumed) current.pauseReason else null,
                computedCost = computeCost(current.whAccumulated, current.tariffPerKwhSnapshot)
            )
            return null
        }

        pushWindow(fastWindow, 3, watts)
        pushWindow(slowWindow, 7, watts)

        val pFast = median(fastWindow)
        val pSlow = median(slowWindow)

        if (current == null) {
            if (pFast >= START_THRESHOLD_W) {
                startNewSession(
                    watts = watts,
                    deltaWh = step.deltaWh,
                    protocolName = protocolName,
                    timestampSec = timestampSec,
                    deviceMac = deviceMac,
                    tariffPerKwh = tariffPerKwh,
                    currencyCode = currencyCode
                )
            }
            return null
        }

        // Accumulate active metrics only when actually transferring energy or above idle threshold
        val nextPeak = max(current.peakWatts, watts)
        if (watts >= UNPLUG_THRESHOLD_W || step.deltaWh > 0.0) {
            sampleSumWatts += watts
            sampleCount++
            activeSecondsDouble += step.dtActiveSec
            if (step.dtActiveSec > 0.0) {
                val boostThresholdW = max(nextPeak * 0.50, 5.0)
                if (watts >= boostThresholdW) {
                    fastBoostSecondsDouble += step.dtActiveSec
                } else {
                    taperSecondsDouble += step.dtActiveSec
                }
            }
        }
        val nextWh = current.whAccumulated + step.deltaWh
        val nextActiveSec = activeSecondsDouble.roundToLong()
        val nextAvg = computeHonestAvgWatts(nextWh)
        val nextProto = pickProtocol(protocolName, current.protocolUsed)
        val rate = if (current.tariffPerKwhSnapshot > 0.0) current.tariffPerKwhSnapshot else max(0.0, tariffPerKwh)
        val curCode = current.currencyCode.ifBlank { currencyCode }

        activeSession = current.copy(
            deviceMac = deviceMac.ifBlank { current.deviceMac },
            lastSampleTimeSec = timestampSec,
            activeDurationSec = nextActiveSec,
            gapDurationSec = gapSecondsDouble.roundToLong(),
            whAccumulated = nextWh,
            peakWatts = nextPeak,
            avgWatts = nextAvg,
            protocolUsed = nextProto,
            isPaused = false,
            pauseReason = null,
            tariffPerKwhSnapshot = rate,
            currencyCode = curCode,
            computedCost = computeCost(nextWh, rate),
            sampleCount = sampleCount,
            wattsSum = sampleSumWatts,
            fastBoostDurationSec = fastBoostSecondsDouble.roundToLong(),
            taperDurationSec = taperSecondsDouble.roundToLong()
        )

        // Check end condition: unplug (pFast < 0.15W) or sustained trickle (pSlow < 0.30W)
        if (pFast < UNPLUG_THRESHOLD_W) {
            val lowStart = lowPowerStartSec ?: timestampSec.also { lowPowerStartSec = it }
            if (timestampSec - lowStart >= UNPLUG_CONFIRM_SEC) {
                val finished = finalizeAt(
                    record = activeSession!!,
                    endTimeSec = timestampSec,
                    endReason = "UNPLUGGED"
                )
                reset()
                return finished
            }
        } else if (pSlow < TRICKLE_END_W) {
            val lowStart = lowPowerStartSec ?: timestampSec.also { lowPowerStartSec = it }
            if (timestampSec - lowStart >= TRICKLE_CONFIRM_SEC) {
                val finished = finalizeAt(
                    record = activeSession!!,
                    endTimeSec = timestampSec,
                    endReason = "TRICKLE_COMPLETE"
                )
                reset()
                return finished
            }
        } else {
            lowPowerStartSec = null
        }

        return null
    }

    /**
     * Marks an active session as paused on unexpected BLE disconnection so it can be stitched
     * if reconnection happens within `stitchGraceWindowSec` without adding phantom gap Wh.
     */
    fun pauseForGap(
        reason: String,
        timestampSec: Double = System.currentTimeMillis() / 1000.0
    ): ChargeSessionRecord? {
        val current = activeSession ?: return null
        val updated = current.copy(
            isPaused = true,
            pauseReason = reason,
            lastSampleTimeSec = max(current.lastSampleTimeSec, timestampSec),
            activeDurationSec = activeSecondsDouble.roundToLong(),
            gapDurationSec = gapSecondsDouble.roundToLong(),
            fastBoostDurationSec = fastBoostSecondsDouble.roundToLong(),
            taperDurationSec = taperSecondsDouble.roundToLong()
        )
        activeSession = updated
        return updated
    }

    /**
     * Finalizes a paused session if it exceeded [graceWindowSec] while disconnected.
     */
    fun checkPauseExpiry(
        graceWindowSec: Int,
        nowSec: Double = System.currentTimeMillis() / 1000.0
    ): ChargeSessionRecord? {
        val current = activeSession ?: return null
        if (!current.isPaused) return null
        if ((nowSec - current.lastSampleTimeSec) > graceWindowSec.toDouble()) {
            val finished = finalizeAt(
                record = current,
                endTimeSec = current.lastSampleTimeSec,
                endReason = "BLE_DROP_TIMEOUT"
            )
            reset()
            return finished
        }
        return null
    }

    /**
     * Forces immediate finalization of the currently active/paused session (e.g. logging disabled,
     * Wh limit triggered, Master Killswitch, or manual stop).
     */
    fun forceFinalizeActiveSession(
        timestampSec: Double = System.currentTimeMillis() / 1000.0,
        endReason: String = "USER_STOP"
    ): ChargeSessionRecord? {
        val current = activeSession ?: return null
        val finalEndSec = if (current.isPaused) current.lastSampleTimeSec else timestampSec
        val finished = finalizeAt(
            record = current,
            endTimeSec = finalEndSec,
            endReason = endReason
        )
        reset()
        return finished
    }

    fun restoreActiveSession(record: ChargeSessionRecord) {
        activeSession = record.copy(isActive = true)
        sampleSumWatts = record.wattsSum
        sampleCount = max(1, record.sampleCount)
        activeSecondsDouble = record.activeDurationSec.toDouble()
        gapSecondsDouble = record.gapDurationSec.toDouble()
        fastBoostSecondsDouble = record.fastBoostDurationSec.toDouble()
        taperSecondsDouble = record.taperDurationSec.toDouble()
        lowPowerStartSec = null
        fastWindow.clear()
        slowWindow.clear()
    }

    fun reset() {
        activeSession = null
        lowPowerStartSec = null
        sampleSumWatts = 0.0
        sampleCount = 0
        activeSecondsDouble = 0.0
        gapSecondsDouble = 0.0
        fastBoostSecondsDouble = 0.0
        taperSecondsDouble = 0.0
        fastWindow.clear()
        slowWindow.clear()
    }

    private fun startNewSession(
        watts: Double,
        deltaWh: Double,
        protocolName: String,
        timestampSec: Double,
        deviceMac: String,
        tariffPerKwh: Double,
        currencyCode: String
    ) {
        sampleSumWatts = watts
        sampleCount = 1
        activeSecondsDouble = 0.0
        gapSecondsDouble = 0.0
        fastBoostSecondsDouble = 0.0
        taperSecondsDouble = 0.0
        lowPowerStartSec = null
        val rate = max(0.0, tariffPerKwh)
        activeSession = ChargeSessionRecord(
            portId = portId,
            portLabel = portLabel,
            deviceMac = deviceMac,
            startTimeSec = timestampSec,
            lastSampleTimeSec = timestampSec,
            activeDurationSec = 0L,
            gapDurationSec = 0L,
            whAccumulated = deltaWh,
            peakWatts = watts,
            avgWatts = watts,
            protocolUsed = protocolName.ifBlank { "PD" },
            isActive = true,
            isPaused = false,
            tariffPerKwhSnapshot = rate,
            currencyCode = currencyCode.ifBlank { "USD" },
            computedCost = computeCost(deltaWh, rate),
            sampleCount = 1,
            wattsSum = watts,
            fastBoostDurationSec = 0L,
            taperDurationSec = 0L
        )
    }

    private fun finalizeAt(
        record: ChargeSessionRecord,
        endTimeSec: Double,
        endReason: String
    ): ChargeSessionRecord? {
        val activeSec = max(record.activeDurationSec, activeSecondsDouble.roundToLong())
        val gapSec = max(record.gapDurationSec, gapSecondsDouble.roundToLong())
        val boostSec = max(record.fastBoostDurationSec, fastBoostSecondsDouble.roundToLong())
        val taperSec = max(record.taperDurationSec, taperSecondsDouble.roundToLong())
        val resolvedAvg = if (activeSec > 0L && record.whAccumulated > 0.0) {
            (record.whAccumulated * 3600.0) / activeSec.toDouble()
        } else if (sampleCount > 0) {
            sampleSumWatts / sampleCount
        } else {
            record.avgWatts
        }
        val finished = record.copy(
            endTimeSec = endTimeSec,
            lastSampleTimeSec = endTimeSec,
            activeDurationSec = activeSec,
            gapDurationSec = gapSec,
            avgWatts = resolvedAvg,
            isActive = false,
            isPaused = false,
            pauseReason = null,
            endReason = endReason,
            computedCost = computeCost(record.whAccumulated, record.tariffPerKwhSnapshot),
            fastBoostDurationSec = boostSec,
            taperDurationSec = taperSec
        )
        return if (
            finished.whAccumulated >= MIN_MEANINGFUL_WH ||
            (activeSec >= MIN_SESSION_DURATION_SEC && finished.peakWatts >= MIN_MEANINGFUL_PEAK_W)
        ) {
            finished
        } else {
            null
        }
    }

    private fun computeHonestAvgWatts(wh: Double): Double {
        return if (activeSecondsDouble >= 1.0 && wh > 0.0) {
            (wh * 3600.0) / activeSecondsDouble
        } else if (sampleCount > 0) {
            sampleSumWatts / sampleCount
        } else {
            0.0
        }
    }

    private fun computeCost(wh: Double, tariffPerKwh: Double): Double {
        if (tariffPerKwh <= 0.0 || wh <= 0.0) return 0.0
        return (wh / 1000.0) * tariffPerKwh
    }

    private fun pickProtocol(incoming: String, current: String): String {
        return if (incoming != "Idle" && incoming != "空闲" && incoming.isNotBlank()) {
            incoming
        } else {
            current
        }
    }

    private fun pushWindow(deque: ArrayDeque<Double>, maxSize: Int, value: Double) {
        if (deque.size >= maxSize) deque.removeFirst()
        deque.addLast(value)
    }

    private fun median(deque: ArrayDeque<Double>): Double {
        if (deque.isEmpty()) return 0.0
        val sorted = deque.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        private const val START_THRESHOLD_W = 0.95
        private const val TRICKLE_END_W = 0.40
        private const val UNPLUG_THRESHOLD_W = 0.15
        private const val UNPLUG_CONFIRM_SEC = 4.0
        private const val TRICKLE_CONFIRM_SEC = 30.0
        private const val MIN_SESSION_DURATION_SEC = 10L
        private const val MIN_MEANINGFUL_WH = 0.025
        private const val MIN_MEANINGFUL_PEAK_W = 1.5
    }
}

/**
 * Smart hysteresis filter distinguishing real sustained charging from idle or brief ~0.5W
 * maintenance/wake-up pings emitted by already-charged devices.
 */
class SmartLoadTracker {
    private var isActive = false
    private var aboveStartSinceSec: Double? = null
    private var belowIdleSinceSec: Double? = null

    fun update(effectiveWatts: Double, timestampSec: Double = System.currentTimeMillis() / 1000.0): Boolean {
        if (!isActive) {
            when {
                effectiveWatts >= INSTANT_ACTIVE_W -> {
                    isActive = true
                    aboveStartSinceSec = null
                    belowIdleSinceSec = null
                }
                effectiveWatts >= SUSTAINED_ACTIVE_W -> {
                    val start = aboveStartSinceSec ?: timestampSec.also { aboveStartSinceSec = it }
                    if (timestampSec - start >= SUSTAINED_CONFIRM_SEC) {
                        isActive = true
                        aboveStartSinceSec = null
                        belowIdleSinceSec = null
                    }
                }
                else -> {
                    aboveStartSinceSec = null
                }
            }
        } else {
            if (effectiveWatts < IDLE_DROP_W) {
                val dropStart = belowIdleSinceSec ?: timestampSec.also { belowIdleSinceSec = it }
                if (timestampSec - dropStart >= IDLE_CONFIRM_SEC) {
                    isActive = false
                    belowIdleSinceSec = null
                    aboveStartSinceSec = null
                }
            } else {
                belowIdleSinceSec = null
            }
        }
        return isActive
    }

    fun current(): Boolean = isActive

    fun reset() {
        isActive = false
        aboveStartSinceSec = null
        belowIdleSinceSec = null
    }

    companion object {
        private const val INSTANT_ACTIVE_W = 2.0
        private const val SUSTAINED_ACTIVE_W = 0.85
        private const val SUSTAINED_CONFIRM_SEC = 4.0
        private const val IDLE_DROP_W = 0.60
        private const val IDLE_CONFIRM_SEC = 5.0
    }
}
