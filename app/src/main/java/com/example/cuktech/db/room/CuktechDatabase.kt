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
        AutomationRuleEntity::class
    ],
    version = 3,
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

        @Volatile
        private var INSTANCE: CuktechDatabase? = null

        fun getInstance(context: Context): CuktechDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CuktechDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

