package com.example.cuktech.power

import com.example.cuktech.db.room.CableLabTestEntity
import com.example.cuktech.notifications.AnomalyAlert
import com.example.cuktech.telemetry.TelemetryIntegritySnapshot
import com.example.ui.viewmodel.CommandWearSummary
import java.util.Locale
import kotlin.math.roundToInt

enum class PowerDoctorGrade {
    EXCELLENT,
    GOOD,
    ATTENTION,
    CRITICAL
}

data class PowerDoctorCategory(
    val id: String,
    val titleKey: String,
    val score: Int,
    val weightPct: Int,
    val detailText: String
)

data class PowerDoctorFinding(
    val id: String,
    val severity: String, // "INFO" | "WARN" | "CRITICAL"
    val titleKey: String,
    val metricDetail: String,
    val actionKey: String
)

data class PowerDoctorReport(
    val overallScore: Int = 100,
    val grade: PowerDoctorGrade = PowerDoctorGrade.EXCELLENT,
    val categories: List<PowerDoctorCategory> = emptyList(),
    val findings: List<PowerDoctorFinding> = emptyList(),
    val evaluatedAtMs: Long = 0L
)

/**
 * Explainable Power Doctor: computes an explicit weighted diagnostic index (0..100)
 * strictly from measured BLE link integrity, cable voltage sag & loop resistance bounds,
 * protocol/anomaly stability, and command audit / relay wear counters.
 */
object PowerDoctorEngine {

    fun evaluate(
        integrity: TelemetryIntegritySnapshot,
        cableDiagnostics: Map<Int, PortCableDiagnostic>,
        cableLabTests: List<CableLabTestEntity>,
        anomalyAlerts: List<AnomalyAlert>,
        wearSummary: CommandWearSummary,
        nowMs: Long = System.currentTimeMillis()
    ): PowerDoctorReport {
        val findings = mutableListOf<PowerDoctorFinding>()

        // 1. Telemetry & BLE Link Health (weight 30%)
        var linkScore = 100
        if (integrity.uptimePct24h in 0.1..99.9) {
            val uptimePenalty = ((100.0 - integrity.uptimePct24h) * 0.7).roundToInt().coerceIn(0, 35)
            linkScore -= uptimePenalty
        }
        if (integrity.openBlindGapsCount > 0) {
            linkScore -= (integrity.openBlindGapsCount * 6).coerceAtMost(24)
            findings += PowerDoctorFinding(
                id = "blind_gaps",
                severity = if (integrity.totalBlindMinutes24h >= 30.0) "WARN" else "INFO",
                titleKey = "doctor_find_blind_gaps",
                metricDetail = String.format(
                    Locale.US,
                    "%d gap(s), %.1f min unmeasured",
                    integrity.openBlindGapsCount,
                    integrity.totalBlindMinutes24h
                ),
                actionKey = "doctor_act_bridge"
            )
        }
        if (integrity.currentRssiDbm in -119..-84) {
            linkScore -= if (integrity.currentRssiDbm <= -90) 18 else 10
            findings += PowerDoctorFinding(
                id = "weak_rssi",
                severity = "WARN",
                titleKey = "doctor_find_weak_rssi",
                metricDetail = "${integrity.currentRssiDbm} dBm",
                actionKey = "doctor_act_closer"
            )
        }
        if (integrity.reconnectCount24h >= 4) {
            linkScore -= ((integrity.reconnectCount24h - 3) * 3).coerceAtMost(15)
        }
        linkScore = linkScore.coerceIn(0, 100)

        // 2. Cable & Voltage Rail Delivery (weight 30%)
        var cableScore = 100
        val activePorts = cableDiagnostics.values.filter { it.isActive }
        val unstableCount = activePorts.count { it.healthRating == CableHealthRating.UNSTABLE }
        val modSagCount = activePorts.count { it.healthRating == CableHealthRating.MODERATE_SAG }
        val worstSagMv = activePorts.maxOfOrNull { it.voltageSagMv } ?: 0
        val latestHighMohmTest = cableLabTests.firstOrNull { it.estimatedMohm >= 450 }

        if (unstableCount > 0) {
            cableScore -= (unstableCount * 28).coerceAtMost(60)
            findings += PowerDoctorFinding(
                id = "cable_unstable",
                severity = "CRITICAL",
                titleKey = "doctor_find_cable_sag",
                metricDetail = "${worstSagMv} mV sag ($unstableCount port(s))",
                actionKey = "doctor_act_swap_cable"
            )
        } else if (modSagCount > 0) {
            cableScore -= (modSagCount * 14).coerceAtMost(30)
            findings += PowerDoctorFinding(
                id = "cable_mod_sag",
                severity = "WARN",
                titleKey = "doctor_find_cable_sag",
                metricDetail = "${worstSagMv} mV sag",
                actionKey = "doctor_act_cable_lab"
            )
        }
        if (latestHighMohmTest != null) {
            cableScore -= 15
            findings += PowerDoctorFinding(
                id = "cable_high_mohm",
                severity = "WARN",
                titleKey = "doctor_find_cable_mohm",
                metricDetail = "${latestHighMohmTest.estimatedMohm} ±${latestHighMohmTest.uncertaintyMohm} mΩ",
                actionKey = "doctor_act_swap_cable"
            )
        }
        cableScore = cableScore.coerceIn(0, 100)

        // 3. Protocol & Anomaly Stability (weight 25%)
        var stabilityScore = 100
        val totalAnomalies = anomalyAlerts.sumOf { it.countInWindow }
        if (totalAnomalies > 0) {
            stabilityScore -= (totalAnomalies * 12).coerceAtMost(65)
            findings += PowerDoctorFinding(
                id = "anomalies_active",
                severity = if (totalAnomalies >= 3) "CRITICAL" else "WARN",
                titleKey = "doctor_find_anomalies",
                metricDetail = "$totalAnomalies alert event(s)",
                actionKey = "doctor_act_inspect_blackbox"
            )
        }
        stabilityScore = stabilityScore.coerceIn(0, 100)

        // 4. Hardware Relay & Command Health (weight 15%)
        var relayScore = 100
        val totalAttempts = wearSummary.totalExecuted + wearSummary.totalRejected
        if (totalAttempts > 0 && wearSummary.totalRejected > 0) {
            val rejectPct = (wearSummary.totalRejected.toDouble() / totalAttempts.toDouble()) * 100.0
            if (rejectPct >= 10.0) {
                relayScore -= ((rejectPct - 5.0) * 1.1).roundToInt().coerceAtMost(35)
                findings += PowerDoctorFinding(
                    id = "cmd_rejects",
                    severity = "WARN",
                    titleKey = "doctor_find_cmd_rejects",
                    metricDetail = String.format(Locale.US, "%d/%d rejected (%.0f%%)", wearSummary.totalRejected, totalAttempts, rejectPct),
                    actionKey = "doctor_act_check_lockout"
                )
            }
        }
        val maxPortToggles = wearSummary.portToggleCounts.values.maxOrNull() ?: 0
        if (maxPortToggles >= 500) {
            relayScore -= 12
        }
        if (wearSummary.killswitchCount >= 20) {
            relayScore -= 8
        }
        relayScore = relayScore.coerceIn(0, 100)

        val weighted = ((linkScore * 30 + cableScore * 30 + stabilityScore * 25 + relayScore * 15) / 100.0)
            .roundToInt()
            .coerceIn(0, 100)

        val grade = when {
            weighted >= 88 -> PowerDoctorGrade.EXCELLENT
            weighted >= 70 -> PowerDoctorGrade.GOOD
            weighted >= 50 -> PowerDoctorGrade.ATTENTION
            else -> PowerDoctorGrade.CRITICAL
        }

        if (findings.isEmpty()) {
            findings += PowerDoctorFinding(
                id = "all_nominal",
                severity = "INFO",
                titleKey = "doctor_find_nominal",
                metricDetail = String.format(
                    Locale.US,
                    "%.1f%% uptime · %d anomalies",
                    integrity.uptimePct24h,
                    totalAnomalies
                ),
                actionKey = "doctor_act_none"
            )
        }

        val categories = listOf(
            PowerDoctorCategory(
                id = "telemetry_link",
                titleKey = "doctor_cat_link",
                score = linkScore,
                weightPct = 30,
                detailText = String.format(Locale.US, "%.1f%% · %d gaps", integrity.uptimePct24h, integrity.openBlindGapsCount)
            ),
            PowerDoctorCategory(
                id = "cable_delivery",
                titleKey = "doctor_cat_cable",
                score = cableScore,
                weightPct = 30,
                detailText = if (worstSagMv > 0) "${worstSagMv} mV sag" else "Nominal"
            ),
            PowerDoctorCategory(
                id = "protocol_stability",
                titleKey = "doctor_cat_stability",
                score = stabilityScore,
                weightPct = 25,
                detailText = "$totalAnomalies alert(s)"
            ),
            PowerDoctorCategory(
                id = "relay_wear",
                titleKey = "doctor_cat_relay",
                score = relayScore,
                weightPct = 15,
                detailText = "${wearSummary.totalExecuted} ok · ${wearSummary.totalRejected} rej"
            )
        )

        return PowerDoctorReport(
            overallScore = weighted,
            grade = grade,
            categories = categories,
            findings = findings,
            evaluatedAtMs = nowMs
        )
    }
}
