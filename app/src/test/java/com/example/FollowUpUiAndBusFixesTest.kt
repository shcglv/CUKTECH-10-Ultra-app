package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.protocol.PortTelemetryDecoder
import com.example.ui.CuktechAppRoot
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.navigation.AppDestination
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DashboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp", sdk = [36])
class FollowUpUiAndBusFixesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        BleBridgeService.ensureCuktechManagersInitialized(app)
    }

    @Test
    fun resolveC3ASharedBusHandlesLowCurrentQuantizationAndExcludesSingleOrFastChargePorts() {
        // Case 1: Both C3 & A on 5V rail, C3 drawing 10W and A showing status=1 with 0.0A/0.0W quantization
        val c3LoadedAStatus1 = mapOf(
            3 to PortTelemetry(3, "USB-C3", voltageV = 5.05, currentA = 1.98, powerW = 10.0, status = 2, enabled = true),
            4 to PortTelemetry(4, "USB-A", voltageV = 5.02, currentA = 0.0, powerW = 0.0, status = 1, enabled = true)
        )
        val bus1 = PortTelemetryDecoder.resolveC3ASharedBus(c3LoadedAStatus1)
        assertTrue(bus1.isSharedActive)
        assertEquals(10.0, bus1.sharedPowerW, 0.05)

        // Case 2: A drawing 7.5W on 5V, C3 plugged on 5V with status=1 and 0.0W
        val aLoadedC3Status1 = mapOf(
            3 to PortTelemetry(3, "USB-C3", voltageV = 5.01, currentA = 0.0, powerW = 0.0, status = 1, enabled = true),
            4 to PortTelemetry(4, "USB-A", voltageV = 5.04, currentA = 1.49, powerW = 7.5, status = 2, enabled = true)
        )
        val bus2 = PortTelemetryDecoder.resolveC3ASharedBus(aLoadedC3Status1)
        assertTrue(bus2.isSharedActive)
        assertEquals(7.5, bus2.sharedPowerW, 0.05)

        // Case 3: Only C3 on 5V (A unplugged at 0V) -> NOT shared
        val singleC3 = mapOf(
            3 to PortTelemetry(3, "USB-C3", voltageV = 5.0, currentA = 1.5, powerW = 7.5, status = 2, enabled = true),
            4 to PortTelemetry(4, "USB-A", voltageV = 0.0, currentA = 0.0, powerW = 0.0, status = 0, enabled = true)
        )
        assertFalse(PortTelemetryDecoder.resolveC3ASharedBus(singleC3).isSharedActive)

        // Case 4: C3 fast charging at 9V/12V -> NOT shared
        val fastC3 = mapOf(
            3 to PortTelemetry(3, "USB-C3", voltageV = 9.0, currentA = 2.5, powerW = 22.5, status = 2, enabled = true),
            4 to PortTelemetry(4, "USB-A", voltageV = 5.0, currentA = 0.5, powerW = 2.5, status = 2, enabled = true)
        )
        assertFalse(PortTelemetryDecoder.resolveC3ASharedBus(fastC3).isSharedActive)
    }

    @Test
    fun portsScreenMergesC3AndAIntoSingleTabAndCombinedDetailCardWhenShared() {
        val viewModel = DashboardViewModel(app)
        val mgr = viewModel.cuktechManager
        mgr.setAuthStateForTest(CuktechAuthState.AUTHENTICATED)
        mgr.setPortsForTest(
            mapOf(
                1 to PortTelemetry(1, "USB-C1", voltageV = 20.0, currentA = 3.0, powerW = 60.0, status = 2, protocolName = "PD3.0 Fixed", enabled = true),
                2 to PortTelemetry(2, "USB-C2", voltageV = 9.0, currentA = 2.0, powerW = 18.0, status = 2, protocolName = "PD3.0 PPS", enabled = true),
                3 to PortTelemetry(3, "USB-C3", voltageV = 5.05, currentA = 1.8, powerW = 9.1, status = 2, protocolName = "BC1.2 DCP", enabled = true),
                4 to PortTelemetry(4, "USB-A", voltageV = 5.02, currentA = 0.0, powerW = 0.0, status = 1, protocolName = "Idle", enabled = true)
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

            composeTestRule.onNodeWithTag("ports_tab_row").assertIsDisplayed()
            composeTestRule.onNodeWithTag("port_tab_1").assertIsDisplayed()
            composeTestRule.onNodeWithTag("port_tab_2").assertIsDisplayed()
            composeTestRule.onNodeWithTag("port_tab_3").assertIsDisplayed()
            composeTestRule.onNodeWithTag("port_tab_4").assertDoesNotExist()

            composeTestRule.onNodeWithTag("port_tab_3").performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("c3a_shared_banner").assertIsDisplayed()
            composeTestRule.onNodeWithTag("port_card_3").assertIsDisplayed()
            composeTestRule.onNodeWithTag("port_card_sub_3").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("port_power_switch_3").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("port_card_sub_4").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("port_power_switch_4").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("usba_low_current_switch").performScrollTo().assertIsDisplayed()
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
    fun settingsHubSplitsBasicAndAdvancedTabsAndBackHandlerReturnsToBasic() {
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

        // Basic tab active by default
        composeTestRule.onNodeWithTag("settings_tab_basic").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_tab_advanced").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_diagnostics_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dynamic_color_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("check_updates_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_continuity_card").assertDoesNotExist()

        // Switch to Advanced tab
        composeTestRule.onNodeWithTag("settings_tab_advanced").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("open_diagnostics_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("settings_continuity_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("saf_backup_export_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("opt_in_tcp_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_author_footer").performScrollTo().assertIsDisplayed()

        // System Back returns from Advanced to Basic tab
        Espresso.pressBack()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("open_diagnostics_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_continuity_card").assertDoesNotExist()
    }

    @Test
    fun russianLocaleRendersOverviewPortsAnalyticsAndSettingsWithoutClippingFailures() {
        val viewModel = DashboardViewModel(app)
        val mgr = viewModel.cuktechManager
        mgr.setAuthStateForTest(CuktechAuthState.AUTHENTICATED)
        mgr.setPortsForTest(
            mapOf(
                1 to PortTelemetry(1, "USB-C1", voltageV = 20.0, currentA = 4.5, powerW = 90.0, status = 2, protocolName = "Xiaomi 120W", enabled = true),
                2 to PortTelemetry(2, "USB-C2", voltageV = 9.0, currentA = 2.0, powerW = 18.0, status = 2, protocolName = "PD3.0 PPS", enabled = true),
                3 to PortTelemetry(3, "USB-C3", voltageV = 5.0, currentA = 1.0, powerW = 5.0, status = 2, protocolName = "BC1.2 DCP", enabled = true),
                4 to PortTelemetry(4, "USB-A", voltageV = 5.0, currentA = 1.0, powerW = 5.0, status = 2, protocolName = "BC1.2 DCP", enabled = true)
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
            composeTestRule.onNodeWithTag("overview_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("overview_hero_card").assertIsDisplayed()
            composeTestRule.onNodeWithTag("overview_port_card_c3a").performScrollTo().assertIsDisplayed()

            composeTestRule.onNodeWithTag("nav_ports").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("ports_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("soft_reset_button").performScrollTo().assertIsDisplayed()

            composeTestRule.onNodeWithTag("nav_analytics").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("analytics_screen").assertIsDisplayed()

            composeTestRule.onNodeWithTag("nav_settings").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
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
}
