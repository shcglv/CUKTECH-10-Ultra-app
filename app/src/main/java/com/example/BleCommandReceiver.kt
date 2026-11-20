package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.security.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver enabling opt-in ADB and external automation with per-install token validation.
 * Disabled by default; every action checks `AutomationAuthManager.config.adbBroadcastEnabled` and
 * requires `--es auth_token "<token>"` validated via constant-time comparison and CommandDispatcher.
 */
class BleCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appCtx = context.applicationContext
        val mgr = BleBridgeService.ensureCuktechManagersInitialized(appCtx)
        val authMgr = mgr.authManager
        val token = intent.getStringExtra("auth_token")

        val clientKey = "adb:$action"
        val cfg = authMgr.config.value
        if (!cfg.adbBroadcastEnabled) {
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Security][ADB] Rejected '$action': ADB/Broadcast automation is disabled (opt-in required)."
            )
            return
        }
        if (authMgr.isAuthLockedOut(clientKey)) {
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Security][ADB] Rejected '$action': source temporarily locked out after failed auth attempts."
            )
            return
        }

        // Server start/stop & status query must also pass constant-time token validation + rate limits
        val tokenValid = authMgr.verifyTokenConstantTime(token)
        authMgr.recordAuthResult(clientKey, tokenValid)
        if (!tokenValid) {
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                LogSanitizer.sanitize("[Security][ADB] Rejected '$action': invalid or missing auth_token.")
            )
            return
        }
        if (!authMgr.allowCommandExecution("adb:global", maxPerWindow = 20, windowMs = 10_000L)) {
            BleBridgeService.addLogMessage(
                BleBridgeService.Companion.LogLevel.LEVEL1,
                "[Security][ADB] Rejected '$action': rate limit exceeded."
            )
            return
        }

        when (action) {
            ACTION_START, ACTION_LEGACY_START -> {
                val port = intent.getIntExtra("port", intent.getIntExtra(BleBridgeService.EXTRA_PORT, 8080))
                startServiceWithAction(appCtx, BleBridgeService.ACTION_START_SERVER) {
                    putExtra(BleBridgeService.EXTRA_PORT, port)
                }
                return
            }
            ACTION_STOP, ACTION_LEGACY_STOP -> {
                val serviceIntent = Intent(appCtx, BleBridgeService::class.java).apply {
                    this.action = BleBridgeService.ACTION_STOP_SERVER
                }
                appCtx.stopService(serviceIntent)
                return
            }
            ACTION_CONNECT_CHARGER -> {
                val mac = intent.getStringExtra("mac")
                startServiceWithAction(appCtx, BleBridgeService.ACTION_CONNECT_CUKTECH) {
                    putExtra(BleBridgeService.EXTRA_MAC, mac)
                }
                return
            }
            ACTION_SCAN_CHARGER -> {
                startServiceWithAction(appCtx, BleBridgeService.ACTION_SCAN_CUKTECH)
                return
            }
            ACTION_QUERY_STATUS -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        mgr.commandDispatcher.dispatch(
                            source = CommandSource.ADB_BROADCAST,
                            command = ChargerCommand.RefreshTelemetry,
                            authToken = token,
                            clientKey = clientKey
                        )
                        BleBridgeService.broadcastTaskerTelemetry(
                            context = appCtx,
                            portsMap = mgr.ports.value,
                            totalWatts = mgr.totalPowerWatts.value,
                            macAddress = mgr.deviceInfo.value.macAddress
                        )
                    } finally {
                        pending.finish()
                    }
                }
                return
            }
        }

        val mappedCommand: ChargerCommand? = when (action) {
            ACTION_DISCONNECT_CHARGER -> ChargerCommand.DisconnectCharger(isManual = true)
            ACTION_SET_PORT_STATE -> ChargerCommand.SetPortEnabled(
                portId = intent.getIntExtra("port", 1),
                enabled = intent.getBooleanExtra("enabled", true)
            )
            ACTION_SET_SCENE_MODE -> ChargerCommand.SetSceneMode(intent.getIntExtra("mode", 1))
            ACTION_SET_SCREEN_TIMEOUT -> ChargerCommand.SetScreenTimeout(intent.getIntExtra("timeout", 1))
            ACTION_SET_CHARGE_LIMIT -> ChargerCommand.SetPortChargeLimit(
                portId = intent.getIntExtra("port", 1),
                whTarget = intent.getFloatExtra("wh_target", 0f).toDouble(),
                mode = intent.getStringExtra("mode") ?: "once",
                enabled = intent.getBooleanExtra("enabled", true)
            )
            ACTION_SET_AUTO_CONNECT -> ChargerCommand.SetAutoReconnect(intent.getBooleanExtra("enabled", true))
            ACTION_SET_PORT_LOGGING -> ChargerCommand.SetPortLogging(
                portId = intent.getIntExtra("port", 1),
                enabled = intent.getBooleanExtra("enabled", true)
            )
            ACTION_SET_TELEMETRY_ENABLED -> ChargerCommand.SetTelemetryEnabled(intent.getBooleanExtra("enabled", true))
            ACTION_SET_LOG_ONLY_WHEN_CHARGING -> ChargerCommand.SetLogOnlyWhenPhoneCharging(intent.getBooleanExtra("enabled", false))
            ACTION_SET_PAUSE_POLLING_WHEN_LOCKED -> ChargerCommand.SetPausePollingWhenLocked(intent.getBooleanExtra("enabled", false))
            ACTION_FORCE_SOFT_RESET -> ChargerCommand.SoftReset
            ACTION_MASTER_KILLSWITCH -> ChargerCommand.MasterKillswitch
            ACTION_UNDO_LAST_COMMAND -> ChargerCommand.UndoLastCommand
            else -> null
        }

        if (mappedCommand != null) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val res = mgr.commandDispatcher.dispatch(
                        source = CommandSource.ADB_BROADCAST,
                        command = mappedCommand,
                        authToken = token,
                        clientKey = clientKey
                    )
                    if (!res.accepted) {
                        BleBridgeService.addLogMessage(
                            BleBridgeService.Companion.LogLevel.LEVEL1,
                            "[ADB] $action rejected: ${res.message}"
                        )
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun startServiceWithAction(
        context: Context,
        actionName: String,
        block: Intent.() -> Unit = {}
    ) {
        val serviceIntent = Intent(context, BleBridgeService::class.java).apply {
            action = actionName
            block()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val ACTION_START = "com.cramqin.blecuktech10.START_SERVER"
        const val ACTION_STOP = "com.cramqin.blecuktech10.STOP_SERVER"
        const val ACTION_LEGACY_START = "com.example.action.START_SERVER"
        const val ACTION_LEGACY_STOP = "com.example.action.STOP_SERVER"

        const val ACTION_CONNECT_CHARGER = "com.cramqin.blecuktech10.CONNECT_CHARGER"
        const val ACTION_DISCONNECT_CHARGER = "com.cramqin.blecuktech10.DISCONNECT_CHARGER"
        const val ACTION_SCAN_CHARGER = "com.cramqin.blecuktech10.SCAN_CHARGER"
        const val ACTION_SET_PORT_STATE = "com.cramqin.blecuktech10.SET_PORT_STATE"
        const val ACTION_SET_SCENE_MODE = "com.cramqin.blecuktech10.SET_SCENE_MODE"
        const val ACTION_SET_SCREEN_TIMEOUT = "com.cramqin.blecuktech10.SET_SCREEN_TIMEOUT"
        const val ACTION_SET_CHARGE_LIMIT = "com.cramqin.blecuktech10.SET_CHARGE_LIMIT"
        const val ACTION_SET_AUTO_CONNECT = "com.cramqin.blecuktech10.SET_AUTO_CONNECT"
        const val ACTION_SET_PORT_LOGGING = "com.cramqin.blecuktech10.SET_PORT_LOGGING"
        const val ACTION_SET_TELEMETRY_ENABLED = "com.cramqin.blecuktech10.SET_TELEMETRY_ENABLED"
        const val ACTION_SET_LOG_ONLY_WHEN_CHARGING = "com.cramqin.blecuktech10.SET_LOG_ONLY_WHEN_CHARGING"
        const val ACTION_SET_PAUSE_POLLING_WHEN_LOCKED = "com.cramqin.blecuktech10.SET_PAUSE_POLLING_WHEN_LOCKED"
        const val ACTION_FORCE_SOFT_RESET = "com.cramqin.blecuktech10.FORCE_SOFT_RESET"
        const val ACTION_MASTER_KILLSWITCH = "com.cramqin.blecuktech10.MASTER_KILLSWITCH"
        const val ACTION_UNDO_LAST_COMMAND = "com.cramqin.blecuktech10.UNDO_LAST_COMMAND"
        const val ACTION_QUERY_STATUS = "com.cramqin.blecuktech10.QUERY_STATUS"
    }
}
