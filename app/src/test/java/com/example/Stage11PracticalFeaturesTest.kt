package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.bridge.BridgeScriptGenerator
import com.example.cuktech.bridge.WifiContextMonitor
import com.example.cuktech.bridge.WifiContextSnapshot
import com.example.cuktech.bridge.WifiContextStatus
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.room.CuktechDatabase
import com.example.cuktech.notifications.CableAnomalyDetector
import com.example.cuktech.notifications.ChargerEventLog
import com.example.cuktech.notifications.ChargerEventType
import com.example.cuktech.prefs.AppPreferences
import com.example.cuktech.prefs.AppPreferencesRepository
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.telemetry.AwayDeltaSummary
import com.example.cuktech.telemetry.AwayPortDelta
import com.example.cuktech.telemetry.AwayPortDeltaType
import com.example.cuktech.telemetry.computeAwayDeltaSummary
import com.example.ui.CuktechAppRoot
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.navigation.AppDestination
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h915dp", sdk = [36])
class Stage11PracticalFeaturesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        BleBridgeService.ensureCuktechManagersInitialized(app)
    }

    @Test
    fun wifiContextMonitorEvaluatesSignaturesPausePolicyAndLeaveLoadAlerts() {
        val (homeKey, homeLabel) = WifiContextMonitor.computeNetworkSignature(
            gatewayOrDnsIp = "192.168.1.1",
            localIpv4 = "192.168.1.42",
            prefixLength = 24,
            rawSsid = "\"Studio-5G\""
        )
        assertTrue(homeKey.startsWith("wifi:"))
        assertTrue(homeLabel.contains("Studio-5G"))

        val (anonKey, anonLabel) = WifiContextMonitor.computeNetworkSignature(
            gatewayOrDnsIp = "192.168.10.1",
            localIpv4 = "192.168.10.25",
            prefixLength = 24,
            rawSsid = "<unknown ssid>"
        )
        assertNotEquals(homeKey, anonKey)
        assertFalse(anonLabel.contains("unknown", ignoreCase = true))
        assertTrue(anonLabel.contains("192.168.10.0/24"))

        assertEquals(
            WifiContextStatus.UNKNOWN,
            WifiContextMonitor.evaluateWifiStatus(isOnWifi = true, currentKey = homeKey, boundKey = homeKey, wifiContextEnabled = false)
        )
        assertEquals(
            WifiContextStatus.NO_WIFI,
            WifiContextMonitor.evaluateWifiStatus(isOnWifi = false, currentKey = "", boundKey = homeKey, wifiContextEnabled = true)
        )
        assertEquals(
            WifiContextStatus.BOUND_HOME_WIFI,
            WifiContextMonitor.evaluateWifiStatus(isOnWifi = true, currentKey = homeKey, boundKey = homeKey, wifiContextEnabled = true)
        )
        assertEquals(
            WifiContextStatus.OTHER_WIFI,
            WifiContextMonitor.evaluateWifiStatus(isOnWifi = true, currentKey = anonKey, boundKey = homeKey, wifiContextEnabled = true)
        )

        val prefs = AppPreferences(
            wifiContextEnabled = true,
            boundWifiKey = homeKey,
            boundWifiLabel = homeLabel,
            wifiBleOnlyAtHome = true,
            wifiLeaveLoadAlertEnabled = true,
            wifiLeaveLoadThresholdWatts = 15.0
        )

        assertFalse(
            WifiContextMonitor.shouldPauseBleReconnect(
                prefs,
                WifiContextSnapshot(isOnWifi = true, currentKey = homeKey, status = WifiContextStatus.BOUND_HOME_WIFI)
            )
        )
        assertTrue(
            WifiContextMonitor.shouldPauseBleReconnect(
                prefs,
                WifiContextSnapshot(isOnWifi = false, currentKey = "", status = WifiContextStatus.NO_WIFI)
            )
        )

        // Transition BOUND_HOME_WIFI -> NO_WIFI with 45W load 5s ago -> triggers alert
        assertTrue(
            WifiContextMonitor.shouldTriggerLeaveHomeLoadAlert(
                prevStatus = WifiContextStatus.BOUND_HOME_WIFI,
                nextStatus = WifiContextStatus.NO_WIFI,
                lastKnownLoadW = 45.0,
                lastKnownLoadAgeMs = 5_000L,
                prefs = prefs
            )
        )
        // Below threshold -> false
        assertFalse(
            WifiContextMonitor.shouldTriggerLeaveHomeLoadAlert(
                prevStatus = WifiContextStatus.BOUND_HOME_WIFI,
                nextStatus = WifiContextStatus.NO_WIFI,
                lastKnownLoadW = 8.0,
                lastKnownLoadAgeMs = 5_000L,
                prefs = prefs
            )
        )
        // Stale telemetry (>10 min) -> false
        assertFalse(
            WifiContextMonitor.shouldTriggerLeaveHomeLoadAlert(
                prevStatus = WifiContextStatus.BOUND_HOME_WIFI,
                nextStatus = WifiContextStatus.NO_WIFI,
                lastKnownLoadW = 60.0,
                lastKnownLoadAgeMs = 12 * 60_000L,
                prefs = prefs
            )
        )
    }

    @Test
    fun computeAwayDeltaSummaryDetectsStoppedStartedAndActivePorts() {
        val pre = mapOf(
            1 to PortTelemetry(1, "USB-C1", powerW = 65.0),
            2 to PortTelemetry(2, "USB-C2", powerW = 0.0),
            3 to PortTelemetry(3, "USB-C3", powerW = 15.0),
            4 to PortTelemetry(4, "USB-A", powerW = 0.0)
        )
        val post = mapOf(
            1 to PortTelemetry(1, "USB-C1", powerW = 0.0),
            2 to PortTelemetry(2, "USB-C2", powerW = 22.5),
            3 to PortTelemetry(3, "USB-C3", powerW = 14.0),
            4 to PortTelemetry(4, "USB-A", powerW = 0.0)
        )

        // Short disconnects < 60s are suppressed
        assertNull(computeAwayDeltaSummary(pre, post, awayDurationSec = 45L, bridgeSamplesSynced = 0))

        val summary = computeAwayDeltaSummary(pre, post, awayDurationSec = 420L, bridgeSamplesSynced = 18)
        assertNotNull(summary)
        assertEquals(3, summary!!.deltas.size)
        assertEquals(18, summary.bridgeSamplesSynced)
        assertEquals(AwayPortDeltaType.STOPPED, summary.deltas.first { it.portId == 1 }.deltaType)
        assertEquals(AwayPortDeltaType.STARTED, summary.deltas.first { it.portId == 2 }.deltaType)
        assertEquals(AwayPortDeltaType.STILL_ACTIVE, summary.deltas.first { it.portId == 3 }.deltaType)
    }

    @Test
    fun bridgeScriptGeneratorMatchesAppContractEndpointsAndMdns() {
        val script = BridgeScriptGenerator.generateCompanionScript(
            chargerMac = "aa:bb:cc:11:22:33",
            port = 8787,
            serviceName = "CUKTECH-Pi-Bridge"
        )
        assertTrue(script.contains("AA:BB:CC:11:22:33"))
        assertTrue(script.contains("BleakScanner"))
        assertTrue(script.contains("/api/v1/status"))
        assertTrue(script.contains("/api/v1/ble"))
        assertTrue(script.contains("/api/v1/telemetry/export"))
        assertTrue(script.contains("/api/v1/telemetry/ingest"))
        assertTrue(script.contains("/api/v1/command"))
        assertTrue(script.contains("/api/v1/command/ack"))
        assertTrue(script.contains("_http._tcp.local."))
    }

    @Test
    fun csvHistoryImporterAndPersistenceDeduplicateAcrossReimports() {
        val db = CuktechDatabase.createInMemory(app)
        val persistence = CuktechPersistence(app, db, runLegacyMigration = false)
        try {
            val csv = """
                # CUKTECH 10 Ultra Telemetry Export
                # Charging Sessions
                session_id,port,start_time,end_time,active_duration_sec,gap_duration_sec,fast_boost_sec,taper_sec,energy_wh,peak_watts,avg_watts,protocol,end_reason,cost,currency
                1,USB-C1,2026-10-01 10:00:00,2026-10-01 10:45:00,2700,0,1200,900,32.500,98.40,43.33,PD3.0 PPS,NORMAL_STOP,0.0120,USD
                2,USB-C2,2026-10-02 14:10:00,2026-10-02 14:30:00,1200,0,600,300,8.125,30.00,24.38,PD3.0 Fixed,NORMAL_STOP,0.0040,USD

                # Raw Telemetry Samples
                timestamp,port_id,voltage_v,current_a,power_w,protocol,cumulative_wh
                2026-10-01 10:05:00,1,20.000,4.500,90.00,PD3.0 PPS,5.200
                2026-10-02 14:15:00,2,9.000,3.000,27.00,PD3.0 Fixed,2.100
            """.trimIndent()

            val first = persistence.importSessionsFromCsv(csv, "AA:BB:CC:DD:EE:FF")
            assertEquals(2, first.importedSessions)
            assertEquals(0, first.skippedDuplicateSessions)
            assertEquals(2, first.importedSamples)

            val second = persistence.importSessionsFromCsv(csv, "AA:BB:CC:DD:EE:FF")
            assertEquals(0, second.importedSessions)
            assertEquals(2, second.skippedDuplicateSessions)
            assertEquals(0, second.importedSamples)
        } finally {
            db.close()
        }
    }

    @Test
    fun overviewScreenDisplaysAndDismissesAwaySummaryCard() {
        val viewModel = DashboardViewModel(app)
        val mgr = viewModel.cuktechManager
        mgr.setAwaySummaryForTest(
            AwayDeltaSummary(
                awayDurationSec = 900L,
                deltas = listOf(
                    AwayPortDelta(1, "USB-C1", 65.0, 0.0, AwayPortDeltaType.STOPPED),
                    AwayPortDelta(2, "USB-C2", 0.0, 18.0, AwayPortDeltaType.STARTED)
                ),
                bridgeSamplesSynced = 12
            )
        )

        try {
            composeTestRule.setContent {
                LocalizedAppContent(appLanguage = "ru") {
                    MyApplicationTheme(useDynamicColor = false) {
                        CuktechAppRoot(
                            viewModel = viewModel,
                            initialDestinationOverride = AppDestination.OVERVIEW,
                            requestPermissionsOnLaunch = false,
                            showOnboardingOverride = false
                        )
                    }
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("overview_away_summary_card")
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onNodeWithTag("away_summary_dismiss_btn")
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("overview_away_summary_card")
                .assertDoesNotExist()
        } finally {
            mgr.dismissAwaySummary()
        }
    }

    @Test
    fun portsScreenShowsQuickOffTimerChipsWhenConnected() {
        val viewModel = DashboardViewModel(app)
        val mgr = viewModel.cuktechManager
        mgr.setAuthStateForTest(CuktechAuthState.AUTHENTICATED)
        mgr.setPortsForTest(
            mapOf(
                1 to PortTelemetry(1, "USB-C1", voltageV = 20.0, currentA = 2.0, powerW = 40.0, status = 2, enabled = true, countdownCode = 30),
                2 to PortTelemetry(2, "USB-C2"),
                3 to PortTelemetry(3, "USB-C3"),
                4 to PortTelemetry(4, "USB-A")
            )
        )

        try {
            composeTestRule.setContent {
                LocalizedAppContent(appLanguage = "ru") {
                    MyApplicationTheme(useDynamicColor = false) {
                        CuktechAppRoot(
                            viewModel = viewModel,
                            initialDestinationOverride = AppDestination.PORTS,
                            requestPermissionsOnLaunch = false,
                            showOnboardingOverride = false
                        )
                    }
                }
            }
            composeTestRule.waitForIdle()

            listOf("port_quick_timer_1_15", "port_quick_timer_1_30", "port_quick_timer_1_60", "port_quick_timer_1_120", "port_quick_timer_1_off")
                .forEach { tag ->
                    composeTestRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                }
        } finally {
            mgr.setPortsForTest(
                mapOf(
                    1 to PortTelemetry(1, "USB-C1"),
                    2 to PortTelemetry(2, "USB-C2"),
                    3 to PortTelemetry(3, "USB-C3"),
                    4 to PortTelemetry(4, "USB-A")
                )
            )
            mgr.setAuthStateForTest(CuktechAuthState.DISCONNECTED)
        }
    }

    @Test
    fun analyticsScreenShowsWrappedTabsBackHandlerCustomizeCardsAndImportCsv() {
        val viewModel = DashboardViewModel(app)
        runBlocking {
            AppPreferencesRepository(app).updateHiddenAnalyticsCards(emptySet())
        }

        try {
            composeTestRule.setContent {
                LocalizedAppContent(appLanguage = "ru") {
                    MyApplicationTheme(useDynamicColor = false) {
                        CuktechAppRoot(
                            viewModel = viewModel,
                            initialDestinationOverride = AppDestination.ANALYTICS,
                            requestPermissionsOnLaunch = false,
                            showOnboardingOverride = false
                        )
                    }
                }
            }
            composeTestRule.waitForIdle()

            // Wrapped tabs all immediately rendered without horizontal scroll
            composeTestRule.onNodeWithTag("analytics_tabs_container").assertIsDisplayed()
            composeTestRule.onNodeWithTag("analytics_tab_summary").assertIsDisplayed()
            composeTestRule.onNodeWithTag("analytics_tab_lifecycle").assertIsDisplayed()
            composeTestRule.onNodeWithTag("analytics_tab_scenes_pdo").assertIsDisplayed()
            composeTestRule.onNodeWithTag("analytics_tab_inventory_lab").assertIsDisplayed()

            // Switch to Lifecycle tab, verify BackHandler returns to Summary
            composeTestRule.onNodeWithTag("analytics_tab_lifecycle").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("analytics_window_1d").assertIsDisplayed()

            Espresso.pressBack()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("analytics_screen")
                .performScrollToNode(hasTestTag("analytics_import_csv_button"))
            composeTestRule.onNodeWithTag("analytics_import_csv_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("analytics_customize_cards_btn").assertIsDisplayed()

            // Hide 10-day energy bars card via Customize Cards sheet
            composeTestRule.onNodeWithTag("analytics_customize_cards_btn").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("analytics_customize_sheet").assertIsDisplayed()
            composeTestRule.onNodeWithTag("analytics_card_toggle_${AppPreferencesRepository.CARD_DAILY_BARS}")
                .performClick()
            composeTestRule.waitForIdle()

            Espresso.pressBack()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("analytics_daily_bars_card").assertDoesNotExist()
        } finally {
            runBlocking {
                AppPreferencesRepository(app).updateHiddenAnalyticsCards(emptySet())
            }
        }
    }

    @Test
    fun settingsHubAdvancedTabOpensBridgeScriptSheetAndShowsWifiContext() {
        val viewModel = DashboardViewModel(app)

        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "ru") {
                MyApplicationTheme(useDynamicColor = false) {
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

        composeTestRule.onNodeWithTag("settings_tab_advanced").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("bridge_script_btn").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("bridge_companion_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bridge_script_copy_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bridge_script_save_btn").assertIsDisplayed()

        Espresso.pressBack()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_wifi_context_section").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("wifi_context_status_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("wifi_bind_current_btn").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("wifi_ble_only_home_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("wifi_leave_alert_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("wifi_leave_threshold_15").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anomalyDetectorIgnoresSteadyVoltageEndOfChargeTopOffPulses() {
        val detector = CableAnomalyDetector()
        var t = 10_000L
        // Simulate 100% battery BMS top-off pulsing (18W -> 0W at steady 20V PD) 9 times
        repeat(9) {
            assertNull(detector.onPortSample(1, "USB-C1", 20.0, 18.0, "PD3.0 Fixed", true, t))
            assertNull(detector.onPortSample(1, "USB-C1", 20.0, 0.0, "PD3.0 Fixed", true, t + 1_000L))
            t += 5_000L
        }
        assertTrue(detector.alerts.value.isEmpty())
    }

    @Test
    fun chargerEventLogPersistsEventsAcrossRestart() {
        val prefs = app.getSharedPreferences("test_event_log_persist", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val log1 = ChargerEventLog(storagePrefs = prefs)
            log1.addEvent(
                type = ChargerEventType.ANOMALY,
                title = "USB-C1: сбой",
                detail = "USB-C1: 3× резких обрывов нагрузки (≥25 Вт → 0 Вт)",
                portId = 1,
                timestampMs = 1_760_000_000_000L
            )
            assertEquals(1, log1.events.value.size)

            val log2 = ChargerEventLog(storagePrefs = prefs)
            assertEquals(1, log2.events.value.size)
            assertEquals(ChargerEventType.ANOMALY, log2.events.value.first().type)
            assertEquals("USB-C1: сбой", log2.events.value.first().title)
            assertEquals(1, log2.unreadCount.value)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun anomalyDetectorSuppressesAfterCompletionWhenEnabledAndFiresWhenDisabled() {
        val detector = CableAnomalyDetector()
        detector.markPortCompleted(portId = 1, nowMs = 10_000L)

        // 3 abrupt >=25W -> 0W drops during 10-min post-completion suppression window
        var t = 20_000L
        repeat(3) {
            assertNull(detector.onPortSample(1, "USB-C1", 20.0, 32.0, "PD3.0 PPS", true, t, suppressAfterCompletion = true))
            assertNull(detector.onPortSample(1, "USB-C1", 20.0, 0.0, "PD3.0 PPS", true, t + 1_000L, suppressAfterCompletion = true))
            t += 5_000L
        }
        assertTrue(detector.alerts.value.isEmpty())

        // Same collapses with suppressAfterCompletion = false trigger POWER_COLLAPSE_LOOP
        var fired: com.example.cuktech.notifications.AnomalyAlert? = null
        repeat(3) {
            detector.onPortSample(1, "USB-C1", 20.0, 32.0, "PD3.0 PPS", true, t, suppressAfterCompletion = false)
            val res = detector.onPortSample(1, "USB-C1", 20.0, 0.0, "PD3.0 PPS", true, t + 1_000L, suppressAfterCompletion = false)
            if (res != null) fired = res
            t += 5_000L
        }
        assertNotNull(fired)
        assertEquals(com.example.cuktech.notifications.AnomalyType.POWER_COLLAPSE_LOOP, fired!!.type)
    }

    @Test
    fun settingsHubNotificationControlsAndEventCenterShortcutWork() {
        val viewModel = DashboardViewModel(app)
        val openEventCenterState = androidx.compose.runtime.mutableStateOf(false)

        try {
            composeTestRule.setContent {
                LocalizedAppContent(appLanguage = "ru") {
                    MyApplicationTheme(useDynamicColor = false) {
                        CuktechAppRoot(
                            viewModel = viewModel,
                            pendingOpenEventCenter = openEventCenterState,
                            initialDestinationOverride = AppDestination.SETTINGS,
                            requestPermissionsOnLaunch = false,
                            showOnboardingOverride = false
                        )
                    }
                }
            }
            composeTestRule.waitForIdle()

            listOf(
                "live_updates_switch",
                "completion_alerts_switch",
                "anomaly_alerts_switch",
                "anomaly_suppress_completion_switch",
                "notif_ble_exit_switch",
                "notif_wifi_leave_switch",
                "threshold_alerts_switch",
                "quiet_hours_switch",
                "notif_system_channels_btn",
                "notif_open_event_center_btn"
            ).forEach { tag ->
                composeTestRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            }

            // Enable threshold alerts and verify system push toggle appears
            composeTestRule.onNodeWithTag("threshold_alerts_switch").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("threshold_push_switch").performScrollTo().assertIsDisplayed()

            // Open Event Center from Settings shortcut
            composeTestRule.onNodeWithTag("notif_open_event_center_btn").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("event_center_sheet").assertIsDisplayed()

            Espresso.pressBack()
            composeTestRule.waitForIdle()

            // Verify notification deep-link flag opens Event Center sheet
            openEventCenterState.value = true
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("event_center_sheet").assertIsDisplayed()
        } finally {
            runBlocking {
                AppPreferencesRepository(app).updateThresholdAlerts(false, 105.0, 1.5)
            }
        }
    }
}
