package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.bridge.BridgeDiscoveryHelper
import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.db.room.BleLinkEventEntity
import com.example.cuktech.db.room.CableLabTestEntity
import com.example.cuktech.db.room.ChargerNodeEntity
import com.example.cuktech.db.room.ChargingProfileEntity
import com.example.cuktech.db.room.CommandAuditEntity
import com.example.cuktech.db.room.DailyEnergyAggregateEntity
import com.example.cuktech.db.room.PortDevicePresetEntity
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.integrations.CloudIntegrations
import com.example.cuktech.integrations.MqttBridgeClient
import com.example.cuktech.integrations.MqttInboundCommand
import com.example.cuktech.notifications.AnomalyAlert
import com.example.cuktech.notifications.AnomalyType
import com.example.cuktech.power.CableCurrentClass
import com.example.cuktech.power.CableHealthRating
import com.example.cuktech.power.CustomKpiAndWorkspaceEngine
import com.example.cuktech.power.CustomKpiDefinition
import com.example.cuktech.power.KpiFormulaType
import com.example.cuktech.power.MultiChargerFleetEngine
import com.example.cuktech.power.PortCableDiagnostic
import com.example.cuktech.power.PowerDoctorEngine
import com.example.cuktech.power.PowerDoctorGrade
import com.example.cuktech.power.WorkspacePreset
import com.example.cuktech.prefs.AppPreferences
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.security.BackupPayload
import com.example.cuktech.security.ConfigShareAndSupportBundle
import com.example.cuktech.security.CuktechDeviceCredentials
import com.example.cuktech.security.SafEncryptedBackupManager
import com.example.cuktech.security.WebDavBackupClient
import com.example.cuktech.security.WebDavConfig
import com.example.cuktech.telemetry.TelemetryIntegritySnapshot
import com.example.ui.CuktechAppRoot
import com.example.ui.components.MultiChargerFleetCard
import com.example.ui.components.PowerDoctorCard
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.navigation.AppDestination
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.CommandWearSummary
import com.example.ui.viewmodel.DashboardViewModel
import org.json.JSONArray
import org.json.JSONObject
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h915dp", sdk = [36])
class Stage10EcosystemFleetAndDoctorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        BleBridgeService.ensureCuktechManagersInitialized(app)
    }

    @Test
    fun mqttWireCodecDiscoveryAndInboundCommandParsingWorkAccurately() {
        val parsed = MqttBridgeClient.parseBrokerUri("mqtts://ha.local:8883")
        assertNotNull(parsed)
        assertEquals("ha.local", parsed!!.host)
        assertEquals(8883, parsed.port)
        assertTrue(parsed.useTls)
        assertNull(MqttBridgeClient.parseBrokerUri("http://invalid.local"))

        val lenBytes = MqttBridgeClient.encodeRemainingLength(321)
        val decodedLen = MqttBridgeClient.decodeRemainingLength(ByteArrayInputStream(lenBytes))
        assertEquals(321, decodedLen)

        val connectPkt = MqttBridgeClient.encodeConnectPacket(
            clientId = "cuktech_test",
            username = "u1",
            password = "p1",
            willTopic = "cuktech10/status",
            willPayload = "offline"
        )
        assertEquals(0x10, connectPkt[0].toInt() and 0xFF)

        val pubPkt = MqttBridgeClient.encodePublishPacket("cuktech10/cmd/port/2/set", "OFF", retain = false)
        assertEquals(0x30, pubPkt[0].toInt() and 0xFF)
        val incoming = MqttBridgeClient.parseIncomingPublishPacket(
            remainingBytes = pubPkt.copyOfRange(2, pubPkt.size)
        )
        assertNotNull(incoming)
        assertEquals("cuktech10/cmd/port/2/set", incoming!!.first)
        assertEquals("OFF", incoming.second)

        val cmdPort = MqttBridgeClient.parseInboundCommand("cuktech10", incoming.first, incoming.second)
        assertEquals(MqttInboundCommand.SetPortEnabled(2, false), cmdPort)

        val cmdScene = MqttBridgeClient.parseInboundCommand("cuktech10", "cuktech10/cmd/scene/set", "{\"mode\":3}")
        assertEquals(MqttInboundCommand.SetSceneMode(3), cmdScene)

        val discovery = MqttBridgeClient.buildHomeAssistantDiscoveryPayloads(
            baseTopic = "cuktech10",
            discoveryPrefix = "homeassistant",
            nodeId = "cuktech_aabbcc",
            deviceName = "Desk CUKTECH"
        )
        assertEquals(14, discovery.size)
        assertTrue(discovery.any { it.first.startsWith("homeassistant/sensor/cuktech_aabbcc/") })
        assertTrue(discovery.any { it.first.startsWith("homeassistant/switch/cuktech_aabbcc/port_1/") })
    }

    @Test
    fun bridgeDiscoveryWebDavAndEventWebhooksValidateAndProtectSecrets() {
        assertEquals(
            "http://192.168.1.50:8787",
            BridgeDiscoveryHelper.buildDiscoveredBaseUrl("192.168.1.50", 8787)
        )
        assertTrue(BridgeDiscoveryHelper.isLikelyBridgeService("CUKTECH-Pi-Bridge", "_http._tcp."))
        assertFalse(BridgeDiscoveryHelper.isLikelyBridgeService("LivingRoomTV", "_http._tcp."))

        val eventPayload = CloudIntegrations.buildEventPayload(
            eventType = "PORT_CONNECTED",
            title = "Port 1 on 11:22:33:44:55:66",
            detail = "Bearer super_secret_token_99",
            portId = 1
        ).toString()
        assertFalse(eventPayload.contains("11:22:33:44:55:66"))
        assertFalse(eventPayload.contains("super_secret_token_99"))

        val cfg = WebDavConfig(baseUrl = "http://nas.local/dav", username = "u")
        assertTrue(cfg.isPlaintextHttpWarning)
        assertEquals(
            "https://cloud.example/dav/cuktech10_cloud.cukbackup",
            WebDavBackupClient.buildBackupFileUrl("https://cloud.example/dav/", "cuktech10_cloud.cukbackup")
        )

        val samplePayload = BackupPayload(
            createdAtMs = 1_700_000_000_000L,
            includesSecrets = false,
            preferencesJson = JSONObject().put("themeMode", "DARK"),
            sessionsArray = JSONArray(),
            dailyAggregatesArray = JSONArray(),
            chargeLimitsArray = JSONArray(),
            aliasesArray = JSONArray(),
            profilesArray = JSONArray()
        )
        val baos = ByteArrayOutputStream()
        SafEncryptedBackupManager.encryptToStream(samplePayload, "Pass123!".toCharArray(), baos)
        val restored = SafEncryptedBackupManager.decryptFromStream(
            ByteArrayInputStream(baos.toByteArray()),
            "Pass123!".toCharArray()
        )
        assertEquals("DARK", restored.preferencesJson.optString("themeMode"))
    }

    @Test
    fun multiChargerFleetEngineAggregatesAndCompares110VVs220VNodes() {
        val now = 1_700_000_000_000L
        val creds = listOf(
            CuktechDeviceCredentials("1", "Desk 220V", "ad1204", "AA:BB:CC:DD:EE:01", "tok1", "key1", "cn"),
            CuktechDeviceCredentials("2", "Travel 110V", "ad1204", "AA:BB:CC:DD:EE:02", "tok2", "key2", "us")
        )
        val nodes = listOf(
            ChargerNodeEntity("AA:BB:CC:DD:EE:01", "Desk 220V", "Office", mainsVoltageMode = 220, isOnline = true, lastTotalWatts = 65.0, lastTotalWhToday = 42.0, lastSeenMs = now),
            ChargerNodeEntity("AA:BB:CC:DD:EE:02", "Travel 110V", "Travel", mainsVoltageMode = 110, isOnline = false, lastTotalWatts = 0.0, lastTotalWhToday = 15.0, lastSeenMs = now - 300_000L)
        )
        val completed = listOf(
            ChargeSessionRecord(id = 1L, portId = 1, portLabel = "USB-C1", deviceMac = "AA:BB:CC:DD:EE:01", startTimeSec = (now / 1000.0) - 600, whAccumulated = 20.0, computedCost = 0.01),
            ChargeSessionRecord(id = 2L, portId = 1, portLabel = "USB-C1", deviceMac = "AA:BB:CC:DD:EE:02", startTimeSec = (now / 1000.0) - 900, whAccumulated = 15.0, computedCost = 0.01)
        )
        val aggs = listOf(
            DailyEnergyAggregateEntity(dateIso = "2026-11-25", portId = 1, deviceMac = "AA:BB:CC:DD:EE:01", totalWh = 80.0, sessionCount = 3)
        )
        val livePorts = mapOf(1 to PortTelemetry(1, "USB-C1", enabled = true, voltageV = 20.0, currentA = 4.0, powerW = 80.0))

        val fleet = MultiChargerFleetEngine.buildFleetSummary(
            savedDevices = creds,
            chargerNodes = nodes,
            completedSessions = completed,
            activeSessions = emptyMap(),
            dailyAggregates = aggs,
            activeMac = "AA:BB:CC:DD:EE:01",
            activeLivePorts = livePorts,
            isActiveOnline = true,
            currencyCode = "USD",
            nowMs = now
        )
        assertEquals(2, fleet.nodesCount)
        assertEquals(1, fleet.onlineCount)
        assertEquals(80.0, fleet.totalLiveWatts, 0.01)
        assertTrue(fleet.fleetTotalWh >= 115.0)

        val cmp = MultiChargerFleetEngine.compareNodes(fleet.nodes[0], fleet.nodes[1])
        assertNotNull(cmp)
        assertEquals(10.0, cmp!!.deltaRatedMaxW, 0.01) // 120W (220V) - 110W (110V)
    }

    @Test
    fun powerDoctorEvaluatesWeightedScoreAndWorkspaceKpiValidatesUnits() {
        val nominal = PowerDoctorEngine.evaluate(
            integrity = TelemetryIntegritySnapshot(uptimePct24h = 100.0, currentRssiDbm = -55),
            cableDiagnostics = emptyMap(),
            cableLabTests = emptyList(),
            anomalyAlerts = emptyList(),
            wearSummary = CommandWearSummary(totalExecuted = 10, totalRejected = 0)
        )
        assertTrue(nominal.overallScore >= 95)
        assertEquals(PowerDoctorGrade.EXCELLENT, nominal.grade)

        val degraded = PowerDoctorEngine.evaluate(
            integrity = TelemetryIntegritySnapshot(uptimePct24h = 70.0, openBlindGapsCount = 3, totalBlindMinutes24h = 40.0, currentRssiDbm = -93, reconnectCount24h = 6),
            cableDiagnostics = mapOf(
                1 to PortCableDiagnostic(
                    portId = 1,
                    portLabel = "USB-C1",
                    isActive = true,
                    isVariablePps = false,
                    nominalRailV = 20.0,
                    measuredVoltageV = 19.05,
                    measuredCurrentA = 3.0,
                    voltageSagMv = 950,
                    estimatedResistanceMohm = 316,
                    peakObservedCurrentA = 3.0,
                    peakObservedWatts = 57.15,
                    currentClass = CableCurrentClass.STANDARD_3A,
                    renegotiationAlertCount = 2,
                    healthRating = CableHealthRating.UNSTABLE,
                    sampleCount = 12,
                    updatedAtMs = 1_700_000_000_000L
                )
            ),
            cableLabTests = listOf(CableLabTestEntity(portId = 1, cableName = "Bad", estimatedMohm = 620, uncertaintyMohm = 30)),
            anomalyAlerts = listOf(
                AnomalyAlert(
                    id = "over",
                    type = AnomalyType.POWER_COLLAPSE_LOOP,
                    portId = 1,
                    portLabel = "USB-C1",
                    countInWindow = 3,
                    summary = "Drop",
                    detectedAtMs = 1_700_000_000_000L
                )
            ),
            wearSummary = CommandWearSummary(totalExecuted = 10, totalRejected = 5)
        )
        assertTrue(degraded.overallScore < 65)
        assertTrue(degraded.findings.size >= 4)

        assertEquals(
            "kpi_err_percent_required",
            CustomKpiAndWorkspaceEngine.validateUnitCompatibility(KpiFormulaType.ACTIVE_LOAD_PCT, "W")
        )
        assertNull(CustomKpiAndWorkspaceEngine.validateUnitCompatibility(KpiFormulaType.ACTIVE_LOAD_PCT, "%"))
        assertEquals(
            "kpi_err_cost_rate_required",
            CustomKpiAndWorkspaceEngine.validateUnitCompatibility(KpiFormulaType.HOURLY_COST_RATE, "USD")
        )

        val eval = CustomKpiAndWorkspaceEngine.evaluateKpis(
            definitions = listOf(
                CustomKpiDefinition("k1", "Load", KpiFormulaType.ACTIVE_LOAD_PCT, "%", 100.0),
                CustomKpiDefinition("k2", "Headroom", KpiFormulaType.PEAK_HEADROOM_W, "W", 120.0)
            ),
            ports = mapOf(1 to PortTelemetry(1, "USB-C1", enabled = true, voltageV = 20.0, currentA = 3.0, powerW = 60.0)),
            ratedMaxW = 120.0,
            effectiveTariffPerKwh = 0.20,
            currencyCode = "USD",
            completedSessions = emptyList(),
            todayWh = 30.0
        )
        assertEquals(50.0, eval[0].currentValue, 0.01)
        assertEquals(60.0, eval[1].currentValue, 0.01)
        assertEquals(WorkspacePreset.OFFICE, WorkspacePreset.fromId("office"))
    }

    @Test
    fun configShareAndSupportBundleStripMacsIpsTokensAndPasswords() {
        val prefs = AppPreferences(themeMode = "DARK", activeWorkspaceId = "OFFICE", tariffRatePerKwh = 0.18)
        val rawConfig = ConfigShareAndSupportBundle.buildShareableConfigJson(
            preferences = prefs,
            profiles = listOf(ChargingProfileEntity(name = "Night AA:BB:CC:DD:EE:FF", sceneMode = 2, isBuiltIn = false)),
            rules = listOf(AutomationRuleEntity(name = "Rule", triggerType = "LOW_POWER_CUTOFF", portId = 1, actionType = "PORT_OFF")),
            presets = listOf(PortDevicePresetEntity(portId = 1, presetType = "LAPTOP_14", deviceLabel = "My Private Laptop", targetWh = 60.0)),
            kpis = listOf(CustomKpiDefinition("k1", "Load", KpiFormulaType.ACTIVE_LOAD_PCT, "%", 100.0))
        )
        assertFalse(rawConfig.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(rawConfig.contains("My Private Laptop"))

        val parsed = ConfigShareAndSupportBundle.parseShareableConfigJson(rawConfig)
        assertTrue(parsed.isSuccess)
        val bundle = parsed.getOrThrow()
        assertEquals(1, bundle.profiles.size)
        assertEquals(1, bundle.rules.size)
        assertEquals(1, bundle.presets.size)
        assertEquals(1, bundle.kpis.size)

        val support = ConfigShareAndSupportBundle.buildAnonymizedSupportBundle(
            stackDiagnosticsText = "Charger AA:BB:CC:DD:EE:FF at 203.0.113.5",
            doctorReport = PowerDoctorEngine.evaluate(
                TelemetryIntegritySnapshot(),
                emptyMap(),
                emptyList(),
                emptyList(),
                CommandWearSummary()
            ),
            integrity = TelemetryIntegritySnapshot(),
            linkEvents = listOf(BleLinkEventEntity(timestampMs = 1000L, eventType = "CONNECT", rssiDbm = -60, detail = "Bearer my_secret_tok")),
            commandAudit = listOf(CommandAuditEntity(timestampMs = 1000L, source = "UI", commandType = "SetPortEnabled", targetPortId = 1, summary = "Set port 1 ON", accepted = true, latencyMs = 20)),
            recentLogLines = listOf("token=abcdef123456 on 198.51.100.22")
        )
        assertFalse(support.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(support.contains("203.0.113.5"))
        assertFalse(support.contains("198.51.100.22"))
        assertFalse(support.contains("my_secret_tok"))
    }

    @Test
    fun overviewWorkspaceKpiValidationAndDoctorFleetCardsRenderCorrectly() {
        val viewModel = DashboardViewModel(app)
        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "en") {
                MyApplicationTheme {
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

        composeTestRule.onNodeWithTag("workspace_kpi_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("workspace_chip_office").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("kpi_add_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_custom_kpi_sheet").assertIsDisplayed()

        // Enter incompatible unit "W" while percentage formula is active -> must show unit compatibility error
        composeTestRule.onNodeWithTag("kpi_title_input").performTextInput("Charger Load")
        composeTestRule.onNodeWithTag("kpi_unit_input").performTextClearance()
        composeTestRule.onNodeWithTag("kpi_unit_input").performTextInput("W")
        composeTestRule.onNodeWithTag("kpi_save_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("kpi_unit_error_text").assertIsDisplayed()

        // Fix unit to "%" and save
        composeTestRule.onNodeWithTag("kpi_unit_input").performTextClearance()
        composeTestRule.onNodeWithTag("kpi_unit_input").performTextInput("%")
        composeTestRule.onNodeWithTag("kpi_save_btn").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun settingsHubExposesWebDavConfigShareBridgeDiscoveryAndMqtt() {
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
        composeTestRule.onNodeWithTag("settings_tab_advanced").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_webdav_open_btn").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_config_share_open_btn").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("bridge_discover_btn").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("mqtt_enable_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("integrations_save_btn").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings_config_share_open_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("config_share_sheet").assertIsDisplayed()
    }

    @Test
    fun fleetAndPowerDoctorStandaloneCardsRenderMetricsCleanly() {
        val fleet = MultiChargerFleetEngine.buildFleetSummary(
            savedDevices = listOf(
                CuktechDeviceCredentials("1", "Desk", "ad1204", "AA:BB:CC:DD:EE:01", "t1", "k1", "cn"),
                CuktechDeviceCredentials("2", "Travel", "ad1204", "AA:BB:CC:DD:EE:02", "t2", "k2", "us")
            ),
            chargerNodes = listOf(
                ChargerNodeEntity("AA:BB:CC:DD:EE:01", "Desk", "Office", mainsVoltageMode = 220, isOnline = true),
                ChargerNodeEntity("AA:BB:CC:DD:EE:02", "Travel", "Travel", mainsVoltageMode = 110, isOnline = false)
            ),
            completedSessions = emptyList(),
            activeSessions = emptyMap(),
            dailyAggregates = emptyList(),
            activeMac = "AA:BB:CC:DD:EE:01",
            activeLivePorts = emptyMap(),
            isActiveOnline = true
        )
        val report = PowerDoctorEngine.evaluate(
            integrity = TelemetryIntegritySnapshot(uptimePct24h = 98.5),
            cableDiagnostics = emptyMap(),
            cableLabTests = emptyList(),
            anomalyAlerts = emptyList(),
            wearSummary = CommandWearSummary()
        )
        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "en") {
                MyApplicationTheme {
                    androidx.compose.foundation.layout.Column {
                        MultiChargerFleetCard(fleet = fleet, onSwitchCharger = {})
                        PowerDoctorCard(report = report, onBuildSupportBundle = { "bundle" })
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("fleet_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fleet_benchmark_result").assertIsDisplayed()
        composeTestRule.onNodeWithTag("power_doctor_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("power_doctor_score_badge").assertIsDisplayed()
    }
}
