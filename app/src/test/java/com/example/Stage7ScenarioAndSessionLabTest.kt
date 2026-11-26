package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.room.CuktechDatabase
import com.example.cuktech.db.room.IncidentBlackBoxEntity
import com.example.cuktech.db.room.ScenarioObservationEntity
import com.example.cuktech.db.room.SessionAnnotationEntity
import com.example.cuktech.db.room.SessionGapEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.power.BlackBoxRingBuffer
import com.example.cuktech.power.ComparisonAlignmentMode
import com.example.cuktech.power.ScenarioAndPdoEngine
import com.example.cuktech.power.SessionClassification
import com.example.cuktech.power.SessionDeepAnalyzer
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PdoCapabilities
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
class Stage7ScenarioAndSessionLabTest {

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
    fun roomMigration5To6CreatesStage3And4TablesAndIndexes() {
        val dbFile = File(app.cacheDir, "migration_5_6_test.db")
        if (dbFile.exists()) dbFile.delete()

        val v5Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `charger_nodes` (`deviceMac` TEXT NOT NULL, `name` TEXT NOT NULL, `locationContext` TEXT NOT NULL, `mainsVoltageMode` INTEGER NOT NULL, `bridgeEndpointUrl` TEXT NOT NULL, `lastSeenMs` INTEGER NOT NULL, `lastTotalWatts` REAL NOT NULL, `lastTotalWhToday` REAL NOT NULL, `lastRssiDbm` INTEGER NOT NULL, `isOnline` INTEGER NOT NULL, PRIMARY KEY(`deviceMac`))")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        val writable = v5Helper.writableDatabase
        CuktechDatabase.MIGRATION_5_6.migrate(writable)

        writable.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('scenario_observations','session_annotations','incident_black_boxes')"
        ).use { cursor ->
            assertEquals(3, cursor.count)
        }
        writable.close()
        v5Helper.close()
        dbFile.delete()
    }

    @Test
    fun persistenceRecordsScenarioObservationsAnnotationsAndBlackBoxes() {
        val now = System.currentTimeMillis()
        persistence.recordScenarioObservation(
            ScenarioObservationEntity(
                timestampMs = now - 5_000L,
                deviceMac = "AA:BB",
                sceneMode = 2,
                activePortsMask = 0x03,
                c1W = 65.0,
                c2W = 30.0,
                effectiveTotalW = 95.0,
                c3aShared = false
            )
        )
        val obs = persistence.getScenarioObservationsSince(now - 60_000L, 100)
        assertEquals(1, obs.size)
        assertEquals(95.0, obs.first().effectiveTotalW, 0.01)

        val annId = persistence.saveSessionAnnotation(
            SessionAnnotationEntity(
                sessionId = 42L,
                timestampMs = now - 3_000L,
                tagLabel = "Thermal",
                noteText = "Stepped down to 65W",
                powerW = 65.0,
                protocolName = "PD 3.1"
            )
        )
        assertTrue(annId > 0L)
        val anns = persistence.getSessionAnnotationsForSession(42L)
        assertEquals(1, anns.size)
        assertEquals("Thermal", anns.first().tagLabel)
        persistence.deleteSessionAnnotation(annId)
        assertTrue(persistence.getSessionAnnotationsForSession(42L).isEmpty())

        persistence.recordIncidentBlackBox(
            IncidentBlackBoxEntity(
                timestampMs = now,
                deviceMac = "AA:BB",
                portId = 1,
                incidentType = "UNEXPECTED_DROP",
                summary = "Drop while 65W",
                windowJson = """{"incidentType":"UNEXPECTED_DROP"}"""
            )
        )
        val boxes = persistence.getIncidentBlackBoxes(10)
        assertEquals(1, boxes.size)
        assertEquals("UNEXPECTED_DROP", boxes.first().incidentType)
    }

    @Test
    fun scenarioAndPdoEngineExploresHardwareVsObservedPdosSimulatesAndDetectsOscillation() {
        val now = System.currentTimeMillis()
        val ports = mapOf(
            1 to PortTelemetry(
                portId = 1,
                portLabel = "USB-C1",
                enabled = true,
                voltageV = 20.0,
                currentA = 3.2,
                powerW = 64.0,
                protocolName = "PD 3.1",
                pdoCaps = PdoCapabilities(listOf(5000, 9000, 12000, 15000, 20000), ppsMaxVoltageMv = 21000)
            ),
            3 to PortTelemetry(
                portId = 3,
                portLabel = "USB-C3",
                enabled = true,
                voltageV = 9.0,
                currentA = 2.0,
                powerW = 18.0,
                protocolName = "PD"
            )
        )
        val samples = mutableListOf<TelemetrySampleEntity>()
        // Port 1 steady 20V + Port 2 oscillating PD <-> PPS 3 times within 90s
        for (i in 0 until 6) {
            samples += TelemetrySampleEntity(
                timestampMs = now - (6 - i) * 15_000L,
                portId = 1,
                voltageV = 20.0,
                currentA = 3.0,
                powerW = 60.0,
                protocolName = "PD 3.1"
            )
            samples += TelemetrySampleEntity(
                timestampMs = now - (6 - i) * 15_000L,
                portId = 2,
                voltageV = if (i % 2 == 0) 9.0 else 11.0,
                currentA = 3.0,
                powerW = if (i % 2 == 0) 27.0 else 33.0,
                protocolName = if (i % 2 == 0) "PD" else "PPS"
            )
        }
        val snapshot = ScenarioAndPdoEngine.analyze(
            ports = ports,
            settings = ChargerSettingsState(sceneMode = 2),
            samples = samples,
            observations = emptyList()
        )
        val c1Pdo = snapshot.pdoPorts.first { it.portId == 1 }
        val c3Pdo = snapshot.pdoPorts.first { it.portId == 3 }
        assertTrue(c1Pdo.hasHardwarePdo)
        assertFalse(c3Pdo.hasHardwarePdo)
        assertTrue(c1Pdo.observedBands.any { it.label == "20V" && it.matchesDeclaredPdo })
        assertTrue(snapshot.protocolTransitions.any { it.isOscillationAlert })

        // Estimate fallback when history is empty (C3 + A -> shared 15W)
        val estC3A = ScenarioAndPdoEngine.simulateLoad(sceneMode = 1, activeMask = 0x0C, ratedMainsMaxW = 120.0)
        assertFalse(estC3A.basedOnHistory)
        assertEquals("ESTIMATE", estC3A.confidence)
        assertTrue(estC3A.c3aSharedActive)
        assertEquals(15.0, estC3A.estimatedTotalCeilingW, 0.1)

        // Empirical history overrides estimate when >= 3 observations match
        val hist = (1..4).map { idx ->
            ScenarioObservationEntity(
                timestampMs = now - idx * 10_000L,
                sceneMode = 2,
                activePortsMask = 0x03,
                c1W = 88.0,
                c2W = 24.0,
                effectiveTotalW = 112.0
            )
        }
        val empSim = ScenarioAndPdoEngine.simulateLoad(sceneMode = 2, activeMask = 0x03, ratedMainsMaxW = 120.0, observations = hist)
        assertTrue(empSim.basedOnHistory)
        assertEquals("MEDIUM", empSim.confidence)
        assertEquals(112.0, empSim.estimatedTotalCeilingW, 0.1)
    }

    @Test
    fun sessionDeepAnalyzerEnforcesCadenceExplainsDropsComparesAndExportsJson() {
        val t0 = System.currentTimeMillis() - 120_000L
        val session = ChargeSessionRecord(
            id = 10L,
            portId = 1,
            portLabel = "USB-C1",
            startTimeSec = t0 / 1000.0,
            endTimeSec = (t0 + 60_000L) / 1000.0,
            lastSampleTimeSec = (t0 + 60_000L) / 1000.0,
            whAccumulated = 1.2,
            peakWatts = 80.0,
            avgWatts = 60.0,
            activeDurationSec = 60L,
            isActive = false,
            protocolUsed = "PD 3.1"
        )

        // 1. Slow 6s cadence -> cadenceSufficient must be false (no fabricated ramp-up)
        val slowSamples = (0..8).map { i ->
            TelemetrySampleEntity(
                timestampMs = t0 + i * 6_000L,
                portId = 1,
                voltageV = 20.0,
                currentA = 3.0,
                powerW = 20.0 + i * 7.5,
                protocolName = "PD 3.1",
                cumulativeWh = i * 0.1
            )
        }
        val slowAnalysis = SessionDeepAnalyzer.analyzeSession(session, slowSamples)
        assertFalse(slowAnalysis.cadenceSufficient)
        assertEquals(0L, slowAnalysis.rampUpSec)

        // 2. Fast 2s cadence with neighbor port drop at i=6 (80W -> 45W when Port 2 activates)
        val fastPort1 = (0..10).map { i ->
            val w = when {
                i == 0 -> 15.0
                i == 1 -> 45.0
                i in 2..5 -> 80.0
                else -> 45.0
            }
            TelemetrySampleEntity(
                timestampMs = t0 + i * 2_000L,
                portId = 1,
                voltageV = 20.0,
                currentA = w / 20.0,
                powerW = w,
                protocolName = "PD 3.1",
                cumulativeWh = i * 0.12
            )
        }
        val neighborPort2 = listOf(
            TelemetrySampleEntity(
                timestampMs = t0 + 12_000L,
                portId = 2,
                voltageV = 9.0,
                currentA = 3.0,
                powerW = 27.0,
                protocolName = "PPS",
                cumulativeWh = 0.05
            )
        )
        val fastAnalysis = SessionDeepAnalyzer.analyzeSession(
            session = session,
            portSamples = fastPort1,
            allPortSamplesInWindow = fastPort1 + neighborPort2,
            gaps = emptyList(),
            peerSessions = emptyList()
        )
        assertTrue(fastAnalysis.cadenceSufficient)
        assertTrue(fastAnalysis.rampUpSec > 0L)
        assertEquals(SessionClassification.MULTIPORT, fastAnalysis.classification)
        assertTrue(fastAnalysis.dropExplanations.any { it.causeCode == "NEIGHBOR_PORT_ACTIVATED" })

        // 3. Two-session comparison (BY_TIME and BY_WH)
        val sessionB = session.copy(id = 11L, whAccumulated = 0.8, peakWatts = 50.0, avgWatts = 40.0, activeDurationSec = 45L)
        val compTime = SessionDeepAnalyzer.compareSessions(session, fastPort1, sessionB, fastPort1.take(6), ComparisonAlignmentMode.BY_TIME)
        assertEquals(31, compTime.points.size)
        assertEquals(0.4, compTime.deltaWh, 0.01)
        assertEquals(30.0, compTime.deltaPeakW, 0.1)

        val compWh = SessionDeepAnalyzer.compareSessions(session, fastPort1, sessionB, fastPort1.take(6), ComparisonAlignmentMode.BY_WH)
        assertEquals(31, compWh.points.size)

        // 4. BlackBoxRingBuffer + Annotated Replay JSON export
        val ring = BlackBoxRingBuffer(windowMs = 300_000L)
        ring.recordFrame(t0, 64.0, "C1:64W")
        ring.recordEvent(t0 + 1000L, "BLE", "RSSI=-70")
        val bbJson = JSONObject(ring.exportJson("MANUAL", "Test dump"))
        assertEquals(1, bbJson.getJSONArray("frames").length())
        assertEquals(1, bbJson.getJSONArray("events").length())

        val replayJson = JSONObject(
            SessionDeepAnalyzer.exportAnnotatedReplayJson(
                session = session,
                samples = fastPort1,
                gaps = listOf(SessionGapEntity(deviceMac = "AA:BB", gapStartMs = t0, gapEndMs = t0 + 2000L)),
                annotations = listOf(SessionAnnotationEntity(sessionId = 10L, timestampMs = t0 + 4000L, tagLabel = "Peak", powerW = 80.0)),
                deepAnalysis = fastAnalysis
            )
        )
        assertEquals(10L, replayJson.getLong("sessionId"))
        assertEquals(1, replayJson.getJSONArray("annotations").length())
        assertEquals(fastPort1.size, replayJson.getJSONArray("samples").length())
    }

    @Test
    fun analyticsScreenStage3And4TabsAndLoadSimulatorRenderCorrectly() {
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

        // Stage 3: Scenes & PDO tab
        composeTestRule.onNodeWithTag("analytics_tab_scenes_pdo").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("pdo_explorer_card"))
        composeTestRule.onNodeWithTag("pdo_explorer_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("scene_matrix_card"))
        composeTestRule.onNodeWithTag("scene_matrix_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("load_simulator_card"))
        composeTestRule.onNodeWithTag("load_simulator_card").assertIsDisplayed()

        // Interact with Load Simulator port toggles
        composeTestRule.onNodeWithTag("sim_port_3").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sim_port_4").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.loadSimulationResult.value.c3aSharedActive)

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("protocol_transitions_card"))
        composeTestRule.onNodeWithTag("protocol_transitions_card").assertIsDisplayed()

        // Scroll back to top tab bar and open Stage 4: Session Lab tab
        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("analytics_tab_session_lab"))
        composeTestRule.onNodeWithTag("analytics_tab_session_lab").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("session_records_card"))
        composeTestRule.onNodeWithTag("session_records_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("session_compare_card"))
        composeTestRule.onNodeWithTag("session_compare_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("incident_blackbox_card"))
        composeTestRule.onNodeWithTag("incident_blackbox_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("blackbox_capture_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }
}
