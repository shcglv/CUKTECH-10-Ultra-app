package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.LocalAutomationEngine
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.db.room.CuktechDatabase
import com.example.cuktech.db.room.PortDevicePresetEntity
import com.example.cuktech.notifications.AnomalyAlert
import com.example.cuktech.notifications.AnomalyType
import com.example.cuktech.power.BudgetStatusLevel
import com.example.cuktech.power.CableCurrentClass
import com.example.cuktech.power.CableDiagnosticEngine
import com.example.cuktech.power.CableHealthRating
import com.example.cuktech.power.DevicePresetCategory
import com.example.cuktech.power.EtaEstimator
import com.example.cuktech.power.PowerBudgetCalculator
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.protocol.PortTelemetryDecoder
import com.example.cuktech.security.SafEncryptedBackupManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProChargerFeaturesTest {

    private lateinit var context: Context
    private lateinit var db: CuktechDatabase
    private lateinit var persistence: CuktechPersistence

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("cuktech_room_migration", Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = CuktechDatabase.createInMemory(context)
        persistence = CuktechPersistence(context, db, runLegacyMigration = false)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- 1. PowerBudgetCalculator ---

    @Test
    fun powerBudgetCalculatesAllLevelsHeadroomAndFractions() {
        val idle = PowerBudgetCalculator.calculate(emptyMap(), sceneMode = 1)
        assertEquals(BudgetStatusLevel.IDLE, idle.statusLevel)
        assertEquals(0.0, idle.totalWatts, 0.01)
        assertEquals(120.0, idle.headroomWatts, 0.01)
        assertEquals(0f, idle.utilizationFraction, 0.001f)

        val balanced = PowerBudgetCalculator.calculate(
            mapOf(
                1 to PortTelemetry(1, "USB-C1", enabled = true, powerW = 30.0),
                2 to PortTelemetry(2, "USB-C2", enabled = true, powerW = 15.0)
            ),
            sceneMode = 2
        )
        assertEquals(BudgetStatusLevel.BALANCED, balanced.statusLevel)
        assertEquals(45.0, balanced.totalWatts, 0.01)
        assertEquals(75.0, balanced.headroomWatts, 0.01)
        assertEquals(0.25f, balanced.allocations[0].totalFraction, 0.001f)
        assertEquals(0.25f, balanced.allocations[0].portFraction, 0.001f)

        val highLoad = PowerBudgetCalculator.calculate(
            mapOf(
                1 to PortTelemetry(1, "USB-C1", enabled = true, powerW = 72.0)
            ),
            sceneMode = 4
        )
        assertEquals(BudgetStatusLevel.HIGH_LOAD, highLoad.statusLevel)
        assertEquals(48.0, highLoad.headroomWatts, 0.01)

        val ceiling = PowerBudgetCalculator.calculate(
            mapOf(
                1 to PortTelemetry(1, "USB-C1", enabled = true, powerW = 90.0),
                2 to PortTelemetry(2, "USB-C2", enabled = true, powerW = 24.0)
            ),
            sceneMode = 4
        )
        assertEquals(BudgetStatusLevel.NEAR_CEILING, ceiling.statusLevel)
        assertEquals(6.0, ceiling.headroomWatts, 0.01)
        assertTrue(ceiling.utilizationFraction >= 0.85f)

        // Simultaneous C3 + A mirrored 5V rail (~10W reported on both C3 & A) must unify into 3 allocations without double-counting
        val sharedPorts = mapOf(
            1 to PortTelemetry(1, "USB-C1", enabled = true, voltageV = 20.0, currentA = 3.0, powerW = 60.0),
            2 to PortTelemetry(2, "USB-C2", enabled = true, voltageV = 0.0, currentA = 0.0, powerW = 0.0),
            3 to PortTelemetry(3, "USB-C3", enabled = true, voltageV = 5.05, currentA = 1.98, powerW = 10.0),
            4 to PortTelemetry(4, "USB-A", enabled = true, voltageV = 5.05, currentA = 1.98, powerW = 10.0)
        )
        val c3aBus = PortTelemetryDecoder.resolveC3ASharedBus(sharedPorts)
        assertTrue(c3aBus.isSharedActive)
        assertEquals(10.0, c3aBus.sharedPowerW, 0.05)
        assertEquals(70.0, PortTelemetryDecoder.computeEffectiveTotalWatts(sharedPorts), 0.05)

        val sharedBudget = PowerBudgetCalculator.calculate(sharedPorts, sceneMode = 1)
        assertTrue(sharedBudget.isSharedC3ABusActive)
        assertEquals(3, sharedBudget.allocations.size)
        assertEquals(70.0, sharedBudget.totalWatts, 0.05)
        assertTrue(sharedBudget.allocations[2].isSharedC3ABus)
        assertEquals(15, sharedBudget.allocations[2].hardwareMaxWatts)
    }

    // --- 2. EtaEstimator ---

    @Test
    fun etaEstimatorComputesRemainingTimeTaperAndIdleStates() {
        val phonePreset = PortDevicePresetEntity(
            portId = 1,
            presetType = DevicePresetCategory.PHONE.code,
            deviceLabel = "Pixel 9 Pro",
            targetWh = 20.0,
            initialSocPct = 20,
            updatedAtMs = 1000L
        )

        // Initial 20% = 4.0 Wh + 2.0 sessionWh = 6.0 Wh stored; 14.0 Wh remaining; at 28W with 1.08 taper -> 0.54h = 32 min
        val activeEta = EtaEstimator.estimate(phonePreset, liveWatts = 28.0, sessionWh = 2.0)
        assertFalse(activeEta.isNearlyFull)
        assertEquals(14.0, activeEta.remainingWh, 0.01)
        assertEquals(0.30f, activeEta.progressFraction, 0.001f)
        assertEquals(32, activeEta.estimatedMinutesRemaining)

        // Taper phase (> 78%): 4.0 + 13.0 = 17.0 Wh (85%), remaining = 3.0 Wh; at 10W with 1.25 taper -> 0.375h = 23 min
        val taperEta = EtaEstimator.estimate(phonePreset, liveWatts = 10.0, sessionWh = 13.0)
        assertFalse(taperEta.isNearlyFull)
        assertEquals(23, taperEta.estimatedMinutesRemaining)

        // Nearly full (>= 98% or <= 0.4 Wh remaining)
        val fullEta = EtaEstimator.estimate(phonePreset, liveWatts = 3.0, sessionWh = 15.8)
        assertTrue(fullEta.isNearlyFull)
        assertEquals(0, fullEta.estimatedMinutesRemaining)

        // Idle (< 0.8W) -> null ETA
        val idleEta = EtaEstimator.estimate(phonePreset, liveWatts = 0.2, sessionWh = 2.0)
        assertFalse(idleEta.isNearlyFull)
        assertNull(idleEta.estimatedMinutesRemaining)

        // mAh -> Wh at 3.85V across 1S/2S/3S packs
        assertEquals(19.25, EtaEstimator.whFromMah(5000, 1), 0.05)
        assertEquals(38.50, EtaEstimator.whFromMah(5000, 2), 0.05)
        assertEquals(57.75, EtaEstimator.whFromMah(5000, 3), 0.05)
    }

    // --- 3. CableDiagnosticEngine ---

    @Test
    fun cableDiagnosticInfersFixedRailSagResistanceEmarkerAndPpsBehavior() {
        val engine = CableDiagnosticEngine()

        // Step 1: Light load on fixed PD 20V rail establishes baseline
        val d1 = engine.onPortTelemetry(
            PortTelemetry(1, "USB-C1", enabled = true, voltageV = 19.95, currentA = 0.50, powerW = 9.97, protocolName = "PD 3.0"),
            nowMs = 1000L
        )
        assertEquals(20.0, d1.nominalRailV ?: 0.0, 0.01)
        assertEquals(50, d1.voltageSagMv)
        assertEquals(CableHealthRating.EXCELLENT, d1.healthRating)

        // Step 2: Heavy load (+3.0A step, ΔV = 0.60V -> R ≈ 200 mΩ, sag from 20V = 650mV -> UNSTABLE)
        val d2 = engine.onPortTelemetry(
            PortTelemetry(1, "USB-C1", enabled = true, voltageV = 19.35, currentA = 3.50, powerW = 67.7, protocolName = "PD 3.0"),
            nowMs = 2000L
        )
        assertEquals(650, d2.voltageSagMv)
        assertEquals(CableCurrentClass.EMARKER_5A, d2.currentClass)
        assertNotNull(d2.estimatedResistanceMohm)
        assertTrue(d2.estimatedResistanceMohm in 180..220)
        assertEquals(CableHealthRating.UNSTABLE, d2.healthRating)

        // Step 3: PPS variable protocol must skip nominal fixed-rail voltage sag inference
        val dPps = engine.onPortTelemetry(
            PortTelemetry(1, "USB-C1", enabled = true, voltageV = 8.40, currentA = 2.50, powerW = 21.0, protocolName = "PPS 3A"),
            nowMs = 3000L
        )
        assertTrue(dPps.isVariablePps)
        assertNull(dPps.nominalRailV)
        assertEquals(0, dPps.voltageSagMv)

        // Step 4: Anomaly alert marks port unstable even with low sag
        val dAlert = engine.onPortTelemetry(
            PortTelemetry(2, "USB-C2", enabled = true, voltageV = 9.00, currentA = 1.50, powerW = 13.5, protocolName = "PD 3.0"),
            alerts = listOf(
                AnomalyAlert(
                    id = "a1",
                    type = AnomalyType.CABLE_RENEGOTIATION_INSTABILITY,
                    portId = 2,
                    portLabel = "USB-C2",
                    countInWindow = 3,
                    summary = "Renegotiation",
                    detectedAtMs = 4000L
                )
            ),
            nowMs = 4000L
        )
        assertEquals(CableHealthRating.UNSTABLE, dAlert.healthRating)
        assertEquals(3, dAlert.renegotiationAlertCount)

        // Step 5: Reset clears peak/resistance/sampleCount
        engine.resetPortStats(1)
        val resetDiag = engine.diagnostics.value[1]!!
        assertEquals(CableHealthRating.IDLE, resetDiag.healthRating)
        assertEquals(0.0, resetDiag.peakObservedCurrentA, 0.001)
        assertNull(resetDiag.estimatedResistanceMohm)
        assertEquals(0, resetDiag.sampleCount)
    }

    // --- 4. LocalAutomationEngine ---

    @Test
    fun localAutomationEvaluatesHysteresisScheduleNoOpCooldownAndAuth() {
        val engine = LocalAutomationEngine()
        val settings = ChargerSettingsState(sceneMode = 1)
        val idleCutoffRule = AutomationRuleEntity(
            id = 10L,
            name = "Idle Auto-Off",
            enabled = true,
            triggerType = LocalAutomationEngine.TRIGGER_LOW_POWER_CUTOFF,
            portId = 1,
            thresholdWatts = 1.5,
            holdSeconds = 30,
            actionType = LocalAutomationEngine.ACTION_PORT_OFF,
            cooldownMinutes = 10
        )

        // Requires auth
        val unauth = engine.evaluate(
            listOf(idleCutoffRule),
            mapOf(1 to PortTelemetry(1, "USB-C1", enabled = true, powerW = 0.8)),
            settings,
            isAuthenticated = false,
            nowMs = 1_000L
        )
        assertTrue(unauth.isEmpty())

        // Arm port with >3W load first
        engine.evaluate(
            listOf(idleCutoffRule),
            mapOf(1 to PortTelemetry(1, "USB-C1", enabled = true, powerW = 20.0)),
            settings,
            isAuthenticated = true,
            nowMs = 2_000L
        )

        // Drops below 1.5W at t=10_000ms -> starts timer, doesn't fire before 30s
        val beforeHold = engine.evaluate(
            listOf(idleCutoffRule),
            mapOf(1 to PortTelemetry(1, "USB-C1", enabled = true, powerW = 0.6)),
            settings,
            isAuthenticated = true,
            nowMs = 10_000L
        )
        assertTrue(beforeHold.isEmpty())

        // Still below threshold at t=41_000ms (31s >= 30s) -> fires PORT_OFF
        val afterHold = engine.evaluate(
            listOf(idleCutoffRule),
            mapOf(1 to PortTelemetry(1, "USB-C1", enabled = true, powerW = 0.6)),
            settings,
            isAuthenticated = true,
            nowMs = 41_000L
        )
        assertEquals(1, afterHold.size)
        assertEquals(ChargerCommand.SetPortEnabled(1, false), afterHold[0].command)

        // Cooldown blocks re-fire when lastTriggeredMs was just set
        val cooledDownRule = idleCutoffRule.copy(lastTriggeredMs = 41_000L)
        val duringCooldown = engine.evaluate(
            listOf(cooledDownRule),
            mapOf(1 to PortTelemetry(1, "USB-C1", enabled = true, powerW = 0.4)),
            settings,
            isAuthenticated = true,
            nowMs = 41_000L + 120_000L // 2m < 10m cooldown
        )
        assertTrue(duringCooldown.isEmpty())

        // Daily Schedule matching exact weekday bit & minuteOfDay (2025-01-15 is a Wednesday)
        val cal = Calendar.getInstance().apply {
            set(2025, Calendar.JANUARY, 15, 23, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val wedBit = LocalAutomationEngine.dayBitForCalendar(cal.get(Calendar.DAY_OF_WEEK))
        val schedRule = AutomationRuleEntity(
            id = 11L,
            name = "Night Dual",
            enabled = true,
            triggerType = LocalAutomationEngine.TRIGGER_DAILY_SCHEDULE,
            minuteOfDay = 23 * 60,
            daysMask = (1 shl wedBit),
            actionType = LocalAutomationEngine.ACTION_SET_SCENE_MODE,
            actionSceneMode = 2,
            cooldownMinutes = 60
        )
        val firedSched = engine.evaluate(
            listOf(schedRule),
            emptyMap(),
            settings = ChargerSettingsState(sceneMode = 1),
            isAuthenticated = true,
            nowMs = cal.timeInMillis
        )
        assertEquals(1, firedSched.size)
        assertEquals(ChargerCommand.SetSceneMode(2), firedSched[0].command)

        // No-op guard when target scene mode is already active
        val noOpSched = engine.evaluate(
            listOf(schedRule),
            emptyMap(),
            settings = ChargerSettingsState(sceneMode = 2),
            isAuthenticated = true,
            nowMs = cal.timeInMillis
        )
        assertTrue(noOpSched.isEmpty())
    }

    // --- 5. Room v1 -> v2 Migration & SAF Encrypted Backup Round-Trip ---

    @Test
    fun roomMigration1To2CreatesProTablesAndSafBackupPreservesPresetsAndRules() {
        // Verify MIGRATION_1_2 creates expected tables & indices cleanly on a raw SQLite v1 handle
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val rawDb = helper.writableDatabase
        CuktechDatabase.MIGRATION_1_2.migrate(rawDb)
        CuktechDatabase.MIGRATION_2_3.migrate(rawDb)

        rawDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('port_device_presets', 'automation_rules')").use { c ->
            assertEquals(2, c.count)
        }
        rawDb.query("PRAGMA table_info(`port_device_presets`)").use { c ->
            val cols = mutableSetOf<String>()
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0) cols.add(c.getString(nameIdx))
            }
            assertTrue(cols.contains("capacityMah"))
            assertTrue(cols.contains("cellSeriesCount"))
        }
        rawDb.query("SELECT name FROM sqlite_master WHERE type='index' AND name IN ('index_automation_rules_enabled', 'index_automation_rules_triggerType')").use { c ->
            assertEquals(2, c.count)
        }
        helper.close()

        // Persistence CRUD + Encrypted SAF Backup roundtrip
        persistence.savePortDevicePreset(
            PortDevicePresetEntity(
                portId = 1,
                presetType = DevicePresetCategory.LAPTOP.code,
                deviceLabel = "MacBook Pro 14",
                targetWh = 72.4,
                initialSocPct = 35,
                updatedAtMs = 5000L
            )
        )
        persistence.saveAutomationRule(
            AutomationRuleEntity(
                name = "Night Quiet",
                enabled = true,
                triggerType = LocalAutomationEngine.TRIGGER_DAILY_SCHEDULE,
                portId = 1,
                thresholdWatts = 1.5,
                holdSeconds = 60,
                minuteOfDay = 23 * 60,
                daysMask = 0b1111111,
                actionType = LocalAutomationEngine.ACTION_SET_SCENE_MODE,
                actionSceneMode = 2,
                cooldownMinutes = 60,
                createdAtMs = 5000L
            )
        )
        assertEquals(1, persistence.getPortDevicePresets().size)
        assertEquals(1, persistence.getAutomationRules().size)

        val payload = persistence.buildBackupPayload(
            preferencesJson = JSONObject().put("appLanguage", "ru"),
            includeSecrets = false
        )
        val baos = ByteArrayOutputStream()
        SafEncryptedBackupManager.encryptToStream(payload, "TestPass123!".toCharArray(), baos, kdfIterations = 10_000)

        // Wipe and restore cleanly
        persistence.deletePortDevicePreset(1)
        val ruleId = persistence.getAutomationRules().first().id
        persistence.deleteAutomationRule(ruleId)
        assertEquals(0, persistence.getPortDevicePresets().size)
        assertEquals(0, persistence.getAutomationRules().size)

        val decrypted = SafEncryptedBackupManager.decryptFromStream(
            ByteArrayInputStream(baos.toByteArray()),
            "TestPass123!".toCharArray()
        )
        persistence.restoreBackupPayload(decrypted, replaceExisting = true)

        val restoredPresets = persistence.getPortDevicePresets()
        assertEquals(1, restoredPresets.size)
        assertEquals("MacBook Pro 14", restoredPresets[0].deviceLabel)
        assertEquals(72.4, restoredPresets[0].targetWh, 0.01)

        val restoredRules = persistence.getAutomationRules()
        assertEquals(1, restoredRules.size)
        assertEquals("Night Quiet", restoredRules[0].name)
        assertEquals(23 * 60, restoredRules[0].minuteOfDay)
    }
}
