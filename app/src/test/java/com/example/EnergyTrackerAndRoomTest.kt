package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.room.CuktechDatabase
import com.example.cuktech.db.room.LegacySqliteMigrator
import com.example.cuktech.energy.AdaptiveEnergyIntegrator
import com.example.cuktech.energy.ChargeSessionDetector
import com.example.cuktech.energy.ChargeSessionRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnergyTrackerAndRoomTest {

    private lateinit var context: Context
    private lateinit var db: CuktechDatabase
    private lateinit var persistence: CuktechPersistence

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("cuktech_room_migration", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.deleteDatabase("cuktech10_history.db")
        db = CuktechDatabase.createInMemory(context)
        persistence = CuktechPersistence(context, db, runLegacyMigration = false)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun integratorExcludesBleGapsAndZeroWattIdleFromWhAndActiveSeconds() {
        val integrator = AdaptiveEnergyIntegrator(maxValidGapSec = 6.0)

        // Initial point sets baseline
        val s0 = integrator.updateWithDetail(60.0, 100.0)
        assertEquals(0.0, s0.deltaWh, 1e-6)
        assertEquals(0.0, s0.dtActiveSec, 1e-6)

        // Valid 3s step at 60W -> 60 * 3 / 3600 = 0.05 Wh
        val s1 = integrator.updateWithDetail(60.0, 103.0)
        assertEquals(0.05, s1.deltaWh, 1e-6)
        assertEquals(3.0, s1.dtActiveSec, 1e-6)
        assertEquals(false, s1.wasGap)

        // 45s BLE disconnect gap -> 0.0 Wh and 0.0 activeSec, gapDuration captured
        val sGap = integrator.updateWithDetail(60.0, 148.0)
        assertEquals(0.0, sGap.deltaWh, 1e-6)
        assertEquals(0.0, sGap.dtActiveSec, 1e-6)
        assertEquals(45.0, sGap.dtGapSec, 1e-6)
        assertTrue(sGap.wasGap)

        // Zero-watt idle interval must not inflate active seconds
        integrator.updateWithDetail(0.0, 150.0) // ramps down from 60W -> 0W
        val beforeIdle = integrator.totalWh
        val sIdle = integrator.updateWithDetail(0.0, 154.0)
        assertEquals(0.0, sIdle.deltaWh, 1e-6)
        assertEquals(0.0, sIdle.dtActiveSec, 1e-6)
        assertEquals(beforeIdle, integrator.totalWh, 1e-6)
    }

    @Test
    fun sessionDetectorStitchesShortGapsWithoutPhantomWhAndTimesOutLongGaps() {
        val integrator = AdaptiveEnergyIntegrator(maxValidGapSec = 6.0)
        val detector = ChargeSessionDetector(portId = 1, portLabel = "USB-C1")

        // Start session at t = 1000..1004 (100W)
        for (t in 1000..1004) {
            val step = integrator.updateWithDetail(100.0, t.toDouble())
            assertNull(
                detector.onSample(
                    watts = 100.0,
                    step = step,
                    protocolName = "PD 3.0",
                    timestampSec = t.toDouble(),
                    tariffPerKwh = 0.25,
                    currencyCode = "USD"
                )
            )
        }
        val beforeGapWh = detector.activeSession!!.whAccumulated

        // Simulate 40s BLE drop (within 120s stitchGraceWindowSec)
        val gapStep = integrator.updateWithDetail(100.0, 1044.0)
        assertNull(
            detector.onSample(
                watts = 100.0,
                step = gapStep,
                protocolName = "PD 3.0",
                timestampSec = 1044.0,
                stitchGraceWindowSec = 120
            )
        )
        val stitched = detector.activeSession!!
        assertEquals(beforeGapWh, stitched.whAccumulated, 1e-6)
        assertTrue(stitched.gapDurationSec >= 40L)

        // Exceed 120s grace window -> finalizes with BLE_DROP_TIMEOUT and starts new session if charging
        val timeoutStep = integrator.updateWithDetail(100.0, 1250.0)
        val finished = detector.onSample(
            watts = 100.0,
            step = timeoutStep,
            protocolName = "PD 3.0",
            timestampSec = 1250.0,
            stitchGraceWindowSec = 120
        )
        assertNotNull(finished)
        assertEquals("BLE_DROP_TIMEOUT", finished?.endReason)
        assertEquals(beforeGapWh, finished!!.whAccumulated, 1e-6)
        // New session automatically started at 1250.0
        assertNotNull(detector.activeSession)
        assertEquals(1250.0, detector.activeSession!!.startTimeSec, 0.01)
    }

    @Test
    fun roomArchivesOldSessionsPreservingTariffSnapshotAndMigratesLegacySqlite() {
        val nowMs = 1_750_000_000_000L
        val nowSec = nowMs / 1000.0
        val oldStart = nowSec - (35.0 * 86_400.0) // 35 days ago -> expired for 30d retention
        val recentStart = nowSec - (5.0 * 86_400.0) // 5 days ago -> stays raw

        persistence.insertCompletedSession(
            ChargeSessionRecord(
                portId = 1,
                portLabel = "USB-C1",
                deviceMac = "AA:BB:CC:DD:EE:01",
                startTimeSec = oldStart,
                endTimeSec = oldStart + 3600.0,
                lastSampleTimeSec = oldStart + 3600.0,
                activeDurationSec = 3600L,
                gapDurationSec = 30L,
                whAccumulated = 500.0,
                peakWatts = 100.0,
                avgWatts = 50.0,
                protocolUsed = "PD 3.0",
                isActive = false,
                endReason = "UNPLUGGED",
                tariffPerKwhSnapshot = 0.20,
                currencyCode = "EUR",
                computedCost = 0.10
            )
        )
        persistence.insertCompletedSession(
            ChargeSessionRecord(
                portId = 2,
                portLabel = "USB-C2",
                deviceMac = "AA:BB:CC:DD:EE:01",
                startTimeSec = recentStart,
                endTimeSec = recentStart + 1800.0,
                lastSampleTimeSec = recentStart + 1800.0,
                activeDurationSec = 1800L,
                whAccumulated = 50.0,
                peakWatts = 45.0,
                avgWatts = 40.0,
                protocolUsed = "PPS",
                isActive = false,
                endReason = "TRICKLE_COMPLETE",
                tariffPerKwhSnapshot = 0.35,
                currencyCode = "USD",
                computedCost = 0.0175
            )
        )

        val summary = persistence.archiveOldSessions(retentionDays = 30, nowMs = nowMs)
        assertEquals(1, summary.archivedSessions)

        val remainingRaw = persistence.getRecentSessions()
        assertEquals(1, remainingRaw.size)
        assertEquals(2, remainingRaw[0].portId)
        assertEquals(0.35, remainingRaw[0].tariffPerKwhSnapshot, 1e-6)

        val aggregates = persistence.getDailyAggregates()
        assertEquals(1, aggregates.size)
        assertEquals(500.0, aggregates[0].totalWh, 1e-4)
        assertEquals(0.10, aggregates[0].totalCost, 1e-6) // Preserved immutable snapshot cost!
        assertEquals("EUR", aggregates[0].currencyCode)
        assertEquals(3600L, aggregates[0].totalActiveSec)
        assertEquals(30L, aggregates[0].totalGapSec)
        assertEquals(1, aggregates[0].archivedSessionsCount)

        // Legacy SQLite migration check
        val legacyFile = context.getDatabasePath("cuktech10_history.db")
        legacyFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { legacy ->
            legacy.execSQL("CREATE TABLE charge_sessions (id INTEGER PRIMARY KEY, port_id INT, port_label TEXT, start_ts REAL, end_ts REAL, wh_accumulated REAL, peak_watts REAL, avg_watts REAL, protocol_used TEXT)")
            legacy.execSQL("CREATE TABLE port_charge_limits (port_id INT PRIMARY KEY, wh_target REAL, mode TEXT, baseline_wh REAL, enabled INT, triggered INT)")
            legacy.execSQL("CREATE TABLE port_history (id INTEGER PRIMARY KEY, ts_ms INT, port_id INT, voltage_v REAL, current_a REAL, power_w REAL, protocol_name TEXT)")
            legacy.execSQL("INSERT INTO charge_sessions VALUES (1, 3, 'USB-C3', 1000.0, 1600.0, 12.5, 30.0, 25.0, 'PD')")
            legacy.execSQL("INSERT INTO port_charge_limits VALUES (3, 20.0, 'once', 5.0, 1, 0)")
        }

        val freshDb = CuktechDatabase.createInMemory(context)
        try {
            val migrated = LegacySqliteMigrator.migrateIfNeeded(context, freshDb)
            assertTrue(migrated)
            assertEquals("LEGACY_IMPORT", freshDb.chargeSessionDao().getRecentCompleted(10).first().endReason)
            assertEquals(20.0, freshDb.portChargeLimitDao().getAll().first().whTarget, 0.001)
            // Idempotent second run returns false
            assertEquals(false, LegacySqliteMigrator.migrateIfNeeded(context, freshDb))
        } finally {
            freshDb.close()
        }
    }
}
