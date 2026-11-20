package com.example.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.BleBridgeService
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tasker / Locale Action Plugin background receiver.
 * Fires when Tasker executes a configured CUKTECH 10 Ultra Action Plugin step.
 * Enforces opt-in Tasker channel activation and constant-time per-install secret token validation
 * through [CommandDispatcher].
 */
class TaskerFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != "com.twofortyfouram.locale.intent.action.FIRE_SETTING") return

        val bundle = intent.getBundleExtra("com.twofortyfouram.locale.intent.extra.BUNDLE") ?: return
        val taskAction = bundle.getString("ACTION") ?: return
        val authToken = bundle.getString("AUTH_TOKEN")

        val mgr = BleBridgeService.ensureCuktechManagersInitialized(context.applicationContext)

        val mappedCommand: ChargerCommand = when (taskAction) {
            "CONNECT" -> ChargerCommand.ConnectCharger(bundle.getString("MAC"))
            "DISCONNECT" -> ChargerCommand.DisconnectCharger(isManual = true)
            "SET_PORT" -> ChargerCommand.SetPortEnabled(
                portId = bundle.getInt("PORT", 1),
                enabled = bundle.getBoolean("STATE", true)
            )
            "SET_MODE" -> ChargerCommand.SetSceneMode(bundle.getInt("MODE", 1))
            "SET_TIMEOUT" -> ChargerCommand.SetScreenTimeout(bundle.getInt("TIMEOUT", 4))
            "SET_IDLE_SCREEN_OFF" -> ChargerCommand.SetIdleScreenOff(bundle.getBoolean("STATE", true))
            "SET_LOW_CURRENT" -> ChargerCommand.SetUsbALowCurrent(bundle.getBoolean("STATE", true))
            "SET_PORT_TIMER" -> ChargerCommand.SetPortCountdown(
                portId = bundle.getInt("PORT", 1),
                minutes = bundle.getInt("TIMER_MINS", 0)
            )
            "SET_CHARGE_LIMIT" -> ChargerCommand.SetPortChargeLimit(
                portId = bundle.getInt("PORT", 1),
                whTarget = bundle.getDouble("WH_TARGET", bundle.getFloat("wh_target", 0f).toDouble()),
                mode = bundle.getString("LIMIT_MODE", "once"),
                enabled = bundle.getBoolean("STATE", true)
            )
            "SOFT_RESET" -> ChargerCommand.SoftReset
            "MASTER_KILLSWITCH" -> ChargerCommand.MasterKillswitch
            "UNDO_LAST_COMMAND" -> ChargerCommand.UndoLastCommand
            "SET_AUTO_CONNECT" -> ChargerCommand.SetAutoReconnect(bundle.getBoolean("STATE", true))
            "SET_PORT_LOGGING" -> ChargerCommand.SetPortLogging(
                portId = bundle.getInt("PORT", 1),
                enabled = bundle.getBoolean("STATE", true)
            )
            "SET_TELEMETRY" -> ChargerCommand.SetTelemetryEnabled(bundle.getBoolean("STATE", true))
            "SET_LOG_WHEN_CHARGING" -> ChargerCommand.SetLogOnlyWhenPhoneCharging(bundle.getBoolean("STATE", false))
            "SET_PAUSE_WHEN_LOCKED" -> ChargerCommand.SetPausePollingWhenLocked(bundle.getBoolean("STATE", false))
            else -> return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val res = mgr.commandDispatcher.dispatch(
                    source = CommandSource.TASKER,
                    command = mappedCommand,
                    authToken = authToken,
                    clientKey = "tasker:$taskAction"
                )
                if (!res.accepted) {
                    BleBridgeService.addLogMessage(
                        BleBridgeService.Companion.LogLevel.LEVEL1,
                        "[Tasker] $taskAction rejected: ${res.message}"
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
