package com.example.cuktech.power

import com.example.cuktech.db.room.CableInventoryEntity
import com.example.cuktech.db.room.CableLabTestEntity
import com.example.cuktech.db.room.ChargerNodeEntity
import com.example.cuktech.db.room.DeviceInventoryEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.energy.ChargeSessionRecord
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class CableLabSampleInput(
    val voltageV: Double,
    val currentA: Double,
    val protocolName: String
)

sealed class CableLabEvaluationResult {
    data class Valid(
        val portId: Int,
        val cableId: Long,
        val cableName: String,
        val nominalRailV: Double,
        val avgVoltageV: Double,
        val avgCurrentA: Double,
        val sampleCount: Int,
        val sagMv: Int,
        val estimatedMohm: Int,
        val uncertaintyMohm: Int,
        val confidence: String // HIGH | MEDIUM | LOW
    ) : CableLabEvaluationResult() {
        fun toEntity(nowMs: Long = System.currentTimeMillis()): CableLabTestEntity = CableLabTestEntity(
            timestampMs = nowMs,
            cableId = cableId,
            cableName = cableName,
            portId = portId,
            nominalRailV = nominalRailV,
            avgVoltageV = avgVoltageV,
            avgCurrentA = avgCurrentA,
            sampleCount = sampleCount,
            sagMv = sagMv,
            estimatedMohm = estimatedMohm,
            uncertaintyMohm = uncertaintyMohm,
            confidence = confidence,
            notes = "Loop: port + cable + connector (±0.1V ADC)"
        )
    }

    data class Insufficient(
        val reasonCode: String // PPS_UNSUPPORTED | LOW_CURRENT | TOO_FEW_SAMPLES | NO_FIXED_RAIL
    ) : CableLabEvaluationResult()
}

data class DeviceMatchSuggestion(
    val device: DeviceInventoryEntity,
    val session: ChargeSessionRecord,
    val matchConfidencePct: Int
)

data class ChargerPassportSummary(
    val chargerName: String,
    val locationContext: String,
    val mainsVoltageMode: Int,
    val totalSessions: Int,
    val totalDeliveredWh: Double,
    val recordedCableTests: Int,
    val avgCableLoopMohm: Int?
)

data class CableLabRunnerState(
    val isRunning: Boolean = false,
    val portId: Int = 1,
    val cableId: Long = 0L,
    val collectedCount: Int = 0,
    val targetCount: Int = 12,
    val lastStatusCode: String = ""
)

/**
 * Stage 5 Domain Engine (#116-#139, #215-#230, #244-#258, #302):
 * - Statistical 0.1V quantization compensator for Cable & Port Loop Diagnostics (#302)
 * - Smart Device Suggestion matching recent session curves against Device Inventory (#216-#217)
 * - Charger Passport & Anonymized Lab Research JSON Export (#134, #136, #137, #244-#258)
 */
object InventoryAndCableLabEngine {

    private val FIXED_RAILS = listOf(5.0, 9.0, 12.0, 15.0, 20.0)

    fun evaluateCableLabSamples(
        portId: Int,
        cableId: Long,
        cableName: String,
        samples: List<CableLabSampleInput>
    ): CableLabEvaluationResult {
        if (samples.isEmpty()) {
            return CableLabEvaluationResult.Insufficient("TOO_FEW_SAMPLES")
        }
        val active = samples.filter { it.currentA >= 0.2 && it.voltageV >= 4.0 }
        if (active.isEmpty()) {
            return CableLabEvaluationResult.Insufficient("TOO_FEW_SAMPLES")
        }

        // Check if majority are variable PPS / Xiaomi HyperCharge (cannot compute fixed-rail voltage sag)
        val ppsCount = active.count {
            it.protocolName.contains("PPS", ignoreCase = true) ||
                it.protocolName.contains("Xiaomi", ignoreCase = true)
        }
        if (ppsCount > active.size / 2) {
            return CableLabEvaluationResult.Insufficient("PPS_UNSUPPORTED")
        }

        val highLoad = active.filter {
            !it.protocolName.contains("PPS", ignoreCase = true) &&
                !it.protocolName.contains("Xiaomi", ignoreCase = true) &&
                it.currentA >= 1.5
        }
        if (highLoad.isEmpty()) {
            return CableLabEvaluationResult.Insufficient("LOW_CURRENT")
        }
        if (highLoad.size < 5) {
            return CableLabEvaluationResult.Insufficient("TOO_FEW_SAMPLES")
        }

        // Find dominant fixed rail across high-load samples
        val railVotes = highLoad.mapNotNull { s ->
            val nearest = FIXED_RAILS.minByOrNull { abs(it - s.voltageV) }
            if (nearest != null && abs(nearest - s.voltageV) <= 0.9) nearest else null
        }
        if (railVotes.size < highLoad.size / 2) {
            return CableLabEvaluationResult.Insufficient("NO_FIXED_RAIL")
        }
        val nominalRail = railVotes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: return CableLabEvaluationResult.Insufficient("NO_FIXED_RAIL")

        val aligned = highLoad.filter { abs(it.voltageV - nominalRail) <= 0.9 }
        if (aligned.size < 5) {
            return CableLabEvaluationResult.Insufficient("TOO_FEW_SAMPLES")
        }

        val avgV = (aligned.map { it.voltageV }.average() * 1000.0).roundToInt() / 1000.0
        val avgI = (aligned.map { it.currentA }.average() * 1000.0).roundToInt() / 1000.0
        if (avgI < 1.5) {
            return CableLabEvaluationResult.Insufficient("LOW_CURRENT")
        }

        val sagMv = ((nominalRail - avgV) * 1000.0).roundToInt().coerceAtLeast(0)
        val estimatedMohm = ((sagMv.toDouble() / avgI)).roundToInt().coerceIn(15, 2500)

        // Quantization uncertainty due to 0.1V (100 mV) + 0.1A ADC resolution on CUKTECH 10 Ultra
        val quantMohm = if (aligned.size >= 10) {
            ((55.0 / avgI) + 20.0).roundToInt().coerceIn(25, 140)
        } else {
            ((90.0 / avgI) + 25.0).roundToInt().coerceIn(40, 180)
        }
        val confidence = when {
            aligned.size >= 10 && avgI >= 2.2 -> "HIGH"
            aligned.size >= 8 && avgI >= 1.5 -> "MEDIUM"
            else -> "LOW"
        }

        return CableLabEvaluationResult.Valid(
            portId = portId,
            cableId = cableId,
            cableName = cableName.ifBlank { "Ad-hoc Cable" },
            nominalRailV = nominalRail,
            avgVoltageV = avgV,
            avgCurrentA = avgI,
            sampleCount = aligned.size,
            sagMv = sagMv,
            estimatedMohm = estimatedMohm,
            uncertaintyMohm = quantMohm,
            confidence = confidence
        )
    }

    fun evaluateFromTelemetryEntities(
        portId: Int,
        cable: CableInventoryEntity?,
        samples: List<TelemetrySampleEntity>
    ): CableLabEvaluationResult {
        val inputs = samples
            .filter { it.portId == portId }
            .sortedByDescending { it.timestampMs }
            .take(30)
            .map { CableLabSampleInput(it.voltageV, it.currentA, it.protocolName) }
        return evaluateCableLabSamples(
            portId = portId,
            cableId = cable?.id ?: 0L,
            cableName = cable?.name ?: "Port $portId Probe",
            samples = inputs
        )
    }

    fun suggestMatchingDevice(
        latestSession: ChargeSessionRecord?,
        devices: List<DeviceInventoryEntity>
    ): DeviceMatchSuggestion? {
        val sess = latestSession ?: return null
        if (devices.isEmpty() || sess.peakWatts < 1.0) return null

        var bestDevice: DeviceInventoryEntity? = null
        var bestScore = 0
        for (dev in devices) {
            var score = 0
            if (dev.ratedMaxW > 1.0) {
                val ratio = minOf(sess.peakWatts, dev.ratedMaxW) / max(sess.peakWatts, dev.ratedMaxW)
                score += (ratio * 60.0).roundToInt()
            }
            if (dev.preferredProtocol.isNotBlank() &&
                sess.protocolUsed.contains(dev.preferredProtocol, ignoreCase = true)
            ) {
                score += 25
            }
            if (dev.assignedPortId == sess.portId) {
                score += 15
            }
            if (score > bestScore) {
                bestScore = score
                bestDevice = dev
            }
        }
        val winner = bestDevice ?: return null
        return if (bestScore >= 55) {
            DeviceMatchSuggestion(winner, sess, bestScore.coerceIn(55, 99))
        } else null
    }

    fun buildChargerPassport(
        activeNode: ChargerNodeEntity?,
        sessions: List<ChargeSessionRecord>,
        cableTests: List<CableLabTestEntity>
    ): ChargerPassportSummary {
        val totalWh = sessions.sumOf { it.whAccumulated }
        val avgMohm = cableTests.takeIf { it.isNotEmpty() }?.map { it.estimatedMohm }?.average()?.roundToInt()
        return ChargerPassportSummary(
            chargerName = activeNode?.name?.takeIf { it.isNotBlank() } ?: "CUKTECH 10 Ultra",
            locationContext = activeNode?.locationContext?.takeIf { it.isNotBlank() } ?: "Default",
            mainsVoltageMode = activeNode?.mainsVoltageMode ?: 220,
            totalSessions = sessions.size,
            totalDeliveredWh = (totalWh * 100.0).roundToInt() / 100.0,
            recordedCableTests = cableTests.size,
            avgCableLoopMohm = avgMohm
        )
    }

    /**
     * Produces anonymized lab research JSON with strict removal of MAC addresses,
     * tokens, charger/user notes, and device names (#134, #137, #253-#258).
     */
    fun exportAnonymizedLabJson(
        passport: ChargerPassportSummary,
        cableTests: List<CableLabTestEntity>,
        cables: List<CableInventoryEntity>,
        scenarioSnapshot: ScenarioAndPdoSnapshot
    ): String {
        val root = JSONObject()
        root.put("schema", "cuktech10_ultra_anonymized_lab_v1")
        root.put("mainsVoltageMode", passport.mainsVoltageMode)
        root.put("totalSessionsCount", passport.totalSessions)
        root.put("totalDeliveredWh", passport.totalDeliveredWh)

        val cableArr = JSONArray()
        for (t in cableTests) {
            val matchedCable = cables.firstOrNull { it.id == t.cableId }
            cableArr.put(
                JSONObject().apply {
                    put("portId", t.portId)
                    put("connectorType", matchedCable?.connectorType ?: "UNKNOWN")
                    put("lengthCm", matchedCable?.lengthCm ?: 0)
                    put("ratedAmps", matchedCable?.ratedAmps ?: 0)
                    put("nominalRailV", t.nominalRailV)
                    put("avgCurrentA", t.avgCurrentA)
                    put("sagMv", t.sagMv)
                    put("estimatedLoopMohm", t.estimatedMohm)
                    put("uncertaintyMohm", t.uncertaintyMohm)
                    put("confidence", t.confidence)
                }
            )
        }
        root.put("cableLoopMeasurements", cableArr)

        val sceneArr = JSONArray()
        for (st in scenarioSnapshot.sceneStats.filter { it.sampleCount > 0 }) {
            sceneArr.put(
                JSONObject().apply {
                    put("sceneMode", st.sceneMode)
                    put("observations", st.sampleCount)
                    put("avgTotalW", st.avgTotalW)
                    put("peakTotalW", st.peakTotalW)
                }
            )
        }
        root.put("scenarioSummary", sceneArr)
        return root.toString(2)
    }
}
