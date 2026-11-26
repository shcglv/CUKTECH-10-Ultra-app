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
    val deviceMac: String = "",
    val sourceMode: String = "BLE_DIRECT", // "BLE_DIRECT" | "BRIDGE_SYNC"
    val rssiDbm: Int = 0
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
    val wattsSum: Double = 0.0,
    val fastBoostDurationSec: Long = 0L,
    val taperDurationSec: Long = 0L
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
        wattsSum = wattsSum,
        fastBoostDurationSec = fastBoostDurationSec,
        taperDurationSec = taperDurationSec
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
            wattsSum = record.wattsSum,
            fastBoostDurationSec = record.fastBoostDurationSec,
            taperDurationSec = record.taperDurationSec
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
    val triggerType: String, // LOW_POWER_CUTOFF, DAILY_SCHEDULE, HIGH_LOAD_SCENE, POWER_GUARD, PHONE_SOC_CUTOFF
    val portId: Int = 1, // 1..4
    val thresholdWatts: Double = 1.5,
    val holdSeconds: Int = 300,
    val minuteOfDay: Int = 23 * 60, // Used by DAILY_SCHEDULE (0..1439)
    val daysMask: Int = 0b1111111, // Mon=bit0 .. Sun=bit6
    val actionType: String = "PORT_OFF", // PORT_OFF, SET_SCENE_MODE, USB_A_LOW_CURRENT_ON, SMART_DISPLAY
    val actionSceneMode: Int = 1,
    val lastTriggeredMs: Long = 0L,
    val cooldownMinutes: Int = 30,
    val createdAtMs: Long = System.currentTimeMillis(),
    val priority: Int = 10,
    val targetPhoneSocPct: Int = 85,
    val actionOrientationLock: Boolean = false,
    val actionIdleScreenOff: Boolean = false,
    val actionScreenTimeout: Int = 1
)

@Entity(
    tableName = "deadline_tasks",
    indices = [
        Index(value = ["enabled"]),
        Index(value = ["deadlineEpochMs"])
    ]
)
data class DeadlineTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val portId: Int = 1,
    val targetWh: Double = 25.0,
    val deadlineEpochMs: Long,
    val priority: Int = 2, // 1 = High, 2 = Medium, 3 = Low
    val enabled: Boolean = true,
    val notes: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "session_gaps",
    indices = [
        Index(value = ["deviceMac", "gapStartMs"])
    ]
)
data class SessionGapEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: Long = 0L,
    val deviceMac: String = "",
    val portId: Int = 0, // 0 = charger-wide BLE gap
    val gapStartMs: Long,
    val gapEndMs: Long,
    val reason: String = "BLE_OUT_OF_RANGE",
    val wasBackfilledByBridge: Boolean = false,
    val backfilledSamplesCount: Int = 0
)

@Entity(
    tableName = "command_audit_log",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["targetPortId"])
    ]
)
data class CommandAuditEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val deviceMac: String = "",
    val source: String, // UI, WIDGET, TASKER, ADB_BROADCAST, TCP_GATEWAY, AUTOMATION_RULE, INTERNAL_SAFETY, BRIDGE
    val commandType: String,
    val targetPortId: Int = 0, // 0 = charger-wide, 1..4 = port
    val summary: String,
    val accepted: Boolean,
    val reconciledConfirmed: Boolean? = null,
    val latencyMs: Long = 0L,
    val errorCode: String? = null
)

@Entity(
    tableName = "ble_link_events",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["deviceMac", "timestampMs"])
    ]
)
data class BleLinkEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val deviceMac: String = "",
    val eventType: String, // CONNECTED, DISCONNECTED, RECONNECT_ATTEMPT, RECONNECT_SUCCESS, RSSI_SAMPLE, GAP_STITCHED, BRIDGE_SYNCED
    val rssiDbm: Int = 0,
    val intervalMs: Long = 0L,
    val detail: String = ""
)

@Entity(tableName = "charger_nodes")
data class ChargerNodeEntity(
    @PrimaryKey
    val deviceMac: String,
    val name: String,
    val locationContext: String = "Default",
    val mainsVoltageMode: Int = 220, // 220 (120W max) or 110 (110W max)
    val bridgeEndpointUrl: String = "",
    val lastSeenMs: Long = 0L,
    val lastTotalWatts: Double = 0.0,
    val lastTotalWhToday: Double = 0.0,
    val lastRssiDbm: Int = 0,
    val isOnline: Boolean = false
)

@Entity(
    tableName = "scenario_observations",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["deviceMac", "sceneMode", "activePortsMask"])
    ]
)
data class ScenarioObservationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val deviceMac: String = "",
    val sceneMode: Int = 1,
    val activePortsMask: Int = 0,
    val c1W: Double = 0.0,
    val c2W: Double = 0.0,
    val c3W: Double = 0.0,
    val aW: Double = 0.0,
    val effectiveTotalW: Double = 0.0,
    val c3aShared: Boolean = false
)

@Entity(
    tableName = "session_annotations",
    indices = [
        Index(value = ["sessionId", "timestampMs"])
    ]
)
data class SessionAnnotationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: Long,
    val timestampMs: Long = 0L,
    val tagLabel: String = "",
    val noteText: String = "",
    val isFavorite: Boolean = false,
    val voltageV: Double = 0.0,
    val currentA: Double = 0.0,
    val powerW: Double = 0.0,
    val protocolName: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "incident_black_boxes",
    indices = [
        Index(value = ["timestampMs"])
    ]
)
data class IncidentBlackBoxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val deviceMac: String = "",
    val portId: Int = 0,
    val incidentType: String,
    val summary: String,
    val windowJson: String
)

@Entity(tableName = "device_inventory")
data class DeviceInventoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val category: String = "PHONE", // LAPTOP | PHONE | TABLET | AUDIO | POWERBANK | OTHER
    val ratedWh: Double = 20.0,
    val ratedMaxW: Double = 45.0,
    val preferredProtocol: String = "PD",
    val assignedPortId: Int = 0, // 0 = any, 1..4
    val totalRecordedWh: Double = 0.0,
    val sessionCount: Int = 0,
    val lastChargeMs: Long = 0L,
    val notes: String = ""
)

@Entity(tableName = "cable_inventory")
data class CableInventoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val connectorType: String = "C_TO_C", // C_TO_C | A_TO_C | C_TO_LIGHTNING
    val ratedAmps: Int = 5,
    val lengthCm: Int = 100,
    val hasEmarker: Boolean = true,
    val lastMeasuredMohm: Int? = null,
    val lastUncertaintyMohm: Int? = null,
    val lastSagMv: Int? = null,
    val testCount: Int = 0,
    val notes: String = ""
)

@Entity(
    tableName = "cable_lab_tests",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["cableId"])
    ]
)
data class CableLabTestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val cableId: Long = 0L,
    val cableName: String = "",
    val portId: Int = 1,
    val nominalRailV: Double = 20.0,
    val avgVoltageV: Double = 20.0,
    val avgCurrentA: Double = 0.0,
    val sampleCount: Int = 0,
    val sagMv: Int = 0,
    val estimatedMohm: Int = 0,
    val uncertaintyMohm: Int = 0,
    val confidence: String = "LOW", // HIGH | MEDIUM | LOW
    val notes: String = ""
)


