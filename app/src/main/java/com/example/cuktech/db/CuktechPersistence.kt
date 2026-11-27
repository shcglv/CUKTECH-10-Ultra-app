package com.example.cuktech.db

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.db.room.BleLinkEventEntity
import com.example.cuktech.db.room.CableInventoryEntity
import com.example.cuktech.db.room.CableLabTestEntity
import com.example.cuktech.db.room.ChargeSessionEntity
import com.example.cuktech.db.room.ChargerNodeEntity
import com.example.cuktech.db.room.ChargingProfileEntity
import com.example.cuktech.db.room.CommandAuditEntity
import com.example.cuktech.db.room.CuktechDatabase
import com.example.cuktech.db.room.DailyEnergyAggregateEntity
import com.example.cuktech.db.room.DeadlineTaskEntity
import com.example.cuktech.db.room.DeviceAliasEntity
import com.example.cuktech.db.room.DeviceInventoryEntity
import com.example.cuktech.db.room.IncidentBlackBoxEntity
import com.example.cuktech.db.room.LegacySqliteMigrator
import com.example.cuktech.db.room.PortChargeLimitEntity
import com.example.cuktech.db.room.PortDevicePresetEntity
import com.example.cuktech.db.room.ScenarioObservationEntity
import com.example.cuktech.db.room.SessionAnnotationEntity
import com.example.cuktech.db.room.SessionGapEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.security.BackupPayload
import com.example.cuktech.security.CuktechDeviceCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

data class PowerChartPoint(
    val timestampMs: Long,
    val totalWatts: Double,
    val portWatts: Map<Int, Double>,
    val portVolts: Map<Int, Double> = emptyMap(),
    val portAmps: Map<Int, Double> = emptyMap(),
    val portWh: Map<Int, Double> = emptyMap(),
    val portProtocols: Map<Int, String> = emptyMap()
)

data class ArchiveSummary(
    val archivedSessions: Int,
    val prunedTelemetrySamples: Int
)

/**
 * Room-backed persistence for CUKTECH 10 Ultra telemetry history, charging sessions,
 * daily energy aggregates (after retention rollover), historical tariff cost snapshots,
 * per-port Wh charge limits, device aliases, charging profiles, CSV export, and SAF backups.
 */
class CuktechPersistence @JvmOverloads constructor(
    private val context: Context,
    private val db: CuktechDatabase = CuktechDatabase.getInstance(context),
    runLegacyMigration: Boolean = true
) {

    private val telemetryDao = db.telemetrySampleDao()
    private val sessionDao = db.chargeSessionDao()
    private val aggregateDao = db.dailyEnergyAggregateDao()
    private val limitDao = db.portChargeLimitDao()
    private val aliasDao = db.deviceAliasDao()
    private val profileDao = db.chargingProfileDao()
    private val presetDao = db.portDevicePresetDao()
    private val ruleDao = db.automationRuleDao()
    private val gapDao = db.sessionGapDao()
    private val auditDao = db.commandAuditDao()
    private val linkEventDao = db.bleLinkEventDao()
    private val chargerNodeDao = db.chargerNodeDao()
    private val scenarioObsDao = db.scenarioObservationDao()
    private val annotationDao = db.sessionAnnotationDao()
    private val blackBoxDao = db.incidentBlackBoxDao()
    private val deviceInvDao = db.deviceInventoryDao()
    private val cableInvDao = db.cableInventoryDao()
    private val cableLabDao = db.cableLabTestDao()
    private val deadlineTaskDao = db.deadlineTaskDao()

    init {
        if (runLegacyMigration) {
            LegacySqliteMigrator.migrateIfNeeded(context, db)
        }
        seedBuiltInProfilesIfEmpty()
    }

    private fun seedBuiltInProfilesIfEmpty() {
        runCatching {
            if (profileDao.getAll().isEmpty()) {
                profileDao.upsertAll(
                    listOf(
                        ChargingProfileEntity(
                            name = "Workstation Hybrid (100W C1)",
                            sceneMode = 2,
                            portSwitchBitmap = 0x0F,
                            screenTimeout = 1,
                            usbALowCurrent = false,
                            idleScreenOff = false,
                            orientationLock = false,
                            isBuiltIn = true
                        ),
                        ChargingProfileEntity(
                            name = "Single Port Burst (120W Max)",
                            sceneMode = 3,
                            portSwitchBitmap = 0x01,
                            screenTimeout = 2,
                            usbALowCurrent = false,
                            idleScreenOff = false,
                            orientationLock = false,
                            isBuiltIn = true
                        ),
                        ChargingProfileEntity(
                            name = "Night Eco (AI + Screen Off)",
                            sceneMode = 1,
                            portSwitchBitmap = 0x0F,
                            screenTimeout = 5,
                            usbALowCurrent = true,
                            idleScreenOff = true,
                            orientationLock = false,
                            isBuiltIn = true
                        )
                    )
                )
            }
        }
    }

    fun observeRecentSessions(limit: Int = 300): Flow<List<ChargeSessionRecord>> =
        sessionDao.observeRecentCompleted(limit).map { list -> list.map { it.toRecord() } }

    fun observeDailyAggregates(): Flow<List<DailyEnergyAggregateEntity>> = aggregateDao.observeAll()

    fun observeAliases(): Flow<List<DeviceAliasEntity>> = aliasDao.observeAll()

    fun observeProfiles(): Flow<List<ChargingProfileEntity>> = profileDao.observeAll()

    fun observePortDevicePresets(): Flow<List<PortDevicePresetEntity>> = presetDao.observeAll()

    fun observeAutomationRules(): Flow<List<AutomationRuleEntity>> = ruleDao.observeAll()

    fun observeSessionGaps(limit: Int = 200): Flow<List<SessionGapEntity>> = gapDao.observeRecentGaps(limit)

    fun observeCommandAudit(limit: Int = 200): Flow<List<CommandAuditEntity>> = auditDao.observeRecent(limit)

    fun observeBleLinkEvents(limit: Int = 300): Flow<List<BleLinkEventEntity>> = linkEventDao.observeRecent(limit)

    fun observeChargerNodes(): Flow<List<ChargerNodeEntity>> = chargerNodeDao.observeAll()

    fun observeScenarioObservations(limit: Int = 2000): Flow<List<ScenarioObservationEntity>> =
        scenarioObsDao.observeRecent(limit)

    fun observeAllSessionAnnotations(limit: Int = 500): Flow<List<SessionAnnotationEntity>> =
        annotationDao.observeAll(limit)

    fun observeIncidentBlackBoxes(limit: Int = 50): Flow<List<IncidentBlackBoxEntity>> =
        blackBoxDao.observeRecent(limit)

    fun observeDeviceInventory(): Flow<List<DeviceInventoryEntity>> = deviceInvDao.observeAll()

    fun observeCableInventory(): Flow<List<CableInventoryEntity>> = cableInvDao.observeAll()

    fun observeCableLabTests(limit: Int = 50): Flow<List<CableLabTestEntity>> =
        cableLabDao.observeRecent(limit)

    fun observeDeadlineTasks(): Flow<List<DeadlineTaskEntity>> = deadlineTaskDao.observeAll()

    fun recordPortSnapshot(
        ports: Collection<PortTelemetry>,
        timestampMs: Long = System.currentTimeMillis(),
        deviceMac: String = "",
        retentionDays: Int = 30,
        sourceMode: String = "BLE_DIRECT",
        rssiDbm: Int = 0
    ) {
        if (ports.isEmpty()) return
        runCatching {
            val entities = ports.map { p ->
                TelemetrySampleEntity(
                    timestampMs = timestampMs,
                    portId = p.portId,
                    voltageV = p.voltageV,
                    currentA = p.currentA,
                    powerW = p.powerW,
                    protocolName = p.protocolName,
                    cumulativeWh = p.cumulativeWh,
                    deviceMac = deviceMac,
                    sourceMode = sourceMode,
                    rssiDbm = rssiDbm
                )
            }
            val pruneCutoffMs = timestampMs - retentionDays.coerceIn(1, 3650).toLong() * 86_400_000L
            db.runInTransaction {
                telemetryDao.insertAll(entities)
                telemetryDao.deleteOlderThan(pruneCutoffMs)
                gapDao.deleteOlderThan(pruneCutoffMs)
                linkEventDao.deleteOlderThan(pruneCutoffMs)
                scenarioObsDao.deleteOlderThan(pruneCutoffMs)
            }
        }
    }

    /**
     * Merges telemetry snapshots pulled from a stationary bridge (`BRIDGE_SYNC`) while
     * strictly deduplicating against samples already recorded by direct phone BLE within 1.5s.
     */
    fun mergeBridgeBackfillSamples(
        deviceMac: String,
        samples: List<TelemetrySampleEntity>,
        retentionDays: Int = 30
    ): Int {
        if (samples.isEmpty()) return 0
        val normalizedMac = deviceMac.uppercase().trim()
        val sorted = samples.sortedBy { it.timestampMs }
        val minTs = sorted.first().timestampMs - 2000L
        val maxTs = sorted.last().timestampMs + 2000L
        var insertedCount = 0
        runCatching {
            db.runInTransaction {
                val existing = telemetryDao.getSamplesInRange(minTs, maxTs)
                    .filter { normalizedMac.isBlank() || it.deviceMac.isBlank() || it.deviceMac.equals(normalizedMac, ignoreCase = true) }
                val existingByPort = existing.groupBy { it.portId }
                val toInsert = mutableListOf<TelemetrySampleEntity>()
                for (s in sorted) {
                    val samePort = existingByPort[s.portId].orEmpty()
                    val duplicate = samePort.any { kotlin.math.abs(it.timestampMs - s.timestampMs) <= 1500L }
                    if (!duplicate) {
                        toInsert += s.copy(
                            id = 0L,
                            deviceMac = normalizedMac.ifBlank { s.deviceMac },
                            sourceMode = "BRIDGE_SYNC"
                        )
                    }
                }
                if (toInsert.isNotEmpty()) {
                    telemetryDao.insertAll(toInsert)
                    insertedCount = toInsert.size
                    gapDao.markRangeBackfilled(
                        deviceMac = normalizedMac,
                        startMs = sorted.first().timestampMs,
                        endMs = sorted.last().timestampMs,
                        addedCount = insertedCount
                    )
                }
                val pruneCutoffMs = System.currentTimeMillis() - retentionDays.coerceIn(1, 3650).toLong() * 86_400_000L
                telemetryDao.deleteOlderThan(pruneCutoffMs)
            }
        }
        return insertedCount
    }

    fun getTelemetrySamplesSince(minTsMs: Long, limit: Int = 10000): List<TelemetrySampleEntity> {
        return runCatching { telemetryDao.getSamplesSince(minTsMs, limit) }.getOrDefault(emptyList())
    }

    fun getSessionGapsSince(minTsMs: Long, limit: Int = 500): List<SessionGapEntity> {
        return runCatching { gapDao.getGapsSince(minTsMs, limit) }.getOrDefault(emptyList())
    }

    fun getLatestTelemetryTimestampMs(deviceMac: String = ""): Long? {
        return runCatching { telemetryDao.getLatestTimestampMs(deviceMac) }.getOrNull()
    }

    fun recordSessionGap(gap: SessionGapEntity): Long {
        return runCatching { gapDao.insertOrReplace(gap) }.getOrDefault(-1L)
    }

    fun recordCommandAudit(entry: CommandAuditEntity): Long {
        return runCatching {
            val id = auditDao.insert(entry)
            auditDao.pruneExcess(1000)
            id
        }.getOrDefault(-1L)
    }

    fun updateCommandAuditReconciled(id: Long, confirmed: Boolean) {
        if (id <= 0L) return
        runCatching { auditDao.updateReconciled(id, confirmed) }
    }

    fun getRecentCommandAudit(limit: Int = 500): List<CommandAuditEntity> {
        return runCatching { auditDao.getRecent(limit) }.getOrDefault(emptyList())
    }

    fun recordBleLinkEvent(event: BleLinkEventEntity): Long {
        return runCatching { linkEventDao.insert(event) }.getOrDefault(-1L)
    }

    fun getBleLinkEventsSince(minTsMs: Long, limit: Int = 2000): List<BleLinkEventEntity> {
        return runCatching { linkEventDao.getEventsSince(minTsMs, limit) }.getOrDefault(emptyList())
    }

    fun upsertChargerNode(node: ChargerNodeEntity) {
        if (node.deviceMac.isBlank()) return
        runCatching { chargerNodeDao.upsert(node) }
    }

    fun getChargerNodes(): List<ChargerNodeEntity> {
        return runCatching { chargerNodeDao.getAll() }.getOrDefault(emptyList())
    }

    fun findChargerNode(mac: String): ChargerNodeEntity? {
        return runCatching { chargerNodeDao.findByMac(mac) }.getOrNull()
    }

    fun recordScenarioObservation(obs: ScenarioObservationEntity): Long {
        return runCatching { scenarioObsDao.insert(obs) }.getOrDefault(-1L)
    }

    fun getScenarioObservationsSince(minTsMs: Long, limit: Int = 10000): List<ScenarioObservationEntity> {
        return runCatching { scenarioObsDao.getSince(minTsMs, limit) }.getOrDefault(emptyList())
    }

    fun saveSessionAnnotation(annotation: SessionAnnotationEntity): Long {
        return runCatching { annotationDao.upsert(annotation) }.getOrDefault(-1L)
    }

    fun getSessionAnnotationsForSession(sessionId: Long): List<SessionAnnotationEntity> {
        return runCatching { annotationDao.getForSession(sessionId) }.getOrDefault(emptyList())
    }

    fun deleteSessionAnnotation(id: Long) {
        runCatching { annotationDao.deleteById(id) }
    }

    fun recordIncidentBlackBox(incident: IncidentBlackBoxEntity): Long {
        return runCatching {
            val id = blackBoxDao.insert(incident)
            blackBoxDao.pruneExcess(100)
            id
        }.getOrDefault(-1L)
    }

    fun getIncidentBlackBoxes(limit: Int = 50): List<IncidentBlackBoxEntity> {
        return runCatching { blackBoxDao.getRecent(limit) }.getOrDefault(emptyList())
    }

    // --- Stage 5: Device & Cable Inventory + Cable Lab Tests ---

    fun saveDeviceInventory(device: DeviceInventoryEntity): Long =
        runCatching { deviceInvDao.upsert(device) }.getOrDefault(-1L)

    fun getDeviceInventory(): List<DeviceInventoryEntity> =
        runCatching { deviceInvDao.getAll() }.getOrDefault(emptyList())

    fun deleteDeviceInventory(id: Long) {
        runCatching { deviceInvDao.deleteById(id) }
    }

    fun saveCableInventory(cable: CableInventoryEntity): Long =
        runCatching { cableInvDao.upsert(cable) }.getOrDefault(-1L)

    fun getCableInventory(): List<CableInventoryEntity> =
        runCatching { cableInvDao.getAll() }.getOrDefault(emptyList())

    fun deleteCableInventory(id: Long) {
        runCatching { cableInvDao.deleteById(id) }
    }

    fun recordCableLabTest(test: CableLabTestEntity): Long {
        return runCatching {
            var insertedId = -1L
            db.runInTransaction {
                insertedId = cableLabDao.insert(test)
                if (test.cableId > 0L) {
                    val existing = cableInvDao.findById(test.cableId)
                    if (existing != null) {
                        cableInvDao.upsert(
                            existing.copy(
                                lastMeasuredMohm = test.estimatedMohm,
                                lastUncertaintyMohm = test.uncertaintyMohm,
                                lastSagMv = test.sagMv,
                                testCount = existing.testCount + 1
                            )
                        )
                    }
                }
            }
            insertedId
        }.getOrDefault(-1L)
    }

    fun getCableLabTests(limit: Int = 50): List<CableLabTestEntity> =
        runCatching { cableLabDao.getRecent(limit) }.getOrDefault(emptyList())

    fun deleteCableLabTest(id: Long) {
        runCatching { cableLabDao.deleteById(id) }
    }

    fun upsertActiveSession(record: ChargeSessionRecord): Long {
        return runCatching {
            val activeEntity = ChargeSessionEntity.fromRecord(record.copy(isActive = true))
            val existing = sessionDao.getActiveSessionForPort(record.portId)
            val target = if (existing != null) activeEntity.copy(id = existing.id) else activeEntity.copy(id = 0L)
            sessionDao.insertOrReplace(target)
        }.getOrDefault(-1L)
    }

    fun loadActiveSessions(): List<ChargeSessionRecord> {
        return runCatching {
            sessionDao.getActiveSessions().map { it.toRecord() }
        }.getOrDefault(emptyList())
    }

    fun clearActiveSessionForPort(portId: Int) {
        runCatching { sessionDao.deleteActiveSessionForPort(portId) }
    }

    fun insertCompletedSession(
        session: ChargeSessionRecord,
        tariffPerKwhOverride: Double? = null,
        currencyCodeOverride: String? = null
    ): Long {
        return runCatching {
            val rate = max(0.0, tariffPerKwhOverride ?: session.tariffPerKwhSnapshot)
            val currency = (currencyCodeOverride ?: session.currencyCode).ifBlank { "USD" }
            val cost = if (rate > 0.0 && session.whAccumulated > 0.0) {
                (session.whAccumulated / 1000.0) * rate
            } else {
                session.computedCost
            }
            val finalRecord = session.copy(
                id = 0L,
                endTimeSec = session.endTimeSec ?: session.lastSampleTimeSec,
                isActive = false,
                isPaused = false,
                pauseReason = null,
                tariffPerKwhSnapshot = rate,
                currencyCode = currency,
                computedCost = cost
            )
            var insertedId = -1L
            db.runInTransaction {
                sessionDao.deleteActiveSessionForPort(session.portId)
                insertedId = sessionDao.insertOrReplace(ChargeSessionEntity.fromRecord(finalRecord))
            }
            insertedId
        }.getOrDefault(-1L)
    }

    /**
     * Rolls completed sessions older than [retentionDays] (default 30 days) into
     * `daily_energy_aggregates` preserving Wh, active duration, gap duration, peak wattage,
     * and historical tariff cost totals before removing expired raw rows.
     */
    fun archiveOldSessions(
        retentionDays: Int = 30,
        nowMs: Long = System.currentTimeMillis()
    ): ArchiveSummary {
        var archivedCount = 0
        var prunedSamples = 0
        runCatching {
            val days = retentionDays.coerceIn(1, 3650)
            val cutoffSec = (nowMs / 1000.0) - (days.toDouble() * 86_400.0)
            val cutoffMs = nowMs - (days.toLong() * 86_400_000L)
            val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }

            db.runInTransaction {
                val expired = sessionDao.getSessionsReadyToArchive(cutoffSec)
                if (expired.isNotEmpty()) {
                    val grouped = expired.groupBy { entity ->
                        val dateIso = dayFormat.format(Date((entity.startTimeSec * 1000.0).toLong()))
                        AggregateGroupKey(
                            dateIso = dateIso,
                            portId = entity.portId,
                            deviceMac = entity.deviceMac,
                            currencyCode = entity.currencyCode.ifBlank { "USD" }
                        )
                    }
                    for ((key, items) in grouped) {
                        val existing = aggregateDao.find(key.dateIso, key.portId, key.deviceMac, key.currencyCode)
                        val sumWh = items.sumOf { it.whAccumulated }
                        val sumActive = items.sumOf { it.activeDurationSec }
                        val sumGap = items.sumOf { it.gapDurationSec }
                        val sumCost = items.sumOf { it.computedCost }
                        val maxPeak = items.maxOfOrNull { it.peakWatts } ?: 0.0
                        val merged = if (existing != null) {
                            existing.copy(
                                totalWh = existing.totalWh + sumWh,
                                totalActiveSec = existing.totalActiveSec + sumActive,
                                totalGapSec = existing.totalGapSec + sumGap,
                                sessionCount = existing.sessionCount + items.size,
                                maxPeakWatts = max(existing.maxPeakWatts, maxPeak),
                                totalCost = existing.totalCost + sumCost,
                                archivedSessionsCount = existing.archivedSessionsCount + items.size
                            )
                        } else {
                            DailyEnergyAggregateEntity(
                                dateIso = key.dateIso,
                                portId = key.portId,
                                deviceMac = key.deviceMac,
                                currencyCode = key.currencyCode,
                                totalWh = sumWh,
                                totalActiveSec = sumActive,
                                totalGapSec = sumGap,
                                sessionCount = items.size,
                                maxPeakWatts = maxPeak,
                                totalCost = sumCost,
                                archivedSessionsCount = items.size
                            )
                        }
                        aggregateDao.upsert(merged)
                    }
                    sessionDao.deleteByIds(expired.map { it.id })
                    archivedCount = expired.size
                }
                prunedSamples = telemetryDao.deleteOlderThan(cutoffMs)
            }
        }
        return ArchiveSummary(archivedSessions = archivedCount, prunedTelemetrySamples = prunedSamples)
    }

    fun getDailyAggregates(): List<DailyEnergyAggregateEntity> =
        runCatching { aggregateDao.getAll() }.getOrDefault(emptyList())

    fun pruneOldSessions(maxRetained: Int = 1000) {
        runCatching { sessionDao.pruneExcessCompleted(maxRetained) }
    }

    fun getRecentSessions(limit: Int = 300): List<ChargeSessionRecord> {
        return runCatching {
            sessionDao.getRecentCompleted(limit).map { it.toRecord() }
        }.getOrDefault(emptyList())
    }

    enum class ClearHistoryOption(val label: String, val description: String) {
        KEEP_ACTIVE("Inactive Sessions Only", "Clear finished sessions; keep currently active charging session"),
        OLDER_24H("Older than 24 Hours", "Clear telemetry and sessions older than 24 hours"),
        OLDER_7D("Older than 7 Days", "Clear telemetry and sessions older than 7 days"),
        OLDER_30D("Older than 30 Days", "Clear telemetry and sessions older than 30 days"),
        CLEAR_ALL("Clear Everything", "Completely wipe all saved charging sessions, aggregates, and telemetry points")
    }

    fun clearSessions() {
        clearHistory(ClearHistoryOption.CLEAR_ALL)
    }

    fun clearHistory(option: ClearHistoryOption) {
        runCatching {
            val nowMs = System.currentTimeMillis()
            val nowSec = nowMs / 1000.0
            db.runInTransaction {
                when (option) {
                    ClearHistoryOption.KEEP_ACTIVE -> {
                        sessionDao.deleteAllCompleted()
                        telemetryDao.deleteOlderThan(nowMs - 300_000L)
                    }
                    ClearHistoryOption.OLDER_24H -> {
                        val cutoffSec = nowSec - 24 * 3600.0
                        val cutoffMs = nowMs - 24L * 3600L * 1000L
                        sessionDao.deleteCompletedOlderThan(cutoffSec)
                        telemetryDao.deleteOlderThan(cutoffMs)
                    }
                    ClearHistoryOption.OLDER_7D -> {
                        val cutoffSec = nowSec - 7 * 24 * 3600.0
                        val cutoffMs = nowMs - 7L * 24L * 3600L * 1000L
                        sessionDao.deleteCompletedOlderThan(cutoffSec)
                        telemetryDao.deleteOlderThan(cutoffMs)
                    }
                    ClearHistoryOption.OLDER_30D -> {
                        val cutoffSec = nowSec - 30 * 24 * 3600.0
                        val cutoffMs = nowMs - 30L * 24L * 3600L * 1000L
                        sessionDao.deleteCompletedOlderThan(cutoffSec)
                        telemetryDao.deleteOlderThan(cutoffMs)
                    }
                    ClearHistoryOption.CLEAR_ALL -> {
                        sessionDao.deleteAll()
                        telemetryDao.deleteAll()
                        aggregateDao.deleteAll()
                    }
                }
            }
        }
    }

    // --- Port Wh Charge Limits ---

    fun savePortChargeLimit(limit: PortChargeLimit) {
        runCatching { limitDao.upsert(PortChargeLimitEntity.fromModel(limit)) }
    }

    fun removePortChargeLimit(portId: Int) {
        runCatching { limitDao.deleteByPortId(portId) }
    }

    fun loadPortChargeLimits(): Map<Int, PortChargeLimit> {
        return runCatching {
            limitDao.getAll().associate { it.portId to it.toModel() }
        }.getOrDefault(emptyMap())
    }

    // --- Device & Port Aliases ---

    fun saveAlias(deviceMac: String, portId: Int, aliasName: String, iconKey: String = "usb") {
        runCatching {
            val trimmed = aliasName.trim()
            if (trimmed.isEmpty()) {
                aliasDao.delete(deviceMac.uppercase().trim(), portId)
            } else {
                aliasDao.upsert(
                    DeviceAliasEntity(
                        deviceMac = deviceMac.uppercase().trim(),
                        portId = portId,
                        aliasName = trimmed,
                        iconKey = iconKey,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun getAliases(): List<DeviceAliasEntity> = runCatching { aliasDao.getAll() }.getOrDefault(emptyList())

    // --- Charging Profiles ---

    fun saveProfile(profile: ChargingProfileEntity): Long =
        runCatching { profileDao.upsert(profile) }.getOrDefault(-1L)

    fun deleteCustomProfile(profileId: Long) {
        runCatching { profileDao.deleteCustomProfile(profileId) }
    }

    fun getProfiles(): List<ChargingProfileEntity> = runCatching { profileDao.getAll() }.getOrDefault(emptyList())

    // --- Port Device Presets (Charging ETA) ---

    fun savePortDevicePreset(preset: PortDevicePresetEntity) {
        runCatching { presetDao.upsert(preset) }
    }

    fun deletePortDevicePreset(portId: Int) {
        runCatching { presetDao.deleteByPortId(portId) }
    }

    fun getPortDevicePresets(): List<PortDevicePresetEntity> =
        runCatching { presetDao.getAll() }.getOrDefault(emptyList())

    // --- Local Automation Rules ---

    fun saveAutomationRule(rule: AutomationRuleEntity): Long =
        runCatching { ruleDao.upsert(rule) }.getOrDefault(-1L)

    fun setAutomationRuleEnabled(id: Long, enabled: Boolean) {
        runCatching { ruleDao.setEnabled(id, enabled) }
    }

    fun markAutomationRuleTriggered(id: Long, triggeredMs: Long) {
        runCatching { ruleDao.updateLastTriggered(id, triggeredMs) }
    }

    fun deleteAutomationRule(id: Long) {
        runCatching { ruleDao.deleteById(id) }
    }

    fun getAutomationRules(): List<AutomationRuleEntity> =
        runCatching { ruleDao.getAll() }.getOrDefault(emptyList())

    // --- Stage 6: Deadline Charging Queue ---

    fun saveDeadlineTask(task: DeadlineTaskEntity): Long =
        runCatching { deadlineTaskDao.upsert(task) }.getOrDefault(-1L)

    fun setDeadlineTaskEnabled(id: Long, enabled: Boolean) {
        runCatching { deadlineTaskDao.setEnabled(id, enabled) }
    }

    fun deleteDeadlineTask(id: Long) {
        runCatching { deadlineTaskDao.deleteById(id) }
    }

    fun getDeadlineTasks(): List<DeadlineTaskEntity> =
        runCatching { deadlineTaskDao.getAll() }.getOrDefault(emptyList())

    // --- CSV Export (SAF stream + MediaStore API 29+) ---

    enum class ExportTimeframe(val label: String) {
        CURRENT_SESSION("Current Active Session"),
        LAST_1_HOUR("Last 1 Hour"),
        LAST_24_HOURS("Last 24 Hours"),
        LAST_7_DAYS("Last 7 Days"),
        ALL_TIME("All Time History")
    }

    enum class ExportScope(val label: String) {
        ALL("Full (Sessions + Telemetry)"),
        SESSIONS_ONLY("Sessions Only"),
        TELEMETRY_ONLY("Raw Telemetry Only")
    }

    fun buildCsvContent(
        timeframe: ExportTimeframe = ExportTimeframe.ALL_TIME,
        scope: ExportScope = ExportScope.ALL,
        currentSessionStartMs: Long = 0L
    ): String {
        val now = System.currentTimeMillis()
        val minTsMs = when (timeframe) {
            ExportTimeframe.CURRENT_SESSION -> if (currentSessionStartMs > 0) currentSessionStartMs else (now - 3600_000L)
            ExportTimeframe.LAST_1_HOUR -> now - 3600_000L
            ExportTimeframe.LAST_24_HOURS -> now - 86_400_000L
            ExportTimeframe.LAST_7_DAYS -> now - (7L * 86_400_000L)
            ExportTimeframe.ALL_TIME -> 0L
        }
        val minStartSec = minTsMs / 1000.0
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val sb = StringBuilder()
        sb.appendLine("# CUKTECH 10 Ultra Telemetry Export")
        sb.appendLine("# Filter: ${timeframe.label} | Scope: ${scope.label} | Exported: ${fmt.format(Date(now))}")

        if (scope == ExportScope.ALL || scope == ExportScope.SESSIONS_ONLY) {
            sb.appendLine("\n# Charging Sessions")
            sb.appendLine("session_id,port,start_time,end_time,active_duration_sec,gap_duration_sec,fast_boost_sec,taper_sec,energy_wh,peak_watts,avg_watts,protocol,end_reason,cost,currency")
            val sessions = runCatching { sessionDao.getCompletedSince(minStartSec, 2000) }.getOrDefault(emptyList())
            for (s in sessions) {
                val startStr = fmt.format(Date((s.startTimeSec * 1000).toLong()))
                val endStr = s.endTimeSec?.let { fmt.format(Date((it * 1000).toLong())) } ?: "active"
                val activeDur = if (s.activeDurationSec > 0L) s.activeDurationSec else maxOf(0L, ((s.endTimeSec ?: (now / 1000.0)) - s.startTimeSec).toLong())
                sb.appendLine(
                    String.format(
                        Locale.US,
                        "%d,%s,%s,%s,%d,%d,%d,%d,%.3f,%.2f,%.2f,%s,%s,%.4f,%s",
                        s.id,
                        csvSafe(s.portLabel),
                        startStr,
                        endStr,
                        activeDur,
                        s.gapDurationSec,
                        s.fastBoostDurationSec,
                        s.taperDurationSec,
                        s.whAccumulated,
                        s.peakWatts,
                        s.avgWatts,
                        csvSafe(s.protocolUsed),
                        csvSafe(s.endReason ?: ""),
                        s.computedCost,
                        csvSafe(s.currencyCode)
                    )
                )
            }
        }

        if (scope == ExportScope.ALL || scope == ExportScope.TELEMETRY_ONLY) {
            sb.appendLine("\n# Raw Port Telemetry History")
            sb.appendLine("timestamp,port_id,voltage_v,current_a,power_w,protocol,cumulative_wh")
            val samples = getTelemetrySamplesSince(minTsMs, 10000)
            for (sample in samples) {
                val tStr = fmt.format(Date(sample.timestampMs))
                sb.appendLine(
                    String.format(
                        Locale.US,
                        "%s,%d,%.2f,%.2f,%.2f,%s,%.3f",
                        tStr,
                        sample.portId,
                        sample.voltageV,
                        sample.currentA,
                        sample.powerW,
                        csvSafe(sample.protocolName),
                        sample.cumulativeWh
                    )
                )
            }
        }
        return sb.toString()
    }

    fun exportCsvToStream(
        outputStream: OutputStream,
        timeframe: ExportTimeframe = ExportTimeframe.ALL_TIME,
        scope: ExportScope = ExportScope.ALL,
        currentSessionStartMs: Long = 0L
    ) {
        val content = buildCsvContent(timeframe, scope, currentSessionStartMs)
        outputStream.write(content.toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    /**
     * Scoped-storage compliant CSV export:
     * - Android 10+ (API 29+): writes directly via `MediaStore.Downloads` without legacy storage permissions.
     * - Android 8–9 (API 26–28): writes to app-private `getExternalFilesDir("cuktech10/history")` fallback.
     */
    fun exportSessionsAndHistoryCsv(
        timeframe: ExportTimeframe = ExportTimeframe.ALL_TIME,
        scope: ExportScope = ExportScope.ALL,
        currentSessionStartMs: Long = 0L
    ): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = when (timeframe) {
            ExportTimeframe.CURRENT_SESSION -> "session"
            ExportTimeframe.LAST_1_HOUR -> "1h"
            ExportTimeframe.LAST_24_HOURS -> "24h"
            ExportTimeframe.LAST_7_DAYS -> "7d"
            ExportTimeframe.ALL_TIME -> "all"
        }
        val fileName = "cuktech10_${suffix}_$stamp.csv"
        val content = buildCsvContent(timeframe, scope, currentSessionStartMs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val savedViaMediaStore = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/cuktech10/history")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore URI null")
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                true
            }.getOrDefault(false)

            val appDir = context.getExternalFilesDir("cuktech10/history") ?: context.cacheDir
            if (!appDir.exists()) appDir.mkdirs()
            val returnFile = File(appDir, fileName)
            if (!savedViaMediaStore) {
                returnFile.writeText(content, Charsets.UTF_8)
            }
            return returnFile
        } else {
            val appDir = context.getExternalFilesDir("cuktech10/history") ?: context.cacheDir
            if (!appDir.exists()) appDir.mkdirs()
            val outFile = File(appDir, fileName)
            outFile.writeText(content, Charsets.UTF_8)
            return outFile
        }
    }

    /**
     * Imports previously exported CUKTECH session & telemetry CSV files into Room,
     * deduplicating sessions by `(portId, round(startTimeSec))` and samples by 1.5s timestamps.
     */
    fun importSessionsFromCsv(csvText: String, fallbackMac: String = ""): CsvImportResult {
        val bundle = CsvHistoryImporter.parseCsv(csvText, fallbackMac)
        if (bundle.sessions.isEmpty() && bundle.samples.isEmpty()) {
            return CsvImportResult(invalidRows = bundle.invalidRows)
        }
        var importedSessionsCount = 0
        var skippedDuplicateCount = 0
        var importedSamplesCount = 0

        db.runInTransaction {
            if (bundle.sessions.isNotEmpty()) {
                val existingKeys = sessionDao.getRecentCompleted(5000)
                    .mapTo(HashSet()) { CsvHistoryImporter.sessionDedupeKey(it.portId, it.startTimeSec) }
                val toInsert = mutableListOf<ChargeSessionEntity>()
                for (sess in bundle.sessions) {
                    val key = CsvHistoryImporter.sessionDedupeKey(sess.portId, sess.startTimeSec)
                    if (existingKeys.add(key)) {
                        toInsert += ChargeSessionEntity.fromRecord(sess)
                    } else {
                        skippedDuplicateCount++
                    }
                }
                if (toInsert.isNotEmpty()) {
                    sessionDao.insertAll(toInsert)
                    importedSessionsCount = toInsert.size
                }
            }

            if (bundle.samples.isNotEmpty()) {
                val sortedSamples = bundle.samples.sortedBy { it.timestampMs }
                val minTs = sortedSamples.first().timestampMs - 2000L
                val maxTs = sortedSamples.last().timestampMs + 2000L
                val existingByPort = telemetryDao.getSamplesInRange(minTs, maxTs).groupBy { it.portId }
                val toInsertSamples = mutableListOf<TelemetrySampleEntity>()
                for (sample in sortedSamples) {
                    val samePort = existingByPort[sample.portId].orEmpty()
                    val isDup = samePort.any { kotlin.math.abs(it.timestampMs - sample.timestampMs) <= 1500L }
                    if (!isDup) {
                        toInsertSamples += sample
                    }
                }
                if (toInsertSamples.isNotEmpty()) {
                    telemetryDao.insertAll(toInsertSamples)
                    importedSamplesCount = toInsertSamples.size
                }
            }
        }

        return CsvImportResult(
            importedSessions = importedSessionsCount,
            skippedDuplicateSessions = skippedDuplicateCount,
            importedSamples = importedSamplesCount,
            invalidRows = bundle.invalidRows
        )
    }

    // --- Backup Payload Export & Restore ---

    fun buildBackupPayload(
        preferencesJson: JSONObject,
        includeSecrets: Boolean,
        devices: List<CuktechDeviceCredentials> = emptyList()
    ): BackupPayload {
        val sessionsArr = JSONArray()
        for (s in sessionDao.getRecentCompleted(2000)) {
            sessionsArr.put(JSONObject().apply {
                put("portId", s.portId)
                put("portLabel", s.portLabel)
                put("deviceMac", s.deviceMac)
                put("startTimeSec", s.startTimeSec)
                if (s.endTimeSec != null) put("endTimeSec", s.endTimeSec)
                put("lastSampleTimeSec", s.lastSampleTimeSec)
                put("activeDurationSec", s.activeDurationSec)
                put("gapDurationSec", s.gapDurationSec)
                put("fastBoostDurationSec", s.fastBoostDurationSec)
                put("taperDurationSec", s.taperDurationSec)
                put("whAccumulated", s.whAccumulated)
                put("peakWatts", s.peakWatts)
                put("avgWatts", s.avgWatts)
                put("protocolUsed", s.protocolUsed)
                put("endReason", s.endReason ?: "")
                put("tariffPerKwhSnapshot", s.tariffPerKwhSnapshot)
                put("currencyCode", s.currencyCode)
                put("computedCost", s.computedCost)
            })
        }

        val aggregatesArr = JSONArray()
        for (a in aggregateDao.getAll()) {
            aggregatesArr.put(JSONObject().apply {
                put("dateIso", a.dateIso)
                put("portId", a.portId)
                put("deviceMac", a.deviceMac)
                put("currencyCode", a.currencyCode)
                put("totalWh", a.totalWh)
                put("totalActiveSec", a.totalActiveSec)
                put("totalGapSec", a.totalGapSec)
                put("sessionCount", a.sessionCount)
                put("maxPeakWatts", a.maxPeakWatts)
                put("totalCost", a.totalCost)
                put("archivedSessionsCount", a.archivedSessionsCount)
            })
        }

        val limitsArr = JSONArray()
        for (l in limitDao.getAll()) {
            limitsArr.put(JSONObject().apply {
                put("portId", l.portId)
                put("whTarget", l.whTarget)
                put("mode", l.mode)
                put("baselineWh", l.baselineWh)
                put("enabled", l.enabled)
                put("triggered", l.triggered)
            })
        }

        val aliasesArr = JSONArray()
        for (al in aliasDao.getAll()) {
            aliasesArr.put(JSONObject().apply {
                put("deviceMac", al.deviceMac)
                put("portId", al.portId)
                put("aliasName", al.aliasName)
                put("iconKey", al.iconKey)
                put("updatedAtMs", al.updatedAtMs)
            })
        }

        val profilesArr = JSONArray()
        for (p in profileDao.getAll().filter { !it.isBuiltIn }) {
            profilesArr.put(JSONObject().apply {
                put("name", p.name)
                put("sceneMode", p.sceneMode)
                put("portSwitchBitmap", p.portSwitchBitmap)
                put("screenTimeout", p.screenTimeout)
                put("usbALowCurrent", p.usbALowCurrent)
                put("idleScreenOff", p.idleScreenOff)
                put("orientationLock", p.orientationLock)
                put("whLimitPort1", p.whLimitPort1)
                put("whLimitPort2", p.whLimitPort2)
                put("whLimitPort3", p.whLimitPort3)
                put("whLimitPort4", p.whLimitPort4)
                put("createdAtMs", p.createdAtMs)
            })
        }

        val secretsArr = if (includeSecrets) {
            JSONArray().apply {
                for (d in devices) {
                    put(JSONObject().apply {
                        put("did", d.did)
                        put("name", d.name)
                        put("model", d.model)
                        put("mac", d.mac)
                        put("tokenHex", d.tokenHex)
                        put("bleKeyHex", d.bleKeyHex)
                        put("region", d.region)
                    })
                }
            }
        } else null

        val presetsArr = JSONArray()
        for (pr in presetDao.getAll()) {
            presetsArr.put(JSONObject().apply {
                put("portId", pr.portId)
                put("presetType", pr.presetType)
                put("deviceLabel", pr.deviceLabel)
                put("targetWh", pr.targetWh)
                put("initialSocPct", pr.initialSocPct)
                put("capacityMah", pr.capacityMah)
                put("cellSeriesCount", pr.cellSeriesCount)
                put("updatedAtMs", pr.updatedAtMs)
            })
        }

        val rulesArr = JSONArray()
        for (rl in ruleDao.getAll()) {
            rulesArr.put(JSONObject().apply {
                put("name", rl.name)
                put("enabled", rl.enabled)
                put("triggerType", rl.triggerType)
                put("portId", rl.portId)
                put("thresholdWatts", rl.thresholdWatts)
                put("holdSeconds", rl.holdSeconds)
                put("minuteOfDay", rl.minuteOfDay)
                put("daysMask", rl.daysMask)
                put("actionType", rl.actionType)
                put("actionSceneMode", rl.actionSceneMode)
                put("cooldownMinutes", rl.cooldownMinutes)
                put("createdAtMs", rl.createdAtMs)
                put("priority", rl.priority)
                put("targetPhoneSocPct", rl.targetPhoneSocPct)
                put("actionOrientationLock", rl.actionOrientationLock)
                put("actionIdleScreenOff", rl.actionIdleScreenOff)
                put("actionScreenTimeout", rl.actionScreenTimeout)
            })
        }

        return BackupPayload(
            createdAtMs = System.currentTimeMillis(),
            includesSecrets = includeSecrets,
            preferencesJson = preferencesJson,
            sessionsArray = sessionsArr,
            dailyAggregatesArray = aggregatesArr,
            chargeLimitsArray = limitsArr,
            aliasesArray = aliasesArr,
            profilesArray = profilesArr,
            devicesSecretsArray = secretsArr,
            portPresetsArray = presetsArr,
            automationRulesArray = rulesArr
        )
    }

    fun restoreBackupPayload(payload: BackupPayload, replaceExisting: Boolean = false) {
        db.runInTransaction {
            if (replaceExisting) {
                sessionDao.deleteAllCompleted()
                aggregateDao.deleteAll()
                limitDao.deleteAll()
                aliasDao.deleteAll()
                profileDao.deleteAllCustom()
                presetDao.deleteAll()
                ruleDao.deleteAll()
            }

            val sessionsToInsert = mutableListOf<ChargeSessionEntity>()
            for (i in 0 until payload.sessionsArray.length()) {
                val obj = payload.sessionsArray.optJSONObject(i) ?: continue
                sessionsToInsert.add(
                    ChargeSessionEntity(
                        portId = obj.optInt("portId", 1),
                        portLabel = obj.optString("portLabel", "USB-C1"),
                        deviceMac = obj.optString("deviceMac", ""),
                        startTimeSec = obj.optDouble("startTimeSec", 0.0),
                        endTimeSec = if (obj.has("endTimeSec")) obj.optDouble("endTimeSec") else null,
                        lastSampleTimeSec = obj.optDouble("lastSampleTimeSec", obj.optDouble("startTimeSec", 0.0)),
                        activeDurationSec = obj.optLong("activeDurationSec", 0L),
                        gapDurationSec = obj.optLong("gapDurationSec", 0L),
                        whAccumulated = obj.optDouble("whAccumulated", 0.0),
                        peakWatts = obj.optDouble("peakWatts", 0.0),
                        avgWatts = obj.optDouble("avgWatts", 0.0),
                        protocolUsed = obj.optString("protocolUsed", "Unknown"),
                        isActive = false,
                        endReason = obj.optString("endReason", "").ifBlank { null },
                        tariffPerKwhSnapshot = obj.optDouble("tariffPerKwhSnapshot", 0.0),
                        currencyCode = obj.optString("currencyCode", "USD"),
                        computedCost = obj.optDouble("computedCost", 0.0),
                        fastBoostDurationSec = obj.optLong("fastBoostDurationSec", 0L),
                        taperDurationSec = obj.optLong("taperDurationSec", 0L)
                    )
                )
            }
            if (sessionsToInsert.isNotEmpty()) {
                sessionDao.insertAll(sessionsToInsert)
            }

            for (i in 0 until payload.dailyAggregatesArray.length()) {
                val obj = payload.dailyAggregatesArray.optJSONObject(i) ?: continue
                aggregateDao.upsert(
                    DailyEnergyAggregateEntity(
                        dateIso = obj.optString("dateIso"),
                        portId = obj.optInt("portId", 1),
                        deviceMac = obj.optString("deviceMac", ""),
                        currencyCode = obj.optString("currencyCode", "USD"),
                        totalWh = obj.optDouble("totalWh", 0.0),
                        totalActiveSec = obj.optLong("totalActiveSec", 0L),
                        totalGapSec = obj.optLong("totalGapSec", 0L),
                        sessionCount = obj.optInt("sessionCount", 0),
                        maxPeakWatts = obj.optDouble("maxPeakWatts", 0.0),
                        totalCost = obj.optDouble("totalCost", 0.0),
                        archivedSessionsCount = obj.optInt("archivedSessionsCount", 0)
                    )
                )
            }

            val limits = mutableListOf<PortChargeLimitEntity>()
            for (i in 0 until payload.chargeLimitsArray.length()) {
                val obj = payload.chargeLimitsArray.optJSONObject(i) ?: continue
                limits.add(
                    PortChargeLimitEntity(
                        portId = obj.optInt("portId", 1),
                        whTarget = obj.optDouble("whTarget", 0.0),
                        mode = obj.optString("mode", "once"),
                        baselineWh = obj.optDouble("baselineWh", 0.0),
                        enabled = obj.optBoolean("enabled", true),
                        triggered = obj.optBoolean("triggered", false)
                    )
                )
            }
            if (limits.isNotEmpty()) limitDao.upsertAll(limits)

            val aliases = mutableListOf<DeviceAliasEntity>()
            for (i in 0 until payload.aliasesArray.length()) {
                val obj = payload.aliasesArray.optJSONObject(i) ?: continue
                aliases.add(
                    DeviceAliasEntity(
                        deviceMac = obj.optString("deviceMac", ""),
                        portId = obj.optInt("portId", 0),
                        aliasName = obj.optString("aliasName", ""),
                        iconKey = obj.optString("iconKey", "usb"),
                        updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                    )
                )
            }
            if (aliases.isNotEmpty()) aliasDao.upsertAll(aliases)

            val profiles = mutableListOf<ChargingProfileEntity>()
            for (i in 0 until payload.profilesArray.length()) {
                val obj = payload.profilesArray.optJSONObject(i) ?: continue
                profiles.add(
                    ChargingProfileEntity(
                        name = obj.optString("name", "Profile"),
                        sceneMode = obj.optInt("sceneMode", 1),
                        portSwitchBitmap = obj.optInt("portSwitchBitmap", 0x0F),
                        screenTimeout = obj.optInt("screenTimeout", 1),
                        usbALowCurrent = obj.optBoolean("usbALowCurrent", false),
                        idleScreenOff = obj.optBoolean("idleScreenOff", false),
                        orientationLock = obj.optBoolean("orientationLock", false),
                        whLimitPort1 = obj.optDouble("whLimitPort1", 0.0),
                        whLimitPort2 = obj.optDouble("whLimitPort2", 0.0),
                        whLimitPort3 = obj.optDouble("whLimitPort3", 0.0),
                        whLimitPort4 = obj.optDouble("whLimitPort4", 0.0),
                        isBuiltIn = false,
                        createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                    )
                )
            }
            if (profiles.isNotEmpty()) profileDao.upsertAll(profiles)

            val presets = mutableListOf<PortDevicePresetEntity>()
            for (i in 0 until payload.portPresetsArray.length()) {
                val obj = payload.portPresetsArray.optJSONObject(i) ?: continue
                val restoredWh = obj.optDouble("targetWh", 19.25)
                val restoredCells = obj.optInt("cellSeriesCount", 1).coerceIn(1, 3)
                val fallbackMah = ((restoredWh * 1000.0) / (restoredCells * 3.85)).toInt().coerceIn(100, 50_000)
                presets.add(
                    PortDevicePresetEntity(
                        portId = obj.optInt("portId", 1),
                        presetType = obj.optString("presetType", "CUSTOM"),
                        deviceLabel = obj.optString("deviceLabel", ""),
                        targetWh = restoredWh,
                        initialSocPct = obj.optInt("initialSocPct", 20),
                        capacityMah = obj.optInt("capacityMah", fallbackMah).coerceIn(100, 50_000),
                        cellSeriesCount = restoredCells,
                        updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                    )
                )
            }
            if (presets.isNotEmpty()) presetDao.upsertAll(presets)

            val rules = mutableListOf<AutomationRuleEntity>()
            for (i in 0 until payload.automationRulesArray.length()) {
                val obj = payload.automationRulesArray.optJSONObject(i) ?: continue
                rules.add(
                    AutomationRuleEntity(
                        name = obj.optString("name", "Rule"),
                        enabled = obj.optBoolean("enabled", true),
                        triggerType = obj.optString("triggerType", "LOW_POWER_CUTOFF"),
                        portId = obj.optInt("portId", 1),
                        thresholdWatts = obj.optDouble("thresholdWatts", 1.5),
                        holdSeconds = obj.optInt("holdSeconds", 300),
                        minuteOfDay = obj.optInt("minuteOfDay", 23 * 60),
                        daysMask = obj.optInt("daysMask", 0b1111111),
                        actionType = obj.optString("actionType", "PORT_OFF"),
                        actionSceneMode = obj.optInt("actionSceneMode", 1),
                        cooldownMinutes = obj.optInt("cooldownMinutes", 30),
                        createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                        priority = obj.optInt("priority", 10),
                        targetPhoneSocPct = obj.optInt("targetPhoneSocPct", 85),
                        actionOrientationLock = obj.optBoolean("actionOrientationLock", false),
                        actionIdleScreenOff = obj.optBoolean("actionIdleScreenOff", false),
                        actionScreenTimeout = obj.optInt("actionScreenTimeout", 1)
                    )
                )
            }
            if (rules.isNotEmpty()) ruleDao.upsertAll(rules)
        }
    }

    private fun csvSafe(value: String): String {
        val clean = value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ")
        return if (clean.contains(",") || clean.contains("\"")) "\"$clean\"" else clean
    }

    private data class AggregateGroupKey(
        val dateIso: String,
        val portId: Int,
        val deviceMac: String,
        val currencyCode: String
    )
}
