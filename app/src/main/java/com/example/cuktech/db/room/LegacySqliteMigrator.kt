package com.example.cuktech.db.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlin.math.max

/**
 * One-time idempotent migration from legacy raw SQLite database (`cuktech10_history.db`)
 * into Room (`cuktech_room.db`). Preserves 100% of existing completed sessions, recent
 * telemetry snapshots, and Wh charge limits without failing on missing/empty tables.
 */
object LegacySqliteMigrator {
    private const val LEGACY_DB_NAME = "cuktech10_history.db"
    private const val PREFS_NAME = "cuktech_room_migration"
    private const val KEY_MIGRATED_V1 = "legacy_sqlite_migrated_v1"

    fun migrateIfNeeded(context: Context, roomDb: CuktechDatabase): Boolean {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED_V1, false)) {
            return false
        }

        val dbFile = appCtx.getDatabasePath(LEGACY_DB_NAME)
        if (!dbFile.exists() || dbFile.length() == 0L) {
            prefs.edit().putBoolean(KEY_MIGRATED_V1, true).apply()
            return false
        }

        var migratedAny = false
        runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
                val sessions = mutableListOf<ChargeSessionEntity>()
                if (tableExists(legacy, "charge_sessions")) {
                    legacy.rawQuery(
                        "SELECT port_id, port_label, start_ts, end_ts, wh_accumulated, peak_watts, avg_watts, protocol_used FROM charge_sessions ORDER BY start_ts DESC LIMIT 2000",
                        null
                    ).use { c ->
                        while (c.moveToNext()) {
                            val startTs = c.getDouble(2)
                            val endTs = if (c.isNull(3)) startTs else c.getDouble(3)
                            val activeSec = max(0L, (endTs - startTs).toLong())
                            sessions.add(
                                ChargeSessionEntity(
                                    portId = c.getInt(0),
                                    portLabel = c.getString(1) ?: "USB",
                                    startTimeSec = startTs,
                                    endTimeSec = endTs,
                                    lastSampleTimeSec = endTs,
                                    activeDurationSec = activeSec,
                                    gapDurationSec = 0L,
                                    whAccumulated = c.getDouble(4),
                                    peakWatts = c.getDouble(5),
                                    avgWatts = c.getDouble(6),
                                    protocolUsed = c.getString(7) ?: "Unknown",
                                    isActive = false,
                                    endReason = "LEGACY_IMPORT"
                                )
                            )
                        }
                    }
                }

                val limits = mutableListOf<PortChargeLimitEntity>()
                if (tableExists(legacy, "port_charge_limits")) {
                    legacy.rawQuery(
                        "SELECT port_id, wh_target, mode, baseline_wh, enabled, triggered FROM port_charge_limits",
                        null
                    ).use { c ->
                        while (c.moveToNext()) {
                            limits.add(
                                PortChargeLimitEntity(
                                    portId = c.getInt(0),
                                    whTarget = c.getDouble(1),
                                    mode = c.getString(2) ?: "once",
                                    baselineWh = c.getDouble(3),
                                    enabled = c.getInt(4) == 1,
                                    triggered = c.getInt(5) == 1
                                )
                            )
                        }
                    }
                }

                val samples = mutableListOf<TelemetrySampleEntity>()
                if (tableExists(legacy, "port_history")) {
                    legacy.rawQuery(
                        "SELECT ts_ms, port_id, voltage_v, current_a, power_w, protocol_name FROM port_history ORDER BY ts_ms DESC LIMIT 10000",
                        null
                    ).use { c ->
                        while (c.moveToNext()) {
                            samples.add(
                                TelemetrySampleEntity(
                                    timestampMs = c.getLong(0),
                                    portId = c.getInt(1),
                                    voltageV = c.getDouble(2),
                                    currentA = c.getDouble(3),
                                    powerW = c.getDouble(4),
                                    protocolName = c.getString(5) ?: "Idle"
                                )
                            )
                        }
                    }
                }

                roomDb.runInTransaction {
                    if (sessions.isNotEmpty()) roomDb.chargeSessionDao().insertAll(sessions)
                    if (limits.isNotEmpty()) roomDb.portChargeLimitDao().upsertAll(limits)
                    if (samples.isNotEmpty()) roomDb.telemetrySampleDao().insertAll(samples)
                }
                migratedAny = sessions.isNotEmpty() || limits.isNotEmpty() || samples.isNotEmpty()
            }
            prefs.edit().putBoolean(KEY_MIGRATED_V1, true).apply()
        }
        return migratedAny
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(name)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }
}
