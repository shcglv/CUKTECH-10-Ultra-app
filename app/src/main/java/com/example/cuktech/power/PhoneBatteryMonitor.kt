package com.example.cuktech.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.cuktech.protocol.PortTelemetry
import kotlin.math.abs
import kotlin.math.roundToInt

data class PhoneBatterySnapshot(
    val socPct: Int = 0,
    val voltageV: Double = 0.0,
    val currentMa: Int = 0,
    val batteryPowerW: Double = 0.0,
    val tempC: Double = 0.0,
    val isCharging: Boolean = false,
    val pluggedSource: String = "UNPLUGGED", // AC | USB | WIRELESS | UNPLUGGED
    val estimatedEfficiencyPct: Int? = null, // Only when plugged & matching active port output
    val matchedPortId: Int? = null,
    val updatedAtMs: Long = 0L
)

/**
 * Reads Android BatteryManager telemetry to support phone calibration (#224, #261)
 * and rough charger-to-battery efficiency comparison (honestly labeled as estimate
 * because phone SoC/display draw is included).
 */
object PhoneBatteryMonitor {

    fun readSnapshot(
        context: Context,
        activeChargerPorts: Map<Int, PortTelemetry> = emptyMap(),
        nowMs: Long = System.currentTimeMillis()
    ): PhoneBatterySnapshot {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val socPct = if (level >= 0) {
            ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
        } else {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 0
        }

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageV = ((voltageMv / 1000.0) * 100.0).roundToInt() / 100.0

        val rawMicroAmps = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        val currentMa = if (rawMicroAmps != Int.MIN_VALUE && rawMicroAmps != 0) {
            // Standard Android returns microamperes; some OEMs return milliamperes
            if (abs(rawMicroAmps) > 20_000) rawMicroAmps / 1000 else rawMicroAmps
        } else 0

        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = (tempTenths / 10.0)

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

        val pluggedSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> "UNPLUGGED"
        }

        val battPowerW = ((voltageV * (abs(currentMa) / 1000.0)) * 100.0).roundToInt() / 100.0

        // Match against active charger port only when phone is plugged in and battery power is meaningfully flowing
        var matchedPid: Int? = null
        var effPct: Int? = null
        if (isCharging && battPowerW >= 1.5) {
            val candidate = activeChargerPorts.values
                .filter { it.enabled && it.powerW >= battPowerW * 0.85 }
                .minByOrNull { abs(it.powerW - battPowerW) }
            if (candidate != null && candidate.powerW >= 2.0) {
                matchedPid = candidate.portId
                effPct = ((battPowerW / candidate.powerW) * 100.0).roundToInt().coerceIn(40, 99)
            }
        }

        return PhoneBatterySnapshot(
            socPct = socPct,
            voltageV = voltageV,
            currentMa = currentMa,
            batteryPowerW = battPowerW,
            tempC = tempC,
            isCharging = isCharging,
            pluggedSource = pluggedSource,
            estimatedEfficiencyPct = effPct,
            matchedPortId = matchedPid,
            updatedAtMs = nowMs
        )
    }
}
