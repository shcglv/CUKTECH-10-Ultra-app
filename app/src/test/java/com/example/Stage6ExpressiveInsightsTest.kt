package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.db.PowerChartPoint
import com.example.cuktech.db.room.ChargeSessionEntity
import com.example.cuktech.db.room.CuktechDatabase
import com.example.cuktech.db.room.DailyEnergyAggregateEntity
import com.example.cuktech.energy.ChargeSessionDetector
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.IntegrationStep
import com.example.cuktech.notifications.ChargerEventLog
import com.example.cuktech.notifications.ChargerEventType
import com.example.cuktech.power.ChargerSummaryCalculator
import com.example.cuktech.power.SummaryPeriodMode
import com.example.cuktech.prefs.AppPreferencesRepository
import com.example.ui.CuktechAppRoot
import com.example.ui.components.ExpressiveTelemetryChartCard
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.navigation.AppDestination
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.ChartInterval
import com.example.ui.viewmodel.ChartMetric
import com.example.ui.viewmodel.ChartPointSample
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h915dp", sdk = [36])
class Stage6ExpressiveInsightsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        BleBridgeService.ensureCuktechManagersInitialized(app)
    }

    @Test
    fun chargeSessionDetectorTracksFastBoostAndTaperPhasesHonestly() {
        val detector = ChargeSessionDetector(portId = 1, portLabel = "USB-C1")
        // Build median windows past startup (START_THRESHOLD_W) at 90W fast boost
        var t = 1_000.0
        detector.onSample(90.0, IntegrationStep(0.0, 0.0, 0.0, false), "PD 3.1", t)
        t += 2.0
        detector.onSample(90.0, IntegrationStep(0.05, 2.0, 0.0, false), "PD 3.1", t)
        t += 4.0
        detector.onSample(90.0, IntegrationStep(0.10, 4.0, 0.0, false), "PD 3.1", t)
        t += 4.0
        detector.onSample(90.0, IntegrationStep(0.10, 4.0, 0.0, false), "PD 3.1", t)

        // Taper phase (< 50% of 90W peak = < 45W), feed 12W trickle/taper
        t += 5.0
        detector.onSample(12.0, IntegrationStep(0.02, 5.0, 0.0, false), "PD 3.1", t)
        t += 5.0
        detector.onSample(12.0, IntegrationStep(0.015, 5.0, 0.0, false), "PD 3.1", t)

        val active = detector.activeSession
        assertNotNull(active)
        assertTrue("Expected fast boost seconds > 0, got ${active!!.fastBoostDurationSec}", active.fastBoostDurationSec > 0L)
        assertTrue("Expected taper seconds > 0, got ${active.taperDurationSec}", active.taperDurationSec > 0L)
    }

    @Test
    fun chargerEventLogEvaluatesThresholdsWithHysteresisAndCooldown() {
        val log = ChargerEventLog(
            maxEntries = 30,
            highLoadCooldownMs = 300_000L,
            lowDropConfirmMs = 10_000L
        )
        val t0 = 1_760_000_000_000L

        // Initial high load crosses 100W threshold -> fires
        val fired1 = log.evaluateThresholds(
            enabled = true,
            totalWatts = 108.0,
            highLoadThresholdW = 100.0,
            portPowers = mapOf(1 to 80.0, 2 to 28.0),
            lowDropThresholdW = 1.5,
            nowMs = t0
        )
        assertEquals(1, fired1.size)
        assertEquals(ChargerEventType.HIGH_LOAD_ALERT, fired1.first().type)

        // Still high 10s later -> suppressed by hysteresis & cooldown
        val fired2 = log.evaluateThresholds(
            enabled = true,
            totalWatts = 110.0,
            highLoadThresholdW = 100.0,
            portPowers = mapOf(1 to 80.0, 2 to 30.0),
            lowDropThresholdW = 1.5,
            nowMs = t0 + 10_000L
        )
        assertTrue(fired2.isEmpty())

        // Port 1 drops to 1.0W -> starts confirm window, not yet fired at t0+15s
        val fired3 = log.evaluateThresholds(
            enabled = true,
            totalWatts = 1.0,
            highLoadThresholdW = 100.0,
            portPowers = mapOf(1 to 1.0, 2 to 0.0),
            lowDropThresholdW = 1.5,
            nowMs = t0 + 15_000L
        )
        assertTrue(fired3.isEmpty())

        // Sustained 12s later -> LOW_POWER_DROP_ALERT fires
        val fired4 = log.evaluateThresholds(
            enabled = true,
            totalWatts = 1.0,
            highLoadThresholdW = 100.0,
            portPowers = mapOf(1 to 1.0, 2 to 0.0),
            lowDropThresholdW = 1.5,
            nowMs = t0 + 27_000L
        )
        assertEquals(1, fired4.size)
        assertEquals(ChargerEventType.LOW_POWER_DROP_ALERT, fired4.first().type)
        assertEquals(2, log.unreadCount.value)

        log.markAllRead()
        assertEquals(0, log.unreadCount.value)
        log.clearAll()
        assertEquals(0, log.events.value.size)
    }

    @Test
    fun summaryCalculatorComputesQualityScoreBoostShareAndRecentDailyBuckets() {
        val nowMs = 1_763_635_200_000L
        val nowSec = nowMs / 1000.0
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))

        val s1 = ChargeSessionRecord(
            id = 1L,
            portId = 1,
            portLabel = "USB-C1",
            startTimeSec = nowSec - 1800.0,
            endTimeSec = nowSec - 60.0,
            whAccumulated = 38.5,
            peakWatts = 95.0,
            avgWatts = 62.0,
            protocolUsed = "PD 3.1",
            isActive = false,
            activeDurationSec = 1740L,
            gapDurationSec = 0L,
            endReason = "TRICKLE_COMPLETE",
            fastBoostDurationSec = 1200L,
            taperDurationSec = 540L
        )

        val summary = ChargerSummaryCalculator.computeSummary(
            mode = SummaryPeriodMode.WEEK,
            periodOffset = 0,
            completedSessions = listOf(s1),
            activeSessions = emptyMap(),
            dailyAggregates = emptyList(),
            anomalyCountInPeriod = 0,
            defaultCurrency = "USD",
            nowMs = nowMs
        )
        assertTrue(summary.hasData)
        assertEquals(38.5, summary.totalWh, 0.01)
        assertEquals(1, summary.topPortId)
        assertEquals(69, summary.fastBoostSharePct)
        assertTrue("Expected quality score >= 85, got ${summary.qualityScore}", summary.qualityScore >= 85)

        val buckets = ChargerSummaryCalculator.computeRecentDailyBuckets(
            daysCount = 10,
            completedSessions = listOf(s1),
            activeSessions = emptyMap(),
            dailyAggregates = listOf(
                DailyEnergyAggregateEntity(
                    dateIso = iso,
                    portId = 2,
                    totalWh = 12.0,
                    totalCost = 0.0,
                    currencyCode = "USD",
                    sessionCount = 1,
                    maxPeakWatts = 40.0,
                    totalActiveSec = 600L,
                    totalGapSec = 0L
                )
            ),
            nowMs = nowMs
        )
        assertEquals(10, buckets.size)
        assertEquals(50.5, buckets.last().totalWh, 0.01)
    }

    @Test
    fun roomV4DatabaseAndPreferencesPersistPhaseFieldsThresholdsAndWidgetShape() = runBlocking {
        val db = CuktechDatabase.createInMemory(app)
        try {
            val rowId = db.chargeSessionDao().insertOrReplace(
                ChargeSessionEntity(
                    portId = 1,
                    portLabel = "USB-C1",
                    startTimeSec = 1000.0,
                    endTimeSec = 2000.0,
                    whAccumulated = 25.0,
                    peakWatts = 88.0,
                    avgWatts = 45.0,
                    protocolUsed = "PD",
                    isActive = false,
                    fastBoostDurationSec = 700L,
                    taperDurationSec = 300L
                )
            )
            val loaded = db.chargeSessionDao().getRecentCompleted().first { it.id == rowId }
            assertEquals(700L, loaded.fastBoostDurationSec)
            assertEquals(300L, loaded.taperDurationSec)
        } finally {
            db.close()
        }

        val repo = AppPreferencesRepository(app)
        try {
            repo.updateThresholdAlerts(enabled = true, highLoadWatts = 110.0, lowPowerDropWatts = 2.5)
            assertTrue(repo.preferences.value.thresholdAlertsEnabled)
            assertEquals(110.0, repo.preferences.value.highLoadAlertWatts, 0.01)
            assertEquals(2.5, repo.preferences.value.lowPowerDropAlertWatts, 0.01)
        } finally {
            repo.updateThresholdAlerts(enabled = false, highLoadWatts = 100.0, lowPowerDropWatts = 1.5)
        }
    }

    @Test
    fun topBarEventCenterBellOpensSheetWithEventsAndMarksRead() {
        val mgr = BleBridgeService.ensureCuktechManagersInitialized(app)
        mgr.eventLog.clearAll()
        mgr.eventLog.addEvent(
            type = ChargerEventType.WH_LIMIT_CUTOFF,
            title = "USB-C1 Wh Limit Cutoff",
            detail = "Reached 20.0 Wh target",
            portId = 1
        )
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
        composeTestRule.onNodeWithTag("topbar_event_center_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("event_center_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("event_item_WH_LIMIT_CUTOFF").assertIsDisplayed()
        composeTestRule.onNodeWithTag("event_center_mark_read_btn").performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, mgr.eventLog.unreadCount.value)
    }

    @Test
    fun expressiveChartCardTogglesCurveAndBarsAndRendersStatsRow() {
        val baseMs = System.currentTimeMillis() - 60_000L
        val samples = (0 until 12).map { i ->
            val w = 20.0 + i * 5.0
            ChartPointSample(
                timestampMs = baseMs + i * 5_000L,
                totalW = w,
                portW = mapOf(1 to w * 0.7, 2 to w * 0.3, 3 to 0.0, 4 to 0.0),
                portV = mapOf(1 to 20.0, 2 to 9.0, 3 to 0.0, 4 to 0.0),
                portA = mapOf(1 to (w * 0.7) / 20.0, 2 to (w * 0.3) / 9.0, 3 to 0.0, 4 to 0.0),
                portWh = mapOf(1 to 1.0 + i * 0.2, 2 to 0.4, 3 to 0.0, 4 to 0.0),
                portProtocol = mapOf(1 to "PD 3.1", 2 to "PD", 3 to "Idle", 4 to "Idle")
            )
        }

        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "en") {
                MyApplicationTheme(useDynamicColor = false) {
                    ExpressiveTelemetryChartCard(
                        interval = ChartInterval.MIN_5,
                        metric = ChartMetric.TOTAL_W,
                        selectedMetrics = setOf(ChartMetric.TOTAL_W),
                        samples = samples,
                        onSelectInterval = {},
                        onSelectMetric = {},
                        onToggleMetric = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("chart_min_max_pills").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chart_style_bars").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("chart_stats_row").assertIsDisplayed()
    }

    @Test
    fun analyticsScreenRendersSummaryDailyBarsAnd24hTimeline() {
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
        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("analytics_summary_card"))
        composeTestRule.onNodeWithTag("analytics_summary_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("analytics_daily_bars_card"))
        composeTestRule.onNodeWithTag("analytics_daily_bars_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("analytics_screen")
            .performScrollToNode(hasTestTag("analytics_timeline_24h_card"))
        composeTestRule.onNodeWithTag("analytics_timeline_24h_card").assertIsDisplayed()
    }

    @Test
    fun settingsScreenTogglesThresholdSlidersAndWidgetGallery() {
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

        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            viewModel.preferencesLoaded.value
        }
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()

        if (viewModel.preferences.value.thresholdAlertsEnabled) {
            composeTestRule.onNodeWithTag("threshold_alerts_switch").performScrollTo().assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithTag("threshold_alerts_switch").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            viewModel.preferences.value.thresholdAlertsEnabled
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("threshold_high_load_slider").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("threshold_low_drop_slider").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings_widget_gallery_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("widget_shape_cookie").assertDoesNotExist()
        composeTestRule.onNodeWithTag("widget_shape_classic").assertDoesNotExist()
        composeTestRule.onNodeWithTag("widget_pin_control_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("widget_pin_compact_button").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings_author_footer").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_author_footer").assertTextContains("Joxit", substring = true)
        composeTestRule.onNodeWithTag("settings_author_footer").assertTextContains("com.joxit.cuktech", substring = true)

        composeTestRule.onNodeWithTag("threshold_alerts_switch").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("open_port_manual_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("port_manual_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("port_manual_c3a_shared_card").assertIsDisplayed()
    }
}
