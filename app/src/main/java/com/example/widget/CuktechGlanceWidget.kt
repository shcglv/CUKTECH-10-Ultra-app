package com.example.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
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
import androidx.glance.layout.fillMaxHeight
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
import androidx.glance.color.ColorProvider
import androidx.compose.ui.graphics.Color
import com.example.BleBridgeService
import com.example.MainActivity
import com.example.R
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.protocol.C3ASharedBusState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.protocol.PortTelemetryDecoder
import com.example.ui.i18n.withAppLocale
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

class CuktechGlancePortReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CuktechGlancePortWidget()

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
    val c3aBus: C3ASharedBusState,
    val sparklinePoints: List<Double>,
    val focusedPortId: Int,
    val focusedPortSparkline: List<Double>,
    val focusedPortSessionWh: Double,
    val config: WidgetInstanceConfig
)

private fun buildSnapshot(context: Context, glanceId: GlanceId): GlanceWidgetSnapshot {
    val awm = GlanceAppWidgetManager(context)
    val appWidgetId = runCatching { awm.getAppWidgetId(glanceId) }.getOrDefault(-1)
    val config = WidgetConfigStore.loadConfig(context, appWidgetId)

    val mgr = BleBridgeService.cuktechManager ?: BleBridgeService.ensureCuktechManagersInitialized(context.applicationContext)
    val authState = mgr.authState.value
    val isConnected = authState == CuktechAuthState.AUTHENTICATED
    val ports = mgr.ports.value.ifEmpty {
        mapOf(
            1 to PortTelemetry(1, "USB-C1"),
            2 to PortTelemetry(2, "USB-C2"),
            3 to PortTelemetry(3, "USB-C3"),
            4 to PortTelemetry(4, "USB-A")
        )
    }
    val c3aBus = if (isConnected) PortTelemetryDecoder.resolveC3ASharedBus(ports) else C3ASharedBusState()
    val totalWatts = if (isConnected) PortTelemetryDecoder.computeEffectiveTotalWatts(ports) else 0.0
    val sceneCode = mgr.settings.value.sceneMode
    val sessionWh = mgr.activeSessions.value.values.sumOf { it.whAccumulated }
        .coerceAtLeast(ports.values.sumOf { it.cumulativeWh })

    val points = if (config.showSparkline && isConnected && totalWatts > 0.2) {
        mgr.powerChartHistory.value.map { it.totalWatts }.takeLast(24)
    } else {
        emptyList()
    }

    val focusedPortId = config.emphasizedPorts.firstOrNull()?.coerceIn(1, 4) ?: 1
    val focusedPortPower = ports[focusedPortId]?.powerW ?: 0.0
    val focusedPortPoints = if (config.showSparkline && isConnected && focusedPortPower > 0.2) {
        mgr.powerChartHistory.value.map { it.portWatts[focusedPortId] ?: 0.0 }.takeLast(24)
    } else {
        emptyList()
    }
    val focusedSessionWh = mgr.activeSessions.value[focusedPortId]?.whAccumulated
        ?: (ports[focusedPortId]?.cumulativeWh ?: 0.0)

    return GlanceWidgetSnapshot(
        authState = authState,
        totalWatts = totalWatts,
        sceneCode = sceneCode,
        sessionWh = sessionWh,
        ports = ports,
        c3aBus = c3aBus,
        sparklinePoints = points,
        focusedPortId = focusedPortId,
        focusedPortSparkline = focusedPortPoints,
        focusedPortSessionWh = focusedSessionWh,
        config = config
    )
}

/**
 * Renders an MD3 Expressive transparent ARGB_8888 sparkline with dynamic primary tint,
 * smooth cubic spline, soft vertical fill gradient, and subtle endpoint dot.
 */
private fun renderExpressiveSparklineBitmap(
    points: List<Double>,
    currentWatts: Double,
    strokeArgb: Int
): Bitmap {
    val width = 280
    val height = 64
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(android.graphics.Color.TRANSPARENT)
    val canvas = Canvas(bmp)

    val samples = if (points.size >= 4) {
        points
    } else {
        listOf(currentWatts * 0.85, currentWatts * 0.91, currentWatts * 0.96, currentWatts)
    }
    val maxW = (samples.maxOrNull() ?: 10.0).coerceAtLeast(15.0) * 1.15
    val padX = 6f
    val padY = 8f
    val usableW = width - padX * 2f
    val usableH = height - padY * 2f
    val stepX = if (samples.size > 1) usableW / (samples.size - 1) else usableW

    val coords = samples.mapIndexed { idx, v ->
        val x = padX + idx * stepX
        val norm = (v / maxW).coerceIn(0.0, 1.0).toFloat()
        val y = (height - padY) - (norm * usableH)
        x to y
    }

    val strokePath = Path()
    val fillPath = Path()
    coords.forEachIndexed { idx, (x, y) ->
        if (idx == 0) {
            strokePath.moveTo(x, y)
            fillPath.moveTo(x, height.toFloat())
            fillPath.lineTo(x, y)
        } else {
            val (px, py) = coords[idx - 1]
            val midX = (px + x) / 2f
            strokePath.cubicTo(midX, py, midX, y, x, y)
            fillPath.cubicTo(midX, py, midX, y, x, y)
        }
    }
    val (lastX, lastY) = coords.last()
    fillPath.lineTo(lastX, height.toFloat())
    fillPath.close()

    val r = android.graphics.Color.red(strokeArgb)
    val g = android.graphics.Color.green(strokeArgb)
    val b = android.graphics.Color.blue(strokeArgb)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(
            0f,
            padY,
            0f,
            height.toFloat(),
            android.graphics.Color.argb(72, r, g, b),
            android.graphics.Color.argb(0, r, g, b),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawPath(fillPath, fillPaint)

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeArgb
        strokeWidth = 4.0f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(strokePath, strokePaint)

    val dotHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(64, r, g, b)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(lastX, lastY, 7.5f, dotHalo)

    val dotCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeArgb
        style = Paint.Style.FILL
    }
    canvas.drawCircle(lastX, lastY, 4.0f, dotCore)

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
        val mgr = BleBridgeService.cuktechManager ?: BleBridgeService.ensureCuktechManagersInitialized(context.applicationContext)
        val locCtx = context.withAppLocale(mgr.preferencesRepository.preferences.value.appLanguage)
        val snapshot = buildSnapshot(context, id)
        val openIntent = buildOpenAppIntent(context, snapshot.config.tapDestination)

        provideContent {
            GlanceTheme {
                ControlWidgetContent(
                    locCtx = locCtx,
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
        val mgr = BleBridgeService.cuktechManager ?: BleBridgeService.ensureCuktechManagersInitialized(context.applicationContext)
        val locCtx = context.withAppLocale(mgr.preferencesRepository.preferences.value.appLanguage)
        val snapshot = buildSnapshot(context, id)
        val openIntent = buildOpenAppIntent(context, snapshot.config.tapDestination)

        provideContent {
            GlanceTheme {
                CompactWidgetContent(
                    locCtx = locCtx,
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

class CuktechGlancePortWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SIZE_MINI, SIZE_MEDIUM)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val mgr = BleBridgeService.cuktechManager ?: BleBridgeService.ensureCuktechManagersInitialized(context.applicationContext)
        val locCtx = context.withAppLocale(mgr.preferencesRepository.preferences.value.appLanguage)
        val snapshot = buildSnapshot(context, id)
        val openIntent = buildOpenAppIntent(context, snapshot.config.tapDestination)

        provideContent {
            GlanceTheme {
                PortFocusWidgetContent(
                    locCtx = locCtx,
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
            val ids = mgr.getGlanceIds(CuktechGlancePortWidget::class.java)
            ids.forEach { glanceId ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    prefs.toMutablePreferences()
                }
                CuktechGlancePortWidget().update(context, glanceId)
            }
        }
    }
}

private val WidgetSurfaceColorProvider = ColorProvider(
    day = Color(0xFFEFF4F7),
    night = Color(0xFF1B2023)
)

private val WidgetCardColorProvider = ColorProvider(
    day = Color(0xFFE2E9ED),
    night = Color(0xFF282E33)
)

@Composable
private fun ControlWidgetContent(
    locCtx: Context,
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
        isConnected -> locCtx.getString(R.string.status_connected)
        isConnecting -> locCtx.getString(R.string.status_connecting)
        else -> locCtx.getString(R.string.status_disconnected)
    }

    val unitW = locCtx.getString(R.string.unit_w)
    val unitV = locCtx.getString(R.string.unit_v)
    val offText = locCtx.getString(R.string.widget_off)
    val sceneLabel = localizedWidgetSceneLabel(locCtx, snapshot.sceneCode)
    val emphasizedList = (1..4).filter { it in snapshot.config.emphasizedPorts }.ifEmpty { listOf(1, 2, 3, 4) }

    val primaryArgb = GlanceTheme.colors.primary.getColor(locCtx).toArgb()
    val sparklineBmp = remember(snapshot.sparklinePoints, snapshot.totalWatts, primaryArgb, isConnected, snapshot.config.showSparkline) {
        if (snapshot.config.showSparkline && isConnected && snapshot.totalWatts > 0.2) {
            renderExpressiveSparklineBitmap(snapshot.sparklinePoints, snapshot.totalWatts, primaryArgb)
        } else {
            null
        }
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(WidgetSurfaceColorProvider)
            .cornerRadius(28.dp)
            .padding(14.dp)
    ) {
        // Header Row: Expressive status indicator + Total W + Session Wh + Scene Pill
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = GlanceModifier
                    .size(10.dp)
                    .cornerRadius(5.dp)
                    .background(
                        when {
                            isConnected -> GlanceTheme.colors.primary
                            isConnecting -> GlanceTheme.colors.tertiary
                            else -> GlanceTheme.colors.outline
                        }
                    )
            ) {}
            Spacer(GlanceModifier.width(8.dp))
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(
                        if (isConnected) actionStartActivity(openIntent)
                        else actionRunCallback<ConnectWidgetAction>()
                    )
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f %s", snapshot.totalWatts, unitW),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                val sub = when {
                    isConnected && snapshot.config.showSessionWh && snapshot.sessionWh > 0.01 ->
                        "$statusText · ${locCtx.getString(R.string.widget_session_wh_fmt, snapshot.sessionWh)}"
                    !isConnected && !isConnecting ->
                        "$statusText · ${locCtx.getString(R.string.widget_tap_connect)}"
                    else -> statusText
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
                    .cornerRadius(18.dp)
                    .background(GlanceTheme.colors.secondaryContainer)
                    .clickable(actionRunCallback<CycleSceneWidgetAction>())
                    .padding(horizontal = 12.dp, vertical = 7.dp)
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

        if (size.height >= 160.dp && sparklineBmp != null) {
            Spacer(GlanceModifier.height(8.dp))
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(16.dp)
                    .background(WidgetCardColorProvider)
                    .clickable(actionStartActivity(openIntent))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Image(
                    provider = ImageProvider(sparklineBmp),
                    contentDescription = locCtx.getString(R.string.overview_live_trend),
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(34.dp)
                )
            }
        }

        Spacer(GlanceModifier.height(10.dp))

        // Interactive M3 Expressive Port Cards (unified C3+A card when shared 5V bus active)
        val collapseShared = isConnected && snapshot.c3aBus.isSharedActive && 3 in emphasizedList && 4 in emphasizedList
        val displayPortIds = if (collapseShared) emphasizedList.filter { it != 4 } else emphasizedList

        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            displayPortIds.forEachIndexed { idx, pid ->
                if (idx > 0) Spacer(GlanceModifier.width(6.dp))
                val isMergedC3A = collapseShared && pid == 3
                val p = snapshot.ports[pid] ?: PortTelemetry(pid, localizedWidgetPortName(locCtx, pid))
                val enabled = p.enabled
                val w = when {
                    !isConnected -> 0.0
                    isMergedC3A -> snapshot.c3aBus.sharedPowerW
                    else -> p.powerW
                }
                val isCharging = isConnected && enabled && w > 0.2

                val bg = when {
                    !isConnected -> WidgetCardColorProvider
                    !enabled -> GlanceTheme.colors.errorContainer
                    isCharging -> GlanceTheme.colors.primaryContainer
                    else -> WidgetCardColorProvider
                }
                val fg = when {
                    !isConnected -> GlanceTheme.colors.onSurfaceVariant
                    !enabled -> GlanceTheme.colors.onErrorContainer
                    isCharging -> GlanceTheme.colors.onPrimaryContainer
                    else -> GlanceTheme.colors.onSurface
                }

                val cardLabel = if (isMergedC3A) locCtx.getString(R.string.port_c3a_short) else localizedWidgetPortName(locCtx, pid)

                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .cornerRadius(18.dp)
                        .background(bg)
                        .clickable(
                            actionRunCallback<TogglePortWidgetAction>(
                                actionParametersOf(PARAM_PORT_ID to pid)
                            )
                        )
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = cardLabel,
                        style = TextStyle(color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = if (!enabled && isConnected) {
                            offText
                        } else {
                            String.format(Locale.US, "%.1f %s", w, unitW)
                        },
                        style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    if (size.width >= 220.dp) {
                        Spacer(GlanceModifier.height(1.dp))
                        val detail = when {
                            !isConnected -> "—"
                            !enabled -> locCtx.getString(R.string.port_status_disabled)
                            isMergedC3A -> "5$unitV · 15$unitW"
                            isCharging && size.height >= 160.dp ->
                                "${p.protocolName} · ${String.format(Locale.US, "%.0f%s", p.voltageV, unitV)}"
                            isCharging -> p.protocolName
                            else -> locCtx.getString(R.string.port_status_idle)
                        }
                        Text(
                            text = detail,
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
    locCtx: Context,
    snapshot: GlanceWidgetSnapshot,
    openIntent: Intent
) {
    val size = LocalSize.current
    val isConnected = snapshot.authState == CuktechAuthState.AUTHENTICATED
    val isConnecting = snapshot.authState == CuktechAuthState.CONNECTING_GATT ||
        snapshot.authState == CuktechAuthState.DISCOVERING_SERVICES ||
        snapshot.authState == CuktechAuthState.PHASE_A_INIT_A4 ||
        snapshot.authState == CuktechAuthState.PHASE_B_HANDSHAKE

    val unitW = locCtx.getString(R.string.unit_w)
    val offText = locCtx.getString(R.string.widget_off)
    val sceneLabel = localizedWidgetSceneLabel(locCtx, snapshot.sceneCode)

    val primaryArgb = GlanceTheme.colors.primary.getColor(locCtx).toArgb()
    val sparklineBmp = remember(snapshot.sparklinePoints, snapshot.totalWatts, primaryArgb, isConnected, snapshot.config.showSparkline) {
        if (snapshot.config.showSparkline && isConnected && snapshot.totalWatts > 0.2) {
            renderExpressiveSparklineBitmap(snapshot.sparklinePoints, snapshot.totalWatts, primaryArgb)
        } else {
            null
        }
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(WidgetSurfaceColorProvider)
            .cornerRadius(24.dp)
            .padding(12.dp)
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
                    .size(9.dp)
                    .cornerRadius(5.dp)
                    .background(
                        when {
                            isConnected -> GlanceTheme.colors.primary
                            isConnecting -> GlanceTheme.colors.tertiary
                            else -> GlanceTheme.colors.outline
                        }
                    )
            ) {}
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = String.format(Locale.US, "%.1f %s", snapshot.totalWatts, unitW),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1
            )
            Box(
                modifier = GlanceModifier
                    .cornerRadius(12.dp)
                    .background(GlanceTheme.colors.secondaryContainer)
                    .clickable(actionRunCallback<CycleSceneWidgetAction>())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = sceneLabel.take(8),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSecondaryContainer,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }
        }

        if (size.height >= 135.dp && sparklineBmp != null) {
            Spacer(GlanceModifier.height(6.dp))
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(12.dp)
                    .background(WidgetCardColorProvider)
                    .clickable(actionStartActivity(openIntent))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Image(
                    provider = ImageProvider(sparklineBmp),
                    contentDescription = locCtx.getString(R.string.overview_live_trend),
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(22.dp)
                )
            }
        } else if (size.height >= 135.dp && !isConnected && !isConnecting) {
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = locCtx.getString(R.string.widget_tap_connect),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                maxLines = 1,
                modifier = GlanceModifier.clickable(actionRunCallback<ConnectWidgetAction>())
            )
        } else if (size.height >= 135.dp && isConnected && snapshot.config.showSessionWh && snapshot.sessionWh > 0.01) {
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = locCtx.getString(R.string.widget_session_wh_fmt, snapshot.sessionWh),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                maxLines = 1
            )
        }

        Spacer(GlanceModifier.height(8.dp))

        // 2x2 Expressive Port Grid (or unified C3+A row when shared 5V bus active)
        Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                CompactPortCell(
                    locCtx = locCtx,
                    portId = 1,
                    port = snapshot.ports[1],
                    isConnected = isConnected,
                    unitW = unitW,
                    offText = offText,
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                )
                Spacer(GlanceModifier.width(6.dp))
                CompactPortCell(
                    locCtx = locCtx,
                    portId = 2,
                    port = snapshot.ports[2],
                    isConnected = isConnected,
                    unitW = unitW,
                    offText = offText,
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            if (isConnected && snapshot.c3aBus.isSharedActive) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight()
                        .cornerRadius(14.dp)
                        .background(GlanceTheme.colors.primaryContainer)
                        .clickable(
                            actionRunCallback<TogglePortWidgetAction>(
                                actionParametersOf(PARAM_PORT_ID to 3)
                            )
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${locCtx.getString(R.string.port_c3a_short)} · ${String.format(Locale.US, "%.1f %s", snapshot.c3aBus.sharedPowerW, unitW)}",
                        style = TextStyle(
                            color = GlanceTheme.colors.onPrimaryContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                }
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    CompactPortCell(
                        locCtx = locCtx,
                        portId = 3,
                        port = snapshot.ports[3],
                        isConnected = isConnected,
                        unitW = unitW,
                        offText = offText,
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    CompactPortCell(
                        locCtx = locCtx,
                        portId = 4,
                        port = snapshot.ports[4],
                        isConnected = isConnected,
                        unitW = unitW,
                        offText = offText,
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactPortCell(
    locCtx: Context,
    portId: Int,
    port: PortTelemetry?,
    isConnected: Boolean,
    unitW: String,
    offText: String,
    modifier: GlanceModifier
) {
    val enabled = port?.enabled ?: true
    val w = if (isConnected) (port?.powerW ?: 0.0) else 0.0
    val isCharging = isConnected && enabled && w > 0.2
    val bg = when {
        !isConnected -> WidgetCardColorProvider
        !enabled -> GlanceTheme.colors.errorContainer
        isCharging -> GlanceTheme.colors.primaryContainer
        else -> WidgetCardColorProvider
    }
    val fg = when {
        !isConnected -> GlanceTheme.colors.onSurfaceVariant
        !enabled -> GlanceTheme.colors.onErrorContainer
        isCharging -> GlanceTheme.colors.onPrimaryContainer
        else -> GlanceTheme.colors.onSurface
    }
    val portLabel = localizedWidgetPortName(locCtx, portId)
    val valueText = when {
        !isConnected -> "—"
        !enabled -> offText
        w >= 10.0 -> String.format(Locale.US, "%.0f %s", w, unitW)
        else -> String.format(Locale.US, "%.1f %s", w, unitW)
    }

    Column(
        modifier = modifier
            .cornerRadius(14.dp)
            .background(bg)
            .clickable(
                actionRunCallback<TogglePortWidgetAction>(
                    actionParametersOf(PARAM_PORT_ID to portId)
                )
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = portLabel,
            style = TextStyle(color = fg, fontSize = 9.sp, fontWeight = FontWeight.Medium),
            maxLines = 1
        )
        Text(
            text = valueText,
            style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            maxLines = 1
        )
    }
}

@Composable
private fun PortFocusWidgetContent(
    locCtx: Context,
    snapshot: GlanceWidgetSnapshot,
    openIntent: Intent
) {
    val isConnected = snapshot.authState == CuktechAuthState.AUTHENTICATED
    val pid = snapshot.focusedPortId
    val port = snapshot.ports[pid] ?: PortTelemetry(pid, localizedWidgetPortName(locCtx, pid))
    val portLabel = localizedWidgetPortName(locCtx, pid)
    val enabled = port.enabled
    val watts = if (isConnected && enabled) port.powerW else 0.0

    val unitW = locCtx.getString(R.string.unit_w)
    val unitV = locCtx.getString(R.string.unit_v)
    val unitA = locCtx.getString(R.string.unit_a)
    val offText = locCtx.getString(R.string.widget_off)

    val primaryArgb = GlanceTheme.colors.primary.getColor(locCtx).toArgb()
    val sparklineBmp = remember(snapshot.focusedPortSparkline, watts, primaryArgb, isConnected, snapshot.config.showSparkline) {
        if (snapshot.config.showSparkline && isConnected && watts > 0.2) {
            renderExpressiveSparklineBitmap(snapshot.focusedPortSparkline, watts, primaryArgb)
        } else {
            null
        }
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(WidgetSurfaceColorProvider)
            .cornerRadius(24.dp)
            .padding(12.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(
                        if (isConnected) actionStartActivity(openIntent)
                        else actionRunCallback<ConnectWidgetAction>()
                    )
            ) {
                Text(
                    text = portLabel,
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                Text(
                    text = if (isConnected && !enabled) offText else String.format(Locale.US, "%.1f %s", watts, unitW),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            }
            Box(
                modifier = GlanceModifier
                    .cornerRadius(14.dp)
                    .background(
                        if (!isConnected) WidgetCardColorProvider
                        else if (enabled) GlanceTheme.colors.primaryContainer
                        else GlanceTheme.colors.errorContainer
                    )
                    .clickable(
                        actionRunCallback<TogglePortWidgetAction>(
                            actionParametersOf(PARAM_PORT_ID to pid)
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (!isConnected) locCtx.getString(R.string.action_connect).take(6)
                    else if (enabled) "ON" else offText,
                    style = TextStyle(
                        color = if (!isConnected) GlanceTheme.colors.onSurfaceVariant
                        else if (enabled) GlanceTheme.colors.onPrimaryContainer
                        else GlanceTheme.colors.onErrorContainer,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            }
        }

        Spacer(GlanceModifier.height(4.dp))

        if (sparklineBmp != null) {
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(12.dp)
                    .background(WidgetCardColorProvider)
                    .clickable(actionStartActivity(openIntent))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Image(
                    provider = ImageProvider(sparklineBmp),
                    contentDescription = portLabel,
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(28.dp)
                )
            }
            Spacer(GlanceModifier.height(4.dp))
        }

        Text(
            text = if (isConnected) {
                "${String.format(Locale.US, "%.1f%s · %.2f%s", port.voltageV, unitV, port.currentA, unitA)} · ${port.protocolName}"
            } else {
                locCtx.getString(R.string.status_disconnected)
            },
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp
            ),
            maxLines = 1
        )
        if (isConnected && snapshot.config.showSessionWh && snapshot.focusedPortSessionWh > 0.01) {
            Text(
                text = locCtx.getString(R.string.widget_session_wh_fmt, snapshot.focusedPortSessionWh),
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
        }
    }
}

private fun localizedWidgetPortName(locCtx: Context, portId: Int): String = when (portId) {
    1 -> locCtx.getString(R.string.port_name_c1)
    2 -> locCtx.getString(R.string.port_name_c2)
    3 -> locCtx.getString(R.string.port_name_c3)
    4 -> locCtx.getString(R.string.port_name_a)
    else -> "P$portId"
}

private fun localizedWidgetSceneLabel(locCtx: Context, sceneCode: Int): String = when (sceneCode) {
    1 -> locCtx.getString(R.string.scenario_ai)
    2 -> locCtx.getString(R.string.scenario_hybrid)
    3 -> locCtx.getString(R.string.scenario_single)
    4 -> locCtx.getString(R.string.scenario_dual)
    else -> locCtx.getString(R.string.scenario_ai)
}
