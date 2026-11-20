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
