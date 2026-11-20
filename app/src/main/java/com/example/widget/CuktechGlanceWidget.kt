package com.example.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.BleBridgeService
import com.example.MainActivity
import com.example.R
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.protocol.CuktechProtocol
import com.example.cuktech.protocol.PortTelemetry
import java.util.Locale

private val PARAM_PORT_ID = ActionParameters.Key<Int>("port_id")

class CuktechGlanceControlReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CuktechGlanceControlWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetConfigStore.removeConfig(context, it) }
    }
}

class CuktechGlanceCompactReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CuktechGlanceCompactWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetConfigStore.removeConfig(context, it) }
    }
}

class TogglePortWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val portId = parameters[PARAM_PORT_ID] ?: return
        val appCtx = context.applicationContext
        val mgr = BleBridgeService.cuktechManager ?: BleBridgeService.ensureCuktechManagersInitialized(appCtx)
        if (mgr.authState.value != CuktechAuthState.AUTHENTICATED) {
            mgr.commandDispatcher.dispatch(
                source = CommandSource.WIDGET,
                command = ChargerCommand.ConnectCharger(null),
                clientKey = "widget:connect"
            )
        } else {
            val curEnabled = mgr.ports.value[portId]?.enabled ?: true
            mgr.commandDispatcher.dispatch(
                source = CommandSource.WIDGET,
                command = ChargerCommand.SetPortEnabled(portId, !curEnabled),
                clientKey = "widget:port:$portId"
            )
        }
        CuktechWidgetUpdater.scheduleReactiveUpdate(appCtx, force = true)
    }
}

class CycleSceneWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val appCtx = context.applicationContext
        val mgr = BleBridgeService.cuktechManager ?: BleBridgeService.ensureCuktechManagersInitialized(appCtx)
        if (mgr.authState.value != CuktechAuthState.AUTHENTICATED) {
            mgr.commandDispatcher.dispatch(
                source = CommandSource.WIDGET,
                command = ChargerCommand.ConnectCharger(null),
                clientKey = "widget:connect"
            )
        } else {
            val curScene = mgr.settings.value.sceneMode
            val nextScene = if (curScene in 1..3) curScene + 1 else 1
            mgr.commandDispatcher.dispatch(
                source = CommandSource.WIDGET,
                command = ChargerCommand.SetSceneMode(nextScene),
                clientKey = "widget:scene"
            )
        }
        CuktechWidgetUpdater.scheduleReactiveUpdate(appCtx, force = true)
    }
}

class ConnectWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val appCtx = context.applicationContext
        val mgr = BleBridgeService.cuktechManager ?: BleBridgeService.ensureCuktechManagersInitialized(appCtx)
        mgr.commandDispatcher.dispatch(
            source = CommandSource.WIDGET,
            command = ChargerCommand.ConnectCharger(null),
            clientKey = "widget:connect"
        )
        CuktechWidgetUpdater.scheduleReactiveUpdate(appCtx, force = true)
    }
}

data class GlanceWidgetSnapshot(
    val authState: CuktechAuthState,
    val totalWatts: Double,
    val sceneCode: Int,
    val sessionWh: Double,
    val ports: Map<Int, PortTelemetry>,
    val sparklineBitmap: Bitmap?,
    val config: WidgetInstanceConfig
)

private fun buildSnapshot(context: Context, glanceId: GlanceId): GlanceWidgetSnapshot {
    val awm = GlanceAppWidgetManager(context)
    val appWidgetId = runCatching { awm.getAppWidgetId(glanceId) }.getOrDefault(-1)
    val config = WidgetConfigStore.loadConfig(context, appWidgetId)

    val mgr = BleBridgeService.cuktechManager
    val authState = mgr?.authState?.value ?: CuktechAuthState.DISCONNECTED
    val isConnected = authState == CuktechAuthState.AUTHENTICATED
    val totalWatts = if (isConnected) (mgr?.totalPowerWatts?.value ?: 0.0) else 0.0
    val sceneCode = mgr?.settings?.value?.sceneMode ?: 1
    val ports = mgr?.ports?.value ?: mapOf(
        1 to PortTelemetry(1, "USB-C1"),
        2 to PortTelemetry(2, "USB-C2"),
        3 to PortTelemetry(3, "USB-C3"),
        4 to PortTelemetry(4, "USB-A")
    )
    val sessionWh = (mgr?.activeSessions?.value?.values?.sumOf { it.whAccumulated } ?: 0.0)
        .coerceAtLeast(ports.values.sumOf { it.cumulativeWh })

    val sparkBmp = if (config.showSparkline && isConnected && totalWatts > 0.2) {
        val points = mgr?.powerChartHistory?.value?.map { it.totalWatts } ?: emptyList()
        renderMaterialSparklineBitmap(points, totalWatts)
    } else null

    return GlanceWidgetSnapshot(
        authState = authState,
        totalWatts = totalWatts,
        sceneCode = sceneCode,
        sessionWh = sessionWh,
        ports = ports,
        sparklineBitmap = sparkBmp,
        config = config
    )
}

private fun renderMaterialSparklineBitmap(points: List<Double>, currentWatts: Double): Bitmap {
    val width = 220
    val height = 56
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    val canvas = Canvas(bmp)
    canvas.drawColor(android.graphics.Color.TRANSPARENT)

    // Use a neutral high-contrast cyan-teal background strip subtly tinted for RGB_565 legibility
    bmp.eraseColor(android.graphics.Color.rgb(18, 32, 42))

    val samples = if (points.size >= 4) {
        points.takeLast(20)
    } else {
        listOf(currentWatts * 0.85, currentWatts * 0.92, currentWatts * 0.96, currentWatts)
    }
    val maxW = (samples.maxOrNull() ?: 10.0).coerceAtLeast(15.0) * 1.15
    val pad = 6f
    val usableH = height - pad * 2f
    val stepX = if (samples.size > 1) width.toFloat() / (samples.size - 1) else width.toFloat()

    val path = Path()
    samples.forEachIndexed { idx, v ->
        val x = idx * stepX
        val norm = (v / maxW).coerceIn(0.0, 1.0).toFloat()
        val y = (height - pad) - (norm * usableH)
        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(77, 208, 225)
        strokeWidth = 3.2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, stroke)
    return bmp
}

private fun buildOpenAppIntent(context: Context, destination: String): Intent {
    return Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("EXTRA_NAV_TAB", destination)
    }
}

class CuktechGlanceControlWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SIZE_COMPACT, SIZE_WIDE, SIZE_EXPANDED)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = buildSnapshot(context, id)
        val openIntent = buildOpenAppIntent(context, snapshot.config.tapDestination)

        provideContent {
            GlanceTheme {
                ControlWidgetContent(
                    context = context,
                    snapshot = snapshot,
                    openIntent = openIntent
                )
            }
        }
    }

    companion object {
        private val SIZE_COMPACT = DpSize(180.dp, 110.dp)
        private val SIZE_WIDE = DpSize(250.dp, 110.dp)
        private val SIZE_EXPANDED = DpSize(300.dp, 180.dp)

        suspend fun updateAll(context: Context) {
            val mgr = GlanceAppWidgetManager(context)
            val ids = mgr.getGlanceIds(CuktechGlanceControlWidget::class.java)
            ids.forEach { glanceId ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    prefs.toMutablePreferences()
                }
                CuktechGlanceControlWidget().update(context, glanceId)
            }
        }
    }
}

class CuktechGlanceCompactWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SIZE_MINI, SIZE_MEDIUM)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = buildSnapshot(context, id)
        val openIntent = buildOpenAppIntent(context, snapshot.config.tapDestination)

        provideContent {
            GlanceTheme {
                CompactWidgetContent(
                    context = context,
                    snapshot = snapshot,
                    openIntent = openIntent
                )
            }
        }
    }

    companion object {
        private val SIZE_MINI = DpSize(110.dp, 110.dp)
        private val SIZE_MEDIUM = DpSize(180.dp, 140.dp)

        suspend fun updateAll(context: Context) {
            val mgr = GlanceAppWidgetManager(context)
            val ids = mgr.getGlanceIds(CuktechGlanceCompactWidget::class.java)
            ids.forEach { glanceId ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    prefs.toMutablePreferences()
                }
                CuktechGlanceCompactWidget().update(context, glanceId)
            }
        }
    }
}

@Composable
private fun ControlWidgetContent(
    context: Context,
    snapshot: GlanceWidgetSnapshot,
    openIntent: Intent
) {
    val size = LocalSize.current
    val isConnected = snapshot.authState == CuktechAuthState.AUTHENTICATED
    val isConnecting = snapshot.authState == CuktechAuthState.CONNECTING_GATT ||
        snapshot.authState == CuktechAuthState.DISCOVERING_SERVICES ||
        snapshot.authState == CuktechAuthState.PHASE_A_INIT_A4 ||
        snapshot.authState == CuktechAuthState.PHASE_B_HANDSHAKE

    val statusText = when {
        isConnected -> context.getString(R.string.status_connected)
        isConnecting -> context.getString(R.string.status_connecting)
        else -> context.getString(R.string.status_disconnected)
    }

    val sceneLabel = CuktechProtocol.SCENE_MODES[snapshot.sceneCode] ?: "AI Mode"
    val emphasizedList = (1..4).filter { it in snapshot.config.emphasizedPorts }.ifEmpty { listOf(1, 2, 3, 4) }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(22.dp)
            .padding(12.dp)
    ) {
        // Header Row: Status dot + Wattage + Scene Pill
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = GlanceModifier
                    .size(10.dp)
                    .cornerRadius(5.dp)
                    .background(
                        if (isConnected) GlanceTheme.colors.primary else GlanceTheme.colors.outline
                    )
            ) {}
            Spacer(GlanceModifier.width(6.dp))
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(
                        if (isConnected) actionStartActivity(openIntent)
                        else actionRunCallback<ConnectWidgetAction>()
                    )
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f W", snapshot.totalWatts),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                val sub = if (isConnected && snapshot.config.showSessionWh && snapshot.sessionWh > 0.01) {
                    "$statusText · ${String.format(Locale.US, "%.2f Wh", snapshot.sessionWh)}"
                } else {
                    statusText
                }
                Text(
                    text = sub,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp
                    ),
                    maxLines = 1
                )
            }

            Box(
                modifier = GlanceModifier
                    .cornerRadius(14.dp)
                    .background(GlanceTheme.colors.secondaryContainer)
                    .clickable(actionRunCallback<CycleSceneWidgetAction>())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = sceneLabel,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSecondaryContainer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }
        }

        if (size.height >= 160.dp && snapshot.sparklineBitmap != null) {
            Spacer(GlanceModifier.height(6.dp))
            Image(
                provider = ImageProvider(snapshot.sparklineBitmap),
                contentDescription = context.getString(R.string.overview_live_trend),
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .cornerRadius(10.dp)
                    .clickable(actionStartActivity(openIntent))
            )
        }

        Spacer(GlanceModifier.height(8.dp))

        // Interactive M3 Port Tiles Row
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            emphasizedList.forEachIndexed { idx, pid ->
                if (idx > 0) Spacer(GlanceModifier.width(6.dp))
                val p = snapshot.ports[pid] ?: PortTelemetry(pid, explicitWidgetPortName(pid))
                val enabled = p.enabled
                val w = if (isConnected) p.powerW else 0.0
                val isCharging = isConnected && enabled && w > 0.2

                val bg = when {
                    !isConnected -> GlanceTheme.colors.surfaceVariant
                    !enabled -> GlanceTheme.colors.errorContainer
                    isCharging -> GlanceTheme.colors.primaryContainer
                    else -> GlanceTheme.colors.surfaceVariant
                }
                val fg = when {
                    !isConnected -> GlanceTheme.colors.onSurfaceVariant
                    !enabled -> GlanceTheme.colors.onErrorContainer
                    isCharging -> GlanceTheme.colors.onPrimaryContainer
                    else -> GlanceTheme.colors.onSurface
                }

                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .cornerRadius(14.dp)
                        .background(bg)
                        .clickable(
                            actionRunCallback<TogglePortWidgetAction>(
                                actionParametersOf(PARAM_PORT_ID to pid)
                            )
                        )
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = explicitWidgetPortName(pid),
                        style = TextStyle(color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1
                    )
                    Text(
                        text = if (!enabled && isConnected) "OFF" else String.format(Locale.US, "%.1fW", w),
                        style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    if (size.width >= 240.dp) {
                        Text(
                            text = when {
                                !isConnected -> "—"
                                !enabled -> context.getString(R.string.port_status_disabled)
                                isCharging -> p.protocolName
                                else -> context.getString(R.string.port_status_idle)
                            },
                            style = TextStyle(color = fg, fontSize = 9.sp),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactWidgetContent(
    context: Context,
    snapshot: GlanceWidgetSnapshot,
    openIntent: Intent
) {
    val isConnected = snapshot.authState == CuktechAuthState.AUTHENTICATED
    val sceneLabel = CuktechProtocol.SCENE_MODES[snapshot.sceneCode] ?: "AI"

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(22.dp)
            .padding(10.dp)
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(
                    if (isConnected) actionStartActivity(openIntent)
                    else actionRunCallback<ConnectWidgetAction>()
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .cornerRadius(4.dp)
                    .background(if (isConnected) GlanceTheme.colors.primary else GlanceTheme.colors.outline)
            ) {}
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = String.format(Locale.US, "%.1f W", snapshot.totalWatts),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1
            )
            Text(
                text = sceneLabel.take(6),
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = GlanceModifier.clickable(actionRunCallback<CycleSceneWidgetAction>()),
                maxLines = 1
            )
        }

        if (snapshot.sparklineBitmap != null) {
            Spacer(GlanceModifier.height(4.dp))
            Image(
                provider = ImageProvider(snapshot.sparklineBitmap),
                contentDescription = context.getString(R.string.overview_live_trend),
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .cornerRadius(8.dp)
                    .clickable(actionStartActivity(openIntent))
            )
        }

        Spacer(GlanceModifier.height(6.dp))

        // 2x2 Port Grid
        val rows = listOf(1 to 2, 3 to 4)
        Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            rows.forEachIndexed { rowIndex, (leftId, rightId) ->
                if (rowIndex > 0) Spacer(GlanceModifier.height(4.dp))
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    CompactPortCell(leftId, snapshot.ports[leftId], isConnected, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(4.dp))
                    CompactPortCell(rightId, snapshot.ports[rightId], isConnected, GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun CompactPortCell(
    portId: Int,
    port: PortTelemetry?,
    isConnected: Boolean,
    modifier: GlanceModifier
) {
    val enabled = port?.enabled ?: true
    val w = if (isConnected) (port?.powerW ?: 0.0) else 0.0
    val isCharging = isConnected && enabled && w > 0.2
    val bg = when {
        !isConnected -> GlanceTheme.colors.surfaceVariant
        !enabled -> GlanceTheme.colors.errorContainer
        isCharging -> GlanceTheme.colors.primaryContainer
        else -> GlanceTheme.colors.surfaceVariant
    }
    val fg = when {
        !isConnected -> GlanceTheme.colors.onSurfaceVariant
        !enabled -> GlanceTheme.colors.onErrorContainer
        isCharging -> GlanceTheme.colors.onPrimaryContainer
        else -> GlanceTheme.colors.onSurface
    }
    val shortName = when (portId) {
        1 -> "C1"
        2 -> "C2"
        3 -> "C3"
        4 -> "A"
        else -> "P$portId"
    }
    val wText = when {
        !isConnected -> "$shortName 0W"
        !enabled -> "$shortName OFF"
        w >= 10.0 -> String.format(Locale.US, "%s %.0fW", shortName, w)
        else -> String.format(Locale.US, "%s %.1fW", shortName, w)
    }

    Box(
        modifier = modifier
            .cornerRadius(10.dp)
            .background(bg)
            .clickable(
                actionRunCallback<TogglePortWidgetAction>(
                    actionParametersOf(PARAM_PORT_ID to portId)
                )
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = wText,
            style = TextStyle(color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium),
            maxLines = 1
        )
    }
}

private fun explicitWidgetPortName(portId: Int): String = when (portId) {
    1 -> "USB-C1"
    2 -> "USB-C2"
    3 -> "USB-C3"
    4 -> "USB-A"
    else -> "Port $portId"
}
