package com.example.cuktech.power

import com.example.cuktech.db.room.PortDevicePresetEntity
import kotlin.math.roundToInt

enum class DevicePresetCategory(val code: String, val defaultWh: Double) {
    PHONE("PHONE", 19.0),
    TABLET("TABLET", 30.0),
    HANDHELD("HANDHELD", 50.0),
    LAPTOP("LAPTOP", 70.0),
    CUSTOM("CUSTOM", 25.0);

    companion object {
        fun fromCode(code: String?): DevicePresetCategory =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: CUSTOM
    }
}

data class EtaEstimate(
    val portId: Int,
    val presetType: DevicePresetCategory,
    val deviceLabel: String,
    val targetWh: Double,
    val remainingWh: Double,
    val estimatedMinutesRemaining: Int?, // null if power < 0.8W
    val progressFraction: Float,
    val isNearlyFull: Boolean,
    val isEstimateOnly: Boolean = true
)

/**
 * Honest energy-based charging ETA estimator using user-assigned device battery Wh presets,
 * initial SoC %, live port wattage, and active session accumulated Wh.
 * Explicitly avoids claiming OEM battery BMS percentages.
 */
object EtaEstimator {
    const val NOMINAL_CELL_VOLTAGE_V = 3.85

    fun whFromMah(capacityMah: Int, cellSeriesCount: Int = 1): Double {
        val safeMah = capacityMah.coerceIn(100, 50_000)
        val safeCells = cellSeriesCount.coerceIn(1, 3)
        return (safeMah * safeCells * NOMINAL_CELL_VOLTAGE_V) / 1000.0
    }

    fun estimate(
        preset: PortDevicePresetEntity,
        liveWatts: Double,
        sessionWh: Double
    ): EtaEstimate {
        val safeTargetWh = preset.targetWh.coerceIn(1.0, 500.0)
        val safeInitPct = preset.initialSocPct.coerceIn(0, 99)
        val alreadyStoredWh = safeTargetWh * (safeInitPct / 100.0) + sessionWh.coerceAtLeast(0.0)
        val progress = (alreadyStoredWh / safeTargetWh).toFloat().coerceIn(0f, 1f)
        val remainingWh = (safeTargetWh - alreadyStoredWh).coerceAtLeast(0.0)
        val nearlyFull = remainingWh <= 0.4 || progress >= 0.98f

        val etaMinutes = when {
            nearlyFull -> 0
            liveWatts < 0.8 -> null
            else -> {
                // Account subtly for CC->CV taper in top ~20% without over-optimistic linear ETA
                val taperFactor = if (progress > 0.78f) 1.25 else 1.08
                val hours = (remainingWh / liveWatts) * taperFactor
                (hours * 60.0).roundToInt().coerceIn(1, 1440)
            }
        }

        return EtaEstimate(
            portId = preset.portId,
            presetType = DevicePresetCategory.fromCode(preset.presetType),
            deviceLabel = preset.deviceLabel,
            targetWh = safeTargetWh,
            remainingWh = remainingWh,
            estimatedMinutesRemaining = etaMinutes,
            progressFraction = progress,
            isNearlyFull = nearlyFull,
            isEstimateOnly = true
        )
    }
}
