package com.example

import android.app.Application
import android.content.Context
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
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandAuditListener
import com.example.cuktech.automation.CommandDispatcher
import com.example.cuktech.automation.CommandResult
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.automation.HardwareCommandAdapter
import com.example.cuktech.bridge.BridgeSyncClient
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.room.CuktechDatabase
import com.example.cuktech.db.room.SessionGapEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.power.PortLifecycleAnalyzer
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.security.AutomationAuthManager
import com.example.cuktech.security.KeystoreKeyProvider
import com.example.cuktech.security.SecureCredentialStore
import com.example.cuktech.telemetry.DisconnectRiskLevel
import com.example.cuktech.telemetry.TelemetryIntegrityEngine
import com.example.ui.CuktechAppRoot
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.navigation.AppDestination
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.test.runTest
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
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h915dp", sdk = [36])
class Stage6HybridLifecycleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var db: CuktechDatabase
    private lateinit var persistence: CuktechPersistence

    private class InMemoryKeyProvider : KeystoreKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun getOrCreateKey(alias: String): SecretKey = keys.getOrPut(alias) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun deleteKey(alias: String) { keys.remove(alias) }
    }

    private class StubHardwareAdapter : HardwareCommandAdapter {
        var settings: ChargerSettingsState = ChargerSettingsState(sceneMode = 1, portSwitchBitmap = 0x0F)
        var ports: Map<Int, PortTelemetry> = mapOf(
            1 to PortTelemetry(portId = 1, portLabel = "USB-C1", enabled = true),
            2 to PortTelemetry(portId = 2, portLabel = "USB-C2", enabled = true),
            3 to PortTelemetry(portId = 3, portLabel = "USB-C3", enabled = true),
            4 to PortTelemetry(portId = 4, portLabel = "USB-A", enabled = true)
        )
        override fun isAuthenticated(): Boolean = true
        override fun currentSettings(): ChargerSettingsState = settings
        override fun currentPorts(): Map<Int, PortTelemetry> = ports
        override fun currentChargeLimits(): Map<Int, PortChargeLimit> = emptyMap()
        override fun activeSessionsSnapshot(): Map<Int, ChargeSessionRecord> = emptyMap()
        override suspend fun writePortBitmap(newBitmap: Int): Boolean {
            settings = settings.copy(portSwitchBitmap = newBitmap)
            return true
        }
        override suspend fun writeSceneMode(mode: Int): Boolean = true
        override suspend fun writeScreenTimeout(timeoutCode: Int): Boolean = true
        override suspend fun writePortCountdown(portId: Int, minutes: Int): Boolean = true
        override suspend fun writeUsbALowCurrent(enabled: Boolean): Boolean = true
        override suspend fun writeIdleScreenOff(enabled: Boolean): Boolean = true
        override suspend fun writeLanguage(langCode: Int): Boolean = true
        override suspend fun writeOrientationLock(locked: Boolean): Boolean = true
        override suspend fun writeGotoScreen(pageIndex: Int): Boolean = true
        override suspend fun writeRotateScreen180(): Boolean = true
        override suspend fun writePortProtocol(portId: Int, protocol: String, enabled: Boolean): Boolean = true
        override suspend fun writeFastChargeProtocols(pd: Boolean, pps: Boolean, ufcs: Boolean, scp: Boolean): Boolean = true
        override suspend fun executeSoftReset(): Boolean = true
        override fun applyPortChargeLimit(portId: Int, whTarget: Double, mode: String, enabled: Boolean) {}
        override fun requestConnect(mac: String?) {}
        override fun requestDisconnect(isManual: Boolean) {}
        override fun requestScan(durationMs: Long) {}
        override fun applyAutoReconnect(enabled: Boolean) {}
        override fun applyPortLogging(portId: Int, enabled: Boolean) {}
        override fun applyTelemetryMaster(enabled: Boolean) {}
        override fun applyLogOnlyWhenPhoneCharging(enabled: Boolean) {}
        override fun applyPausePollingWhenLocked(enabled: Boolean) {}
        override fun requestRefreshTelemetry() {}
        override fun finalizeSessionForPortWithReason(portId: Int, reason: String) {}
    }

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
    fun bridgeBackfillMergesWithoutDuplicatingDirectBleAndMarksSessionGap() {
        val mac = "AA:BB:CC:DD:EE:01"
        val baseTs = System.currentTimeMillis() - 120_000L

        // Direct BLE sample at baseTs
        persistence.recordPortSnapshot(
            ports = listOf(
                PortTelemetry(portId = 1, portLabel = "USB-C1", enabled = true, voltageV = 20.0, currentA = 3.0, powerW = 60.0, protocolName = "PD 3.1")
            ),
            timestampMs = baseTs,
            deviceMac = mac,
            sourceMode = "BLE_DIRECT",
            rssiDbm = -64
        )

        // Unbackfilled blind gap while away from charger
        persistence.recordSessionGap(
            SessionGapEntity(
                deviceMac = mac,
                portId = 0,
                gapStartMs = baseTs + 10_000L,
                gapEndMs = baseTs + 70_000L,
                reason = "BLE_OUT_OF_RANGE",
                wasBackfilledByBridge = false,
                backfilledSamplesCount = 0
            )
        )

        // Bridge payload contains 1 overlapping point (+800ms <= 1500ms) + 2 genuinely missing gap points
        val bridgeSamples = listOf(
            TelemetrySampleEntity(timestampMs = baseTs + 800L, portId = 1, voltageV = 20.0, currentA = 3.0, powerW = 60.0, protocolName = "PD 3.1", deviceMac = mac),
            TelemetrySampleEntity(timestampMs = baseTs + 25_000L, portId = 1, voltageV = 20.0, currentA = 3.2, powerW = 64.0, protocolName = "PD 3.1", deviceMac = mac),
            TelemetrySampleEntity(timestampMs = baseTs + 45_000L, portId = 1, voltageV = 20.0, currentA = 3.1, powerW = 62.0, protocolName = "PD 3.1", deviceMac = mac)
        )

        val mergedCount = persistence.mergeBridgeBackfillSamples(mac, bridgeSamples)
        assertEquals(2, mergedCount)

        val stored = persistence.getTelemetrySamplesSince(baseTs - 5_000L, 100)
        assertEquals(3, stored.size)
        assertEquals(1, stored.count { it.sourceMode == "BLE_DIRECT" })
        assertEquals(2, stored.count { it.sourceMode == "BRIDGE_SYNC" })

        val gaps = persistence.getSessionGapsSince(baseTs - 5_000L, 50)
        assertEquals(1, gaps.size)
        assertTrue(gaps.first().wasBackfilledByBridge)
        assertEquals(2, gaps.first().backfilledSamplesCount)
    }

    @Test
    fun roomMigration4To5AddsNewColumnsAndTablesCleanly() {
        val dbFile = File(app.cacheDir, "migration_4_5_test.db")
        if (dbFile.exists()) dbFile.delete()

        val v4Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE `telemetry_samples` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `timestampMs` INTEGER NOT NULL,
                                `portId` INTEGER NOT NULL,
                                `voltageV` REAL NOT NULL,
                                `currentA` REAL NOT NULL,
                                `powerW` REAL NOT NULL,
                                `protocolName` TEXT NOT NULL,
                                `cumulativeWh` REAL NOT NULL,
                                `deviceMac` TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("INSERT INTO `telemetry_samples` (`timestampMs`,`portId`,`voltageV`,`currentA`,`powerW`,`protocolName`,`cumulativeWh`,`deviceMac`) VALUES (1760000000000,1,20.0,2.5,50.0,'PD',1.0,'AA:BB')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        val writable = v4Helper.writableDatabase
        CuktechDatabase.MIGRATION_4_5.migrate(writable)

        writable.query("SELECT sourceMode, rssiDbm FROM telemetry_samples").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("BLE_DIRECT", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        writable.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('session_gaps','command_audit_log','ble_link_events','charger_nodes')").use { c ->
            assertEquals(4, c.count)
        }
        writable.close()
        v4Helper.close()
        dbFile.delete()
    }

    @Test
    fun bridgeSyncClientValidatesUrlsAndParsesFlexibleJsonPayloads() {
        assertTrue(BridgeSyncClient.isValidBridgeUrl("http://192.168.1.50:8787"))
        assertTrue(BridgeSyncClient.isValidBridgeUrl("https://bridge.home.arpa"))
        assertFalse(BridgeSyncClient.isValidBridgeUrl(""))
        assertFalse(BridgeSyncClient.isValidBridgeUrl("ftp://192.168.1.50"))

        val json = """
            {
              "samples": [
                {"ts": 1760000010, "portId": 1, "voltageV": 20.0, "currentA": 3.0, "protocol": "PD 3.1"},
                {"ts_ms": 1760000020000, "port_id": 2, "v": 9.0, "a": 2.0, "w": 18.0, "protocolName": "PPS"},
                {"ts_ms": 1760000030000, "port_id": 9, "w": 10.0}
              ]
            }
        """.trimIndent()
        val parsed = BridgeSyncClient.parseBridgeSamplesJson(json, defaultMac = "11:22:33:44:55:66")
        assertEquals(2, parsed.size)
        assertEquals(1760000010_000L, parsed[0].timestampMs)
        assertEquals(60.0, parsed[0].powerW, 0.01)
        assertEquals("BRIDGE_SYNC", parsed[0].sourceMode)
        assertEquals("11:22:33:44:55:66", parsed[0].deviceMac)
        assertEquals(2, parsed[1].portId)
        assertEquals("PPS", parsed[1].protocolName)
    }

    @Test
    fun telemetryIntegrityEngineRequiresTwoWeakRssiReadsAndTracksBlindGapUptime() {
        val engine = TelemetryIntegrityEngine()
        val t0 = 1_760_000_000_000L

        engine.onTelemetrySampleReceived(t0)
        engine.onTelemetrySampleReceived(t0 + 2_000L)
        engine.onTelemetrySampleReceived(t0 + 4_500L)
        engine.onRssiSample(-88, t0 + 4_500L)

        // First weak RSSI should NOT trigger alert yet (debounce requires 2 consecutive samples)
        val risk1 = engine.evaluateBleExitRisk(
            rssiDbm = -88,
            totalWatts = 65.0,
            hasActiveWhLimit = false,
            thresholdDbm = -86,
            enabled = true,
            nowMs = t0 + 4_500L
        )
        assertFalse(risk1.shouldAlert)
        assertEquals(DisconnectRiskLevel.HIGH, risk1.riskLevel)

        // Second consecutive weak RSSI under load -> triggers alert
        val risk2 = engine.evaluateBleExitRisk(
            rssiDbm = -90,
            totalWatts = 65.0,
            hasActiveWhLimit = false,
            thresholdDbm = -86,
            enabled = true,
            nowMs = t0 + 6_500L
        )
        assertTrue(risk2.shouldAlert)

        // Immediate third read inside cooldown -> suppressed
        val risk3 = engine.evaluateBleExitRisk(
            rssiDbm = -91,
            totalWatts = 65.0,
            hasActiveWhLimit = false,
            thresholdDbm = -86,
            enabled = true,
            nowMs = t0 + 10_000L
        )
        assertFalse(risk3.shouldAlert)

        val blindGap = SessionGapEntity(
            deviceMac = "AA:BB",
            gapStartMs = t0 - 3_600_000L, // 60 min blind gap inside 24h (1440 min)
            gapEndMs = t0,
            wasBackfilledByBridge = false
        )
        val snap = engine.buildSnapshot(
            nowMs = t0 + 5_000L,
            isAuthenticated = true,
            gapsLast24h = listOf(blindGap),
            directSamples24h = 200,
            bridgeSamples24h = 0,
            rssiThresholdDbm = -86
        )
        assertEquals(1, snap.openBlindGapsCount)
        assertTrue("Expected ~95.8% uptime, got ${snap.uptimePct24h}", snap.uptimePct24h in 95.0..96.5)

        val steps = engine.buildConnectionWizardSteps(
            hasBtPerms = true,
            btEnabled = true,
            discoveredCount = 1,
            hasActiveCred = true,
            authStateName = "AUTHENTICATED"
        )
        assertEquals(6, steps.size)
        assertTrue(steps.all { it.passed })
    }

    @Test
    fun portLifecycleAnalyzerEnforcesBleGapSafeDisconnectVerificationAndHonestProtocols() {
        val t0 = 1_760_000_000_000L
        val samples = mutableListOf<TelemetrySampleEntity>()

        // Build continuous 5s telemetry on Port 1 (Exact PIID 17) for 3 minutes at 65W + 25W on Port 3 (Heuristic)
        for (i in 0..36) {
            val ts = t0 + i * 5_000L
            samples += TelemetrySampleEntity(
                timestampMs = ts,
                portId = 1,
                voltageV = 20.0,
                currentA = 3.25,
                powerW = 65.0,
                protocolName = "PD 3.1",
                deviceMac = "AA:BB",
                sourceMode = "BLE_DIRECT"
            )
            samples += TelemetrySampleEntity(
                timestampMs = ts,
                portId = 3,
                voltageV = 9.0,
                currentA = 2.7,
                powerW = 24.3,
                protocolName = "PD",
                deviceMac = "AA:BB",
                sourceMode = "BLE_DIRECT"
            )
        }

        val confirmedDropSec = (t0 + 180_000L) / 1000.0
        val gapOverlapDropSec = (t0 + 500_000L) / 1000.0
        val sessions = listOf(
            // Real verified drop while BLE cadence was continuous (<= 10s)
            ChargeSessionRecord(
                id = 1L,
                portId = 1,
                portLabel = "USB-C1",
                startTimeSec = (t0 / 1000.0),
                endTimeSec = confirmedDropSec,
                lastSampleTimeSec = confirmedDropSec,
                activeDurationSec = 180L,
                whAccumulated = 3.25,
                peakWatts = 65.0,
                avgWatts = 65.0,
                protocolUsed = "PD 3.1",
                isActive = false,
                deviceMac = "AA:BB",
                endReason = "UNEXPECTED_DROP"
            ),
            // Drop ending right inside a BLE out-of-range gap -> MUST NOT count as unexpected disconnect
            ChargeSessionRecord(
                id = 2L,
                portId = 1,
                portLabel = "USB-C1",
                startTimeSec = (t0 + 300_000L) / 1000.0,
                endTimeSec = gapOverlapDropSec,
                lastSampleTimeSec = gapOverlapDropSec,
                activeDurationSec = 100L,
                whAccumulated = 1.5,
                peakWatts = 55.0,
                avgWatts = 55.0,
                protocolUsed = "PD 3.1",
                isActive = false,
                deviceMac = "AA:BB",
                endReason = "UNEXPECTED_DROP"
            )
        )
        val gaps = listOf(
            SessionGapEntity(
                deviceMac = "AA:BB",
                gapStartMs = (t0 + 480_000L),
                gapEndMs = (t0 + 520_000L),
                wasBackfilledByBridge = false
            )
        )

        val result = PortLifecycleAnalyzer.analyze(
            samples = samples,
            sessions = sessions,
            gaps = gaps,
            windowStartMs = t0 - 60_000L,
            windowEndMs = t0 + 600_000L,
            deviceMacFilter = "AA:BB"
        )

        val port1 = result.portStats.first { it.portId == 1 }
        assertEquals(2, port1.connectionCount)
        assertEquals("Only the drop during continuous BLE cadence should count", 1, port1.unexpectedDisconnectCount)
        assertTrue(result.simultaneousMultiport.minutes2Ports > 2.5)
        assertTrue(result.plateausCount >= 1)
        assertTrue(result.peakHoldMinutes >= 2.5)
        assertEquals(7, result.powerBands.size)

        val pd31 = result.protocolStats.first { it.protocolName == "PD 3.1" }
        val pdHeuristic = result.protocolStats.first { it.protocolName == "PD" }
        assertTrue(pd31.exactHardwareCount > 0)
        assertEquals(0, pd31.heuristicCount)
        assertTrue(pdHeuristic.heuristicCount > 0)
        assertEquals(0, pdHeuristic.exactHardwareCount)
    }

    @Test
    fun commandDispatcherInvokesAuditListenerForUiAndBridgeSources() = runTest {
        val credStore = SecureCredentialStore(app, InMemoryKeyProvider())
        val authManager = AutomationAuthManager(app, credStore)
        val audited = mutableListOf<Triple<CommandSource, String, Int>>()
        val dispatcher = CommandDispatcher(
            authManager = authManager,
            adapter = StubHardwareAdapter(),
            auditListener = CommandAuditListener { _, source, commandType, targetPortId, _, accepted, _, _ ->
                if (accepted) audited += Triple(source, commandType, targetPortId)
            }
        )

        val resUi = dispatcher.dispatch(CommandSource.UI, ChargerCommand.SetPortEnabled(2, false))
        assertTrue(resUi is CommandResult.Executed)

        val resBridge = dispatcher.dispatch(CommandSource.BRIDGE, ChargerCommand.SetSceneMode(3))
        assertTrue(resBridge is CommandResult.Executed)

        assertEquals(2, audited.size)
        assertEquals(Triple(CommandSource.UI, "SetPortEnabled", 2), audited[0])
        assertEquals(Triple(CommandSource.BRIDGE, "SetSceneMode", 0), audited[1])
    }

    @Test
    fun overviewAnalyticsDiagnosticsAndSettingsRenderStage6HybridFeatures() {
        val viewModel = DashboardViewModel(app)

        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "en") {
                MyApplicationTheme(useDynamicColor = false) {
                    CuktechAppRoot(
                        viewModel = viewModel,
                        initialDestinationOverride = AppDestination.OVERVIEW,
                        requestPermissionsOnLaunch = false
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // 1. Overview multi-charger strip & integrity chip + sheets
        composeTestRule.onNodeWithTag("overview_integrity_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("overview_multi_charger_chip").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("multi_charger_hub_sheet").assertIsDisplayed()
    }

    @Test
    fun settingsContinuitySectionDisplaysBridgeAndExitControls() {
        val viewModel = DashboardViewModel(app)

        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "en") {
                MyApplicationTheme(useDynamicColor = false) {
                    CuktechAppRoot(
                        viewModel = viewModel,
                        initialDestinationOverride = AppDestination.SETTINGS,
                        requestPermissionsOnLaunch = false
                    )
                }
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { viewModel.preferencesLoaded.value }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bridge_sync_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("bridge_url_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("ble_exit_alert_switch").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun integrityRadarCardAndAnalyticsSubTabsRenderCorrectly() {
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

        // Switch to Ports Lifecycle tab
        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("analytics_tab_lifecycle"))
        composeTestRule.onNodeWithTag("analytics_tab_lifecycle").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("analytics_window_7d").assertIsDisplayed()
        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("lifecycle_ports_card"))
        composeTestRule.onNodeWithTag("lifecycle_ports_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("lifecycle_heatmap_card"))
        composeTestRule.onNodeWithTag("lifecycle_heatmap_card").assertIsDisplayed()

        // Switch to Power & Protocols tab (scroll back up to tabs first after scrolling to heatmap)
        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("analytics_tab_power_protocols"))
        composeTestRule.onNodeWithTag("analytics_tab_power_protocols").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("power_bands_card"))
        composeTestRule.onNodeWithTag("power_bands_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("power_protocols_card"))
        composeTestRule.onNodeWithTag("power_protocols_card").assertIsDisplayed()
    }

    @Test
    fun standaloneIntegrityRadarAndDiagnosticsAndSettingsContinuityRender() {
        val viewModel = DashboardViewModel(app)

        // Verify Diagnostics Screen Wizard, Radar, and Audit cards
        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "en") {
                MyApplicationTheme(useDynamicColor = false) {
                    CuktechAppRoot(
                        viewModel = viewModel,
                        initialDestinationOverride = AppDestination.DIAGNOSTICS,
                        requestPermissionsOnLaunch = false
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("diag_wizard_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diagnostics_screen")
            .performScrollToNode(hasTestTag("integrity_radar_card"))
        composeTestRule.onNodeWithTag("integrity_radar_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diagnostics_screen")
            .performScrollToNode(hasTestTag("diag_audit_card"))
        composeTestRule.onNodeWithTag("diag_audit_card").assertIsDisplayed()
    }
}
