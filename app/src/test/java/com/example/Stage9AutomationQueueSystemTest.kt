package com.example

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.automation.AutomationSafetyAndDeadlineEngine
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandDispatcher
import com.example.cuktech.automation.CommandResult
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.automation.DeadlineReadinessStatus
import com.example.cuktech.automation.LocalAutomationEngine
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.db.room.CuktechDatabase
import com.example.cuktech.db.room.DeadlineTaskEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.security.AutomationAuthManager
import com.example.tile.CuktechQuickSettingsTileService
import com.example.ui.CuktechAppRoot
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.navigation.AppDestination
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DashboardViewModel
import com.example.widget.CuktechGlancePortReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h915dp", sdk = [36])
class Stage9AutomationQueueSystemTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var db: CuktechDatabase
    private lateinit var persistence: CuktechPersistence

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        BleBridgeService.ensureCuktechManagersInitialized(app)
        db = CuktechDatabase.createInMemory(app)
        persistence = CuktechPersistence(app, db, runLegacyMigration = false)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun roomMigration7To8UpdatesAutomationRulesAndCreatesDeadlineTasks() {
        val dbFile = File(app.cacheDir, "migration_7_8_test.db")
        if (dbFile.exists()) dbFile.delete()

        val v7Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE `automation_rules` (
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
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        val writable = v7Helper.writableDatabase
        CuktechDatabase.MIGRATION_7_8.migrate(writable)

        writable.query("SELECT name FROM sqlite_master WHERE type='table' AND name='deadline_tasks'").use { c ->
            assertEquals(1, c.count)
        }
        writable.query("PRAGMA table_info('automation_rules')").use { c ->
            val cols = mutableSetOf<String>()
            while (c.moveToNext()) {
                cols += c.getString(c.getColumnIndexOrThrow("name"))
            }
            assertTrue("priority" in cols)
            assertTrue("targetPhoneSocPct" in cols)
            assertTrue("actionIdleScreenOff" in cols)
        }
        writable.close()
        v7Helper.close()
        dbFile.delete()
    }

    @Test
    fun localAutomationEngineEnforcesStaleBleGatePriorityAndPhoneSocCutoff() {
        val engine = LocalAutomationEngine()
        val ports = mapOf(
            1 to PortTelemetry(1, "USB-C1", enabled = true, voltageV = 20.0, currentA = 5.0, powerW = 100.0),
            2 to PortTelemetry(2, "USB-C2", enabled = true, voltageV = 9.0, currentA = 2.0, powerW = 18.0)
        )
        val settings = ChargerSettingsState(sceneMode = 1)
        val guardP1 = AutomationRuleEntity(
            id = 10L,
            name = "P1 Guard",
            enabled = true,
            triggerType = LocalAutomationEngine.TRIGGER_POWER_GUARD,
            portId = 1,
            thresholdWatts = 90.0,
            holdSeconds = 5,
            actionType = LocalAutomationEngine.ACTION_PORT_OFF,
            priority = 1
        )
        val burstP5 = AutomationRuleEntity(
            id = 20L,
            name = "P5 Burst",
            enabled = true,
            triggerType = LocalAutomationEngine.TRIGGER_HIGH_LOAD_SCENE,
            portId = 1,
            thresholdWatts = 65.0,
            holdSeconds = 5,
            actionType = LocalAutomationEngine.ACTION_SET_SCENE_MODE,
            actionSceneMode = 3,
            priority = 5
        )
        val phoneCutoff = AutomationRuleEntity(
            id = 30L,
            name = "Phone 85%",
            enabled = true,
            triggerType = LocalAutomationEngine.TRIGGER_PHONE_SOC_CUTOFF,
            portId = 2,
            holdSeconds = 10,
            targetPhoneSocPct = 85,
            actionType = LocalAutomationEngine.ACTION_PORT_OFF,
            priority = 2
        )

        val t0 = 1_000_000L
        // 1. Stale telemetry (>12s old) MUST NOT trigger anything
        val staleRes = engine.evaluate(
            rules = listOf(guardP1, burstP5, phoneCutoff),
            ports = ports,
            settings = settings,
            isAuthenticated = true,
            nowMs = t0 + 20_000L,
            lastTelemetryReceivedMs = t0,
            phoneBatterySocPct = 90,
            phoneIsCharging = true
        )
        assertTrue(staleRes.isEmpty())
        assertTrue(engine.lastStaleSkipMs > 0L)

        // 2. Fresh telemetry arms hysteresis timers then fires after 11s
        val arm = engine.evaluate(
            rules = listOf(guardP1, burstP5, phoneCutoff),
            ports = ports,
            settings = settings,
            isAuthenticated = true,
            nowMs = t0 + 21_000L,
            lastTelemetryReceivedMs = t0 + 21_000L,
            phoneBatterySocPct = 90,
            phoneIsCharging = true
        )
        assertTrue(arm.isEmpty())

        val fired = engine.evaluate(
            rules = listOf(guardP1, burstP5, phoneCutoff),
            ports = ports,
            settings = settings,
            isAuthenticated = true,
            nowMs = t0 + 32_000L,
            lastTelemetryReceivedMs = t0 + 32_000L,
            phoneBatterySocPct = 90,
            phoneIsCharging = true
        )
        assertEquals(3, fired.size)
        assertEquals(10L, fired.first().rule.id) // P1 sorted first
        assertTrue(fired.any { it.rule.id == 30L && it.command == ChargerCommand.SetPortEnabled(2, false) })

        // 3. Same-tick conflict on same port: only higher-priority rule wins
        val engine2 = LocalAutomationEngine()
        val p1Off = guardP1.copy(id = 101L, holdSeconds = 2, priority = 1)
        val p9Off = guardP1.copy(id = 102L, holdSeconds = 2, priority = 9)
        engine2.evaluate(listOf(p9Off, p1Off), ports, settings, isAuthenticated = true, nowMs = t0, lastTelemetryReceivedMs = t0)
        val conflictWinners = engine2.evaluate(listOf(p9Off, p1Off), ports, settings, isAuthenticated = true, nowMs = t0 + 3_000L, lastTelemetryReceivedMs = t0 + 3_000L)
        assertEquals(1, conflictWinners.size)
        assertEquals(101L, conflictWinners.first().rule.id)
        assertEquals(1, engine2.lastConflictSuppressedCount)
    }

    @Test
    fun safetyConflictDetectorHistorySimulatorDeadlineQueueAndNfcParserWorkAccurately() {
        // 1. Conflict detection
        val r1 = AutomationRuleEntity(
            id = 1L, name = "Low Cutoff", enabled = true,
            triggerType = LocalAutomationEngine.TRIGGER_LOW_POWER_CUTOFF,
            portId = 1, thresholdWatts = 30.0, holdSeconds = 10, actionType = LocalAutomationEngine.ACTION_PORT_OFF
        )
        val r2 = AutomationRuleEntity(
            id = 2L, name = "High Scene", enabled = true,
            triggerType = LocalAutomationEngine.TRIGGER_HIGH_LOAD_SCENE,
            portId = 1, thresholdWatts = 20.0, holdSeconds = 10, actionType = LocalAutomationEngine.ACTION_SET_SCENE_MODE, actionSceneMode = 4
        )
        val safety = AutomationSafetyAndDeadlineEngine.detectConflictsAndSafety(listOf(r1, r2))
        assertTrue(safety.hasCritical)
        assertTrue(safety.conflicts.any { it.code == "OPPOSING_WATT_BAND" })

        // 2. Historical rule simulator
        val guardRule = AutomationRuleEntity(
            id = 5L, name = "Laptop Burst", enabled = true,
            triggerType = LocalAutomationEngine.TRIGGER_HIGH_LOAD_SCENE,
            portId = 1, thresholdWatts = 60.0, holdSeconds = 4,
            actionType = LocalAutomationEngine.ACTION_SET_SCENE_MODE, actionSceneMode = 4
        )
        val baseTs = 1_700_000_000_000L
        val samples = listOf(
            TelemetrySampleEntity(timestampMs = baseTs, portId = 1, voltageV = 20.0, currentA = 4.0, powerW = 80.0, protocolName = "PD"),
            TelemetrySampleEntity(timestampMs = baseTs + 2_000L, portId = 1, voltageV = 20.0, currentA = 4.0, powerW = 80.0, protocolName = "PD"),
            TelemetrySampleEntity(timestampMs = baseTs + 5_000L, portId = 1, voltageV = 20.0, currentA = 4.0, powerW = 80.0, protocolName = "PD")
        )
        val sim = AutomationSafetyAndDeadlineEngine.simulateRulesOnHistory(listOf(guardRule), samples, initialSceneMode = 1)
        assertEquals(3, sim.evaluatedSamples)
        assertEquals(1, sim.triggerCountsByRuleId[5L])

        // 3. Deadline queue readiness
        val now = 2_000_000_000L
        val tasks = listOf(
            DeadlineTaskEntity(id = 1L, name = "Laptop Flight", portId = 1, targetWh = 60.0, deadlineEpochMs = now + 120 * 60_000L, priority = 1),
            DeadlineTaskEntity(id = 2L, name = "Phone", portId = 2, targetWh = 20.0, deadlineEpochMs = now + 20 * 60_000L, priority = 2),
            DeadlineTaskEntity(id = 3L, name = "Watch", portId = 4, targetWh = 5.0, deadlineEpochMs = now + 60 * 60_000L, priority = 3)
        )
        val ports = mapOf(
            1 to PortTelemetry(1, "USB-C1", enabled = true, powerW = 60.0, cumulativeWh = 0.0), // 60 min ETA vs 120 min => ON_TRACK
            2 to PortTelemetry(2, "USB-C2", enabled = true, powerW = 10.0, cumulativeWh = 0.0), // 120 min ETA vs 20 min => WILL_MISS
            4 to PortTelemetry(4, "USB-A", enabled = true, powerW = 0.2, cumulativeWh = 0.0)    // <0.8W => PAUSED_NO_LOAD
        )
        val readiness = AutomationSafetyAndDeadlineEngine.evaluateDeadlineQueue(tasks, ports, emptyMap(), now)
        assertEquals(DeadlineReadinessStatus.ON_TRACK, readiness[0].status)
        assertEquals(DeadlineReadinessStatus.WILL_MISS, readiness[1].status)
        assertEquals(DeadlineReadinessStatus.PAUSED_NO_LOAD, readiness[2].status)

        // 4. NFC URI parser
        val nfcScene = AutomationSafetyAndDeadlineEngine.parseNfcActionUri("cuktech://action/scene/3")
        assertNotNull(nfcScene)
        assertEquals(ChargerCommand.SetSceneMode(3), nfcScene!!.command)

        val nfcToggle = AutomationSafetyAndDeadlineEngine.parseNfcActionUri("cuktech://action/port/1/toggle", ports)
        assertNotNull(nfcToggle)
        assertEquals(ChargerCommand.SetPortEnabled(1, false), nfcToggle!!.command)

        val nfcTrickle = AutomationSafetyAndDeadlineEngine.parseNfcActionUri("cuktech://action/usb_a_trickle/on")
        assertNotNull(nfcTrickle)
        assertEquals(ChargerCommand.SetUsbALowCurrent(true), nfcTrickle!!.command)

        assertNull(AutomationSafetyAndDeadlineEngine.parseNfcActionUri("https://evil.example/scene/1"))
    }

    @Test
    fun manifestRegistersQuickSettingsTileAndDedicatedPortWidget() {
        val pm = app.packageManager
        val tileInfo = pm.getServiceInfo(
            ComponentName(app, CuktechQuickSettingsTileService::class.java),
            PackageManager.GET_META_DATA
        )
        assertNotNull(tileInfo)
        assertEquals(android.Manifest.permission.BIND_QUICK_SETTINGS_TILE, tileInfo.permission)

        val widgetInfo = pm.getReceiverInfo(
            ComponentName(app, CuktechGlancePortReceiver::class.java),
            PackageManager.GET_META_DATA
        )
        assertNotNull(widgetInfo)
    }

    @Test
    fun settingsHubRendersStage6CardsAndAddsDeadlineTaskViaModalSheet() {
        val viewModel = DashboardViewModel(app)
        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "en") {
                MyApplicationTheme {
                    CuktechAppRoot(
                        viewModel = viewModel,
                        initialDestinationOverride = AppDestination.SETTINGS,
                        requestPermissionsOnLaunch = false,
                        showOnboardingOverride = false
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("automation_safety_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("automation_simulate_btn").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag("automation_sim_result_banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("automation_sim_result_banner").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("deadline_queue_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_deadline_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("add_deadline_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("deadline_name_input").performTextInput("Laptop Before Train")
        composeTestRule.onNodeWithTag("save_deadline_btn").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nfc_quick_actions_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("widget_pin_port_button").performScrollTo().assertIsDisplayed()
    }
}
