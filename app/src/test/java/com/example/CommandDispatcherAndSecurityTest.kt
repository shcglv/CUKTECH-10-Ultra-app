package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandDispatcher
import com.example.cuktech.automation.CommandResult
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.automation.HardwareCommandAdapter
import com.example.cuktech.automation.TcpGatewayServer
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.security.AutomationAuthManager
import com.example.cuktech.security.KeystoreKeyProvider
import com.example.cuktech.security.SecureCredentialStore
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommandDispatcherAndSecurityTest {

    private lateinit var context: Context
    private lateinit var credStore: SecureCredentialStore
    private lateinit var authManager: AutomationAuthManager
    private lateinit var adapter: FakeHardwareAdapter
    private var fakeClockMs = 1_000_000L

    private class InMemoryKeyProvider : KeystoreKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun getOrCreateKey(alias: String): SecretKey = keys.getOrPut(alias) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun deleteKey(alias: String) { keys.remove(alias) }
    }

    private class FakeHardwareAdapter : HardwareCommandAdapter {
        var auth: Boolean = true
        var settings: ChargerSettingsState = ChargerSettingsState(sceneMode = 1, portSwitchBitmap = 0x0F)
        var ports: Map<Int, PortTelemetry> = mapOf(
            1 to PortTelemetry(portId = 1, portLabel = "USB-C1", enabled = true),
            2 to PortTelemetry(portId = 2, portLabel = "USB-C2", enabled = true),
            3 to PortTelemetry(portId = 3, portLabel = "USB-C3", enabled = true),
            4 to PortTelemetry(portId = 4, portLabel = "USB-A", enabled = true)
        )
        var limits: Map<Int, PortChargeLimit> = emptyMap()
        var failWriteOnBitmapMask: Int? = null
        val bitmapHistory = mutableListOf<Int>()
        val finalizedReasons = mutableListOf<Pair<Int, String>>()

        override fun isAuthenticated(): Boolean = auth
        override fun currentSettings(): ChargerSettingsState = settings
        override fun currentPorts(): Map<Int, PortTelemetry> = ports
        override fun currentChargeLimits(): Map<Int, PortChargeLimit> = limits
        override fun activeSessionsSnapshot(): Map<Int, ChargeSessionRecord> = emptyMap()

        override suspend fun writePortBitmap(newBitmap: Int): Boolean {
            if (failWriteOnBitmapMask != null && newBitmap == failWriteOnBitmapMask) {
                return false
            }
            bitmapHistory.add(newBitmap)
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
        override suspend fun writeLanguage(langCode: Int): Boolean {
            settings = settings.copy(language = langCode)
            return true
        }
        override suspend fun writeOrientationLock(locked: Boolean): Boolean {
            settings = settings.copy(orientationLock = locked)
            return true
        }
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

        override fun finalizeSessionForPortWithReason(portId: Int, reason: String) {
            finalizedReasons.add(portId to reason)
        }
    }

    @Before
    fun setUp() {
        fakeClockMs = 1_000_000L
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("cuktech_secure_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("cuktech_automation_auth", Context.MODE_PRIVATE).edit().clear().commit()
        credStore = SecureCredentialStore(context, InMemoryKeyProvider())
        authManager = AutomationAuthManager(context, credStore)
        adapter = FakeHardwareAdapter()
    }

    @Test
    fun externalChannelsDisabledByDefaultAndRequireValidTokenWithLockoutAndRateLimits() = runTest {
        val cfg = authManager.config.value
        assertFalse(cfg.taskerEnabled)
        assertFalse(cfg.adbBroadcastEnabled)
        assertFalse(cfg.tcpGatewayEnabled)
        assertFalse(cfg.tcpAllowLanBind)

        val dispatcher = CommandDispatcher(authManager, adapter, clockMs = { fakeClockMs })

        // Opt-in disabled -> rejected
        val rDisabled = dispatcher.dispatch(
            CommandSource.ADB_BROADCAST,
            ChargerCommand.SetPortEnabled(1, false),
            authToken = authManager.getOrCreateToken()
        )
        assertFalse(rDisabled.accepted)
        assertEquals("CHANNEL_DISABLED", (rDisabled as CommandResult.Rejected).code)

        // Enable ADB broadcasts; invalid token -> UNAUTHORIZED & lockout after 5 tries
        authManager.setAdbBroadcastEnabled(true)
        repeat(5) {
            val bad = dispatcher.dispatch(
                CommandSource.ADB_BROADCAST,
                ChargerCommand.SetPortEnabled(1, false),
                authToken = "wrong_token_$it",
                clientKey = "adb:tester"
            )
            assertEquals("UNAUTHORIZED", (bad as CommandResult.Rejected).code)
        }
        val locked = dispatcher.dispatch(
            CommandSource.ADB_BROADCAST,
            ChargerCommand.SetPortEnabled(1, false),
            authToken = authManager.getOrCreateToken(),
            clientKey = "adb:tester"
        )
        assertEquals("AUTH_LOCKED_OUT", (locked as CommandResult.Rejected).code)

        // Valid token from clean source succeeds, then parameter validation blocks bad SIID/PIID args
        val validToken = authManager.getOrCreateToken()
        val okRes = dispatcher.dispatch(
            CommandSource.ADB_BROADCAST,
            ChargerCommand.SetSceneMode(3),
            authToken = validToken,
            clientKey = "adb:valid"
        )
        assertTrue(okRes.accepted)
        assertEquals(3, adapter.settings.sceneMode)

        val badParam = dispatcher.dispatch(
            CommandSource.UI,
            ChargerCommand.SetPortProtocol(portId = 4, protocol = "pd", enabled = true)
        )
        assertEquals("INVALID_PARAMS", (badParam as CommandResult.Rejected).code)
    }

    @Test
    fun masterKillswitchExecutesSequentiallyReportsPartialFailureAndRestoresSnapshotSafely() = runTest {
        val dispatcher = CommandDispatcher(authManager, adapter, clockMs = { fakeClockMs })
        // Fail on clearing port 3 (bitmap after port1+2 off = 0b1100 -> port3 clear = 0b1000 = 8)
        adapter.failWriteOnBitmapMask = 0b1000

        val partial = dispatcher.dispatch(CommandSource.UI, ChargerCommand.MasterKillswitch)
        assertTrue(partial is CommandResult.KillswitchExecuted)
        val ks = partial as CommandResult.KillswitchExecuted
        assertFalse(ks.allSucceeded)
        assertEquals(4, ks.outcomes.size)
        assertTrue(ks.outcomes[0].succeeded)
        assertTrue(ks.outcomes[1].succeeded)
        assertFalse(ks.outcomes[2].succeeded)
        assertTrue(ks.outcomes[3].succeeded)

        // Clear fault and verify restore restores previous 0x0F snapshot
        adapter.failWriteOnBitmapMask = null
        val restore = dispatcher.dispatch(CommandSource.UI, ChargerCommand.RestoreKillswitch)
        assertTrue(restore.accepted)
        assertEquals(0x0F, adapter.settings.portSwitchBitmap)
    }

    @Test
    fun undoWorksOnceSupersededByNextCommandAndBlockedAfterSafetyCutoff() = runTest {
        val dispatcher = CommandDispatcher(authManager, adapter, clockMs = { fakeClockMs })

        // Single change -> Undo reverts it
        assertTrue(dispatcher.dispatch(CommandSource.UI, ChargerCommand.SetSceneMode(2)).accepted)
        assertEquals(2, adapter.settings.sceneMode)
        assertNotNull(dispatcher.undoAvailability.value)

        assertTrue(dispatcher.dispatch(CommandSource.UI, ChargerCommand.UndoLastCommand).accepted)
        assertEquals(1, adapter.settings.sceneMode)

        // Two commands: undo window only applies to latest, and safety cutoff blocks it completely
        assertTrue(dispatcher.dispatch(CommandSource.UI, ChargerCommand.SetPortEnabled(2, false)).accepted)
        fakeClockMs += 1_000L
        dispatcher.notifySafetyCutoffTriggered(portId = 2, reason = "WH_LIMIT")
        val blockedUndo = dispatcher.dispatch(CommandSource.UI, ChargerCommand.UndoLastCommand)
        assertFalse(blockedUndo.accepted)
        assertEquals("NOTHING_TO_UNDO", (blockedUndo as CommandResult.Rejected).code)

        // Expired undo (> 20s)
        assertTrue(dispatcher.dispatch(CommandSource.UI, ChargerCommand.SetSceneMode(4)).accepted)
        fakeClockMs += 25_000L
        val expiredUndo = dispatcher.dispatch(CommandSource.UI, ChargerCommand.UndoLastCommand)
        assertEquals("UNDO_EXPIRED", (expiredUndo as CommandResult.Rejected).code)
    }

    @Test
    fun tcpGatewayEnforcesAuthHandshakeRawCommandBlockAndLoopbackBindDefault() = runTest {
        val dispatcher = CommandDispatcher(authManager, adapter, clockMs = { fakeClockMs })
        val gateway = TcpGatewayServer(
            authManager = authManager,
            dispatcher = dispatcher,
            bleManagerProvider = { null },
            onClientListChanged = {},
            logger = { _, _ -> }
        )

        val state = TcpGatewayServer.SessionAuthState()
        // Unauthenticated non-auth command rejected & closes connection
        val (unauthResp, closeUnauth) = gateway.processClientJsonLine("c1", "127.0.0.1", state, """{"cmd":"set_port","port":1,"enabled":false}""")
        assertTrue(closeUnauth)
        assertEquals("AUTH_REQUIRED", JSONObject(unauthResp).getString("code"))

        // Auth handshake with valid token succeeds
        authManager.setTcpGatewayEnabled(true)
        val token = authManager.getOrCreateToken()
        val (authResp, closeAuth) = gateway.processClientJsonLine("c1", "127.0.0.1", state, """{"cmd":"auth","token":"$token"}""")
        assertFalse(closeAuth)
        assertEquals("authenticated", JSONObject(authResp).getString("status"))
        assertTrue(state.authenticated)

        // Raw GATT / stack command rejected
        for (rawCmd in listOf("read", "write", "scan", "connect", "reset_bluetooth", "request_mtu", "discover")) {
            val (rawResp, _) = gateway.processClientJsonLine("c1", "127.0.0.1", state, """{"cmd":"$rawCmd"}""")
            assertEquals("COMMAND_FORBIDDEN", JSONObject(rawResp).getString("code"))
        }

        // High-level dispatcher command succeeds over authenticated TCP channel
        val (portResp, _) = gateway.processClientJsonLine("c1", "127.0.0.1", state, """{"cmd":"set_port","port":1,"enabled":false}""")
        assertEquals("ok", JSONObject(portResp).getString("status"))
        assertEquals(0x0E, adapter.settings.portSwitchBitmap)

        // Oversized payload triggers disconnect
        val hugeLine = "x".repeat(TcpGatewayServer.MAX_LINE_BYTES + 10)
        val (hugeResp, closeHuge) = gateway.processClientJsonLine("c1", "127.0.0.1", state, hugeLine)
        assertTrue(closeHuge)
        assertEquals("LINE_TOO_LARGE", JSONObject(hugeResp).getString("code"))

        // Verify loopback bind on ephemeral free port
        val freePort = ServerSocket(0).use { it.localPort }
        assertTrue(gateway.start(portOverride = freePort))
        assertEquals("127.0.0.1", gateway.boundHost)
        assertEquals(freePort, gateway.boundPort)
        gateway.stop()
    }
}
