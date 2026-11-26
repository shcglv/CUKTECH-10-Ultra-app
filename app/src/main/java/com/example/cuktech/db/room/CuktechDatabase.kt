package com.example.cuktech.db.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TelemetrySampleEntity::class,
        ChargeSessionEntity::class,
        DailyEnergyAggregateEntity::class,
        PortChargeLimitEntity::class,
        DeviceAliasEntity::class,
        ChargingProfileEntity::class,
        PortDevicePresetEntity::class,
        AutomationRuleEntity::class,
        SessionGapEntity::class,
        CommandAuditEntity::class,
        BleLinkEventEntity::class,
        ChargerNodeEntity::class,
        ScenarioObservationEntity::class,
        SessionAnnotationEntity::class,
        IncidentBlackBoxEntity::class,
        DeviceInventoryEntity::class,
        CableInventoryEntity::class,
        CableLabTestEntity::class,
        DeadlineTaskEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class CuktechDatabase : RoomDatabase() {
    abstract fun telemetrySampleDao(): TelemetrySampleDao
    abstract fun chargeSessionDao(): ChargeSessionDao
    abstract fun dailyEnergyAggregateDao(): DailyEnergyAggregateDao
    abstract fun portChargeLimitDao(): PortChargeLimitDao
    abstract fun deviceAliasDao(): DeviceAliasDao
    abstract fun chargingProfileDao(): ChargingProfileDao
    abstract fun portDevicePresetDao(): PortDevicePresetDao
    abstract fun automationRuleDao(): AutomationRuleDao
    abstract fun sessionGapDao(): SessionGapDao
    abstract fun commandAuditDao(): CommandAuditDao
    abstract fun bleLinkEventDao(): BleLinkEventDao
    abstract fun chargerNodeDao(): ChargerNodeDao
    abstract fun scenarioObservationDao(): ScenarioObservationDao
    abstract fun sessionAnnotationDao(): SessionAnnotationDao
    abstract fun incidentBlackBoxDao(): IncidentBlackBoxDao
    abstract fun deviceInventoryDao(): DeviceInventoryDao
    abstract fun cableInventoryDao(): CableInventoryDao
    abstract fun cableLabTestDao(): CableLabTestDao
    abstract fun deadlineTaskDao(): DeadlineTaskDao

    companion object {
        const val DB_NAME = "cuktech_room.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `port_device_presets` (
                        `portId` INTEGER NOT NULL,
                        `presetType` TEXT NOT NULL,
                        `deviceLabel` TEXT NOT NULL,
                        `targetWh` REAL NOT NULL,
                        `initialSocPct` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`portId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `automation_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `triggerType` TEXT NOT NULL,
                        `portId` INTEGER NOT NULL,
                        `thresholdWatts` REAL NOT NULL,
                        `holdSeconds` INTEGER NOT NULL,
                        `minuteOfDay` INTEGER NOT NULL,
                        `daysMask` INTEGER NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `actionSceneMode` INTEGER NOT NULL,
                        `lastTriggeredMs` INTEGER NOT NULL,
                        `cooldownMinutes` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_automation_rules_enabled` ON `automation_rules` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_automation_rules_triggerType` ON `automation_rules` (`triggerType`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `port_device_presets` ADD COLUMN `capacityMah` INTEGER NOT NULL DEFAULT 5000")
                db.execSQL("ALTER TABLE `port_device_presets` ADD COLUMN `cellSeriesCount` INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `fastBoostDurationSec` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `taperDurationSec` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `telemetry_samples` ADD COLUMN `sourceMode` TEXT NOT NULL DEFAULT 'BLE_DIRECT'")
                db.execSQL("ALTER TABLE `telemetry_samples` ADD COLUMN `rssiDbm` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_gaps` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `deviceMac` TEXT NOT NULL,
                        `portId` INTEGER NOT NULL,
                        `gapStartMs` INTEGER NOT NULL,
                        `gapEndMs` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        `wasBackfilledByBridge` INTEGER NOT NULL,
                        `backfilledSamplesCount` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_gaps_deviceMac_gapStartMs` ON `session_gaps` (`deviceMac`, `gapStartMs`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `command_audit_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `deviceMac` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `commandType` TEXT NOT NULL,
                        `targetPortId` INTEGER NOT NULL,
                        `summary` TEXT NOT NULL,
                        `accepted` INTEGER NOT NULL,
                        `reconciledConfirmed` INTEGER,
                        `latencyMs` INTEGER NOT NULL,
                        `errorCode` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_command_audit_log_timestampMs` ON `command_audit_log` (`timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_command_audit_log_targetPortId` ON `command_audit_log` (`targetPortId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ble_link_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `deviceMac` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `rssiDbm` INTEGER NOT NULL,
                        `intervalMs` INTEGER NOT NULL,
                        `detail` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ble_link_events_timestampMs` ON `ble_link_events` (`timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ble_link_events_deviceMac_timestampMs` ON `ble_link_events` (`deviceMac`, `timestampMs`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `charger_nodes` (
                        `deviceMac` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `locationContext` TEXT NOT NULL,
                        `mainsVoltageMode` INTEGER NOT NULL,
                        `bridgeEndpointUrl` TEXT NOT NULL,
                        `lastSeenMs` INTEGER NOT NULL,
                        `lastTotalWatts` REAL NOT NULL,
                        `lastTotalWhToday` REAL NOT NULL,
                        `lastRssiDbm` INTEGER NOT NULL,
                        `isOnline` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceMac`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scenario_observations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `deviceMac` TEXT NOT NULL,
                        `sceneMode` INTEGER NOT NULL,
                        `activePortsMask` INTEGER NOT NULL,
                        `c1W` REAL NOT NULL,
                        `c2W` REAL NOT NULL,
                        `c3W` REAL NOT NULL,
                        `aW` REAL NOT NULL,
                        `effectiveTotalW` REAL NOT NULL,
                        `c3aShared` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scenario_observations_timestampMs` ON `scenario_observations` (`timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scenario_observations_deviceMac_sceneMode_activePortsMask` ON `scenario_observations` (`deviceMac`, `sceneMode`, `activePortsMask`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_annotations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `tagLabel` TEXT NOT NULL,
                        `noteText` TEXT NOT NULL,
                        `isFavorite` INTEGER NOT NULL,
                        `voltageV` REAL NOT NULL,
                        `currentA` REAL NOT NULL,
                        `powerW` REAL NOT NULL,
                        `protocolName` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_annotations_sessionId_timestampMs` ON `session_annotations` (`sessionId`, `timestampMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `incident_black_boxes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `deviceMac` TEXT NOT NULL,
                        `portId` INTEGER NOT NULL,
                        `incidentType` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `windowJson` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_incident_black_boxes_timestampMs` ON `incident_black_boxes` (`timestampMs`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `device_inventory` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `ratedWh` REAL NOT NULL,
                        `ratedMaxW` REAL NOT NULL,
                        `preferredProtocol` TEXT NOT NULL,
                        `assignedPortId` INTEGER NOT NULL,
                        `totalRecordedWh` REAL NOT NULL,
                        `sessionCount` INTEGER NOT NULL,
                        `lastChargeMs` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cable_inventory` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `connectorType` TEXT NOT NULL,
                        `ratedAmps` INTEGER NOT NULL,
                        `lengthCm` INTEGER NOT NULL,
                        `hasEmarker` INTEGER NOT NULL,
                        `lastMeasuredMohm` INTEGER,
                        `lastUncertaintyMohm` INTEGER,
                        `lastSagMv` INTEGER,
                        `testCount` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cable_lab_tests` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `cableId` INTEGER NOT NULL,
                        `cableName` TEXT NOT NULL,
                        `portId` INTEGER NOT NULL,
                        `nominalRailV` REAL NOT NULL,
                        `avgVoltageV` REAL NOT NULL,
                        `avgCurrentA` REAL NOT NULL,
                        `sampleCount` INTEGER NOT NULL,
                        `sagMv` INTEGER NOT NULL,
                        `estimatedMohm` INTEGER NOT NULL,
                        `uncertaintyMohm` INTEGER NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `notes` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cable_lab_tests_timestampMs` ON `cable_lab_tests` (`timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cable_lab_tests_cableId` ON `cable_lab_tests` (`cableId`)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `automation_rules` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE `automation_rules` ADD COLUMN `targetPhoneSocPct` INTEGER NOT NULL DEFAULT 85")
                db.execSQL("ALTER TABLE `automation_rules` ADD COLUMN `actionOrientationLock` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `automation_rules` ADD COLUMN `actionIdleScreenOff` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `automation_rules` ADD COLUMN `actionScreenTimeout` INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `deadline_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `portId` INTEGER NOT NULL,
                        `targetWh` REAL NOT NULL,
                        `deadlineEpochMs` INTEGER NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_deadline_tasks_enabled` ON `deadline_tasks` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_deadline_tasks_deadlineEpochMs` ON `deadline_tasks` (`deadlineEpochMs`)")
            }
        }

        @Volatile
        private var INSTANCE: CuktechDatabase? = null

        fun getInstance(context: Context): CuktechDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CuktechDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun createInMemory(context: Context): CuktechDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                CuktechDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}

