package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandDispatcher
import com.example.cuktech.automation.CommandResult
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.automation.HardwareCommandAdapter
import com.example.cuktech.automation.TcpGatewayServer
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.security.AutomationAuthManager
import com.example.cuktech.security.BackupPayload
import com.example.cuktech.security.InvalidBackupException
import com.example.cuktech.security.KeystoreKeyProvider
import com.example.cuktech.security.LogSanitizer
import com.example.cuktech.security.SafEncryptedBackupManager
import com.example.cuktech.security.SecureCredentialStore
import com.example.cuktech.updater.GitHubApkUpdater
import com.example.cuktech.updater.ReleaseUpdateInfo
import com.example.cuktech.updater.UpdateDownloadState
import com.example.ui.CuktechAppRoot
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.navigation.AppDestination
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h915dp", sdk = [36])
class Stage5RegressionAndSecurityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    private class InMemoryKeyProvider : KeystoreKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun getOrCreateKey(alias: String): SecretKey = keys.getOrPut(alias) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun deleteKey(alias: String) {
            keys.remove(alias)
        }
    }

    private class ThrowingKeyProvider : KeystoreKeyProvider {
        override fun getOrCreateKey(alias: String): SecretKey {
            throw GeneralSecurityException("Hardware Keystore unavailable")
        }
        override fun deleteKey(alias: String) = Unit
    }

    private class FakeStage5HardwareAdapter : HardwareCommandAdapter {
        var settings: ChargerSettingsState = ChargerSettingsState(sceneMode = 1, portSwitchBitmap = 0x0F)
        val ports: Map<Int, PortTelemetry> = mapOf(
            1 to PortTelemetry(1, "USB-C1", voltageV = 20.0, currentA = 3.0, powerW = 60.0, enabled = true),
            2 to PortTelemetry(2, "USB-C2", voltageV = 9.0, currentA = 2.0, powerW = 18.0, enabled = true),
            3 to PortTelemetry(3, "USB-C3", enabled = false),
            4 to PortTelemetry(4, "USB-A", enabled = true)
        )
        override fun isAuthenticated(): Boolean = true
        override fun currentSettings(): ChargerSettingsState = settings
        override fun currentPorts(): Map<Int, PortTelemetry> = ports
        override fun currentChargeLimits(): Map<Int, PortChargeLimit> = emptyMap()
        override fun activeSessionsSnapshot(): Map<Int, ChargeSessionRecord> = emptyMap()
        override suspend fun writePortBitmap(newBitmap: Int): Boolean {
            settings = settings.copy(portSwitchBitmap = newBitmap and 0x0F)
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
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        BleBridgeService.ensureCuktechManagersInitialized(app)
    }

    @Test
    fun manifestAndAutomationDefaultsEnforceFailClosedSecurityPosture() {
        // 1. allowBackup must be disabled
        val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
        assertEquals(0, appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)

        // 2. Foreground service must declare connectedDevice type
        val pmSvc = app.packageManager.getServiceInfo(
            android.content.ComponentName(app, BleBridgeService::class.java),
            0
        )
        assertTrue((pmSvc.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) != 0)

        // 3. External automation defaults: all channels OFF, loopback-only TCP
        val freshPrefs = app.getSharedPreferences("cuktech_automation_auth", Context.MODE_PRIVATE)
        freshPrefs.edit().clear().commit()
        val store = SecureCredentialStore(app, InMemoryKeyProvider())
        val authMgr = AutomationAuthManager(app, store)
        val cfg = authMgr.config.value
        assertFalse(cfg.taskerEnabled)
        assertFalse(cfg.adbBroadcastEnabled)
        assertFalse(cfg.tcpGatewayEnabled)
        assertFalse(cfg.tcpAllowLanBind)
        assertEquals(8080, cfg.tcpPort)
        assertTrue(authMgr.getOrCreateToken().length >= 64)
    }

    @Test
    fun secureCredentialStoreFailClosedWhenKeystoreUnavailableAndDeletesLegacyKeys() {
        val prefs = app.getSharedPreferences("cuktech_secure_vault", Context.MODE_PRIVATE)
        prefs.edit().putString("test_fallback_k", "legacy-insecure-key-material").commit()

        val failingStore = SecureCredentialStore(app, ThrowingKeyProvider())
        assertFalse(prefs.contains("test_fallback_k"))
        assertFalse(failingStore.putEncryptedSecret("mi_token", "secret123"))
        assertNull(failingStore.getEncryptedSecret("mi_token"))
    }

    @Test
    fun tcpGatewayRejectsRawGattAndEnforcesAuthHandshake() = runTest {
        val store = SecureCredentialStore(app, InMemoryKeyProvider())
        val authMgr = AutomationAuthManager(app, store)
        authMgr.setTcpGatewayEnabled(true)
        val token = authMgr.getOrCreateToken()

        val adapter = FakeStage5HardwareAdapter()
        val dispatcher = CommandDispatcher(authMgr, adapter)
        val server = TcpGatewayServer(
            authManager = authMgr,
            dispatcher = dispatcher,
            bleManagerProvider = { null },
            onClientListChanged = {},
            logger = { _, _ -> }
        )

        assertEquals(TcpGatewayServer.LOOPBACK_HOST, server.boundHost)

        val sessionState = TcpGatewayServer.SessionAuthState()
        // Unauthenticated query must fail with AUTH_REQUIRED and request socket close
        val (unauthResp, closeUnauth) = server.processClientJsonLine(
            "c1", "127.0.0.1", sessionState, """{"cmd":"set_port","port":1,"enabled":false}"""
        )
        assertTrue(closeUnauth)
        assertEquals("AUTH_REQUIRED", JSONObject(unauthResp).optString("code"))

        // Authenticate with valid token
        val (authResp, closeAuth) = server.processClientJsonLine(
            "c1", "127.0.0.1", sessionState, """{"cmd":"auth","token":"$token"}"""
        )
        assertFalse(closeAuth)
        assertEquals("authenticated", JSONObject(authResp).optString("status"))
        assertTrue(sessionState.authenticated)

        // Legacy arbitrary raw GATT commands remain blocked even after authentication
        for (rawCmd in listOf("scan", "connect", "read", "write", "notify", "reset_bluetooth", "discover")) {
            val (forbiddenResp, _) = server.processClientJsonLine(
                "c1", "127.0.0.1", sessionState, """{"cmd":"$rawCmd"}"""
            )
            assertEquals("COMMAND_FORBIDDEN", JSONObject(forbiddenResp).optString("code"))
        }

        // Allowed high-level command executes cleanly through CommandDispatcher
        val (okResp, _) = server.processClientJsonLine(
            "c1", "127.0.0.1", sessionState, """{"cmd":"set_port","port":1,"enabled":false}"""
        )
        assertEquals("ok", JSONObject(okResp).optString("status"))
        assertEquals(0x0E, adapter.settings.portSwitchBitmap)
    }

    @Test
    fun logSanitizerMasksSecretsInDiagnosticsClipboardOutput() {
        val raw = """
            [INFO] Connected to AA:BB:CC:11:22:33 (192.168.1.44:8080)
            [DEBUG] Authorization: Bearer supersecret_ha_token_999
            [DEBUG] {"cmd":"auth","token":"abcdef0123456789abcdef0123456789"}
            [DEBUG] ssecurity=XYZ_SECRET_KEY&serviceToken=ABC_TOKEN_123
        """.trimIndent()

        val clean = LogSanitizer.sanitize(raw, maskNetworkIdentifiers = true)
        assertFalse(clean.contains("AA:BB:CC:11:22:33"))
        assertFalse(clean.contains("192.168.1.44"))
        assertFalse(clean.contains("supersecret_ha_token_999"))
        assertFalse(clean.contains("abcdef0123456789abcdef0123456789"))
        assertFalse(clean.contains("XYZ_SECRET_KEY"))
        assertFalse(clean.contains("ABC_TOKEN_123"))
    }

    @Test
    fun safBackupExcludesSecretsByDefaultAndRejectsWrongPassphrase() {
        val payload = BackupPayload(
            createdAtMs = 1_763_635_200_000L,
            includesSecrets = false,
            preferencesJson = JSONObject().put("appLanguage", "ru"),
            sessionsArray = JSONArray(),
            dailyAggregatesArray = JSONArray(),
            chargeLimitsArray = JSONArray(),
            aliasesArray = JSONArray(),
            profilesArray = JSONArray(),
            devicesSecretsArray = JSONArray().put(JSONObject().put("token", "SHOULD_NOT_APPEAR"))
        )
        val out = ByteArrayOutputStream()
        SafEncryptedBackupManager.encryptToStream(
            payload = payload,
            password = "StrongPassphrase!2026".toCharArray(),
            outputStream = out,
            kdfIterations = 10_000
        )

        val rawEnvelope = out.toString(Charsets.UTF_8.name())
        assertFalse(rawEnvelope.contains("SHOULD_NOT_APPEAR"))

        // Wrong passphrase must fail-closed
        var failed = false
        try {
            SafEncryptedBackupManager.decryptFromStream(
                ByteArrayInputStream(out.toByteArray()),
                "WrongPassphrase".toCharArray()
            )
        } catch (_: InvalidBackupException) {
            failed = true
        }
        assertTrue(failed)

        // Valid passphrase restores payload without secrets
        val restored = SafEncryptedBackupManager.decryptFromStream(
            ByteArrayInputStream(out.toByteArray()),
            "StrongPassphrase!2026".toCharArray()
        )
        assertFalse(restored.includesSecrets)
        assertNull(restored.devicesSecretsArray)
        assertEquals("ru", restored.preferencesJson.getString("appLanguage"))
    }

    @Test
    fun githubApkUpdaterBlocksUpdatesWithoutHttpsOrValidSha256() {
        val missingShaRelease = ReleaseUpdateInfo(
            currentVersion = "2.0.0",
            latestVersion = "2.1.0",
            tagName = "v2.1.0",
            releaseName = "v2.1.0",
            changelog = "Test",
            htmlUrl = GitHubApkUpdater.RELEASES_PAGE_URL,
            publishedAtIso = "",
            apkName = "test.apk",
            apkDownloadUrl = "https://github.com/joxit/cuktech-10-ultra/releases/download/v2.1.0/test.apk",
            apkSizeBytes = 1024L,
            expectedSha256 = null,
            checksumSourceUrl = null,
            isUpdateAvailable = true
        )

        var lastState: UpdateDownloadState = UpdateDownloadState.Idle
        GitHubApkUpdater.downloadAndVerifyApk(app, missingShaRelease) { lastState = it }
        assertTrue(lastState is UpdateDownloadState.Error)
        assertTrue((lastState as UpdateDownloadState.Error).message.contains("SHA-256"))

        val httpRelease = missingShaRelease.copy(
            apkDownloadUrl = "http://insecure.example.com/app.apk",
            expectedSha256 = "a".repeat(64)
        )
        GitHubApkUpdater.downloadAndVerifyApk(app, httpRelease) { lastState = it }
        assertTrue(lastState is UpdateDownloadState.Error)
        assertTrue((lastState as UpdateDownloadState.Error).message.contains("HTTPS"))
    }

    @Test
    fun masterKillswitchDialogExecutesAndSafetyCutoffInvalidatesUndoInUi() {
        val mgr = BleBridgeService.ensureCuktechManagersInitialized(app)
        val adapter = FakeStage5HardwareAdapter()
        val dispatcher = CommandDispatcher(mgr.authManager, adapter)
        val viewModel = DashboardViewModel(
            application = app,
            cuktechManager = mgr,
            dispatcher = dispatcher
        )

        mgr.setAuthStateForTest(CuktechAuthState.AUTHENTICATED)
        try {
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

            // Confirm Master Killswitch dialog triggers sequential shutoff & Undo Snackbar
            composeTestRule.onNodeWithTag("overview_hero_card").assertIsDisplayed()
            composeTestRule.onNodeWithTag("master_killswitch_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("confirm_killswitch_button").performClick()
            composeTestRule.waitForIdle()

            assertEquals(0, adapter.settings.portSwitchBitmap)
            composeTestRule.onNodeWithText("Undo").assertIsDisplayed()

            // Safety cutoff must immediately invalidate Killswitch Undo and Snackbar
            dispatcher.notifySafetyCutoffTriggered(1, "WH_LIMIT")
            composeTestRule.waitForIdle()
            assertTrue(composeTestRule.onAllNodesWithText("Undo").fetchSemanticsNodes().isEmpty())
        } finally {
            mgr.setAuthStateForTest(CuktechAuthState.DISCONNECTED)
        }
    }

    @Test
    fun settingsHubBackupUpdateAndAutomationSectionsWorkInEnglishAndRussian() {
        val viewModel = DashboardViewModel(app)
        val lang = mutableStateOf("en")

        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = lang.value) {
                MyApplicationTheme(useDynamicColor = false) {
                    CuktechAppRoot(
                        viewModel = viewModel,
                        initialDestinationOverride = AppDestination.SETTINGS,
                        requestPermissionsOnLaunch = false
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_tab_basic").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_tab_advanced").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_diagnostics_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("check_updates_button").performScrollTo().assertIsDisplayed()

        // Switch to Advanced tab for Backup and Security/TCP controls
        composeTestRule.onNodeWithTag("settings_tab_advanced").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("saf_backup_export_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("saf_backup_restore_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("opt_in_tcp_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("toggle_tcp_server_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("opt_in_tcp_switch").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("toggle_tcp_server_button").performScrollTo().assertIsDisplayed()

        // Switch locale live to Russian and verify MD3E controls & cards render cleanly across both tabs
        lang.value = "ru"
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_settings").assertIsDisplayed()
        composeTestRule.onNodeWithTag("saf_backup_export_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("rotate_token_button").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings_tab_basic").performClick()
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.onAllNodesWithText("Настройки").fetchSemanticsNodes().size >= 2)
        composeTestRule.onNodeWithTag("dynamic_color_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("replay_onboarding_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("github_updates_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("check_updates_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun topBarShowsConnectionPillWithoutDebugIconAndOverviewHasNoBodyConnectionCard() {
        val viewModel = DashboardViewModel(app)

        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "ru") {
                MyApplicationTheme(useDynamicColor = false) {
                    CuktechAppRoot(
                        viewModel = viewModel,
                        initialDestinationOverride = AppDestination.OVERVIEW,
                        requestPermissionsOnLaunch = false
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("topbar_connection_pill").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_pair_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connection_connect_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("topbar_diagnostics_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("connection_status_card").assertDoesNotExist()
    }

    @Test
    fun onboardingThreeStepFlowAdvancesAndInvokesCompletionCallback() {
        var finished = false

        composeTestRule.setContent {
            LocalizedAppContent(appLanguage = "ru") {
                MyApplicationTheme(useDynamicColor = false) {
                    OnboardingScreen(onFinish = { finished = true })
                }
            }
        }

        composeTestRule.onNodeWithTag("onboarding_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("onboarding_next_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("onboarding_port_manual_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("onboarding_next_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("onboarding_next_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("onboarding_perm_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("onboarding_finish_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(finished)
    }

    @Test
    fun conditionalWakeLockOnlyHeldDuringActivePollingAndReleasedOnScreenOffUnplugDisconnect() {
        val controller = Robolectric.buildService(BleBridgeService::class.java).create()
        val service = controller.get()
        val mgr = BleBridgeService.ensureCuktechManagersInitialized(app)

        try {
            // Standby / idle service must not hold WakeLock
            service.startForegroundForBleConnection()
            service.dismissForegroundNotificationIfIdle()
            assertFalse(service.isWakeLockHeld())

            // Simulate authenticated charger & active polling while phone is charging
            mgr.setTelemetryMasterEnabled(true)
            mgr.setLogOnlyWhenPhoneCharging(true)
            mgr.setPausePollingWhenScreenLocked(false)
            mgr.isPhoneCharging.value = false
            mgr.setAuthStateForTest(CuktechAuthState.AUTHENTICATED)
            mgr.startBackgroundLoops()
            assertFalse("WakeLock must stay released when logOnlyWhenPhoneCharging=true and phone is unplugged", service.isWakeLockHeld())

            // Plugging phone into AC power starts active polling and acquires WakeLock
            service.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue(mgr.isPhoneCharging.value)
            assertTrue("WakeLock must be held when actively polling and phone is charging", service.isWakeLockHeld())

            // Unplugging phone releases WakeLock immediately
            service.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertFalse("WakeLock must be released immediately on ACTION_POWER_DISCONNECTED", service.isWakeLockHeld())

            // Screen lock policy releases WakeLock on ACTION_SCREEN_OFF and reacquires on ACTION_SCREEN_ON
            mgr.setLogOnlyWhenPhoneCharging(false)
            mgr.setPausePollingWhenScreenLocked(true)
            mgr.isScreenOn.value = true
            mgr.syncWakeLockWithPollingState()
            assertTrue(service.isWakeLockHeld())

            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertFalse("WakeLock must be released immediately on ACTION_SCREEN_OFF", service.isWakeLockHeld())

            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue("WakeLock must reacquire when screen turns back on during active polling", service.isWakeLockHeld())

            // Disconnecting / stopping loops releases WakeLock cleanly
            mgr.stopAllJobsForServiceDestroy()
            assertFalse("WakeLock must be released on disconnect/teardown", service.isWakeLockHeld())
        } finally {
            mgr.stopAllJobsForServiceDestroy()
            mgr.setLogOnlyWhenPhoneCharging(false)
            mgr.setPausePollingWhenScreenLocked(false)
            mgr.isPhoneCharging.value = false
            mgr.isScreenOn.value = true
            controller.destroy()
        }
    }

    @Test
    fun sameVersionBuildConfigAndOrientationLockToggleWorkCorrectly() {
        val currentJson = """
            {
              "tag_name": "v${BuildConfig.VERSION_NAME}",
              "name": "Release ${BuildConfig.VERSION_NAME}",
              "prerelease": false,
              "draft": false,
              "body": "Latest",
              "html_url": "https://github.com/joxit/cuktech-10-ultra/releases/latest",
              "assets": []
            }
        """.trimIndent()
        val parsed = GitHubApkUpdater.parseGitHubReleaseJson(currentJson, BuildConfig.VERSION_NAME)
        assertFalse("Same version as BuildConfig.VERSION_NAME must not trigger update alert", parsed.isUpdateAvailable)

        val mgr = BleBridgeService.ensureCuktechManagersInitialized(app)
        val adapter = FakeStage5HardwareAdapter()
        val dispatcher = CommandDispatcher(mgr.authManager, adapter)
        val viewModel = DashboardViewModel(
            application = app,
            cuktechManager = mgr,
            dispatcher = dispatcher
        )

        mgr.setAuthStateForTest(CuktechAuthState.AUTHENTICATED)
        try {
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
            composeTestRule.onNodeWithTag("orientation_lock_switch").performScrollTo().assertIsDisplayed()
        } finally {
            mgr.setAuthStateForTest(CuktechAuthState.DISCONNECTED)
        }
    }
}
