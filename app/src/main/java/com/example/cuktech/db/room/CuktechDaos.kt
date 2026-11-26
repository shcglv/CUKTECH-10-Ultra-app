package com.example.cuktech.db.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetrySampleDao {
    @Insert
    fun insertAll(samples: List<TelemetrySampleEntity>)

    @Query("SELECT * FROM telemetry_samples WHERE timestampMs >= :minTsMs ORDER BY timestampMs DESC LIMIT :limit")
    fun getSamplesSince(minTsMs: Long, limit: Int = 10000): List<TelemetrySampleEntity>

    @Query("SELECT * FROM telemetry_samples WHERE timestampMs BETWEEN :startMs AND :endMs ORDER BY timestampMs ASC")
    fun getSamplesInRange(startMs: Long, endMs: Long): List<TelemetrySampleEntity>

    @Query("SELECT MAX(timestampMs) FROM telemetry_samples WHERE (:deviceMac = '' OR deviceMac = :deviceMac)")
    fun getLatestTimestampMs(deviceMac: String = ""): Long?

    @Query("DELETE FROM telemetry_samples WHERE timestampMs < :cutoffMs")
    fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM telemetry_samples")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM telemetry_samples")
    fun count(): Long
}

@Dao
interface ChargeSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(session: ChargeSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(sessions: List<ChargeSessionEntity>)

    @Query("SELECT * FROM charge_sessions WHERE isActive = 0 ORDER BY startTimeSec DESC LIMIT :limit")
    fun getRecentCompleted(limit: Int = 300): List<ChargeSessionEntity>

    @Query("SELECT * FROM charge_sessions WHERE isActive = 0 ORDER BY startTimeSec DESC LIMIT :limit")
    fun observeRecentCompleted(limit: Int = 300): Flow<List<ChargeSessionEntity>>

    @Query("SELECT * FROM charge_sessions WHERE isActive = 1")
    fun getActiveSessions(): List<ChargeSessionEntity>

    @Query("SELECT * FROM charge_sessions WHERE isActive = 1 AND portId = :portId LIMIT 1")
    fun getActiveSessionForPort(portId: Int): ChargeSessionEntity?

    @Query("DELETE FROM charge_sessions WHERE isActive = 1 AND portId = :portId")
    fun deleteActiveSessionForPort(portId: Int)

    @Query("SELECT * FROM charge_sessions WHERE isActive = 0 AND startTimeSec >= :minStartSec ORDER BY startTimeSec DESC LIMIT :limit")
    fun getCompletedSince(minStartSec: Double, limit: Int = 1000): List<ChargeSessionEntity>

    @Query("SELECT * FROM charge_sessions WHERE isActive = 0 AND isArchived = 0 AND COALESCE(endTimeSec, lastSampleTimeSec) < :cutoffSec")
    fun getSessionsReadyToArchive(cutoffSec: Double): List<ChargeSessionEntity>

    @Query("DELETE FROM charge_sessions WHERE id IN (:ids)")
    fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM charge_sessions WHERE isActive = 0 AND COALESCE(endTimeSec, lastSampleTimeSec) < :cutoffSec")
    fun deleteCompletedOlderThan(cutoffSec: Double): Int

    @Query("DELETE FROM charge_sessions WHERE isActive = 0")
    fun deleteAllCompleted()

    @Query("DELETE FROM charge_sessions")
    fun deleteAll()

    @Query(
        """
        DELETE FROM charge_sessions
        WHERE isActive = 0 AND id NOT IN (
            SELECT id FROM charge_sessions WHERE isActive = 0 ORDER BY startTimeSec DESC LIMIT :maxRetained
        )
        """
    )
    fun pruneExcessCompleted(maxRetained: Int = 1000): Int
}

@Dao
interface DailyEnergyAggregateDao {
    @Query("SELECT * FROM daily_energy_aggregates WHERE dateIso = :dateIso AND portId = :portId AND deviceMac = :deviceMac AND currencyCode = :currencyCode LIMIT 1")
    fun find(dateIso: String, portId: Int, deviceMac: String, currencyCode: String): DailyEnergyAggregateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(aggregate: DailyEnergyAggregateEntity)

    @Query("SELECT * FROM daily_energy_aggregates ORDER BY dateIso DESC, portId ASC")
    fun getAll(): List<DailyEnergyAggregateEntity>

    @Query("SELECT * FROM daily_energy_aggregates ORDER BY dateIso DESC, portId ASC")
    fun observeAll(): Flow<List<DailyEnergyAggregateEntity>>

    @Query("DELETE FROM daily_energy_aggregates")
    fun deleteAll()
}

@Dao
interface PortChargeLimitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(limit: PortChargeLimitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(limits: List<PortChargeLimitEntity>)

    @Query("DELETE FROM port_charge_limits WHERE portId = :portId")
    fun deleteByPortId(portId: Int)

    @Query("DELETE FROM port_charge_limits")
    fun deleteAll()

    @Query("SELECT * FROM port_charge_limits")
    fun getAll(): List<PortChargeLimitEntity>
}

@Dao
interface DeviceAliasDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(alias: DeviceAliasEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(aliases: List<DeviceAliasEntity>)

    @Query("SELECT * FROM device_aliases ORDER BY deviceMac ASC, portId ASC")
    fun getAll(): List<DeviceAliasEntity>

    @Query("SELECT * FROM device_aliases ORDER BY deviceMac ASC, portId ASC")
    fun observeAll(): Flow<List<DeviceAliasEntity>>

    @Query("DELETE FROM device_aliases WHERE deviceMac = :deviceMac AND portId = :portId")
    fun delete(deviceMac: String, portId: Int)

    @Query("DELETE FROM device_aliases")
    fun deleteAll()
}

@Dao
interface ChargingProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(profile: ChargingProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(profiles: List<ChargingProfileEntity>)

    @Query("SELECT * FROM charging_profiles ORDER BY isBuiltIn DESC, id ASC")
    fun getAll(): List<ChargingProfileEntity>

    @Query("SELECT * FROM charging_profiles ORDER BY isBuiltIn DESC, id ASC")
    fun observeAll(): Flow<List<ChargingProfileEntity>>

    @Query("DELETE FROM charging_profiles WHERE id = :id AND isBuiltIn = 0")
    fun deleteCustomProfile(id: Long)

    @Query("DELETE FROM charging_profiles WHERE isBuiltIn = 0")
    fun deleteAllCustom()
}

@Dao
interface PortDevicePresetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(preset: PortDevicePresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(presets: List<PortDevicePresetEntity>)

    @Query("SELECT * FROM port_device_presets ORDER BY portId ASC")
    fun getAll(): List<PortDevicePresetEntity>

    @Query("SELECT * FROM port_device_presets ORDER BY portId ASC")
    fun observeAll(): Flow<List<PortDevicePresetEntity>>

    @Query("DELETE FROM port_device_presets WHERE portId = :portId")
    fun deleteByPortId(portId: Int)

    @Query("DELETE FROM port_device_presets")
    fun deleteAll()
}

@Dao
interface AutomationRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(rule: AutomationRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(rules: List<AutomationRuleEntity>)

    @Query("SELECT * FROM automation_rules ORDER BY createdAtMs DESC, id DESC")
    fun getAll(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules ORDER BY createdAtMs DESC, id DESC")
    fun observeAll(): Flow<List<AutomationRuleEntity>>

    @Query("UPDATE automation_rules SET enabled = :enabled WHERE id = :id")
    fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE automation_rules SET lastTriggeredMs = :triggeredMs WHERE id = :id")
    fun updateLastTriggered(id: Long, triggeredMs: Long)

    @Query("DELETE FROM automation_rules WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM automation_rules")
    fun deleteAll()
}

@Dao
interface SessionGapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(gap: SessionGapEntity): Long

    @Query("SELECT * FROM session_gaps WHERE gapEndMs >= :minTsMs ORDER BY gapStartMs DESC LIMIT :limit")
    fun getGapsSince(minTsMs: Long, limit: Int = 500): List<SessionGapEntity>

    @Query("SELECT * FROM session_gaps ORDER BY gapStartMs DESC LIMIT :limit")
    fun observeRecentGaps(limit: Int = 200): Flow<List<SessionGapEntity>>

    @Query("UPDATE session_gaps SET wasBackfilledByBridge = 1, backfilledSamplesCount = backfilledSamplesCount + :addedCount WHERE deviceMac = :deviceMac AND gapEndMs >= :startMs AND gapStartMs <= :endMs")
    fun markRangeBackfilled(deviceMac: String, startMs: Long, endMs: Long, addedCount: Int): Int

    @Query("DELETE FROM session_gaps WHERE gapEndMs < :cutoffMs")
    fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM session_gaps")
    fun deleteAll()
}

@Dao
interface CommandAuditDao {
    @Insert
    fun insert(entry: CommandAuditEntity): Long

    @Query("SELECT * FROM command_audit_log ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<CommandAuditEntity>>

    @Query("SELECT * FROM command_audit_log ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun getRecent(limit: Int = 500): List<CommandAuditEntity>

    @Query("UPDATE command_audit_log SET reconciledConfirmed = :confirmed WHERE id = :id")
    fun updateReconciled(id: Long, confirmed: Boolean)

    @Query(
        """
        DELETE FROM command_audit_log
        WHERE id NOT IN (
            SELECT id FROM command_audit_log ORDER BY timestampMs DESC, id DESC LIMIT :maxRetained
        )
        """
    )
    fun pruneExcess(maxRetained: Int = 1000): Int

    @Query("DELETE FROM command_audit_log")
    fun deleteAll()
}

@Dao
interface BleLinkEventDao {
    @Insert
    fun insert(event: BleLinkEventEntity): Long

    @Query("SELECT * FROM ble_link_events ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<BleLinkEventEntity>>

    @Query("SELECT * FROM ble_link_events WHERE timestampMs >= :minTsMs ORDER BY timestampMs DESC LIMIT :limit")
    fun getEventsSince(minTsMs: Long, limit: Int = 2000): List<BleLinkEventEntity>

    @Query("DELETE FROM ble_link_events WHERE timestampMs < :cutoffMs")
    fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM ble_link_events")
    fun deleteAll()
}

@Dao
interface ChargerNodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(node: ChargerNodeEntity)

    @Query("SELECT * FROM charger_nodes ORDER BY isOnline DESC, lastSeenMs DESC")
    fun observeAll(): Flow<List<ChargerNodeEntity>>

    @Query("SELECT * FROM charger_nodes ORDER BY isOnline DESC, lastSeenMs DESC")
    fun getAll(): List<ChargerNodeEntity>

    @Query("SELECT * FROM charger_nodes WHERE deviceMac = :mac LIMIT 1")
    fun findByMac(mac: String): ChargerNodeEntity?

    @Query("DELETE FROM charger_nodes WHERE deviceMac = :mac")
    fun deleteByMac(mac: String)

    @Query("DELETE FROM charger_nodes")
    fun deleteAll()
}

@Dao
interface ScenarioObservationDao {
    @Insert
    fun insert(obs: ScenarioObservationEntity): Long

    @Query("SELECT * FROM scenario_observations WHERE timestampMs >= :minTsMs ORDER BY timestampMs DESC LIMIT :limit")
    fun getSince(minTsMs: Long, limit: Int = 10000): List<ScenarioObservationEntity>

    @Query("SELECT * FROM scenario_observations ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 2000): Flow<List<ScenarioObservationEntity>>

    @Query("DELETE FROM scenario_observations WHERE timestampMs < :cutoffMs")
    fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM scenario_observations")
    fun deleteAll()
}

@Dao
interface SessionAnnotationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(annotation: SessionAnnotationEntity): Long

    @Query("SELECT * FROM session_annotations WHERE sessionId = :sessionId ORDER BY timestampMs ASC, createdAtMs ASC")
    fun getForSession(sessionId: Long): List<SessionAnnotationEntity>

    @Query("SELECT * FROM session_annotations ORDER BY createdAtMs DESC LIMIT :limit")
    fun observeAll(limit: Int = 500): Flow<List<SessionAnnotationEntity>>

    @Query("DELETE FROM session_annotations WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM session_annotations WHERE sessionId = :sessionId")
    fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM session_annotations")
    fun deleteAll()
}

@Dao
interface IncidentBlackBoxDao {
    @Insert
    fun insert(incident: IncidentBlackBoxEntity): Long

    @Query("SELECT * FROM incident_black_boxes ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<IncidentBlackBoxEntity>>

    @Query("SELECT * FROM incident_black_boxes ORDER BY timestampMs DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): List<IncidentBlackBoxEntity>

    @Query(
        """
        DELETE FROM incident_black_boxes
        WHERE id NOT IN (
            SELECT id FROM incident_black_boxes ORDER BY timestampMs DESC LIMIT :maxRetained
        )
        """
    )
    fun pruneExcess(maxRetained: Int = 100): Int

    @Query("DELETE FROM incident_black_boxes")
    fun deleteAll()
}

@Dao
interface DeviceInventoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(device: DeviceInventoryEntity): Long

    @Query("SELECT * FROM device_inventory ORDER BY lastChargeMs DESC, name ASC")
    fun observeAll(): Flow<List<DeviceInventoryEntity>>

    @Query("SELECT * FROM device_inventory ORDER BY lastChargeMs DESC, name ASC")
    fun getAll(): List<DeviceInventoryEntity>

    @Query("DELETE FROM device_inventory WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM device_inventory")
    fun deleteAll()
}

@Dao
interface CableInventoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(cable: CableInventoryEntity): Long

    @Query("SELECT * FROM cable_inventory ORDER BY testCount DESC, name ASC")
    fun observeAll(): Flow<List<CableInventoryEntity>>

    @Query("SELECT * FROM cable_inventory ORDER BY testCount DESC, name ASC")
    fun getAll(): List<CableInventoryEntity>

    @Query("SELECT * FROM cable_inventory WHERE id = :id LIMIT 1")
    fun findById(id: Long): CableInventoryEntity?

    @Query("DELETE FROM cable_inventory WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM cable_inventory")
    fun deleteAll()
}

@Dao
interface CableLabTestDao {
    @Insert
    fun insert(test: CableLabTestEntity): Long

    @Query("SELECT * FROM cable_lab_tests ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<CableLabTestEntity>>

    @Query("SELECT * FROM cable_lab_tests ORDER BY timestampMs DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): List<CableLabTestEntity>

    @Query("DELETE FROM cable_lab_tests WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM cable_lab_tests")
    fun deleteAll()
}

@Dao
interface DeadlineTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(task: DeadlineTaskEntity): Long

    @Query("SELECT * FROM deadline_tasks ORDER BY priority ASC, deadlineEpochMs ASC, id ASC")
    fun observeAll(): Flow<List<DeadlineTaskEntity>>

    @Query("SELECT * FROM deadline_tasks ORDER BY priority ASC, deadlineEpochMs ASC, id ASC")
    fun getAll(): List<DeadlineTaskEntity>

    @Query("UPDATE deadline_tasks SET enabled = :enabled WHERE id = :id")
    fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM deadline_tasks WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM deadline_tasks")
    fun deleteAll()
}


