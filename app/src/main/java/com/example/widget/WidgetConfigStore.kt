package com.example.widget

import android.content.Context
import com.example.BleBridgeService
import com.example.cuktech.ble.CuktechAuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

data class WidgetInstanceConfig(
    val emphasizedPorts: Set<Int> = setOf(1, 2, 3, 4),
    val showSparkline: Boolean = true,
    val showSessionWh: Boolean = true,
    val tapDestination: String = "OVERVIEW"
)

object WidgetConfigStore {
    private const val PREFS_NAME = "cuktech_widget_instance_prefs"

    fun saveConfig(context: Context, appWidgetId: Int, config: WidgetInstanceConfig) {
        if (appWidgetId <= 0) return
        val portsCsv = config.emphasizedPorts.filter { it in 1..4 }.ifEmpty { setOf(1, 2, 3, 4) }
            .sorted().joinToString(",")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("ports_$appWidgetId", portsCsv)
            .putBoolean("sparkline_$appWidgetId", config.showSparkline)
            .putBoolean("wh_$appWidgetId", config.showSessionWh)
            .putString("dest_$appWidgetId", config.tapDestination)
            .apply()
    }

    fun loadConfig(context: Context, appWidgetId: Int): WidgetInstanceConfig {
        if (appWidgetId <= 0) return WidgetInstanceConfig()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawPorts = prefs.getString("ports_$appWidgetId", "1,2,3,4") ?: "1,2,3,4"
        val ports = rawPorts.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..4 }.toSet()
            .ifEmpty { setOf(1, 2, 3, 4) }
        val spark = prefs.getBoolean("sparkline_$appWidgetId", true)
        val wh = prefs.getBoolean("wh_$appWidgetId", true)
        val dest = prefs.getString("dest_$appWidgetId", "OVERVIEW")?.takeIf {
            it in setOf("OVERVIEW", "PORTS", "ANALYTICS", "SETTINGS")
        } ?: "OVERVIEW"
        return WidgetInstanceConfig(
            emphasizedPorts = ports,
            showSparkline = spark,
            showSessionWh = wh,
            tapDestination = dest
        )
    }

    fun removeConfig(context: Context, appWidgetId: Int) {
        if (appWidgetId <= 0) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("ports_$appWidgetId")
            .remove("sparkline_$appWidgetId")
            .remove("wh_$appWidgetId")
            .remove("dest_$appWidgetId")
            .apply()
    }
}

/**
 * Event-driven Glance widget updater with snapshot deduplication and rate limiting.
 * Never wakes up idle widgets periodically and skips duplicate updates when no visible field changed.
 */
object CuktechWidgetUpdater {
    private val updateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile
    private var lastSignature: String = ""
    @Volatile
    private var lastAuthGroup: String = ""
    private val lastUpdateWallMs = AtomicLong(0L)

    fun computeStateSignature(
        authState: CuktechAuthState,
        totalWatts: Double,
        sceneMode: Int,
        portBitmap: Int,
        usbALowCurrent: Boolean,
        portPowers: List<Pair<Int, Double>>,
        portProtocols: List<Pair<Int, String>>,
        sessionWhSum: Double
    ): String {
        val totalTenths = (totalWatts * 10.0).roundToInt()
        val whTenths = (sessionWhSum * 10.0).roundToInt()
        val pDesc = portPowers.sortedBy { it.first }.joinToString(",") { (id, w) ->
            val proto = portProtocols.firstOrNull { it.first == id }?.second.orEmpty()
            "$id:${(w * 10.0).roundToInt()}:$proto"
        }
        return String.format(
            Locale.US,
            "%s|%d|%d|%d|%b|%d|%s",
            authState.name,
            totalTenths,
            sceneMode,
            portBitmap,
            usbALowCurrent,
            whTenths,
            pDesc
        )
    }

    fun shouldDispatchUpdate(
        signature: String,
        authGroup: String,
        nowMs: Long,
        force: Boolean = false
    ): Boolean {
        if (force) return true
        if (signature == lastSignature) return false
        val structuralChange = authGroup != lastAuthGroup
        val elapsed = nowMs - lastUpdateWallMs.get()
        return structuralChange || elapsed >= 1000L
    }

    fun scheduleReactiveUpdate(context: Context, force: Boolean = false) {
        val appCtx = context.applicationContext
        val mgr = BleBridgeService.cuktechManager
        val authState = mgr?.authState?.value ?: CuktechAuthState.DISCONNECTED
        val settings = mgr?.settings?.value
        val ports = mgr?.ports?.value ?: emptyMap()
        val totalW = if (authState == CuktechAuthState.AUTHENTICATED) (mgr?.totalPowerWatts?.value ?: 0.0) else 0.0
        val sessionWh = (mgr?.activeSessions?.value?.values?.sumOf { it.whAccumulated } ?: 0.0)
            .coerceAtLeast(ports.values.sumOf { it.cumulativeWh })

        val sig = computeStateSignature(
            authState = authState,
            totalWatts = totalW,
            sceneMode = settings?.sceneMode ?: 1,
            portBitmap = settings?.portSwitchBitmap ?: 0x0F,
            usbALowCurrent = settings?.usbALowCurrent ?: false,
            portPowers = (1..4).map { id -> id to (ports[id]?.powerW ?: 0.0) },
            portProtocols = (1..4).map { id -> id to (ports[id]?.protocolName ?: "Normal") },
            sessionWhSum = sessionWh
        )
        val authGroup = authState.name
        val nowMs = System.currentTimeMillis()
        if (!shouldDispatchUpdate(sig, authGroup, nowMs, force)) {
            return
        }
        lastSignature = sig
        lastAuthGroup = authGroup
        lastUpdateWallMs.set(nowMs)

        updateScope.launch {
            runCatching { CuktechGlanceControlWidget.updateAll(appCtx) }
            runCatching { CuktechGlanceCompactWidget.updateAll(appCtx) }
        }
    }

    fun markDispatchedForTest(signature: String, authGroup: String, nowMs: Long) {
        lastSignature = signature
        lastAuthGroup = authGroup
        lastUpdateWallMs.set(nowMs)
    }

    fun resetDeduplicationForTest() {
        lastSignature = ""
        lastAuthGroup = ""
        lastUpdateWallMs.set(0L)
    }
}
