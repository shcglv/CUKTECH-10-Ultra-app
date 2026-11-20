package com.example.cuktech.db.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TelemetrySampleEntity::class,
        ChargeSessionEntity::class,
        DailyEnergyAggregateEntity::class,
        PortChargeLimitEntity::class,
        DeviceAliasEntity::class,
        ChargingProfileEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CuktechDatabase : RoomDatabase() {
    abstract fun telemetrySampleDao(): TelemetrySampleDao
    abstract fun chargeSessionDao(): ChargeSessionDao
    abstract fun dailyEnergyAggregateDao(): DailyEnergyAggregateDao
    abstract fun portChargeLimitDao(): PortChargeLimitDao
    abstract fun deviceAliasDao(): DeviceAliasDao
    abstract fun chargingProfileDao(): ChargingProfileDao

    companion object {
        const val DB_NAME = "cuktech_room.db"

        @Volatile
        private var INSTANCE: CuktechDatabase? = null

        fun getInstance(context: Context): CuktechDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CuktechDatabase::class.java,
                    DB_NAME
                )
                    .allowMainThreadQueries()
                    .fallbackToDestructiveMigration(dropAllTables = false)
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
