package com.example.cuktech.db.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit

@Entity(
    tableName = "telemetry_samples",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["portId", "timestampMs"])
    ]
)
data class TelemetrySampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMs: Long,
    val portId: Int,
    val voltageV: Double,
    val currentA: Double,
    val powerW: Double,
    val protocolName: String,
    val cumulativeWh: Double = 0.0,
    val deviceMac: String = ""
)

@Entity(
    tableName = "charge_sessions",
    indices = [
        Index(value = ["startTimeSec"]),
        Index(value = ["isActive"]),
        Index(value = ["isArchived"])
    ]
)
data class ChargeSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val portId: Int,
    val portLabel: String,
    val deviceMac: String = "",
    val startTimeSec: Double,
    val endTimeSec: Double? = null,
    val lastSampleTimeSec: Double = startTimeSec,
    val activeDurationSec: Long = 0L,
    val gapDurationSec: Long = 0L,
    val whAccumulated: Double = 0.0,
    val peakWatts: Double = 0.0,
    val avgWatts: Double = 0.0,
    val protocolUsed: String = "Idle",
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val pauseReason: String? = null,
    val endReason: String? = null,
    val tariffPerKwhSnapshot: Double = 0.0,
    val currencyCode: String = "USD",
    val computedCost: Double = 0.0,
    val isArchived: Boolean = false,
    val sampleCount: Int = 0,
    val wattsSum: Double = 0.0
) {
    fun toRecord(): ChargeSessionRecord = ChargeSessionRecord(
        id = id,
        portId = portId,
        portLabel = portLabel,
        startTimeSec = startTimeSec,
        endTimeSec = endTimeSec,
        whAccumulated = whAccumulated,
        peakWatts = peakWatts,
        avgWatts = avgWatts,
        protocolUsed = protocolUsed,
        isActive = isActive,
        deviceMac = deviceMac,
        lastSampleTimeSec = lastSampleTimeSec,
        activeDurationSec = activeDurationSec,
        gapDurationSec = gapDurationSec,
        isPaused = isPaused,
        pauseReason = pauseReason,
        endReason = endReason,
        tariffPerKwhSnapshot = tariffPerKwhSnapshot,
        currencyCode = currencyCode,
        computedCost = computedCost,
        isArchived = isArchived,
        sampleCount = sampleCount,
        wattsSum = wattsSum
    )

    companion object {
        fun fromRecord(record: ChargeSessionRecord): ChargeSessionEntity = ChargeSessionEntity(
            id = record.id,
            portId = record.portId,
            portLabel = record.portLabel,
            deviceMac = record.deviceMac,
            startTimeSec = record.startTimeSec,
            endTimeSec = record.endTimeSec,
            lastSampleTimeSec = record.lastSampleTimeSec,
            activeDurationSec = record.activeDurationSec,
            gapDurationSec = record.gapDurationSec,
            whAccumulated = record.whAccumulated,
            peakWatts = record.peakWatts,
            avgWatts = record.avgWatts,
            protocolUsed = record.protocolUsed,
            isActive = record.isActive,
            isPaused = record.isPaused,
            pauseReason = record.pauseReason,
            endReason = record.endReason,
            tariffPerKwhSnapshot = record.tariffPerKwhSnapshot,
            currencyCode = record.currencyCode,
            computedCost = record.computedCost,
            isArchived = record.isArchived,
            sampleCount = record.sampleCount,
            wattsSum = record.wattsSum
        )
    }
}

@Entity(
    tableName = "daily_energy_aggregates",
    primaryKeys = ["dateIso", "portId", "deviceMac", "currencyCode"]
)
data class DailyEnergyAggregateEntity(
    val dateIso: String,
    val portId: Int,
    val deviceMac: String = "",
    val currencyCode: String = "USD",
    val totalWh: Double = 0.0,
    val totalActiveSec: Long = 0L,
    val totalGapSec: Long = 0L,
    val sessionCount: Int = 0,
    val maxPeakWatts: Double = 0.0,
    val totalCost: Double = 0.0,
    val archivedSessionsCount: Int = 0
)

@Entity(tableName = "port_charge_limits")
data class PortChargeLimitEntity(
    @PrimaryKey
    val portId: Int,
    val whTarget: Double,
    val mode: String = "once",
    val baselineWh: Double = 0.0,
    val enabled: Boolean = true,
    val triggered: Boolean = false
) {
    fun toModel(): PortChargeLimit = PortChargeLimit(
        portId = portId,
        whTarget = whTarget,
        mode = mode,
        baselineWh = baselineWh,
        enabled = enabled,
        triggered = triggered
    )

    companion object {
        fun fromModel(limit: PortChargeLimit): PortChargeLimitEntity = PortChargeLimitEntity(
            portId = limit.portId,
            whTarget = limit.whTarget,
            mode = limit.mode,
            baselineWh = limit.baselineWh,
            enabled = limit.enabled,
            triggered = limit.triggered
        )
    }
}

@Entity(
    tableName = "device_aliases",
    primaryKeys = ["deviceMac", "portId"]
)
data class DeviceAliasEntity(
    val deviceMac: String,
    val portId: Int, // 0 = charger alias, 1..4 = port device alias
    val aliasName: String,
    val iconKey: String = "usb",
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "charging_profiles")
data class ChargingProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val sceneMode: Int = 1,
    val portSwitchBitmap: Int = 0x0F,
    val screenTimeout: Int = 1,
    val usbALowCurrent: Boolean = false,
    val idleScreenOff: Boolean = false,
    val orientationLock: Boolean = false,
    val whLimitPort1: Double = 0.0,
    val whLimitPort2: Double = 0.0,
    val whLimitPort3: Double = 0.0,
    val whLimitPort4: Double = 0.0,
    val isBuiltIn: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "port_device_presets")
data class PortDevicePresetEntity(
    @PrimaryKey
    val portId: Int, // 1..4
    val presetType: String, // CUSTOM (kept for backward compatibility)
    val deviceLabel: String = "",
    val targetWh: Double = 19.25,
    val initialSocPct: Int = 20,
    val capacityMah: Int = 5000,
    val cellSeriesCount: Int = 1,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "automation_rules",
    indices = [
        Index(value = ["enabled"]),
        Index(value = ["triggerType"])
    ]
)
data class AutomationRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val enabled: Boolean = true,
    val triggerType: String, // LOW_POWER_CUTOFF, DAILY_SCHEDULE, HIGH_LOAD_SCENE
    val portId: Int = 1, // 1..4
    val thresholdWatts: Double = 1.5,
    val holdSeconds: Int = 300,
    val minuteOfDay: Int = 23 * 60, // Used by DAILY_SCHEDULE (0..1439)
    val daysMask: Int = 0b1111111, // Mon=bit0 .. Sun=bit6
    val actionType: String = "PORT_OFF", // PORT_OFF, SET_SCENE_MODE
    val actionSceneMode: Int = 1,
    val lastTriggeredMs: Long = 0L,
    val cooldownMinutes: Int = 30,
    val createdAtMs: Long = System.currentTimeMillis()
)

