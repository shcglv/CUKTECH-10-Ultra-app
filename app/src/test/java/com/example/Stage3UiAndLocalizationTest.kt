package com.example

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandDispatcher
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.automation.HardwareCommandAdapter
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import com.example.ui.CuktechAppRoot
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.navigation.AppDestination
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h915dp", sdk = [36])
class Stage3UiAndLocalizationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    private class FakeUiHardwareAdapter : HardwareCommandAdapter {
        var settings: ChargerSettingsState = ChargerSettingsState(sceneMode = 1, portSwitchBitmap = 0x0F)
        val ports: Map<Int, PortTelemetry> = mapOf(
            1 to PortTelemetry(1, "USB-C1", enabled = true),
            2 to PortTelemetry(2, "USB-C2", enabled = true),
            3 to PortTelemetry(3, "USB-C3", enabled = true),
            4 to PortTelemetry(4, "USB-A", enabled = true)
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
        override fun applyPortChargeLimit(portId: Int, whTarget: Double, mode: String, enabled: Boolean) = Unit
        override fun requestConnect(mac: String?) = Unit
        override fun requestDisconnect(isManual: Boolean) = Unit
        override fun requestScan(durationMs: Long) = Unit
        override fun applyAutoReconnect(enabled: Boolean) = Unit
        override fun applyPortLogging(portId: Int, enabled: Boolean) = Unit
        override fun applyTelemetryMaster(enabled: Boolean) = Unit
        override fun applyLogOnlyWhenPhoneCharging(enabled: Boolean) = Unit
        override fun applyPausePollingWhenLocked(enabled: Boolean) = Unit
        override fun requestRefreshTelemetry() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        BleBridgeService.ensureCuktechManagersInitialized(app)
    }

    @Test
    fun overviewLaunchesByDefaultWithExplicitPortNamesAndZeroDebugConsole() {
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

        composeTestRule.onNodeWithTag("overview_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("overview_hero_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diagnostics_screen").assertDoesNotExist()
        assertTrue(composeTestRule.onAllNodesWithText("CONSOLE", ignoreCase = true).fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("overview_port_card_1").performScrollTo()
        composeTestRule.onNodeWithText("USB-C1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("overview_port_card_2").performScrollTo()
        composeTestRule.onNodeWithText("USB-C2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("overview_port_card_3").performScrollTo()
        composeTestRule.onNodeWithText("USB-C3").assertIsDisplayed()
        composeTestRule.onNodeWithTag("overview_port_card_4").performScrollTo()
        composeTestRule.onNodeWithText("USB-A").assertIsDisplayed()
    }

    @Test
    fun portsScreenShowsStrictPerPortHardwareProtocolsWithoutPdOnC3OrUsbA() {
        val viewModel = DashboardViewModel(app)

        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "en") {
                MyApplicationTheme(useDynamicColor = false) {
                    CuktechAppRoot(
                        viewModel = viewModel,
                        initialDestinationOverride = AppDestination.PORTS,
                        requestPermissionsOnLaunch = false
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("ports_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("port_1_proto_pd").assertExists()
        composeTestRule.onNodeWithTag("port_1_proto_pps").assertExists()
        composeTestRule.onNodeWithTag("port_1_proto_ufcs").assertExists()

        composeTestRule.onNodeWithTag("ports_screen").performScrollToNode(hasTestTag("port_card_3"))
        composeTestRule.onNodeWithTag("port_3_proto_ufcs").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("port_3_proto_scp").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("port_3_proto_pd").assertDoesNotExist()
        composeTestRule.onNodeWithTag("port_3_proto_pps").assertDoesNotExist()

        composeTestRule.onNodeWithTag("ports_screen").performScrollToNode(hasTestTag("port_card_4"))
        composeTestRule.onNodeWithTag("port_4_proto_ufcs").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("port_4_proto_scp").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("usba_low_current_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("port_4_proto_pd").assertDoesNotExist()
        composeTestRule.onNodeWithTag("port_4_proto_pps").assertDoesNotExist()
    }

    @Test
    fun dynamicLocaleSwitchUpdatesUiStringsImmediatelyBetweenEnAndRu() {
        val viewModel = DashboardViewModel(app)
        val langState = mutableStateOf("en")

        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = langState.value) {
                MyApplicationTheme(useDynamicColor = false) {
                    CuktechAppRoot(
                        viewModel = viewModel,
                        initialDestinationOverride = AppDestination.OVERVIEW,
                        requestPermissionsOnLaunch = false
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Overview").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ports").assertIsDisplayed()
        composeTestRule.onNodeWithText("Analytics").assertIsDisplayed()
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()

        langState.value = "ru"
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Обзор").assertIsDisplayed()
        composeTestRule.onNodeWithText("Порты").assertIsDisplayed()
        composeTestRule.onNodeWithText("Аналитика").assertIsDisplayed()
        composeTestRule.onNodeWithText("Настройки").assertIsDisplayed()
        composeTestRule.onNodeWithTag("overview_port_card_1").performScrollTo()
        composeTestRule.onNodeWithText("USB-C1").assertIsDisplayed()
    }

    @Test
    fun analyticsSeparatedMetricsAndInteractiveSessionReplayWorkWithRealTelemetrySamples() {
        val mgr = BleBridgeService.ensureCuktechManagersInitialized(app)
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000.0
        val startSec = nowSec - 60.0

        mgr.persistence.recordPortSnapshot(
            ports = listOf(PortTelemetry(1, "USB-C1", voltageV = 20.0, currentA = 4.5, powerW = 90.0, protocolName = "PD 3.0", cumulativeWh = 0.5)),
            timestampMs = nowMs - 40_000L,
            deviceMac = "AA:BB:CC:11:22:33"
        )
        mgr.persistence.recordPortSnapshot(
            ports = listOf(PortTelemetry(1, "USB-C1", voltageV = 20.0, currentA = 5.0, powerW = 100.0, protocolName = "PD 3.0", cumulativeWh = 1.2)),
            timestampMs = nowMs - 20_000L,
            deviceMac = "AA:BB:CC:11:22:33"
        )
        val sessionId = mgr.persistence.insertCompletedSession(
            ChargeSessionRecord(
                portId = 1,
                portLabel = "USB-C1",
                deviceMac = "AA:BB:CC:11:22:33",
                startTimeSec = startSec,
                endTimeSec = nowSec - 10.0,
                lastSampleTimeSec = nowSec - 10.0,
                activeDurationSec = 50L,
                whAccumulated = 1.2,
                peakWatts = 100.0,
                avgWatts = 95.0,
                protocolUsed = "PD 3.0",
                isActive = false,
                endReason = "UNPLUGGED"
            )
        )
        mgr.reloadSessionsFromDb()
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

        composeTestRule.onNodeWithTag("analytics_chart_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chart_metric_voltage_v").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("chart_metric_current_a").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("chart_metric_cumulative_wh").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("chart_metric_protocols").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("replay_session_button_$sessionId"))
        composeTestRule.onNodeWithTag("replay_session_button_$sessionId").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session_replay_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("replay_sample_readout").assertIsDisplayed()
        composeTestRule.onNodeWithTag("replay_scrubber_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("replay_play_pause_button").performClick()
    }

    @Test
    fun undoSnackbarShowsForUserCommandAndClearsAutomaticallyWhenSafetyCutoffFires() {
        val mgr = BleBridgeService.ensureCuktechManagersInitialized(app)
        val fakeDispatcher = CommandDispatcher(mgr.authManager, FakeUiHardwareAdapter())
        val viewModel = DashboardViewModel(
            application = app,
            cuktechManager = mgr,
            dispatcher = fakeDispatcher
        )

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

        runBlocking {
            fakeDispatcher.dispatch(CommandSource.UI, ChargerCommand.SetPortEnabled(1, false))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Undo Port 1 -> ON").assertIsDisplayed()
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed()

        // Safety cutoff (e.g. WH_LIMIT) must invalidate Undo and dismiss the Snackbar immediately
        fakeDispatcher.notifySafetyCutoffTriggered(1, "WH_LIMIT")
        composeTestRule.waitForIdle()

        assertTrue(composeTestRule.onAllNodesWithText("Undo Port 1 -> ON").fetchSemanticsNodes().isEmpty())
    }
}
