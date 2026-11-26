package com.example.cuktech.power

import com.example.cuktech.db.room.SessionAnnotationEntity
import com.example.cuktech.db.room.SessionGapEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.energy.ChargeSessionRecord
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class SessionPhaseType {
    RAMP_UP,
    STABLE,
    TAPER,
    POWER_DROP,
    INTERRUPTION,
    IDLE
}

data class SessionPhaseSegment(
    val phase: SessionPhaseType,
    val startMs: Long,
    val endMs: Long,
    val durationSec: Long,
    val avgWatts: Double,
    val peakWatts: Double,
    val protocolName: String
)

data class PowerDropExplanation(
    val timestampMs: Long,
    val beforeW: Double,
    val afterW: Double,
    val dropPct: Double,
    val causeCode: String, // NEIGHBOR_PORT_ACTIVATED | PROTOCOL_STEP_DOWN | BLE_GAP_OVERLAP | DEVICE_TAPER_OR_UNEXPLAINED
    val detail: String
)

enum class SessionClassification {
    FAST_BOOST,
    LONG_STEADY,
    SHORT_BURST,
    INTERRUPTED,
    LOW_POWER_TRICKLE,
    MULTIPORT,
    INCOMPLETE_DROP
}

data class SessionDeepAnalysis(
    val sessionId: Long,
    val phases: List<SessionPhaseSegment> = emptyList(),
    val rampUpSec: Long = 0L,
    val stabilizationSec: Long = 0L,
    val cadenceSufficient: Boolean = false,
    val medianCadenceMs: Long = 0L,
    val powerVolatilityIndexPct: Double = 0.0,
    val voltageStabilityScore: Int = 100,
    val currentStabilityScore: Int = 100,
    val classification: SessionClassification = SessionClassification.SHORT_BURST,
    val dropExplanations: List<PowerDropExplanation> = emptyList(),
    val gapCount: Int = 0,
    val bridgeBackfilledSamples: Int = 0,
    val vsNormDurationRatio: Double = 1.0,
    val vsNormWhRatio: Double = 1.0
)

data class SessionPersonalRecords(
    val maxPeakWatts: Double = 0.0,
    val maxPeakSessionId: Long = 0L,
    val maxWh: Double = 0.0,
    val maxWhSessionId: Long = 0L,
    val maxDurationSec: Long = 0L,
    val maxDurationSessionId: Long = 0L,
    val maxWhPerHour: Double = 0.0,
    val maxWhPerHourSessionId: Long = 0L,
    val totalCompletedCount: Int = 0,
    val failureRatePct: Double = 0.0,
    val classificationCounts: Map<SessionClassification, Int> = emptyMap()
)

enum class ComparisonAlignmentMode {
    BY_TIME,
    BY_WH
}

data class SessionComparisonPoint(
    val xValue: Double, // minutes for BY_TIME, Wh for BY_WH
    val powerA: Double?,
    val powerB: Double?
)

data class SessionComparisonResult(
    val sessionA: ChargeSessionRecord? = null,
    val sessionB: ChargeSessionRecord? = null,
    val alignment: ComparisonAlignmentMode = ComparisonAlignmentMode.BY_TIME,
    val points: List<SessionComparisonPoint> = emptyList(),
    val deltaWh: Double = 0.0,
    val deltaPeakW: Double = 0.0,
    val deltaAvgW: Double = 0.0,
    val deltaDurationSec: Long = 0L
)

/**
 * Thread-safe rolling 5-minute ring buffer of multi-port snapshots and recent BLE/command events
 * used to dump an Incident Black Box (#110, #140).
 */
class BlackBoxRingBuffer(
    private val windowMs: Long = 300_000L,
    private val maxEvents: Int = 20
) {
    private data class SampleFrame(
        val timestampMs: Long,
        val totalW: Double,
        val portsSummary: String
    )

    private val frames = ArrayDeque<SampleFrame>()
    private val events = ArrayDeque<String>()

    @Synchronized
    fun recordFrame(timestampMs: Long, totalW: Double, portsSummary: String) {
        frames.addLast(SampleFrame(timestampMs, totalW, portsSummary))
        val cutoff = timestampMs - windowMs
        while (frames.isNotEmpty() && frames.first().timestampMs < cutoff) {
            frames.removeFirst()
        }
    }

    @Synchronized
    fun recordEvent(timestampMs: Long, tag: String, detail: String) {
        events.addLast("$timestampMs|$tag|$detail")
        while (events.size > maxEvents) {
            events.removeFirst()
        }
    }

    @Synchronized
    fun exportJson(incidentType: String, summary: String): String {
        val root = JSONObject()
        root.put("incidentType", incidentType)
        root.put("summary", summary)
        root.put("capturedAtMs", System.currentTimeMillis())
        val framesArr = JSONArray()
        for (f in frames) {
            framesArr.put(
                JSONObject().apply {
                    put("ts", f.timestampMs)
                    put("w", (f.totalW * 10.0).roundToInt() / 10.0)
                    put("ports", f.portsSummary)
                }
            )
        }
        val eventsArr = JSONArray()
        for (e in events) {
            eventsArr.put(e)
        }
        root.put("frames", framesArr)
        root.put("events", eventsArr)
        return root.toString()
    }
}

/**
 * Stage 4 domain engine:
 * - Phase segmentation (Ramp-up / Stable / Taper / Drop / Interruption / Idle) with strict cadence checks
 * - Volatility & Stability scores
 * - Drop explanation correlation across sibling ports, protocols, and BLE gaps (#63)
 * - Personal records, norms, classification, two-session overlay comparison (#91-#109)
 * - Annotated Replay JSON export (#115)
 */
object SessionDeepAnalyzer {

    private const val REQUIRED_RAMP_CADENCE_MS = 3_500L

    fun analyzeSession(
        session: ChargeSessionRecord,
        portSamples: List<TelemetrySampleEntity>,
        allPortSamplesInWindow: List<TelemetrySampleEntity> = portSamples,
        gaps: List<SessionGapEntity> = emptyList(),
        peerSessions: List<ChargeSessionRecord> = emptyList()
    ): SessionDeepAnalysis {
        val sorted = portSamples.sortedBy { it.timestampMs }
        val sStartMs = (session.startTimeSec * 1000.0).toLong()
        val sEndMs = ((session.endTimeSec ?: session.lastSampleTimeSec) * 1000.0).toLong()
        val sessionGaps = gaps.filter { it.gapEndMs >= sStartMs && it.gapStartMs <= sEndMs }
        val bridgeCount = sorted.count { it.sourceMode == "BRIDGE_SYNC" }

        val cadences = if (sorted.size >= 2) {
            (1 until sorted.size).map { sorted[it].timestampMs - sorted[it - 1].timestampMs }.filter { it > 0L }.sorted()
        } else emptyList()
        val medianCadence = if (cadences.isNotEmpty()) cadences[cadences.size / 2] else 0L
        val startupGapOverlap = sessionGaps.any { it.gapStartMs <= sStartMs + 30_000L }
        val cadenceSufficient = sorted.size >= 4 && medianCadence in 1L..REQUIRED_RAMP_CADENCE_MS && !startupGapOverlap

        val peakW = max(session.peakWatts, sorted.maxOfOrNull { it.powerW } ?: 0.0)
        val phases = segmentPhases(sorted, sessionGaps, peakW)

        var rampSec = 0L
        var stabSec = 0L
        if (cadenceSufficient && peakW >= 5.0) {
            val target75 = peakW * 0.75
            val firstHigh = sorted.firstOrNull { it.powerW >= target75 }
            if (firstHigh != null) {
                rampSec = ((firstHigh.timestampMs - sorted.first().timestampMs) / 1000L).coerceAtLeast(0L)
            }
            val firstStable = phases.firstOrNull { it.phase == SessionPhaseType.STABLE }
            if (firstStable != null) {
                stabSec = ((firstStable.startMs - sorted.first().timestampMs) / 1000L).coerceAtLeast(0L)
            }
        }

        val activeSamples = sorted.filter { it.powerW >= 1.0 }
        val volatilityPct = computeCvPct(activeSamples.map { it.powerW })
        val vStability = computeStabilityScore(activeSamples.map { it.voltageV }, scale = 8.0)
        val aStability = computeStabilityScore(activeSamples.map { it.currentA }, scale = 2.5)

        val dropExplanations = explainPowerDrops(
            portSamples = sorted,
            allSamples = allPortSamplesInWindow,
            gaps = sessionGaps
        )

        val hasSiblingConcurrent = allPortSamplesInWindow.any {
            it.portId != session.portId &&
                it.powerW >= 1.0 &&
                it.timestampMs in sStartMs..sEndMs
        }
        val classification = classifySession(
            session = session,
            gapCount = sessionGaps.size,
            hasSiblingConcurrent = hasSiblingConcurrent
        )

        val samePortCompleted = peerSessions.filter {
            !it.isActive && it.portId == session.portId && it.id != session.id && it.activeDurationSec > 0L
        }
        val normDur = medianDouble(samePortCompleted.map { it.durationSeconds.toDouble() })
        val normWh = medianDouble(samePortCompleted.map { it.whAccumulated })
        val durRatio = if (normDur > 1.0) round2(session.durationSeconds / normDur) else 1.0
        val whRatio = if (normWh > 0.05) round2(session.whAccumulated / normWh) else 1.0

        return SessionDeepAnalysis(
            sessionId = session.id,
            phases = phases,
            rampUpSec = rampSec,
            stabilizationSec = stabSec,
            cadenceSufficient = cadenceSufficient,
            medianCadenceMs = medianCadence,
            powerVolatilityIndexPct = round1(volatilityPct),
            voltageStabilityScore = vStability,
            currentStabilityScore = aStability,
            classification = classification,
            dropExplanations = dropExplanations,
            gapCount = sessionGaps.size,
            bridgeBackfilledSamples = bridgeCount,
            vsNormDurationRatio = durRatio,
            vsNormWhRatio = whRatio
        )
    }

    private fun segmentPhases(
        samples: List<TelemetrySampleEntity>,
        gaps: List<SessionGapEntity>,
        peakW: Double
    ): List<SessionPhaseSegment> {
        if (samples.isEmpty()) return emptyList()
        val result = mutableListOf<SessionPhaseSegment>()
        var curPhase: SessionPhaseType? = null
        var segStart = samples.first().timestampMs
        var segSamples = mutableListOf<TelemetrySampleEntity>()

        fun flush(endMs: Long) {
            val phase = curPhase ?: return
            if (segSamples.isEmpty()) return
            result += SessionPhaseSegment(
                phase = phase,
                startMs = segStart,
                endMs = max(segStart, endMs),
                durationSec = ((max(segStart, endMs) - segStart) / 1000L).coerceAtLeast(1L),
                avgWatts = round1(segSamples.map { it.powerW }.average()),
                peakWatts = round1(segSamples.maxOf { it.powerW }),
                protocolName = segSamples.groupingBy { it.protocolName }.eachCount().maxByOrNull { it.value }?.key ?: ""
            )
        }

        for (i in samples.indices) {
            val s = samples[i]
            val prev = if (i > 0) samples[i - 1] else null
            if (prev != null && s.timestampMs - prev.timestampMs > 20_000L) {
                flush(prev.timestampMs)
                val gapMatch = gaps.any { it.gapStartMs <= s.timestampMs && it.gapEndMs >= prev.timestampMs }
                result += SessionPhaseSegment(
                    phase = SessionPhaseType.INTERRUPTION,
                    startMs = prev.timestampMs,
                    endMs = s.timestampMs,
                    durationSec = ((s.timestampMs - prev.timestampMs) / 1000L).coerceAtLeast(1L),
                    avgWatts = 0.0,
                    peakWatts = 0.0,
                    protocolName = if (gapMatch) "BLE Gap" else "Paused"
                )
                curPhase = null
                segStart = s.timestampMs
                segSamples = mutableListOf()
            }

            val detectedPhase = when {
                s.powerW < 0.5 -> SessionPhaseType.IDLE
                prev != null && prev.powerW >= 10.0 && s.powerW < prev.powerW * 0.72 -> SessionPhaseType.POWER_DROP
                peakW >= 15.0 && i <= samples.size / 3 && s.powerW < peakW * 0.75 && (prev == null || s.powerW >= prev.powerW) ->
                    SessionPhaseType.RAMP_UP
                peakW >= 15.0 && i >= samples.size / 2 && s.powerW <= peakW * 0.48 ->
                    SessionPhaseType.TAPER
                else -> SessionPhaseType.STABLE
            }

            if (curPhase == null) {
                curPhase = detectedPhase
                segStart = s.timestampMs
            } else if (curPhase != detectedPhase && segSamples.size >= 2) {
                flush(prev?.timestampMs ?: s.timestampMs)
                curPhase = detectedPhase
                segStart = s.timestampMs
                segSamples = mutableListOf()
            }
            segSamples += s
        }
        flush(samples.last().timestampMs)
        return result
    }

    private fun explainPowerDrops(
        portSamples: List<TelemetrySampleEntity>,
        allSamples: List<TelemetrySampleEntity>,
        gaps: List<SessionGapEntity>
    ): List<PowerDropExplanation> {
        if (portSamples.size < 2) return emptyList()
        val explanations = mutableListOf<PowerDropExplanation>()
        for (i in 1 until portSamples.size) {
            val prev = portSamples[i - 1]
            val cur = portSamples[i]
            val dt = cur.timestampMs - prev.timestampMs
            if (dt <= 0L || dt > 15_000L) continue
            if (prev.powerW < 10.0) continue
            val dropPct = ((prev.powerW - cur.powerW) / prev.powerW) * 100.0
            if (dropPct <= 25.0) continue

            val gapNear = gaps.any { abs(it.gapStartMs - cur.timestampMs) <= 15_000L || cur.timestampMs in it.gapStartMs..it.gapEndMs }
            val neighborActivated = allSamples.any { other ->
                other.portId != cur.portId &&
                    other.timestampMs in (prev.timestampMs - 5_000L)..(cur.timestampMs + 5_000L) &&
                    other.powerW >= 5.0
            }
            val protocolChanged = prev.protocolName != cur.protocolName

            val (causeCode, detail) = when {
                gapNear -> "BLE_GAP_OVERLAP" to "BLE gap overlap (${prev.powerW.toInt()}W -> ${cur.powerW.toInt()}W)"
                neighborActivated -> "NEIGHBOR_PORT_ACTIVATED" to "Sibling port active (${prev.powerW.toInt()}W -> ${cur.powerW.toInt()}W)"
                protocolChanged -> "PROTOCOL_STEP_DOWN" to "${prev.protocolName} -> ${cur.protocolName}"
                else -> "DEVICE_TAPER_OR_UNEXPLAINED" to "${round1(prev.powerW)}W -> ${round1(cur.powerW)}W"
            }

            if (explanations.none { abs(it.timestampMs - cur.timestampMs) < 15_000L }) {
                explanations += PowerDropExplanation(
                    timestampMs = cur.timestampMs,
                    beforeW = round1(prev.powerW),
                    afterW = round1(cur.powerW),
                    dropPct = round1(dropPct),
                    causeCode = causeCode,
                    detail = detail
                )
            }
        }
        return explanations
    }

    fun classifySession(
        session: ChargeSessionRecord,
        gapCount: Int = 0,
        hasSiblingConcurrent: Boolean = false
    ): SessionClassification {
        val dur = session.durationSeconds
        return when {
            session.endReason == "UNEXPECTED_DROP" || session.endReason == "SAFETY_CUTOFF" ->
                SessionClassification.INCOMPLETE_DROP
            gapCount > 0 || session.gapDurationSec > 30L ->
                SessionClassification.INTERRUPTED
            hasSiblingConcurrent ->
                SessionClassification.MULTIPORT
            session.peakWatts < 8.0 ->
                SessionClassification.LOW_POWER_TRICKLE
            session.peakWatts >= 45.0 ->
                SessionClassification.FAST_BOOST
            dur >= 3600L ->
                SessionClassification.LONG_STEADY
            else ->
                SessionClassification.SHORT_BURST
        }
    }

    fun computePersonalRecords(sessions: List<ChargeSessionRecord>): SessionPersonalRecords {
        val completed = sessions.filter { !it.isActive }
        if (completed.isEmpty()) return SessionPersonalRecords()

        val peakRec = completed.maxByOrNull { it.peakWatts }
        val whRec = completed.maxByOrNull { it.whAccumulated }
        val durRec = completed.maxByOrNull { it.durationSeconds }
        val rateRec = completed
            .filter { it.durationSeconds >= 60L }
            .maxByOrNull { it.whAccumulated / (it.durationSeconds / 3600.0) }
        val maxRate = rateRec?.let { round1(it.whAccumulated / (it.durationSeconds / 3600.0)) } ?: 0.0

        val failCount = completed.count { it.endReason == "UNEXPECTED_DROP" || it.endReason == "SAFETY_CUTOFF" }
        val failPct = round1((failCount.toDouble() / completed.size) * 100.0)
        val counts = completed.groupingBy { classifySession(it) }.eachCount()

        return SessionPersonalRecords(
            maxPeakWatts = round1(peakRec?.peakWatts ?: 0.0),
            maxPeakSessionId = peakRec?.id ?: 0L,
            maxWh = round2(whRec?.whAccumulated ?: 0.0),
            maxWhSessionId = whRec?.id ?: 0L,
            maxDurationSec = durRec?.durationSeconds ?: 0L,
            maxDurationSessionId = durRec?.id ?: 0L,
            maxWhPerHour = maxRate,
            maxWhPerHourSessionId = rateRec?.id ?: 0L,
            totalCompletedCount = completed.size,
            failureRatePct = failPct,
            classificationCounts = counts
        )
    }

    fun compareSessions(
        sessionA: ChargeSessionRecord?,
        samplesA: List<TelemetrySampleEntity>,
        sessionB: ChargeSessionRecord?,
        samplesB: List<TelemetrySampleEntity>,
        alignment: ComparisonAlignmentMode
    ): SessionComparisonResult {
        if (sessionA == null || sessionB == null) {
            return SessionComparisonResult(sessionA = sessionA, sessionB = sessionB, alignment = alignment)
        }
        val sortedA = samplesA.sortedBy { it.timestampMs }
        val sortedB = samplesB.sortedBy { it.timestampMs }

        val points = mutableListOf<SessionComparisonPoint>()
        if (alignment == ComparisonAlignmentMode.BY_TIME) {
            val t0A = sortedA.firstOrNull()?.timestampMs ?: (sessionA.startTimeSec * 1000.0).toLong()
            val t0B = sortedB.firstOrNull()?.timestampMs ?: (sessionB.startTimeSec * 1000.0).toLong()
            val maxMinA = sortedA.lastOrNull()?.let { (it.timestampMs - t0A) / 60_000.0 } ?: (sessionA.durationSeconds / 60.0)
            val maxMinB = sortedB.lastOrNull()?.let { (it.timestampMs - t0B) / 60_000.0 } ?: (sessionB.durationSeconds / 60.0)
            val maxX = max(maxMinA, maxMinB).coerceAtLeast(0.5)
            val steps = 30
            for (i in 0..steps) {
                val xMin = (i.toDouble() / steps) * maxX
                val pA = interpolateAtMinute(sortedA, t0A, xMin)
                val pB = interpolateAtMinute(sortedB, t0B, xMin)
                points += SessionComparisonPoint(round2(xMin), pA, pB)
            }
        } else {
            val maxWhA = sortedA.maxOfOrNull { it.cumulativeWh } ?: sessionA.whAccumulated
            val maxWhB = sortedB.maxOfOrNull { it.cumulativeWh } ?: sessionB.whAccumulated
            val maxX = max(maxWhA, maxWhB).coerceAtLeast(0.5)
            val steps = 30
            for (i in 0..steps) {
                val xWh = (i.toDouble() / steps) * maxX
                val pA = interpolateAtWh(sortedA, xWh)
                val pB = interpolateAtWh(sortedB, xWh)
                points += SessionComparisonPoint(round2(xWh), pA, pB)
            }
        }

        return SessionComparisonResult(
            sessionA = sessionA,
            sessionB = sessionB,
            alignment = alignment,
            points = points,
            deltaWh = round2(sessionA.whAccumulated - sessionB.whAccumulated),
            deltaPeakW = round1(sessionA.peakWatts - sessionB.peakWatts),
            deltaAvgW = round1(sessionA.avgWatts - sessionB.avgWatts),
            deltaDurationSec = sessionA.durationSeconds - sessionB.durationSeconds
        )
    }

    fun exportAnnotatedReplayJson(
        session: ChargeSessionRecord,
        samples: List<TelemetrySampleEntity>,
        gaps: List<SessionGapEntity>,
        annotations: List<SessionAnnotationEntity>,
        deepAnalysis: SessionDeepAnalysis?
    ): String {
        val root = JSONObject()
        root.put("sessionId", session.id)
        root.put("portId", session.portId)
        root.put("portLabel", session.portLabel)
        root.put("whAccumulated", round2(session.whAccumulated))
        root.put("peakWatts", round1(session.peakWatts))
        root.put("avgWatts", round1(session.avgWatts))
        root.put("protocol", session.protocolUsed)
        if (deepAnalysis != null) {
            root.put("classification", deepAnalysis.classification.name)
            root.put("powerVolatilityPct", deepAnalysis.powerVolatilityIndexPct)
            root.put("voltageStabilityScore", deepAnalysis.voltageStabilityScore)
            root.put("currentStabilityScore", deepAnalysis.currentStabilityScore)
        }

        val annArr = JSONArray()
        for (a in annotations) {
            annArr.put(
                JSONObject().apply {
                    put("timestampMs", a.timestampMs)
                    put("tag", a.tagLabel)
                    put("note", a.noteText)
                    put("favorite", a.isFavorite)
                    put("powerW", a.powerW)
                    put("protocol", a.protocolName)
                }
            )
        }
        root.put("annotations", annArr)

        val gapsArr = JSONArray()
        for (g in gaps) {
            gapsArr.put(
                JSONObject().apply {
                    put("startMs", g.gapStartMs)
                    put("endMs", g.gapEndMs)
                    put("reason", g.reason)
                    put("backfilled", g.wasBackfilledByBridge)
                }
            )
        }
        root.put("gaps", gapsArr)

        val samplesArr = JSONArray()
        for (s in samples) {
            samplesArr.put(
                JSONObject().apply {
                    put("ts", s.timestampMs)
                    put("v", s.voltageV)
                    put("a", s.currentA)
                    put("w", s.powerW)
                    put("wh", s.cumulativeWh)
                    put("proto", s.protocolName)
                    put("src", s.sourceMode)
                }
            )
        }
        root.put("samples", samplesArr)
        return root.toString(2)
    }

    private fun interpolateAtMinute(samples: List<TelemetrySampleEntity>, t0Ms: Long, targetMin: Double): Double? {
        if (samples.isEmpty()) return null
        val targetMs = t0Ms + (targetMin * 60_000.0).toLong()
        if (targetMs < samples.first().timestampMs - 5_000L || targetMs > samples.last().timestampMs + 5_000L) return null
        return samples.minByOrNull { abs(it.timestampMs - targetMs) }?.powerW
    }

    private fun interpolateAtWh(samples: List<TelemetrySampleEntity>, targetWh: Double): Double? {
        if (samples.isEmpty()) return null
        val maxWh = samples.maxOf { it.cumulativeWh }
        if (targetWh > maxWh + 0.05) return null
        return samples.minByOrNull { abs(it.cumulativeWh - targetWh) }?.powerW
    }

    private fun computeCvPct(values: List<Double>): Double {
        if (values.size < 3) return 0.0
        val mean = values.average()
        if (mean <= 0.1) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return (sqrt(variance) / mean) * 100.0
    }

    private fun computeStabilityScore(values: List<Double>, scale: Double): Int {
        if (values.size < 3) return 100
        val cv = computeCvPct(values)
        return (100.0 - cv * scale).roundToInt().coerceIn(0, 100)
    }

    private fun medianDouble(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0
}
