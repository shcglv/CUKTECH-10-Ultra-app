package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.*

import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.automation.TcpGatewayServer
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.ble.CuktechBleManager
import com.example.cuktech.cloud.XiaomiCloudClient
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.integrations.CloudIntegrations
import com.example.cuktech.diagnostics.BleDiagnostics
import com.example.cuktech.notifications.ChargingNotificationManager
import com.example.cuktech.notifications.LiveNotificationPayload
import com.example.cuktech.prefs.AppPreferencesRepository
import com.example.cuktech.protocol.CuktechProtocol
import com.example.cuktech.protocol.PortTelemetryDecoder
import com.example.cuktech.security.AutomationAuthManager
import com.example.cuktech.security.LogSanitizer
import com.example.cuktech.security.SecureCredentialStore
import com.example.cuktech.updater.GitHubApkUpdater
import com.example.cuktech.updater.ReleaseUpdateInfo
import com.example.cuktech.updater.UpdateDownloadState
import com.example.ui.i18n.withAppLocale

class BleBridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    fun getBluetoothGatt(): BluetoothGatt? = bluetoothGatt

    @Volatile
    var negotiatedMtu: Int = 23
        private set

    fun isWakeLockHeld(): Boolean = wakeLock?.isHeld == true

    // Sequential GATT task queue
    private val taskChannel = Channel<BleTask>(Channel.UNLIMITED)
    private var activeTaskDeferred: CompletableDeferred<Boolean>? = null
    @Volatile
    private var activeTaskReadBytes: ByteArray? = null

    private var isScanning = false
    private var scanCallbackInternal: ScanCallback? = null

    val companionBleState: Companion.BleConnectionState
        get() = _bleConnectionState.value

    val isServerRunning: Boolean
        get() = Companion.isServerRunning.value

    private val companionBleAddress: String?
        get() = _bleDeviceAddress.value

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    val wasActive = bluetoothGatt != null || _bleConnectionState.value != BleConnectionState.DISCONNECTED ||
                        (cuktechManager?.authState?.value != null && cuktechManager?.authState?.value != CuktechAuthState.DISCONNECTED)
                    log(LogLevel.LEVEL2, "Phone Bluetooth turned OFF. Disconnecting active session.")
                    cuktechManager?.disconnectCharger(isManual = false)
                    closeGattClean(triggerCooldown = wasActive)
                } else if (state == BluetoothAdapter.STATE_ON) {
                    val mgr = cuktechManager
                    if (mgr != null && mgr.isAutoReconnectConfigured.value && !mgr.userManuallyDisconnected && !mgr.lastConnectedMac.isNullOrBlank()) {
                        log(LogLevel.LEVEL2, "Phone Bluetooth turned back ON. Triggering auto-reconnect to ${mgr.lastConnectedMac}...")
                        serviceScope.launch {
                            delay(1200L)
                            if (mgr.authState.value == CuktechAuthState.DISCONNECTED) {
                                mgr.connectToCharger(mgr.lastConnectedMac!!)
                            }
                        }
                    }
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    cuktechManager?.isScreenOn?.value = true
                    cuktechManager?.kickAutoReconnectIfNeeded()
                    cuktechManager?.syncWakeLockWithPollingState()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    cuktechManager?.isScreenOn?.value = false
                    val isChargingPriority = cuktechManager?.logOnlyWhenPhoneCharging?.value == true && cuktechManager?.isPhoneCharging?.value == true
                    if (!isChargingPriority && cuktechManager?.pausePollingWhenScreenLocked?.value == true) {
                        cuktechManager?.finalizeAllActiveSessions()
                    }
                    cuktechManager?.syncWakeLockWithPollingState()
                }
            }
        }
    }

    private val powerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    cuktechManager?.isPhoneCharging?.value = true
                    cuktechManager?.syncWakeLockWithPollingState()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    cuktechManager?.isPhoneCharging?.value = false
                    if (cuktechManager?.logOnlyWhenPhoneCharging?.value == true) {
                        cuktechManager?.finalizeAllActiveSessions()
                    }
                    cuktechManager?.syncWakeLockWithPollingState()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureCuktechManagersInitialized(applicationContext)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        try {
            ContextCompat.registerReceiver(
                this,
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}

        // Listen for screen lock/unlock and phone charger plug/unplug to power telemetry policies
        try {
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(
                this,
                screenStateReceiver,
                screenFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val powerFilter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            ContextCompat.registerReceiver(
                this,
                powerStateReceiver,
                powerFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            // Initial power and screen state sync
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                applicationContext.registerReceiver(null, filter)
            }
            val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            cuktechManager?.isPhoneCharging?.value = isCharging

            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            cuktechManager?.isScreenOn?.value = powerManager?.isInteractive ?: true
        } catch (_: Exception) {}

        // Initialize state
        _wifiIp.value = getLocalIpAddress()

        // Start processing sequential BLE task queue
        startQueueProcessor()

        log(LogLevel.LEVEL2, "BLE Bridge Service created.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Started service, not bound
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_START_SERVER) {
            val port = intent.getIntExtra(EXTRA_PORT, 8080)
            startTcpServer(port)
        } else if (action == ACTION_STOP_SERVER) {
            stopTcpServer()
            dismissForegroundNotificationIfIdle()
        } else if (action == ACTION_CONNECT_CUKTECH) {
            createNotificationChannelAndStartForeground()
            val mac = intent.getStringExtra(EXTRA_MAC)
            cuktechManager?.connectToCharger(mac)
        } else if (action == ACTION_DISCONNECT_CUKTECH) {
            cuktechManager?.disconnectCharger()
            dismissForegroundNotificationIfIdle()
        } else if (action == ACTION_SCAN_CUKTECH) {
            cuktechManager?.startSearchForCuktechChargers(10000L)
        } else if (action == ACTION_ENSURE_SERVICE) {
            // Ensure service instance is initialized in memory for BLE scans/connections without showing notification yet
        } else if (action == ACTION_RESTORE_NOTIFICATION) {
            forceRestoreNotification()
        } else {
            dismissForegroundNotificationIfIdle()
        }

        return START_STICKY
    }

    fun startForegroundForBleConnection() {
        createNotificationChannelAndStartForeground()
    }

    fun dismissForegroundNotificationIfIdle() {
        val isBleActive = cuktechManager?.authState?.value != null &&
                cuktechManager?.authState?.value != com.example.cuktech.ble.CuktechAuthState.DISCONNECTED
        if (isBleActive) return

        val isAutoReconnectConfigured = cuktechManager?.isAutoReconnectConfigured?.value == true
        val isUserManual = cuktechManager?.userManuallyDisconnected == true
        val hasTargetMac = !cuktechManager?.lastConnectedMac.isNullOrBlank() || cuktechManager?.credentialStore?.activeDevice?.value != null

        // If Auto-Connect on Proximity is enabled and user did NOT manually tap disconnect,
        // keep the foreground service notification alive (without holding WakeLock in standby) so background auto-reconnect can run
        if (isAutoReconnectConfigured && !isUserManual && hasTargetMac) {
            releaseWakeLock()
            val lang = cuktechManager?.preferencesRepository?.preferences?.value?.appLanguage ?: "system"
            val locCtx = applicationContext.withAppLocale(lang)
            updateNotificationTelemetry(
                title = locCtx.getString(R.string.notif_standby_title),
                content = locCtx.getString(R.string.notif_standby_content),
                bigText = locCtx.getString(R.string.notif_standby_big)
            )
            return
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
            lastNotifSignature = ""
            val isServerActive = tcpGatewayServer?.isRunning == true
            if (!isServerActive) {
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    internal var wakeLock: PowerManager.WakeLock? = null

    internal fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cuktech::BackgroundKeepalive")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours safeguard
                log(LogLevel.LEVEL2, "Acquired partial CPU wake lock for active telemetry polling.")
            }
        } catch (e: Exception) {
            log(LogLevel.LEVEL1, "Error acquiring WakeLock: ${e.message}")
        }
    }

    internal fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                log(LogLevel.LEVEL2, "Released CPU wake lock.")
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        log(LogLevel.LEVEL2, "BLE Bridge Service destroying...")
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(powerStateReceiver)
        } catch (_: Exception) {}
        releaseWakeLock()
        cleanupServer()
        cuktechManager?.stopAllJobsForServiceDestroy()
        closeGattClean(triggerCooldown = false)
        stopBleScan()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    // --- Foreground Service Notification ---

    private val serviceStartTimeMs = System.currentTimeMillis()
    private var lastNotifSignature: String = ""

    private fun buildOpenPendingIntent(): PendingIntent {
        val openIntent = (packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_NAV_TAB", "OVERVIEW")
        }
        return PendingIntent.getActivity(
            this, 1001, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildDisconnectPendingIntent(): PendingIntent {
        val disconnectIntent = Intent(this, BleBridgeService::class.java).apply {
            action = ACTION_DISCONNECT_CUKTECH
        }
        return PendingIntent.getService(
            this, 0, disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannelAndStartForeground() {
        ChargingNotificationManager.ensureChannels(this)
        val prefs = cuktechManager?.preferencesRepository?.preferences?.value
        val liveEnabled = prefs?.liveUpdatesEnabled ?: true
        val locCtx = applicationContext.withAppLocale(prefs?.appLanguage ?: "system")
        val notif = ChargingNotificationManager.buildOngoingNotification(
            context = locCtx,
            payload = LiveNotificationPayload(
                title = "⚡ ${locCtx.getString(R.string.app_full_name)}",
                content = locCtx.getString(R.string.notif_connecting),
                bigText = locCtx.getString(R.string.notif_connecting),
                totalWatts = -1.0,
                nearestCountdownEndMs = null,
                activeLimitProgressPct = null,
                liveUpdatesEnabled = liveEnabled,
                serviceStartTimeMs = serviceStartTimeMs
            ),
            openPendingIntent = buildOpenPendingIntent(),
            disconnectPendingIntent = buildDisconnectPendingIntent()
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasBlePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else true

                if (hasBlePerm) {
                    startForeground(
                        NOTIFICATION_ID,
                        notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notif)
                }
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            android.util.Log.w("BleBridgeService", "Foreground service start deferred: ${e.message}")
            if (e !is SecurityException && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is android.app.ForegroundServiceStartNotAllowedException)) {
                log(LogLevel.LEVEL1, "Error starting foreground service: ${e.message}")
            }
        }
    }

    fun dismissConnectedIdleNotification() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
            lastNotifSignature = ""
        }
    }

    /**
     * Updates the ongoing / Live Update notification with live telemetry, countdown chronometer,
     * and Android 16 ProgressStyle without resetting the post timestamp unnecessarily.
     */
    fun updateNotificationTelemetry(
        title: String,
        content: String,
        bigText: String? = null,
        totalWatts: Double = -1.0,
        nearestCountdownEndMs: Long? = null,
        activeLimitProgressPct: Int? = null,
        liveUpdatesEnabled: Boolean = true
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        var isNotificationActive = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                isNotificationActive = nm.activeNotifications.any { it.id == NOTIFICATION_ID }
            } catch (_: Exception) {}
        }

        val resolvedBig = bigText ?: content
        val sig = "$title|$content|$resolvedBig|${nearestCountdownEndMs ?: -1}|${activeLimitProgressPct ?: -1}|$liveUpdatesEnabled"
        if (isNotificationActive && sig == lastNotifSignature) {
            return
        }
        lastNotifSignature = sig

        runCatching {
            val lang = cuktechManager?.preferencesRepository?.preferences?.value?.appLanguage ?: "system"
            val locCtx = applicationContext.withAppLocale(lang)
            val notif = ChargingNotificationManager.buildOngoingNotification(
                context = locCtx,
                payload = LiveNotificationPayload(
                    title = title,
                    content = content,
                    bigText = resolvedBig,
                    totalWatts = totalWatts,
                    nearestCountdownEndMs = nearestCountdownEndMs,
                    activeLimitProgressPct = activeLimitProgressPct,
                    liveUpdatesEnabled = liveUpdatesEnabled,
                    serviceStartTimeMs = serviceStartTimeMs
                ),
                openPendingIntent = buildOpenPendingIntent(),
                disconnectPendingIntent = buildDisconnectPendingIntent()
            )
            if (!isNotificationActive) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val hasBlePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        } else true
                        if (hasBlePerm) {
                            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                        } else {
                            startForeground(NOTIFICATION_ID, notif)
                        }
                    } else {
                        startForeground(NOTIFICATION_ID, notif)
                    }
                    return@runCatching
                } catch (_: Exception) {
                    // Fallback to standard notify when background startForeground is disallowed
                }
            }
            nm.notify(NOTIFICATION_ID, notif)
        }
    }

    fun forceRestoreNotification() {
        lastNotifSignature = ""
        cuktechManager?.let { mgr ->
            val prefs = mgr.preferencesRepository.preferences.value
            val liveEnabled = prefs.liveUpdatesEnabled
            val locCtx = applicationContext.withAppLocale(prefs.appLanguage)
            val locale = com.example.ui.i18n.resolveLocale(prefs.appLanguage)
            val unitW = locCtx.getString(R.string.unit_w)
            if (mgr.authState.value == CuktechAuthState.AUTHENTICATED) {
                val ports = mgr.ports.value
                val totalW = PortTelemetryDecoder.computeEffectiveTotalWatts(ports)
                val hasTimers = mgr.countdownRemainingSeconds.value.isNotEmpty() || mgr.usbALowCurrentRemainingSeconds.value != null
                val hasLimits = mgr.chargeLimits.value.values.any { it.enabled && !it.triggered }
                if (!mgr.isSmartCharging.value && !hasTimers && !hasLimits) {
                    dismissConnectedIdleNotification()
                    return
                }
                val c3a = PortTelemetryDecoder.resolveC3ASharedBus(ports)
                val title = if (totalW > 0.05) {
                    String.format(locale, "⚡ CUKTECH 10 Ultra · %.1f %s", totalW, unitW)
                } else {
                    locCtx.getString(R.string.notif_standby_title)
                }
                val content = if (totalW > 0.05) {
                    val parts = mutableListOf<String>()
                    ports[1]?.takeIf { it.powerW > 0.05 }?.let {
                        parts.add(String.format(locale, "C1: %.1f %s", it.powerW, unitW))
                    }
                    ports[2]?.takeIf { it.powerW > 0.05 }?.let {
                        parts.add(String.format(locale, "C2: %.1f %s", it.powerW, unitW))
                    }
                    if (c3a.isSharedActive) {
                        parts.add(String.format(locale, "C3+A: %.1f %s", c3a.sharedPowerW, unitW))
                    } else {
                        ports[3]?.takeIf { it.powerW > 0.05 }?.let {
                            parts.add(String.format(locale, "C3: %.1f %s", it.powerW, unitW))
                        }
                        ports[4]?.takeIf { it.powerW > 0.05 }?.let {
                            parts.add(String.format(locale, "A: %.1f %s", it.powerW, unitW))
                        }
                    }
                    if (parts.isNotEmpty()) parts.joinToString(" · ") else locCtx.getString(R.string.notif_active_output_fallback)
                } else locCtx.getString(R.string.notif_all_idle)
                updateNotificationTelemetry(title, content, totalWatts = totalW, liveUpdatesEnabled = liveEnabled)
            } else if (mgr.isAutoReconnectConfigured.value && !mgr.userManuallyDisconnected && !mgr.lastConnectedMac.isNullOrBlank()) {
                updateNotificationTelemetry(
                    title = locCtx.getString(R.string.notif_standby_title),
                    content = locCtx.getString(R.string.notif_standby_content),
                    bigText = locCtx.getString(R.string.notif_standby_big),
                    liveUpdatesEnabled = liveEnabled
                )
            }
        }
    }

    private var lastToastTimeMs: Long = 0L
    private var lastToastConnected: Boolean? = null

    /**
     * Shows a clean Android Toast alert when CUKTECH 10 Ultra connects or disconnects.
     * Debounced so rapid successive callbacks do not trigger duplicate toasts.
     */
    fun notifyAlertFlash(title: String, message: String, isConnected: Boolean) {
        val now = System.currentTimeMillis()
        if (lastToastConnected == isConnected && now - lastToastTimeMs < 2500L) {
            return
        }
        lastToastTimeMs = now
        lastToastConnected = isConnected

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val lang = cuktechManager?.preferencesRepository?.preferences?.value?.appLanguage ?: "system"
                val locCtx = applicationContext.withAppLocale(lang)
                val text = locCtx.getString(if (isConnected) R.string.toast_connected else R.string.toast_disconnected)
                android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    // --- TCP Socket Gateway Server Support (Delegated to hardened TcpGatewayServer) ---

    private fun startTcpServer(port: Int) {
        val mgr = cuktechManager ?: ensureCuktechManagersInitialized(applicationContext)
        val auth = automationAuthManager ?: AutomationAuthManager(applicationContext, mgr.credentialStore).also {
            automationAuthManager = it
        }
        val gw = tcpGatewayServer ?: TcpGatewayServer(
            authManager = auth,
            dispatcher = mgr.commandDispatcher,
            bleManagerProvider = { cuktechManager },
            onClientListChanged = { updateClientsList() },
            logger = { isErr, msg ->
                log(if (isErr) LogLevel.LEVEL1 else LogLevel.LEVEL2, msg)
            }
        ).also { tcpGatewayServer = it }

        if (!auth.config.value.tcpGatewayEnabled) {
            log(LogLevel.LEVEL1, "TCP Gateway is disabled. Enable opt-in TCP Gateway in Security settings first.")
            _isServerRunning.value = false
            return
        }

        _wifiIp.value = if (auth.config.value.tcpAllowLanBind) getLocalIpAddress() else TcpGatewayServer.LOOPBACK_HOST
        val started = gw.start(port)
        _isServerRunning.value = started && gw.isRunning
        if (started) {
            _serverPort.value = gw.boundPort
        }
        updateClientsList()
    }

    fun stopTcpServer() {
        log(LogLevel.LEVEL2, "Stopping TCP Gateway Server...")
        cleanupServer()
    }

    private fun cleanupServer() {
        tcpGatewayServer?.stop()
        _isServerRunning.value = false
        updateClientsList()
    }

    fun updateClientsList() {
        val list = tcpGatewayServer?.currentClientsMeta()?.map { client ->
            val authBadge = if (client.authenticated) "auth" else "pending"
            ClientInfo(id = "${client.id} ($authBadge)", ip = client.ip, port = client.port)
        }?.toMutableList() ?: mutableListOf()

        // Only register Cuktech 10 Ultra as an active client when genuinely AUTHENTICATED
        val cuktech = cuktechManager
        val bleAddr = _bleDeviceAddress.value
        if (cuktech != null && bleAddr != null && cuktech.authState.value == CuktechAuthState.AUTHENTICATED) {
            val devName = cuktech.deviceInfo.value.name.ifBlank { "CUKTECH 10 Ultra" }
            list.add(
                0,
                ClientInfo(
                    id = "$devName ($bleAddr)",
                    ip = "BLE Gateway",
                    port = _serverPort.value,
                    isCuktechCharger = true,
                    cuktechMac = bleAddr
                )
            )
        }
        _connectedClients.value = list
    }

    fun bootClientInternal(clientId: String) {
        val rawId = clientId.substringBefore(" (")
        log(LogLevel.LEVEL2, "Booting network client by request: $rawId")
        tcpGatewayServer?.disconnectClient(rawId)
    }

    fun broadcastJson(jsonString: String) {
        tcpGatewayServer?.broadcastTelemetryToAuthenticatedClients(jsonString)
    }

    // --- BLE Scan Implementation ---

    fun startNativeCuktechScan(durationMs: Long = 10000L, targetMac: String? = null) {
        if (bluetoothAdapter?.isEnabled != true) {
            log(LogLevel.LEVEL1, "Scan aborted: Bluetooth is turned OFF on your phone. Please enable Bluetooth.")
            cuktechManager?.onScanStopped()
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            log(LogLevel.LEVEL1, "Scan failed: Bluetooth LE Scanner not available")
            cuktechManager?.onScanStopped()
            return
        }
        if (isScanning) {
            stopBleScan()
        }
        isScanning = true
        log(LogLevel.LEVEL2, "Starting CUKTECH 10 Ultra BLE discovery scan (${durationMs / 1000}s)...")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                cuktechManager?.onScanResult(result)
            }

            override fun onScanFailed(errorCode: Int) {
                log(LogLevel.LEVEL1, "CUKTECH Scan failed with error code: $errorCode")
                isScanning = false
                cuktechManager?.onScanStopped()
            }
        }
        scanCallbackInternal = callback
        try {
            val filters = mutableListOf<ScanFilter>()
            val cleanMac = targetMac?.uppercase()?.trim()
            if (!cleanMac.isNullOrBlank()) {
                try {
                    filters.add(ScanFilter.Builder().setDeviceAddress(cleanMac).build())
                } catch (_: Exception) {}
            }
            if (filters.isEmpty()) {
                filters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(CuktechProtocol.MI_SERVICE_UUID)).build())
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            scanner.startScan(filters, settings, callback)
            serviceScope.launch {
                delay(durationMs)
                if (isScanning && scanCallbackInternal === callback) {
                    stopBleScan()
                }
            }
        } catch (e: SecurityException) {
            log(LogLevel.LEVEL1, "Security Exception during CUKTECH scan: ${e.message}")
            isScanning = false
            cuktechManager?.onScanStopped()
        }
    }

    fun stopBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val callback = scanCallbackInternal
        if (scanner != null && callback != null && isScanning) {
            try {
                scanner.stopScan(callback)
                log(LogLevel.LEVEL2, "BLE Scan stopped.")
            } catch (e: SecurityException) {
                log(LogLevel.LEVEL1, "Security Exception stopping scan: ${e.message}")
            }
        }
        isScanning = false
        scanCallbackInternal = null
        cuktechManager?.onScanStopped()
    }

    // --- BLE Connection & GATT Core ---

    fun connectBleNative(address: String, autoConnect: Boolean = false): Boolean {
        if (bluetoothAdapter?.isEnabled != true) {
            log(LogLevel.LEVEL1, "Connection aborted: Bluetooth is turned OFF on your phone. Please enable Bluetooth.")
            setBleConnectionState(BleConnectionState.DISCONNECTED, null)
            cuktechManager?.onConnectionFailed("Bluetooth is turned off")
            return false
        }
        if (isScanning) {
            stopBleScan()
        }
        if (bluetoothGatt != null) {
            closeGattClean(triggerCooldown = false)
        }
        val device = try {
            bluetoothAdapter?.getRemoteDevice(address.uppercase().trim())
        } catch (e: Exception) {
            null
        }
        if (device == null) {
            log(LogLevel.LEVEL1, "Invalid MAC address: $address")
            return false
        }
        negotiatedMtu = 23
        log(LogLevel.LEVEL2, "Connecting to CUKTECH BLE peripheral: $address (autoConnect=$autoConnect)")
        setBleConnectionState(BleConnectionState.CONNECTING, address)
        return try {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this, autoConnect, gattCallback)
            }
            bluetoothGatt != null
        } catch (e: SecurityException) {
            log(LogLevel.LEVEL1, "Security exception connecting: ${e.message}")
            setBleConnectionState(BleConnectionState.DISCONNECTED, null)
            false
        } catch (e: Exception) {
            log(LogLevel.LEVEL1, "Unexpected connection failure: ${e.message}")
            setBleConnectionState(BleConnectionState.DISCONNECTED, null)
            false
        }
    }

    fun disconnectBleNative() {
        closeGattClean()
    }

    fun requestGattRssi(): Boolean {
        val g = bluetoothGatt ?: return false
        return try {
            g.readRemoteRssi()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun enqueueBleRead(serviceUuid: UUID, charUuid: UUID): ByteArray? {
        val deferred = CompletableDeferred<ByteArray?>()
        val task = BleTask.Read(serviceUuid, charUuid) { ok, bytes ->
            deferred.complete(if (ok) bytes else null)
        }
        taskChannel.trySend(task)
        return withTimeoutOrNull(3500L) { deferred.await() }
    }

    suspend fun enqueueBleWrite(
        serviceUuid: UUID,
        charUuid: UUID,
        data: ByteArray,
        withResponse: Boolean
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val task = BleTask.Write(serviceUuid, charUuid, data, withResponse) { ok, _ ->
            deferred.complete(ok)
        }
        taskChannel.trySend(task)
        return withTimeoutOrNull(3500L) { deferred.await() } ?: false
    }

    suspend fun enqueueBleNotify(serviceUuid: UUID, charUuid: UUID, enable: Boolean): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val task = BleTask.RegisterNotify(serviceUuid, charUuid, enable) { ok, _ ->
            deferred.complete(ok)
        }
        taskChannel.trySend(task)
        return withTimeoutOrNull(3500L) { deferred.await() } ?: false
    }

    fun refreshGattCache(gatt: BluetoothGatt?): Boolean {
        if (gatt == null) return false
        return try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            (refreshMethod.invoke(gatt) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun closeGattClean(triggerCooldown: Boolean = true) {
        val wasActive = bluetoothGatt != null || _bleConnectionState.value != BleConnectionState.DISCONNECTED ||
            (cuktechManager?.authState?.value != null && cuktechManager?.authState?.value != CuktechAuthState.DISCONNECTED)
        val gatt = bluetoothGatt
        bluetoothGatt = null
        if (gatt != null) {
            log(LogLevel.LEVEL2, "Executing GATT handle teardown...")
            try {
                gatt.disconnect()
            } catch (_: Exception) {}
            try {
                gatt.close()
            } catch (e: Exception) {
                log(LogLevel.LEVEL1, "Error calling close: ${e.message}")
            }
        }

        activeTaskDeferred?.complete(false)
        activeTaskDeferred = null
        activeTaskReadBytes = null
        while (taskChannel.tryReceive().isSuccess) {
            // Drain pending tasks
        }

        setBleConnectionState(BleConnectionState.DISCONNECTED, null)
        negotiatedMtu = 23
        if (triggerCooldown && wasActive) {
            cuktechManager?.triggerDisconnectCooldown(2000L)
        }
        dismissForegroundNotificationIfIdle()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) {
                try { gatt.close() } catch (_: Exception) {}
                return
            }
            val addr = gatt.device.address
            cuktechManager?.onConnectionStateChange(addr, status, newState)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    log(LogLevel.LEVEL2, "BLE state connected! Initiating Auto-MTU Negotiation...")
                    if (cuktechManager == null) {
                        setBleConnectionState(BleConnectionState.CONNECTED, addr)
                    } else {
                        setBleConnectionState(BleConnectionState.CONNECTING, addr)
                    }

                    // Broadcast connected event to authenticated gateway clients
                    broadcastJson(JSONObject().apply {
                        put("status", "connected")
                        put("timestamp", System.currentTimeMillis())
                    }.toString())

                    // Request balanced connection priority for steady background telemetry
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    } catch (_: Exception) {}

                    // Auto-MTU Negotiation immediately
                    try {
                        log(LogLevel.LEVEL2, "Requesting hardware MTU of 512...")
                        val ok = gatt.requestMtu(512)
                        if (!ok) {
                            log(LogLevel.LEVEL1, "MTU request could not be pre-scheduled. Discovering services with default MTU...")
                            gatt.discoverServices()
                        }
                    } catch (e: SecurityException) {
                        log(LogLevel.LEVEL1, "Permission error requesting MTU: ${e.message}")
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    log(LogLevel.LEVEL2, "BLE peripheral disconnected cleanly.")
                    closeGattClean()
                }
            } else {
                log(LogLevel.LEVEL1, "GATT status error: $status (newState=$newState). Safe disconnect triggered.")
                if (status == 133) {
                    val refreshed = refreshGattCache(gatt)
                    log(LogLevel.LEVEL2, "GATT 133 workaround: refreshGattCache=$refreshed")
                }
                closeGattClean()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cuktechManager?.onRemoteRssi(rssi)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                log(LogLevel.LEVEL2, "Negotiated packet MTU successfully: $mtu bytes")
            } else {
                negotiatedMtu = 23
                log(LogLevel.LEVEL1, "MTU auto-negotiation failed with status: $status")
            }

            // Discover services always after MTU change (success or fail)
            try {
                log(LogLevel.LEVEL2, "Initiating service discovery...")
                gatt.discoverServices()
            } catch (e: SecurityException) {
                log(LogLevel.LEVEL1, "Security Exception in discoverServices: ${e.message}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val hasMiService = gatt.getService(CuktechProtocol.MI_SERVICE_UUID) != null
                if (!hasMiService) {
                    val refreshed = refreshGattCache(gatt)
                    log(LogLevel.LEVEL1, "MiOT service 0xFE95 missing on discovery; refreshGattCache=$refreshed")
                }
                log(LogLevel.LEVEL2, "BLE GATT services discovered successfully!")
                cuktechManager?.onServicesDiscovered(gatt, status)

                gatt.services.forEach { service ->
                    log(LogLevel.LEVEL3, "Service: ${service.uuid}")
                    service.characteristics.forEach { char ->
                        val props = getCharPropertiesString(char.properties)
                        log(LogLevel.LEVEL3, "  -> Char: ${char.uuid} ($props)")
                    }
                }
            } else {
                refreshGattCache(gatt)
                log(LogLevel.LEVEL1, "Service discovery failed with status $status (refreshed GATT cache)")
                cuktechManager?.onServicesDiscovered(gatt, status)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        private fun handleCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val ok = (status == BluetoothGatt.GATT_SUCCESS)
            log(LogLevel.LEVEL3, "GATT Read Callback -> Status=$status, Size=${value.size}")

            if (ok) {
                activeTaskReadBytes = value.copyOf()
            }

            activeTaskDeferred?.complete(ok)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicChanged(gatt, characteristic, characteristic.value ?: byteArrayOf())
        }

        private fun handleCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val cUuid = characteristic.uuid?.toString() ?: ""
            log(LogLevel.LEVEL3, "GATT Telemetry Stream Rx from [$cUuid]")
            characteristic.uuid?.let { uuid ->
                cuktechManager?.onCharacteristicChanged(uuid, value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val ok = (status == BluetoothGatt.GATT_SUCCESS)
            log(LogLevel.LEVEL3, "GATT Write Callback -> Status=$status")
            activeTaskDeferred?.complete(ok)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val ok = (status == BluetoothGatt.GATT_SUCCESS)
            log(LogLevel.LEVEL3, "GATT Descriptor Write Callback -> Status=$status")
            activeTaskDeferred?.complete(ok)
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            log(LogLevel.LEVEL2, "GATT Service Changed. Rediscovering...")
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                log(LogLevel.LEVEL1, "Security Exception in discoverServices: ${e.message}")
            }
        }
    }

    // --- Sequential BLE Hardware Queue Processing ---

    private fun startQueueProcessor() {
        serviceScope.launch(Dispatchers.Default) {
            for (task in taskChannel) {
                executeTask(task)
            }
        }
    }

    private suspend fun executeTask(task: BleTask) {
        val deferred = CompletableDeferred<Boolean>()
        activeTaskDeferred = deferred
        activeTaskReadBytes = null

        val initiated = when (task) {
            is BleTask.Read -> performRead(task)
            is BleTask.Write -> performWrite(task)
            is BleTask.RegisterNotify -> performNotify(task)
        }

        if (!initiated) {
            task.onResult(false, null)
            activeTaskDeferred = null
            return
        }

        val isWriteNoResp = (task is BleTask.Write && !task.withResponse)
        // Wait for async callback with 3-second timeout fallback (or 250ms for no-response writes)
        try {
            val timeoutMs = if (isWriteNoResp) 250L else 3000L
            val ok = withTimeout(timeoutMs) {
                deferred.await()
            }
            task.onResult(ok, activeTaskReadBytes)
        } catch (e: TimeoutCancellationException) {
            if (isWriteNoResp) {
                // WRITE_TYPE_NO_RESPONSE was accepted by hardware stack; complete as success
                task.onResult(true, null)
            } else {
                log(LogLevel.LEVEL1, "BLE Hardware task timeout (3s) triggered for: ${task.charUuid}")
                task.onResult(false, null)
            }
        } finally {
            activeTaskDeferred = null
            activeTaskReadBytes = null
        }
    }

    private fun performRead(task: BleTask.Read): Boolean {
        val gatt = bluetoothGatt ?: return false
        val s = gatt.getService(task.serviceUuid) ?: return false
        val c = s.getCharacteristic(task.charUuid) ?: return false

        return try {
            log(LogLevel.LEVEL3, "Initiating hardware characteristic read: ${task.charUuid}")
            gatt.readCharacteristic(c)
        } catch (e: SecurityException) {
            log(LogLevel.LEVEL1, "SecurityException initiating read: ${e.message}")
            false
        } catch (e: Exception) {
            log(LogLevel.LEVEL1, "Error initiating gatt.readCharacteristic: ${e.message}")
            false
        }
    }

    private fun performWrite(task: BleTask.Write): Boolean {
        val gatt = bluetoothGatt ?: return false
        val s = gatt.getService(task.serviceUuid) ?: return false
        val c = s.getCharacteristic(task.charUuid) ?: return false

        return try {
            val canWriteWithResp = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val canWriteNoResp = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val targetWriteType = when {
                task.withResponse && canWriteWithResp -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                canWriteNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                canWriteWithResp -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else -> if (task.withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            c.writeType = targetWriteType
            log(LogLevel.LEVEL3, "Writing ${task.data.size}B to ${task.charUuid} (type=$targetWriteType, props=${c.properties})")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val code = gatt.writeCharacteristic(c, task.data, c.writeType)
                if (code != BluetoothStatusCodes.SUCCESS) {
                    log(LogLevel.LEVEL1, "writeCharacteristic rejected for ${task.charUuid} (code=$code)")
                }
                code == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = task.data
                @Suppress("DEPRECATION")
                val ok = gatt.writeCharacteristic(c)
                if (!ok) {
                    log(LogLevel.LEVEL1, "writeCharacteristic returned false for ${task.charUuid}")
                }
                ok
            }
        } catch (e: SecurityException) {
            log(LogLevel.LEVEL1, "SecurityException initiating write: ${e.message}")
            false
        } catch (e: Exception) {
            log(LogLevel.LEVEL1, "Error initiating gatt.writeCharacteristic: ${e.message}")
            false
        }
    }

    private fun performNotify(task: BleTask.RegisterNotify): Boolean {
        val gatt = bluetoothGatt ?: return false
        val s = gatt.getService(task.serviceUuid) ?: return false
        val c = s.getCharacteristic(task.charUuid) ?: return false

        return try {
            log(LogLevel.LEVEL3, "Enabling locally notifications for: ${task.charUuid} -> ${task.enable}")
            val okLocally = gatt.setCharacteristicNotification(c, task.enable)
            if (!okLocally) {
                log(LogLevel.LEVEL1, "GATT setCharacteristicNotification locally returned failure")
                return false
            }

            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val desc = c.getDescriptor(cccdUuid)
            if (desc == null) {
                log(LogLevel.LEVEL1, "CCCD descriptor not found on char: ${task.charUuid}")
                return false
            }

            val value = if (task.enable) {
                if ((c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val code = gatt.writeDescriptor(desc, value)
                code == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                desc.value = value
                @Suppress("DEPRECATION")
                val ok = gatt.writeDescriptor(desc)
                ok
            }
        } catch (e: SecurityException) {
            log(LogLevel.LEVEL1, "SecurityException writing CCCD descriptor: ${e.message}")
            false
        } catch (e: Exception) {
            log(LogLevel.LEVEL1, "Error writing CCCD descriptor: ${e.message}")
            false
        }
    }

    // --- Static state mappings & UI contracts ---

    companion object {
        const val ACTION_START_SERVER = "com.joxit.cuktech.action.START_SERVER"
        const val ACTION_STOP_SERVER = "com.joxit.cuktech.action.STOP_SERVER"
        const val ACTION_ENSURE_SERVICE = "com.joxit.cuktech.action.ENSURE_SERVICE"
        const val ACTION_RESTORE_NOTIFICATION = "com.joxit.cuktech.RESTORE_NOTIFICATION"
        const val ACTION_CONNECT_CUKTECH = "com.joxit.cuktech.CONNECT_CHARGER"
        const val ACTION_DISCONNECT_CUKTECH = "com.joxit.cuktech.DISCONNECT_CHARGER"
        const val ACTION_SCAN_CUKTECH = "com.joxit.cuktech.SCAN_CHARGER"
        const val ACTION_TELEMETRY_UPDATE = "com.joxit.cuktech.TELEMETRY_UPDATE"
        const val EXTRA_PORT = "com.joxit.cuktech.extra.PORT"
        const val EXTRA_MAC = "mac"
        const val NOTIFICATION_ID = 10101

        private var instance: BleBridgeService? = null
        fun getInstance(): BleBridgeService? = instance

        @Volatile
        var credentialStore: SecureCredentialStore? = null
            private set

        @Volatile
        var persistence: CuktechPersistence? = null
            private set

        @Volatile
        var preferencesRepository: AppPreferencesRepository? = null
            private set

        @Volatile
        var automationAuthManager: AutomationAuthManager? = null
            private set

        @Volatile
        var tcpGatewayServer: TcpGatewayServer? = null
            private set

        @Volatile
        var cuktechManager: CuktechBleManager? = null
            private set

        @Volatile
        var bleDiagnostics: BleDiagnostics? = null
            private set

        @Volatile
        var cloudIntegrations: CloudIntegrations? = null
            private set

        val xiaomiCloudClient = XiaomiCloudClient().apply {
            logger = { isError, msg ->
                addLogMessage(if (isError) LogLevel.LEVEL1 else LogLevel.LEVEL2, msg)
            }
        }

        @Synchronized
        fun ensureCuktechManagersInitialized(context: Context): CuktechBleManager {
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences("cuktech_app_prefs", Context.MODE_PRIVATE)
            val savedLvl = prefs.getString("console_log_level", LogLevel.LEVEL1.name) ?: LogLevel.LEVEL1.name
            _logLevel.value = runCatching { LogLevel.valueOf(savedLvl) }.getOrDefault(LogLevel.LEVEL1)

            val store = credentialStore ?: SecureCredentialStore(appCtx).also { credentialStore = it }
            val db = persistence ?: CuktechPersistence(appCtx).also { persistence = it }
            val prefsRepo = preferencesRepository ?: AppPreferencesRepository(appCtx).also { preferencesRepository = it }
            val authMgr = automationAuthManager ?: AutomationAuthManager(appCtx, store).also { automationAuthManager = it }
            bleDiagnostics ?: BleDiagnostics(appCtx).also { bleDiagnostics = it }
            val mgr = cuktechManager ?: CuktechBleManager(appCtx, store, db, prefsRepo, authMgr).also { createdMgr ->
                cuktechManager = createdMgr
                val integrations = CloudIntegrations(appCtx, store) { portId, enabled ->
                    CoroutineScope(Dispatchers.Default).launch {
                        createdMgr.commandDispatcher.dispatch(
                            source = CommandSource.AUTOMATION_RULE,
                            command = ChargerCommand.SetPortEnabled(portId, enabled),
                            clientKey = "cloud_integrations"
                        )
                    }
                }
                cloudIntegrations = integrations
                tcpGatewayServer = TcpGatewayServer(
                    authManager = authMgr,
                    dispatcher = createdMgr.commandDispatcher,
                    bleManagerProvider = { cuktechManager },
                    onClientListChanged = { refreshClientsList() },
                    logger = { isErr, msg ->
                        addLogMessage(if (isErr) LogLevel.LEVEL1 else LogLevel.LEVEL2, msg)
                    }
                )

                createdMgr.onTelemetryUpdated = { portsMap, totalWatts, devInfo ->
                    integrations.onTelemetryUpdate(portsMap, totalWatts)
                    broadcastTaskerTelemetry(appCtx, portsMap, totalWatts, devInfo.macAddress)
                    tcpGatewayServer?.let { gw ->
                        if (gw.isRunning) {
                            val portsJson = JSONArray()
                            portsMap.values.sortedBy { it.portId }.forEach { p ->
                                portsJson.put(JSONObject().apply {
                                    put("port", p.portId)
                                    put("label", p.portLabel)
                                    put("v", p.voltageV)
                                    put("a", p.currentA)
                                    put("w", p.powerW)
                                    put("protocol", p.protocolName)
                                    put("enabled", p.enabled)
                                })
                            }
                            val payload = JSONObject().apply {
                                put("status", "telemetry")
                                put("timestamp", System.currentTimeMillis())
                                put("total_watts", totalWatts)
                                put("ports", portsJson)
                            }.toString()
                            gw.broadcastTelemetryToAuthenticatedClients(payload)
                        }
                    }
                    com.example.widget.CuktechWidgetUpdater.scheduleReactiveUpdate(appCtx)
                }

                val syncScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
                syncScope.launch {
                    createdMgr.authState.collect { authState ->
                        val mac = createdMgr.deviceInfo.value.macAddress.ifBlank { null }
                        when (authState) {
                            CuktechAuthState.AUTHENTICATED -> {
                                _bleConnectionState.value = BleConnectionState.CONNECTED
                                _bleDeviceAddress.value = mac
                                refreshClientsList()
                                addLogMessage(LogLevel.LEVEL2, "CUKTECH 10 Ultra connected & authenticated ($mac). Active client registered.")
                            }
                            CuktechAuthState.DISCONNECTED -> {
                                if (_bleConnectionState.value != BleConnectionState.DISCONNECTED) {
                                    _bleConnectionState.value = BleConnectionState.DISCONNECTED
                                    _bleDeviceAddress.value = null
                                    refreshClientsList()
                                    addLogMessage(LogLevel.LEVEL2, "CUKTECH 10 Ultra disconnected.")
                                }
                            }
                            CuktechAuthState.CONNECTING_GATT,
                            CuktechAuthState.DISCOVERING_SERVICES,
                            CuktechAuthState.PHASE_A_INIT_A4,
                            CuktechAuthState.PHASE_B_HANDSHAKE -> {
                                _bleConnectionState.value = BleConnectionState.CONNECTING
                                if (mac != null) _bleDeviceAddress.value = mac
                                addLogMessage(LogLevel.LEVEL2, "CUKTECH 10 Ultra BLE: ${authState.label}")
                            }
                            CuktechAuthState.AUTH_FAILED -> {
                                addLogMessage(LogLevel.LEVEL1, "CUKTECH 10 Ultra authentication failed. Verify MiOT token / Xiaomi account credentials.")
                            }
                            else -> {}
                        }
                        com.example.widget.CuktechWidgetUpdater.scheduleReactiveUpdate(appCtx)
                    }
                }

                syncScope.launch {
                    createdMgr.settings.collect {
                        com.example.widget.CuktechWidgetUpdater.scheduleReactiveUpdate(appCtx)
                    }
                }

                syncScope.launch {
                    createdMgr.statusMessage.collect { msg ->
                        if (msg.isNotBlank() && msg != "Ready" && msg != "Disconnected") {
                            addLogMessage(LogLevel.LEVEL2, "[Cuktech] $msg")
                        }
                    }
                }
            }
            return mgr
        }

        fun broadcastTaskerTelemetry(
            context: Context,
            portsMap: Map<Int, com.example.cuktech.protocol.PortTelemetry>,
            totalWatts: Double,
            macAddress: String
        ) {
            runCatching {
                val intent = Intent(ACTION_TELEMETRY_UPDATE).apply {
                    putExtra("mac", macAddress)
                    putExtra("total_watts", totalWatts)
                    for ((pid, p) in portsMap) {
                        putExtra("port_${pid}_v", p.voltageV)
                        putExtra("port_${pid}_a", p.currentA)
                        putExtra("port_${pid}_w", p.powerW)
                        putExtra("port_${pid}_protocol", p.protocolName)
                        putExtra("port_${pid}_enabled", p.enabled)
                    }
                }
                context.sendBroadcast(intent)
            }
        }

        enum class BleConnectionState {
            DISCONNECTED,
            CONNECTING,
            CONNECTED
        }

        enum class LogLevel {
            LEVEL1, // Errors Only
            LEVEL2, // Standard
            LEVEL3  // Full Telemetry
        }

        data class ClientInfo(
            val id: String,
            val ip: String,
            val port: Int,
            val connectedAt: Long = System.currentTimeMillis(),
            val isCuktechCharger: Boolean = false,
            val cuktechMac: String? = null
        )
        data class LogMessage(val timestamp: Long, val level: LogLevel, val message: String)

        private val _isServerRunning = MutableStateFlow(false)
        val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

        private val _serverPort = MutableStateFlow(8080)
        val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

        private val _wifiIp = MutableStateFlow("Disconnected / No Wifi")
        val wifiIp: StateFlow<String> = _wifiIp.asStateFlow()

        private val _connectedClients = MutableStateFlow<List<ClientInfo>>(emptyList())
        val connectedClients: StateFlow<List<ClientInfo>> = _connectedClients.asStateFlow()

        private val _bleConnectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
        val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState.asStateFlow()

        private val _bleDeviceAddress = MutableStateFlow<String?>(null)
        val bleDeviceAddress: StateFlow<String?> = _bleDeviceAddress.asStateFlow()

        private val _logLevel = MutableStateFlow(LogLevel.LEVEL1)
        val logLevel: StateFlow<LogLevel> = _logLevel.asStateFlow()

        private val logsList = Collections.synchronizedList(LinkedList<LogMessage>())
        private val _logs = MutableStateFlow<List<LogMessage>>(emptyList())
        val logs: StateFlow<List<LogMessage>> = _logs.asStateFlow()

        private val _updateAvailable = MutableStateFlow(false)
        val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

        private val _releaseUpdateInfo = MutableStateFlow<ReleaseUpdateInfo?>(null)
        val releaseUpdateInfo: StateFlow<ReleaseUpdateInfo?> = _releaseUpdateInfo.asStateFlow()

        private val _updateDownloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
        val updateDownloadState: StateFlow<UpdateDownloadState> = _updateDownloadState.asStateFlow()

        fun notifyAlertFlash(title: String, message: String, isConnected: Boolean) {
            instance?.notifyAlertFlash(title, message, isConnected)
        }

        fun checkForUpdates(onCompleted: ((Result<ReleaseUpdateInfo>) -> Unit)? = null) {
            CoroutineScope(Dispatchers.IO).launch {
                val res = GitHubApkUpdater.checkLatestRelease(BuildConfig.VERSION_NAME)
                res.onSuccess { info ->
                    _releaseUpdateInfo.value = info
                    _updateAvailable.value = info.isUpdateAvailable
                }
                onCompleted?.invoke(res)
            }
        }

        fun downloadAndVerifyLatestApk(context: Context) {
            val info = _releaseUpdateInfo.value ?: return
            CoroutineScope(Dispatchers.IO).launch {
                GitHubApkUpdater.downloadAndVerifyApk(context.applicationContext, info) { state ->
                    _updateDownloadState.value = state
                }
            }
        }

        fun resetUpdateDownloadState() {
            _updateDownloadState.value = UpdateDownloadState.Idle
        }

        fun setLogLevel(level: LogLevel, context: Context? = null) {
            _logLevel.value = level
            updateLogsFlow()
            val ctx = context ?: instance?.applicationContext
            ctx?.getSharedPreferences("cuktech_app_prefs", Context.MODE_PRIVATE)
                ?.edit()
                ?.putString("console_log_level", level.name)
                ?.apply()
        }

        fun clearLogs() {
            logsList.clear()
            updateLogsFlow()
        }

        fun addLogMessage(level: LogLevel, message: String) {
            val sanitized = LogSanitizer.sanitize(message)
            val msg = LogMessage(System.currentTimeMillis(), level, sanitized)
            logsList.add(msg)
            while (logsList.size > 1000) {
                logsList.removeAt(0)
            }
            updateLogsFlow()
        }

        private fun updateLogsFlow() {
            val filterLvl = _logLevel.value
            val listCopy = synchronized(logsList) { ArrayList(logsList) }
            val filtered = listCopy.filter { msg ->
                when (filterLvl) {
                    LogLevel.LEVEL1 -> msg.level == LogLevel.LEVEL1
                    LogLevel.LEVEL2 -> msg.level == LogLevel.LEVEL1 || msg.level == LogLevel.LEVEL2
                    LogLevel.LEVEL3 -> true
                }
            }
            _logs.value = filtered
        }

        fun refreshClientsList() {
            instance?.updateClientsList() ?: run {
                val cuktech = cuktechManager
                val bleAddr = _bleDeviceAddress.value
                val list = tcpGatewayServer?.currentClientsMeta()?.map { c ->
                    val authBadge = if (c.authenticated) "auth" else "pending"
                    ClientInfo(id = "${c.id} ($authBadge)", ip = c.ip, port = c.port)
                }?.toMutableList() ?: mutableListOf()
                if (cuktech != null && bleAddr != null && cuktech.authState.value == CuktechAuthState.AUTHENTICATED) {
                    val devName = cuktech.deviceInfo.value.name.ifBlank { "CUKTECH 10 Ultra" }
                    list.add(
                        0,
                        ClientInfo(
                            id = "$devName ($bleAddr)",
                            ip = "BLE Gateway",
                            port = _serverPort.value,
                            isCuktechCharger = true,
                            cuktechMac = bleAddr
                        )
                    )
                }
                _connectedClients.value = list
            }
        }

        fun disconnectClientExternal(id: String) {
            val cuktech = cuktechManager
            val currentChargerClient = _connectedClients.value.firstOrNull { it.id == id && it.isCuktechCharger }
            if (currentChargerClient != null) {
                cuktech?.disconnectCharger()
                addLogMessage(LogLevel.LEVEL2, "CUKTECH 10 Ultra disconnected by user request.")
            } else {
                instance?.bootClientInternal(id)
            }
        }

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                if (interfaces != null) {
                    for (intf in Collections.list(interfaces)) {
                        val addrs = intf.inetAddresses
                        for (addr in Collections.list(addrs)) {
                            if (!addr.isLoopbackAddress) {
                                val sAddr = addr.hostAddress ?: continue
                                if (!sAddr.contains(':')) {
                                    return sAddr
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            return "127.0.0.1"
        }
    }

    private fun setBleConnectionState(state: BleConnectionState, address: String?) {
        _bleConnectionState.value = state
        _bleDeviceAddress.value = address
        updateClientsList()
        if (state == BleConnectionState.CONNECTED && address != null) {
            log(LogLevel.LEVEL2, "CUKTECH 10 Ultra connected ($address) - registered as active client")
        } else if (state == BleConnectionState.DISCONNECTED) {
            log(LogLevel.LEVEL2, "BLE peripheral disconnected")
        }
    }

    private fun log(level: LogLevel, valString: String) {
        addLogMessage(level, valString)
    }

    private fun getCharPropertiesString(properties: Int): String {
        val list = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) list.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list.add("WRITE_NO_RESPONSE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list.add("INDICATE")
        return if (list.isEmpty()) "NONE" else list.joinToString("|")
    }
}

// --- Sequential BLE task queue model ---

sealed class BleTask(val charUuid: UUID, val onResult: (Boolean, ByteArray?) -> Unit) {
    class Read(val serviceUuid: UUID, charUuid: UUID, onResult: (Boolean, ByteArray?) -> Unit) : BleTask(charUuid, onResult)
    class Write(val serviceUuid: UUID, charUuid: UUID, val data: ByteArray, val withResponse: Boolean, onResult: (Boolean, ByteArray?) -> Unit) : BleTask(charUuid, onResult)
    class RegisterNotify(val serviceUuid: UUID, charUuid: UUID, val enable: Boolean, onResult: (Boolean, ByteArray?) -> Unit) : BleTask(charUuid, onResult)
}
