package com.example

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandDispatcher
import com.example.cuktech.automation.CommandResult
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.automation.HardwareCommandAdapter
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.notifications.AnomalyType
import com.example.cuktech.notifications.CableAnomalyDetector
import com.example.cuktech.notifications.ChargingCompletionDetector
import com.example.cuktech.notifications.ChargingNotificationManager
import com.example.cuktech.notifications.LiveNotificationPayload
import com.example.cuktech.notifications.PersistedTimerStore
import com.example.cuktech.prefs.AppPreferences
import com.example.cuktech.prefs.AppPreferencesRepository
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.security.AutomationAuthManager
import com.example.cuktech.security.KeystoreKeyProvider
import com.example.cuktech.security.SecureCredentialStore
import com.example.cuktech.updater.GitHubApkUpdater
import com.example.widget.CuktechWidgetUpdater
import com.example.widget.WidgetConfigStore
import com.example.widget.WidgetInstanceConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Stage4FeaturesTest {

    private lateinit var context: Context

    private class InMemoryKeyProvider : KeystoreKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun getOrCreateKey(alias: String): SecretKey = keys.getOrPut(alias) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun deleteKey(alias: String) { keys.remove(alias) }
    }

    private class FakeProfileAdapter : HardwareCommandAdapter {
        var auth = true
        var settings = ChargerSettingsState(sceneMode = 1, portSwitchBitmap = 0x0F)
        var ports = mapOf(
            1 to PortTelemetry(1, "USB-C1", enabled = true),
            2 to PortTelemetry(2, "USB-C2", enabled = true),
            3 to PortTelemetry(3, "USB-C3", enabled = true),
            4 to PortTelemetry(4, "USB-A", enabled = true)
        )
        val appliedLimits = mutableMapOf<Int, Pair<Double, Boolean>>()

        override fun isAuthenticated(): Boolean = auth
        override fun currentSettings(): ChargerSettingsState = settings
        override fun currentPorts(): Map<Int, PortTelemetry> = ports
        override fun currentChargeLimits(): Map<Int, PortChargeLimit> = emptyMap()
        override fun activeSessionsSnapshot(): Map<Int, ChargeSessionRecord> = emptyMap()

        override suspend fun writePortBitmap(newBitmap: Int): Boolean {
            settings = settings.copy(portSwitchBitmap = newBitmap)
            return true
        }
        override suspend fun writeSceneMode(mode: Int): Boolean {
            settings = settings.copy(sceneMode = mode)
            return true
        }
        override suspend fun writeScreenTimeout(timeoutCode: Int): Boolean {
            settings = settings.copy(screenTimeout = timeoutCode)
            return true
        }
        override suspend fun writePortCountdown(portId: Int, minutes: Int): Boolean = true
        override suspend fun writeUsbALowCurrent(enabled: Boolean): Boolean {
            settings = settings.copy(usbALowCurrent = enabled)
            return true
        }
        override suspend fun writeIdleScreenOff(enabled: Boolean): Boolean {
            settings = settings.copy(idleScreenOff = enabled)
            return true
        }
        override suspend fun writeLanguage(langCode: Int): Boolean = true
        override suspend fun writeOrientationLock(locked: Boolean): Boolean {
            settings = settings.copy(orientationLock = locked)
            return true
        }
        override suspend fun writeGotoScreen(pageIndex: Int): Boolean = true
        override suspend fun writeRotateScreen180(): Boolean = true
        override suspend fun writePortProtocol(portId: Int, protocol: String, enabled: Boolean): Boolean = true
        override suspend fun writeFastChargeProtocols(pd: Boolean, pps: Boolean, ufcs: Boolean, scp: Boolean): Boolean = true
        override suspend fun executeSoftReset(): Boolean = true
        override fun applyPortChargeLimit(portId: Int, whTarget: Double, mode: String, enabled: Boolean) {
            appliedLimits[portId] = whTarget to enabled
        }
        override fun requestConnect(mac: String?) = Unit
        override fun requestDisconnect(isManual: Boolean) = Unit
        override fun requestScan(durationMs: Long) = Unit
        override fun applyAutoReconnect(enabled: Boolean) = Unit
        override fun applyPortLogging(portId: Int, enabled: Boolean) = Unit
        override fun applyTelemetryMaster(enabled: Boolean) = Unit
        override fun applyLogOnlyWhenPhoneCharging(enabled: Boolean) = Unit
        override fun applyPausePollingWhenLocked(enabled: Boolean) = Unit
        override fun requestRefreshTelemetry() = Unit
        override fun finalizeSessionForPortWithReason(portId: Int, reason: String) = Unit
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        CuktechWidgetUpdater.resetDeduplicationForTest()
    }

    @Test
    fun completionDetectorTriggersOnSustainedTrickleAndIgnoresAbruptUnplug() {
        val detector = ChargingCompletionDetector()
        val prefs = AppPreferences(
            completionHysteresisSec = 60,
            completionPowerThresholdW = 1.0,
            completionRepeatEnabled = true,
            completionRepeatIntervalMin = 5,
            completionMaxRepeats = 2,
            quietHoursEnabled = true,
            quietHoursStartMinute = 23 * 60,
            quietHoursEndMinute = 7 * 60
        )

        // 1. Meaningful active charging on USB-C1
        assertNull(detector.onTelemetrySample(1, "USB-C1", 45.0, true, 12.0, 1_000L, prefs, 14 * 60))

        // 2. Drops into trickle band (0.4W) at t=10s -> hysteresis starts
        assertNull(detector.onTelemetrySample(1, "USB-C1", 0.4, true, 14.5, 10_000L, prefs, 14 * 60))
        // Still below 60s hysteresis at t=50s
        assertNull(detector.onTelemetrySample(1, "USB-C1", 0.3, true, 14.6, 50_000L, prefs, 14 * 60))

        // 3. Reaches 60s hysteresis at t=70s -> fires initial completion (not quiet hours)
        val firstEvent = detector.onTelemetrySample(1, "USB-C1", 0.3, true, 14.7, 70_000L, prefs, 14 * 60)
        assertNotNull(firstEvent)
        assertEquals(0, firstEvent!!.repeatIndex)
        assertFalse(firstEvent.suppressedByQuietHours)
        assertEquals(45.0, firstEvent.peakWatts, 0.01)

        // 4. Repeat after 5 minutes during Quiet Hours (23:30)
        val repeat1 = detector.onTelemetrySample(1, "USB-C1", 0.25, true, 14.8, 70_000L + 300_000L, prefs, 23 * 60 + 30)
        assertNotNull(repeat1)
        assertEquals(1, repeat1!!.repeatIndex)
        assertTrue(repeat1.suppressedByQuietHours)

        // 5. Repeat 2 works, repeat 3 capped
        assertNotNull(detector.onTelemetrySample(1, "USB-C1", 0.25, true, 14.8, 70_000L + 600_000L, prefs, 23 * 60 + 35))
        assertNull(detector.onTelemetrySample(1, "USB-C1", 0.25, true, 14.8, 70_000L + 900_000L, prefs, 23 * 60 + 40))

        // 6. Abrupt physical cable unplug from active 35W -> 0.0W on USB-C2 MUST NOT trigger completion
        assertNull(detector.onTelemetrySample(2, "USB-C2", 35.0, true, 5.0, 1_000L, prefs, 14 * 60))
        assertNull(detector.onTelemetrySample(2, "USB-C2", 0.0, true, 5.0, 2_000L, prefs, 14 * 60))
        assertNull(detector.onTick(2, "USB-C2", 5.0, 120_000L, prefs, 14 * 60))
    }

    @Test
    fun completionDetectorCancelsOnShortDipAndSuppressesOnSafetyOrDisconnect() {
        val detector = ChargingCompletionDetector()
        val prefs = AppPreferences(completionHysteresisSec = 60, completionPowerThresholdW = 1.0)

        // Active -> trickle -> rebounds to 25W before 60s -> cancels timer
        detector.onTelemetrySample(1, "USB-C1", 30.0, true, 4.0, 1_000L, prefs, 12 * 60)
        detector.onTelemetrySample(1, "USB-C1", 0.4, true, 4.1, 10_000L, prefs, 12 * 60)
        detector.onTelemetrySample(1, "USB-C1", 25.0, true, 4.5, 25_000L, prefs, 12 * 60)
        assertNull(detector.onTick(1, "USB-C1", 4.5, 90_000L, prefs, 12 * 60))

        // Safety cutoff suppresses completion until replug >= 2.5W
        detector.notifySafetyCutoff(1)
        assertNull(detector.onTelemetrySample(1, "USB-C1", 0.3, true, 4.5, 100_000L, prefs, 12 * 60))
        assertNull(detector.onTick(1, "USB-C1", 4.5, 200_000L, prefs, 12 * 60))

        // BLE disconnect resets everything
        detector.onTelemetrySample(3, "USB-C3", 20.0, true, 1.0, 1_000L, prefs, 12 * 60)
        detector.onTelemetrySample(3, "USB-C3", 0.4, true, 1.1, 5_000L, prefs, 12 * 60)
        detector.notifyBleDisconnected()
        assertNull(detector.onTick(3, "USB-C3", 1.1, 100_000L, prefs, 12 * 60))
    }

    @Test
    fun anomalyDetectorDetectsRenegotiationCollapseAndBleFlapping() {
        val detector = CableAnomalyDetector()

        // 3x PD -> 5V Normal fallback in 2 min on USB-C1
        var alert: com.example.cuktech.notifications.AnomalyAlert? = null
        var t = 10_000L
        repeat(3) {
            detector.onPortSample(1, "USB-C1", 11.0, 30.0, "PD", true, t)
            alert = detector.onPortSample(1, "USB-C1", 5.0, 7.5, "Normal", true, t + 1_000L)
            t += 5_000L
        }
        assertNotNull(alert)
        assertEquals(AnomalyType.CABLE_RENEGOTIATION_INSTABILITY, alert!!.type)
        assertEquals(1, alert!!.portId)

        // 3x load collapses >=15W -> <=0.5W on USB-C2
        var collapseAlert: com.example.cuktech.notifications.AnomalyAlert? = null
        t = 10_000L
        repeat(3) {
            detector.onPortSample(2, "USB-C2", 20.0, 45.0, "PPS", true, t)
            collapseAlert = detector.onPortSample(2, "USB-C2", 20.0, 0.0, "PPS", true, t + 1_000L)
            t += 5_000L
        }
        assertNotNull(collapseAlert)
        assertEquals(AnomalyType.POWER_COLLAPSE_LOOP, collapseAlert!!.type)

        // 3x unplanned BLE disconnect within 3 min
        assertNull(detector.recordUnplannedBleDisconnect(1_000L))
        assertNull(detector.recordUnplannedBleDisconnect(20_000L))
        val bleAlert = detector.recordUnplannedBleDisconnect(40_000L)
        assertNotNull(bleAlert)
        assertEquals(AnomalyType.BLE_FLAP_LOOP, bleAlert!!.type)
        assertEquals(3, detector.alerts.value.size)

        detector.dismissAlert(bleAlert.id)
        assertEquals(2, detector.alerts.value.size)
    }

    @Test
    fun appPreferencesQuietHoursTouTariffAndJsonBackupRoundtrip() = runTest {
        val prefs = AppPreferences(
            quietHoursEnabled = true,
            quietHoursStartMinute = 23 * 60,
            quietHoursEndMinute = 7 * 60,
            touEnabled = true,
            tariffRatePerKwh = 0.25,
            tariffNightRatePerKwh = 0.10,
            tariffDayStartMinute = 7 * 60,
            tariffNightStartMinute = 23 * 60,
            currencyCode = "EUR",
            completionHysteresisSec = 90,
            completionPowerThresholdW = 1.5,
            completionSoundEnabled = false,
            completionVibrationEnabled = true,
            completionRepeatEnabled = true,
            completionRepeatIntervalMin = 10,
            completionMaxRepeats = 3
        )

        // Midnight wrap checks
        assertTrue(prefs.isWithinQuietHours(23 * 60 + 30))
        assertTrue(prefs.isWithinQuietHours(2 * 60 + 15))
        assertFalse(prefs.isWithinQuietHours(7 * 60))
        assertFalse(prefs.isWithinQuietHours(15 * 60))

        // ToU rate checks
        assertEquals(0.10, prefs.resolveEffectiveTariffPerKwh(1 * 60), 0.0001)
        assertEquals(0.25, prefs.resolveEffectiveTariffPerKwh(12 * 60), 0.0001)

        val repo = AppPreferencesRepository(context)
        repo.applyFromBackupJson(prefs.toJson())
        val restored = repo.preferences.value
        assertTrue(restored.touEnabled)
        assertEquals(0.25, restored.tariffRatePerKwh, 0.0001)
        assertEquals(0.10, restored.tariffNightRatePerKwh, 0.0001)
        assertEquals("EUR", restored.currencyCode)
        assertEquals(90, restored.completionHysteresisSec)
        assertEquals(1.5, restored.completionPowerThresholdW, 0.01)
        assertFalse(restored.completionSoundEnabled)
        assertTrue(restored.completionRepeatEnabled)
        assertEquals(10, restored.completionRepeatIntervalMin)
        assertEquals(3, restored.completionMaxRepeats)
    }

    @Test
    fun persistedTimerStoreSavesLoadsAndPrunesExpiredEntries() {
        val nowSec = 2_000_000_000L
        PersistedTimerStore.savePortCountdownEndSec(context, 1, nowSec + 600L)
        PersistedTimerStore.savePortCountdownEndSec(context, 2, nowSec - 10L) // already expired
        PersistedTimerStore.saveUsbALowCurrentEndSec(context, nowSec + 14_400L)

        val active = PersistedTimerStore.loadActivePortCountdowns(context, nowSec)
        assertEquals(mapOf(1 to (nowSec + 600L)), active)
        assertEquals(nowSec + 14_400L, PersistedTimerStore.loadUsbALowCurrentEndSec(context, nowSec))

        PersistedTimerStore.clearPortCountdown(context, 1)
        assertTrue(PersistedTimerStore.loadActivePortCountdowns(context, nowSec).isEmpty())
    }

    @Test
    fun chargingNotificationManagerFormatsShortCriticalTextAndBuildsPromotedNotification() {
        val nowMs = 1_700_000_000_000L
        assertEquals("5:00", ChargingNotificationManager.formatShortCriticalText(65.0, nowMs + 300_000L, nowMs))
        assertEquals("1h30m", ChargingNotificationManager.formatShortCriticalText(65.0, nowMs + 5_400_000L, nowMs))
        assertEquals("112W", ChargingNotificationManager.formatShortCriticalText(112.4, null, nowMs))
        assertEquals("45.2W", ChargingNotificationManager.formatShortCriticalText(45.2, null, nowMs))

        val pi = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = ChargingNotificationManager.buildOngoingNotification(
            context = context,
            payload = LiveNotificationPayload(
                title = "CUKTECH 10 Ultra",
                content = "65.0 W · C1: 65.0W",
                bigText = "USB-C1: 20V 3.25A 65W",
                totalWatts = 65.0,
                nearestCountdownEndMs = nowMs + 300_000L,
                activeLimitProgressPct = 65,
                liveUpdatesEnabled = true,
                serviceStartTimeMs = nowMs - 10_000L
            ),
            openPendingIntent = pi,
            disconnectPendingIntent = pi,
            nowMs = nowMs
        )
        assertNotNull(notif)
        assertEquals("65.0 W · C1: 65.0W", notif.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        assertEquals("5:00", NotificationCompat.getShortCriticalText(notif))
        assertTrue(NotificationCompat.isRequestPromotedOngoing(notif))
    }

    @Test
    fun widgetConfigStoreAndSnapshotDeduplicationBehaveCorrectly() {
        val custom = WidgetInstanceConfig(
            emphasizedPorts = setOf(1, 4),
            showSparkline = false,
            showSessionWh = true,
            tapDestination = "PORTS"
        )
        WidgetConfigStore.saveConfig(context, 42, custom)
        val loaded = WidgetConfigStore.loadConfig(context, 42)
        assertEquals(setOf(1, 4), loaded.emphasizedPorts)
        assertFalse(loaded.showSparkline)
        assertEquals("PORTS", loaded.tapDestination)

        WidgetConfigStore.removeConfig(context, 42)
        assertEquals(WidgetInstanceConfig(), WidgetConfigStore.loadConfig(context, 42))

        val sig1 = CuktechWidgetUpdater.computeStateSignature(
            authState = CuktechAuthState.AUTHENTICATED,
            totalWatts = 45.2,
            sceneMode = 1,
            portBitmap = 0x0F,
            usbALowCurrent = false,
            portPowers = listOf(1 to 45.2, 2 to 0.0, 3 to 0.0, 4 to 0.0),
            portProtocols = listOf(1 to "PD", 2 to "Normal", 3 to "Normal", 4 to "Normal"),
            sessionWhSum = 12.3
        )
        CuktechWidgetUpdater.markDispatchedForTest(sig1, "AUTHENTICATED", 10_000L)

        // Identical snapshot -> suppressed even after 10s
        assertFalse(CuktechWidgetUpdater.shouldDispatchUpdate(sig1, "AUTHENTICATED", 20_000L, force = false))

        // Changed power within 1s -> rate limited
        val sig2 = sig1.replace("452", "460")
        assertFalse(CuktechWidgetUpdater.shouldDispatchUpdate(sig2, "AUTHENTICATED", 10_500L, force = false))

        // Changed power after >=1s -> dispatched
        assertTrue(CuktechWidgetUpdater.shouldDispatchUpdate(sig2, "AUTHENTICATED", 11_100L, force = false))

        // Auth state transition -> immediate dispatch regardless of 1s throttle
        assertTrue(CuktechWidgetUpdater.shouldDispatchUpdate(sig2, "DISCONNECTED", 10_200L, force = false))
    }

    @Test
    fun githubApkUpdaterParsesReleaseDigestAndEnforcesStrictSha256() {
        val sampleApkFile = File(context.cacheDir, "test_update.apk").apply {
            writeText("cuktech10-binary-test-payload")
        }
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(sampleApkFile.readBytes())
            .joinToString("") { "%02x".format(it) }

        val releaseJson = """
            {
              "tag_name": "v2.5.0",
              "name": "BLEBridge v2.5.0",
              "prerelease": false,
              "draft": false,
              "html_url": "https://github.com/cramqin/BLEBridge-Cuktech10-Ultra/releases/tag/v2.5.0",
              "published_at": "2026-11-20T12:00:00Z",
              "body": "### Changelog\n- Material 3 Glance Widgets\n- Android 16 Live Updates",
              "assets": [
                {
                  "name": "blebridge-v2.5.0-release.apk",
                  "size": 12582912,
                  "browser_download_url": "https://github.com/cramqin/BLEBridge-Cuktech10-Ultra/releases/download/v2.5.0/blebridge-v2.5.0-release.apk",
                  "digest": "sha256:$expectedSha"
                },
                {
                  "name": "blebridge-v2.5.0-release.apk.sha256",
                  "size": 96,
                  "browser_download_url": "https://github.com/cramqin/BLEBridge-Cuktech10-Ultra/releases/download/v2.5.0/blebridge-v2.5.0-release.apk.sha256"
                }
              ]
            }
        """.trimIndent()

        val info = GitHubApkUpdater.parseGitHubReleaseJson(releaseJson, currentVersion = "2.0.0")
        assertTrue(info.isUpdateAvailable)
        assertEquals("2.5.0", info.latestVersion)
        assertEquals(expectedSha, info.expectedSha256)
        assertTrue(GitHubApkUpdater.verifyFileSha256(sampleApkFile, expectedSha))
        assertFalse(GitHubApkUpdater.verifyFileSha256(sampleApkFile, "0".repeat(64)))

        val sameVer = GitHubApkUpdater.parseGitHubReleaseJson(releaseJson, currentVersion = "2.5.0")
        assertFalse(sameVer.isUpdateAvailable)

        sampleApkFile.delete()
    }

    @Test
    fun commandDispatcherAppliesChargingProfileAndWhLimits() = runTest {
        val store = SecureCredentialStore(context, InMemoryKeyProvider())
        val authMgr = AutomationAuthManager(context, store)
        val adapter = FakeProfileAdapter()
        val dispatcher = CommandDispatcher(authMgr, adapter)

        val result = dispatcher.dispatch(
            source = CommandSource.UI,
            command = ChargerCommand.ApplyChargingProfile(
                name = "Desk Workstation",
                sceneMode = 2,
                portSwitchBitmap = 0x09, // USB-C1 + USB-A ON, C2/C3 OFF
                screenTimeout = 3,
                usbALowCurrent = true,
                idleScreenOff = true,
                orientationLock = true,
                whLimitPort1 = 50.0,
                whLimitPort4 = 5.0
            )
        )

        assertTrue(result is CommandResult.Executed)
        assertEquals(2, adapter.settings.sceneMode)
        assertEquals(0x09, adapter.settings.portSwitchBitmap)
        assertEquals(3, adapter.settings.screenTimeout)
        assertTrue(adapter.settings.usbALowCurrent)
        assertTrue(adapter.settings.idleScreenOff)
        assertTrue(adapter.settings.orientationLock)
        assertEquals(50.0 to true, adapter.appliedLimits[1])
        assertEquals(5.0 to true, adapter.appliedLimits[4])
    }
}
