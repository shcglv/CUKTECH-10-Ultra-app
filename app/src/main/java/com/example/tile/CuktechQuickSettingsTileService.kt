package com.example.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.BleBridgeService
import com.example.MainActivity
import com.example.R
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.ble.CuktechAuthState
import com.example.ui.i18n.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Android Quick Settings Tile (#178, #263) that displays live charger power/USB-C1 status
 * and toggles USB-C1 (or reconnects / opens the app when disconnected) through [CommandDispatcher].
 */
class CuktechQuickSettingsTileService : TileService() {

    private var tileScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onStopListening() {
        tileScope?.cancel()
        tileScope = null
        super.onStopListening()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val appCtx = applicationContext
        val mgr = BleBridgeService.cuktechManager ?: BleBridgeService.ensureCuktechManagersInitialized(appCtx)
        val auth = mgr.authState.value

        if (auth == CuktechAuthState.AUTHENTICATED) {
            val c1Enabled = mgr.ports.value[1]?.enabled ?: true
            val scope = tileScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob()).also { tileScope = it }
            scope.launch {
                mgr.commandDispatcher.dispatch(
                    source = CommandSource.WIDGET,
                    command = ChargerCommand.SetPortEnabled(1, !c1Enabled),
                    clientKey = "qs_tile:c1"
                )
                refreshTile()
            }
        } else if (mgr.credentialStore.activeDevice.value != null || mgr.credentialStore.devices.value.isNotEmpty()) {
            val scope = tileScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob()).also { tileScope = it }
            scope.launch {
                mgr.commandDispatcher.dispatch(
                    source = CommandSource.WIDGET,
                    command = ChargerCommand.ConnectCharger(null),
                    clientKey = "qs_tile:connect"
                )
                refreshTile()
            }
        } else {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        launchIntent,
                        android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launchIntent)
            }
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val appCtx = applicationContext
        val mgr = BleBridgeService.cuktechManager
        val locCtx = appCtx.withAppLocale(mgr?.preferencesRepository?.preferences?.value?.appLanguage ?: "system")

        if (mgr == null || mgr.authState.value != CuktechAuthState.AUTHENTICATED) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = locCtx.getString(R.string.qs_tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = locCtx.getString(R.string.status_disconnected)
            }
        } else {
            val c1 = mgr.ports.value[1]
            val c1On = c1?.enabled ?: true
            val totalW = mgr.totalPowerWatts.value
            tile.state = if (c1On) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = locCtx.getString(R.string.qs_tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val c1State = if (c1On) {
                    String.format(Locale.US, "%.1f W", c1?.powerW ?: 0.0)
                } else {
                    locCtx.getString(R.string.widget_off)
                }
                tile.subtitle = String.format(Locale.US, "%.1fW · C1 %s", totalW, c1State)
            }
        }
        runCatching { tile.updateTile() }
    }
}
