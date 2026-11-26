package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.room.CableInventoryEntity
import com.example.cuktech.db.room.ChargerNodeEntity
import com.example.cuktech.db.room.CuktechDatabase
import com.example.cuktech.db.room.DeviceInventoryEntity
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.power.CableLabEvaluationResult
import com.example.cuktech.power.CableLabSampleInput
import com.example.cuktech.power.InventoryAndCableLabEngine
import com.example.cuktech.power.PhoneBatteryMonitor
import com.example.cuktech.power.ScenarioAndPdoSnapshot
import com.example.cuktech.power.SceneModeEmpiricalStats
import com.example.cuktech.protocol.PortTelemetry
import com.example.ui.CuktechAppRoot
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.navigation.AppDestination
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DashboardViewModel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class Stage8InventoryAndCableLabTest {

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
    fun roomMigration6To7CreatesStage5TablesAndIndexes() {
        val dbFile = File(app.cacheDir, "migration_6_7_test.db")
        if (dbFile.exists()) dbFile.delete()

        val v6Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `charger_nodes` (`deviceMac` TEXT NOT NULL, `name` TEXT NOT NULL, `locationContext` TEXT NOT NULL, `mainsVoltageMode` INTEGER NOT NULL, `bridgeEndpointUrl` TEXT NOT NULL, `lastSeenMs` INTEGER NOT NULL, `lastTotalWatts` REAL NOT NULL, `lastTotalWhToday` REAL NOT NULL, `lastRssiDbm` INTEGER NOT NULL, `isOnline` INTEGER NOT NULL, PRIMARY KEY(`deviceMac`))")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        val writable = v6Helper.writableDatabase
        CuktechDatabase.MIGRATION_6_7.migrate(writable)

        writable.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('device_inventory','cable_inventory','cable_lab_tests')"
        ).use { cursor ->
            assertEquals(3, cursor.count)
        }
        writable.close()
        v6Helper.close()
        dbFile.delete()
    }

    @Test
    fun persistenceCrudAndAtomicCableLabTestUpdate() {
        val devId = persistence.saveDeviceInventory(
            DeviceInventoryEntity(
                name = "Work Laptop",
                category = "LAPTOP",
                ratedWh = 72.0,
                ratedMaxW = 100.0,
                preferredProtocol = "PD",
                assignedPortId = 1
            )
        )
        assertTrue(devId > 0L)
        assertEquals(1, persistence.getDeviceInventory().size)

        val cableId = persistence.saveCableInventory(
            CableInventoryEntity(
                name = "Cuktech 6A Braided",
                connectorType = "C_TO_C",
                ratedAmps = 6,
                lengthCm = 150,
                hasEmarker = true
            )
        )
        assertTrue(cableId > 0L)
        assertEquals(1, persistence.getCableInventory().size)

        val valid = CableLabEvaluationResult.Valid(
            portId = 1,
            cableId = cableId,
            cableName = "Cuktech 6A Braided",
            nominalRailV = 20.0,
            avgVoltageV = 19.7,
            avgCurrentA = 3.0,
            sampleCount = 12,
            sagMv = 300,
            estimatedMohm = 100,
            uncertaintyMohm = 38,
            confidence = "HIGH"
        )
        val testId = persistence.recordCableLabTest(valid.toEntity())
        assertTrue(testId > 0L)

        val updatedCable = persistence.getCableInventory().first()
        assertEquals(100, updatedCable.lastMeasuredMohm)
        assertEquals(38, updatedCable.lastUncertaintyMohm)
        assertEquals(300, updatedCable.lastSagMv)
        assertEquals(1, updatedCable.testCount)

        persistence.deleteCableLabTest(testId)
        assertTrue(persistence.getCableLabTests().isEmpty())
        persistence.deleteCableInventory(cableId)
        assertTrue(persistence.getCableInventory().isEmpty())
        persistence.deleteDeviceInventory(devId)
        assertTrue(persistence.getDeviceInventory().isEmpty())
    }

    @Test
    fun inventoryAndCableLabEngineValidatesFixedRailsRejectsPpsAndAnonymizesJson() {
        // 1. PPS rejection
        val ppsSamples = List(10) { CableLabSampleInput(voltageV = 9.4, currentA = 3.0, protocolName = "PPS") }
        val ppsRes = InventoryAndCableLabEngine.evaluateCableLabSamples(1, 0L, "Test", ppsSamples)
        assertEquals("PPS_UNSUPPORTED", (ppsRes as CableLabEvaluationResult.Insufficient).reasonCode)

        // 2. Low current rejection
        val lowCurrentSamples = List(10) { CableLabSampleInput(voltageV = 20.0, currentA = 0.8, protocolName = "PD 3.0") }
        val lowRes = InventoryAndCableLabEngine.evaluateCableLabSamples(1, 0L, "Test", lowCurrentSamples)
        assertEquals("LOW_CURRENT", (lowRes as CableLabEvaluationResult.Insufficient).reasonCode)

        // 3. Too few samples rejection
        val fewSamples = List(4) { CableLabSampleInput(voltageV = 20.0, currentA = 2.5, protocolName = "PD 3.0") }
        val fewRes = InventoryAndCableLabEngine.evaluateCableLabSamples(1, 0L, "Test", fewSamples)
        assertEquals("TOO_FEW_SAMPLES", (fewRes as CableLabEvaluationResult.Insufficient).reasonCode)

        // 4. Valid fixed-rail evaluation (10 samples @ 19.7V, 2.5A => sag=300mV, R=120mOhm)
        val validSamples = List(10) { CableLabSampleInput(voltageV = 19.7, currentA = 2.5, protocolName = "PD 3.1") }
        val validRes = InventoryAndCableLabEngine.evaluateCableLabSamples(1, 1L, "Braided 1.5m", validSamples)
        assertTrue(validRes is CableLabEvaluationResult.Valid)
        val ok = validRes as CableLabEvaluationResult.Valid
        assertEquals(20.0, ok.nominalRailV, 0.01)
        assertEquals(300, ok.sagMv)
        assertEquals(120, ok.estimatedMohm)
        assertTrue(ok.uncertaintyMohm > 0)
        assertEquals("HIGH", ok.confidence)

        // 5. Smart Device Match suggestion
        val devices = listOf(
            DeviceInventoryEntity(id = 1L, name = "ThinkPad X1", category = "LAPTOP", ratedWh = 57.0, ratedMaxW = 65.0, preferredProtocol = "PD", assignedPortId = 1),
            DeviceInventoryEntity(id = 2L, name = "Earbuds", category = "AUDIO", ratedWh = 2.0, ratedMaxW = 5.0, preferredProtocol = "5V", assignedPortId = 4)
        )
        val session = ChargeSessionRecord(
            id = 55L,
            portId = 1,
            portLabel = "USB-C1",
            deviceMac = "AA:BB:CC:11:22:33",
            startTimeSec = 1000.0,
            endTimeSec = 2800.0,
            lastSampleTimeSec = 2800.0,
            activeDurationSec = 1800L,
            isActive = false,
            whAccumulated = 28.0,
            peakWatts = 64.0,
            avgWatts = 55.0,
            protocolUsed = "PD 3.0"
        )
        val match = InventoryAndCableLabEngine.suggestMatchingDevice(session, devices)
        assertNotNull(match)
        assertEquals("ThinkPad X1", match!!.device.name)
        assertTrue(match.matchConfidencePct >= 80)

        // 6. Charger Passport & Anonymized JSON Export strips PII/MAC/device names
        val node = ChargerNodeEntity(
            deviceMac = "AA:BB:CC:11:22:33",
            name = "My Private Desk Charger",
            locationContext = "Bedroom Desk",
            mainsVoltageMode = 220
        )
        val passport = InventoryAndCableLabEngine.buildChargerPassport(node, listOf(session), listOf(ok.toEntity()))
        val jsonStr = InventoryAndCableLabEngine.exportAnonymizedLabJson(
            passport = passport,
            cableTests = listOf(ok.toEntity()),
            cables = listOf(CableInventoryEntity(id = 1L, name = "Secret Cable Name", connectorType = "C_TO_C", ratedAmps = 5, lengthCm = 150)),
            scenarioSnapshot = ScenarioAndPdoSnapshot(
                sceneStats = listOf(
                    SceneModeEmpiricalStats(
                        sceneMode = 1,
                        sampleCount = 10,
                        avgTotalW = 60.0,
                        peakTotalW = 65.0,
                        typicalCombinationLabel = "C1",
                        c3aSharedCount = 0
                    )
                )
            )
        )
        assertFalse(jsonStr.contains("AA:BB:CC:11:22:33"))
        assertFalse(jsonStr.contains("My Private Desk Charger"))
        assertFalse(jsonStr.contains("Bedroom Desk"))
        assertFalse(jsonStr.contains("Secret Cable Name"))
        val parsed = JSONObject(jsonStr)
        assertEquals("cuktech10_ultra_anonymized_lab_v1", parsed.getString("schema"))
        assertEquals(1, parsed.getJSONArray("cableLoopMeasurements").length())
        assertEquals(1, parsed.getJSONArray("scenarioSummary").length())
    }

    @Test
    fun phoneBatteryMonitorReadsSnapshotSafelyUnderRobolectric() {
        val snap = PhoneBatteryMonitor.readSnapshot(
            context = app,
            activeChargerPorts = mapOf(
                1 to PortTelemetry(portId = 1, portLabel = "USB-C1", enabled = true, voltageV = 9.0, currentA = 2.0, powerW = 18.0, protocolName = "PD")
            )
        )
        assertTrue(snap.socPct in 0..100)
        assertTrue(snap.updatedAtMs > 0L)
    }

    @Test
    fun analyticsScreenInventoryAndLabTabRendersAndInteracts() {
        val viewModel = DashboardViewModel(app)

        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "en") {
                MyApplicationTheme(useDynamicColor = false) {
                    CuktechAppRoot(
                        viewModel = viewModel,
                        initialDestinationOverride = AppDestination.ANALYTICS,
                        requestPermissionsOnLaunch = false
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("analytics_tab_inventory_lab").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("phone_battery_card"))
        composeTestRule.onNodeWithTag("phone_battery_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("device_inventory_card"))
        composeTestRule.onNodeWithTag("device_inventory_card").assertIsDisplayed()

        // Add device through dialog
        composeTestRule.onNodeWithTag("add_device_inventory_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_device_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("device_name_input").performTextInput("Pixel 9 Pro")
        composeTestRule.onNodeWithTag("save_device_btn").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("cable_lab_card"))
        composeTestRule.onNodeWithTag("cable_lab_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("cable_lab_analyze_recent_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("charger_passport_card"))
        composeTestRule.onNodeWithTag("charger_passport_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("export_anonymized_lab_btn").performScrollTo().assertIsDisplayed()
    }
}
