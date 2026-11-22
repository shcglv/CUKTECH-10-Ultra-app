package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.db.PowerChartPoint
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.protocol.PortTelemetry
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.ChartInterval
import com.example.ui.viewmodel.ChartMetric
import com.example.ui.viewmodel.ChartPointSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds a monotone-clamped cubic Bezier path through [points] keeping all curve control points
 * strictly within [minY]..[maxY] so power curves never dip below zero or overshoot bounds.
 */
fun buildMonotoneCubicPath(
    points: List<Offset>,
    minY: Float,
    maxY: Float
): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size == 1) return path
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }

    val slopes = FloatArray(points.size - 1)
    for (i in 0 until points.size - 1) {
        val dx = (points[i + 1].x - points[i].x).coerceAtLeast(0.001f)
        slopes[i] = (points[i + 1].y - points[i].y) / dx
    }

    val tangents = FloatArray(points.size)
    tangents[0] = slopes[0]
    tangents[points.lastIndex] = slopes[slopes.lastIndex]
    for (i in 1 until points.lastIndex) {
        val mPrev = slopes[i - 1]
        val mNext = slopes[i]
        tangents[i] = if (mPrev * mNext <= 0f) 0f else (mPrev + mNext) * 0.5f
    }

    for (i in 0 until points.size - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val dx = p1.x - p0.x
        val c1x = p0.x + dx / 3f
        val c1y = (p0.y + tangents[i] * dx / 3f).coerceIn(minY, maxY)
        val c2x = p1.x - dx / 3f
        val c2y = (p1.y - tangents[i + 1] * dx / 3f).coerceIn(minY, maxY)
        path.cubicTo(c1x, c1y, c2x, c2y, p1.x, p1.y)
    }
    return path
}

/**
 * Smooth Bezier live sparkline with gradient fill and optional touch scrubbing on OverviewScreen.
 */
@Composable
fun ExpressiveSparkline(
    points: List<PowerChartPoint>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    val pts = remember(points) { points.takeLast(60) }
    val haptic = LocalHapticFeedback.current
    var scrubIndex by remember(pts.size) { mutableIntStateOf(-1) }
    val tsFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val unitW = stringResource(R.string.unit_w)

    LaunchedEffect(scrubIndex) {
        if (scrubIndex >= 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp)
            .testTag("overview_sparkline")
            .pointerInput(pts) {
                if (pts.size < 2) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        val frac = (off.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        scrubIndex = (frac * (pts.size - 1)).roundToInt().coerceIn(0, pts.lastIndex)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        scrubIndex = (frac * (pts.size - 1)).roundToInt().coerceIn(0, pts.lastIndex)
                    },
                    onDragEnd = { scrubIndex = -1 },
                    onDragCancel = { scrubIndex = -1 }
                )
            }
            .pointerInput(pts) {
                if (pts.size < 2) return@pointerInput
                detectTapGestures(
                    onTap = { off ->
                        val frac = (off.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val tapped = (frac * (pts.size - 1)).roundToInt().coerceIn(0, pts.lastIndex)
                        scrubIndex = if (scrubIndex == tapped) -1 else tapped
                    }
                )
            }
    ) {
        val crosshairColor = MaterialTheme.colorScheme.outline
        val bgSurface = MaterialTheme.colorScheme.surfaceContainerLow

        Canvas(modifier = Modifier.fillMaxSize()) {
            val padY = 6.dp.toPx()
            val usableH = (size.height - padY * 2).coerceAtLeast(4f)
            if (pts.size >= 2) {
                val maxW = (pts.maxOfOrNull { it.totalWatts } ?: 10.0).coerceAtLeast(10.0)
                val offsets = pts.mapIndexed { idx, pt ->
                    val x = (idx.toFloat() / (pts.size - 1)) * size.width
                    val y = size.height - padY - ((pt.totalWatts / maxW).toFloat().coerceIn(0f, 1f) * usableH)
                    Offset(x, y)
                }
                val curvePath = buildMonotoneCubicPath(offsets, minY = padY, maxY = size.height - padY)
                val fillPath = Path().apply {
                    addPath(curvePath)
                    lineTo(offsets.last().x, size.height)
                    lineTo(offsets.first().x, size.height)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0.0f))
                    )
                )
                drawPath(
                    path = curvePath,
                    color = lineColor,
                    style = Stroke(width = 2.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                val activeIdx = if (scrubIndex in offsets.indices) scrubIndex else offsets.lastIndex
                val marker = offsets[activeIdx]
                if (scrubIndex in offsets.indices) {
                    drawLine(
                        color = crosshairColor.copy(alpha = 0.55f),
                        start = Offset(marker.x, 0f),
                        end = Offset(marker.x, size.height),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
                drawCircle(color = bgSurface, radius = 5.dp.toPx(), center = marker)
                drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = marker)
            } else if (pts.size == 1) {
                val y = size.height / 2f
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.5.dp.toPx())
            }
        }

        if (scrubIndex in pts.indices) {
            val pt = pts[scrubIndex]
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 2.dp,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(
                    text = "${tsFmt.format(Date(pt.timestampMs))} · ${String.format(Locale.US, "%.1f", pt.totalWatts)} $unitW",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = TelemetryMonoFontFamily
                    ).tabularNums(),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Next-gen Analytics telemetry card with smooth Bezier curves, stacked area mode (`STACKED_W`),
 * interactive port legend toggles, and horizontal touch-scrubbing with floating tooltip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpressiveTelemetryChartCard(
    interval: ChartInterval,
    metric: ChartMetric,
    samples: List<ChartPointSample>,
    onSelectInterval: (ChartInterval) -> Unit,
    onSelectMetric: (ChartMetric) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val haptic = LocalHapticFeedback.current
    val timeFmt = remember(interval) {
        SimpleDateFormat(
            if (interval == ChartInterval.SEC_60 || interval == ChartInterval.MIN_5) "HH:mm:ss" else "HH:mm",
            Locale.US
        )
    }

    var visiblePorts by remember { mutableStateOf(setOf(1, 2, 3, 4)) }
    var scrubIndex by remember(samples, metric, interval) { mutableIntStateOf(-1) }

    LaunchedEffect(scrubIndex) {
        if (scrubIndex >= 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("analytics_chart_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Interval selector row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val intervalLabels = listOf(
                    ChartInterval.SEC_60 to stringResource(R.string.analytics_interval_60s),
                    ChartInterval.MIN_5 to stringResource(R.string.analytics_interval_5m),
                    ChartInterval.MIN_30 to stringResource(R.string.analytics_interval_30m),
                    ChartInterval.SESSION to stringResource(R.string.analytics_interval_session),
                    ChartInterval.TODAY to stringResource(R.string.analytics_interval_today),
                    ChartInterval.CUSTOM to stringResource(R.string.analytics_interval_custom)
                )
                intervalLabels.forEach { (iv, label) ->
                    FilterChip(
                        selected = interval == iv,
                        onClick = { onSelectInterval(iv) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium.tabularNums()) },
                        modifier = Modifier.testTag("chart_interval_${iv.name.lowercase()}")
                    )
                }
            }

            // All 7 telemetry metrics visible via FlowRow (Total W, Stacked W, Per-Port W, V, A, Wh, Protocols)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val metricLabels = listOf(
                    ChartMetric.TOTAL_W to stringResource(R.string.analytics_metric_total_w),
                    ChartMetric.STACKED_W to stringResource(R.string.analytics_metric_stacked_w),
                    ChartMetric.PER_PORT_W to stringResource(R.string.analytics_metric_per_port_w),
                    ChartMetric.VOLTAGE_V to stringResource(R.string.analytics_metric_voltage_v),
                    ChartMetric.CURRENT_A to stringResource(R.string.analytics_metric_current_a),
                    ChartMetric.CUMULATIVE_WH to stringResource(R.string.analytics_metric_cum_wh),
                    ChartMetric.PROTOCOLS to stringResource(R.string.analytics_metric_protocols)
                )
                metricLabels.forEach { (m, label) ->
                    FilterChip(
                        selected = metric == m,
                        onClick = { onSelectMetric(m) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("chart_metric_${m.name.lowercase()}")
                    )
                }
            }

            if (samples.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.analytics_no_samples),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (metric == ChartMetric.PROTOCOLS) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (portId in 1..4) {
                        val color = semantic.colorForPort(portId)
                        val rawLatest = samples.lastOrNull()?.portProtocol?.get(portId) ?: "Idle"
                        val latestProto = if (rawLatest == "Idle") stringResource(R.string.port_status_idle) else rawLatest
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = explicitPortName(portId),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(64.dp)
                            )
                            Canvas(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(18.dp)
                            ) {
                                val segW = size.width / samples.size.coerceAtLeast(1)
                                samples.forEachIndexed { idx, pt ->
                                    val p = pt.portProtocol[portId] ?: "Idle"
                                    val active = p != "Idle" && (pt.portW[portId] ?: 0.0) > 0.2
                                    val fill = if (active) color.copy(alpha = 0.85f) else color.copy(alpha = 0.12f)
                                    drawRoundRect(
                                        color = fill,
                                        topLeft = Offset(idx * segW, 0f),
                                        size = Size((segW + 1f).coerceAtMost(size.width - idx * segW), size.height),
                                        cornerRadius = CornerRadius(3f, 3f)
                                    )
                                }
                            }
                            Text(
                                text = latestProto,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(76.dp)
                            )
                        }
                    }
                }
            } else {
                val unit = when (metric) {
                    ChartMetric.TOTAL_W, ChartMetric.STACKED_W, ChartMetric.PER_PORT_W -> stringResource(R.string.unit_w)
                    ChartMetric.VOLTAGE_V -> stringResource(R.string.unit_v)
                    ChartMetric.CURRENT_A -> stringResource(R.string.unit_a)
                    ChartMetric.CUMULATIVE_WH -> stringResource(R.string.unit_wh)
                    ChartMetric.PROTOCOLS -> ""
                }

                val activePortList = remember(visiblePorts) {
                    listOf(1, 2, 3, 4).filter { it in visiblePorts }.ifEmpty { listOf(1, 2, 3, 4) }
                }

                val valFmt = if (metric == ChartMetric.CURRENT_A || metric == ChartMetric.CUMULATIVE_WH) "%.2f" else "%.1f"
                val maxVal = remember(samples, metric, activePortList) {
                    val peak = when (metric) {
                        ChartMetric.TOTAL_W -> samples.maxOfOrNull { it.totalW } ?: 10.0
                        ChartMetric.STACKED_W -> samples.maxOfOrNull { s ->
                            activePortList.sumOf { pid -> (s.portW[pid] ?: 0.0).coerceAtLeast(0.0) }
                        } ?: 10.0
                        ChartMetric.PER_PORT_W -> samples.maxOfOrNull { s ->
                            activePortList.maxOfOrNull { pid -> s.portW[pid] ?: 0.0 } ?: 0.0
                        } ?: 10.0
                        ChartMetric.VOLTAGE_V -> samples.maxOfOrNull { s ->
                            activePortList.maxOfOrNull { pid -> s.portV[pid] ?: 0.0 } ?: 0.0
                        } ?: 5.0
                        ChartMetric.CURRENT_A -> samples.maxOfOrNull { s ->
                            activePortList.maxOfOrNull { pid -> s.portA[pid] ?: 0.0 } ?: 0.0
                        } ?: 0.5
                        ChartMetric.CUMULATIVE_WH -> samples.maxOfOrNull { s ->
                            activePortList.maxOfOrNull { pid -> s.portWh[pid] ?: 0.0 } ?: 0.0
                        } ?: 0.2
                        ChartMetric.PROTOCOLS -> 1.0
                    }
                    val minFloor = when (metric) {
                        ChartMetric.CURRENT_A -> 0.5
                        ChartMetric.CUMULATIVE_WH -> 0.2
                        ChartMetric.VOLTAGE_V -> 5.0
                        else -> 5.0
                    }
                    peak.coerceAtLeast(minFloor) * 1.12
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        modifier = Modifier.height(185.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${String.format(Locale.US, valFmt, maxVal)} $unit",
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${String.format(Locale.US, valFmt, maxVal / 2.0)} $unit",
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "0 $unit",
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val totalLineColor = MaterialTheme.colorScheme.primary
                    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    val crosshairColor = MaterialTheme.colorScheme.outline
                    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow

                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .height(185.dp)
                    ) {
                        val density = LocalDensity.current
                        val boxW = with(density) { maxWidth.toPx() }

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(samples) {
                                    if (samples.size < 2) return@pointerInput
                                    detectHorizontalDragGestures(
                                        onDragStart = { off ->
                                            val frac = (off.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                            scrubIndex = (frac * (samples.size - 1)).roundToInt().coerceIn(0, samples.lastIndex)
                                        },
                                        onHorizontalDrag = { change, _ ->
                                            change.consume()
                                            val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                            scrubIndex = (frac * (samples.size - 1)).roundToInt().coerceIn(0, samples.lastIndex)
                                        },
                                        onDragEnd = { },
                                        onDragCancel = { }
                                    )
                                }
                                .pointerInput(samples) {
                                    if (samples.size < 2) return@pointerInput
                                    detectTapGestures(
                                        onTap = { off ->
                                            val frac = (off.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                            val tapped = (frac * (samples.size - 1)).roundToInt().coerceIn(0, samples.lastIndex)
                                            scrubIndex = if (scrubIndex == tapped) -1 else tapped
                                        }
                                    )
                                }
                        ) {
                            val padTop = 6.dp.toPx()
                            val usableH = (size.height - padTop).coerceAtLeast(4f)
                            drawLine(gridColor, Offset(0f, padTop), Offset(size.width, padTop), 1.dp.toPx())
                            drawLine(gridColor, Offset(0f, padTop + usableH / 2f), Offset(size.width, padTop + usableH / 2f), 1.dp.toPx())
                            drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())

                            if (samples.size >= 2) {
                                when (metric) {
                                    ChartMetric.TOTAL_W -> {
                                        val offsets = samples.mapIndexed { idx, s ->
                                            val x = (idx.toFloat() / (samples.size - 1)) * size.width
                                            val y = size.height - ((s.totalW / maxVal).toFloat().coerceIn(0f, 1f) * usableH)
                                            Offset(x, y)
                                        }
                                        val curve = buildMonotoneCubicPath(offsets, padTop, size.height)
                                        val fill = Path().apply {
                                            addPath(curve)
                                            lineTo(offsets.last().x, size.height)
                                            lineTo(offsets.first().x, size.height)
                                            close()
                                        }
                                        drawPath(
                                            path = fill,
                                            brush = Brush.verticalGradient(
                                                listOf(totalLineColor.copy(alpha = 0.28f), totalLineColor.copy(alpha = 0.0f))
                                            )
                                        )
                                        drawPath(
                                            path = curve,
                                            color = totalLineColor,
                                            style = Stroke(width = 2.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                        )
                                        if (scrubIndex in offsets.indices) {
                                            val pt = offsets[scrubIndex]
                                            drawLine(crosshairColor.copy(alpha = 0.6f), Offset(pt.x, 0f), Offset(pt.x, size.height), 1.5.dp.toPx())
                                            drawCircle(surfaceLow, radius = 5.5.dp.toPx(), center = pt)
                                            drawCircle(totalLineColor, radius = 3.8.dp.toPx(), center = pt)
                                        }
                                    }

                                    ChartMetric.STACKED_W -> {
                                        // Draw stacked layers bottom-up (4 -> 3 -> 2 -> 1)
                                        val drawOrder = activePortList.sortedDescending()
                                        var prevTotals = DoubleArray(samples.size) { 0.0 }
                                        var prevOffsets = samples.indices.map { idx ->
                                            Offset((idx.toFloat() / (samples.size - 1)) * size.width, size.height)
                                        }

                                        for (portId in drawOrder) {
                                            val color = semantic.colorForPort(portId)
                                            val nextTotals = DoubleArray(samples.size) { idx ->
                                                prevTotals[idx] + (samples[idx].portW[portId] ?: 0.0).coerceAtLeast(0.0)
                                            }
                                            val nextOffsets = samples.indices.map { idx ->
                                                val x = (idx.toFloat() / (samples.size - 1)) * size.width
                                                val y = size.height - ((nextTotals[idx] / maxVal).toFloat().coerceIn(0f, 1f) * usableH)
                                                Offset(x, y)
                                            }

                                            val topCurve = buildMonotoneCubicPath(nextOffsets, padTop, size.height)
                                            val bottomCurveRev = buildMonotoneCubicPath(prevOffsets.reversed(), padTop, size.height)
                                            val areaPath = Path().apply {
                                                addPath(topCurve)
                                                lineTo(prevOffsets.last().x, prevOffsets.last().y)
                                                addPath(bottomCurveRev)
                                                close()
                                            }
                                            drawPath(path = areaPath, color = color.copy(alpha = 0.42f))
                                            drawPath(
                                                path = topCurve,
                                                color = color,
                                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                            )
                                            prevTotals = nextTotals
                                            prevOffsets = nextOffsets
                                        }

                                        if (scrubIndex in samples.indices) {
                                            val cx = (scrubIndex.toFloat() / (samples.size - 1)) * size.width
                                            drawLine(crosshairColor.copy(alpha = 0.65f), Offset(cx, 0f), Offset(cx, size.height), 1.5.dp.toPx())
                                            var cumulative = 0.0
                                            for (portId in drawOrder) {
                                                cumulative += (samples[scrubIndex].portW[portId] ?: 0.0).coerceAtLeast(0.0)
                                                val cy = size.height - ((cumulative / maxVal).toFloat().coerceIn(0f, 1f) * usableH)
                                                drawCircle(surfaceLow, radius = 5.dp.toPx(), center = Offset(cx, cy))
                                                drawCircle(semantic.colorForPort(portId), radius = 3.5.dp.toPx(), center = Offset(cx, cy))
                                            }
                                        }
                                    }

                                    else -> {
                                        for (portId in activePortList) {
                                            val pColor = semantic.colorForPort(portId)
                                            val offsets = samples.mapIndexed { idx, s ->
                                                val v = when (metric) {
                                                    ChartMetric.PER_PORT_W -> s.portW[portId] ?: 0.0
                                                    ChartMetric.VOLTAGE_V -> s.portV[portId] ?: 0.0
                                                    ChartMetric.CURRENT_A -> s.portA[portId] ?: 0.0
                                                    ChartMetric.CUMULATIVE_WH -> s.portWh[portId] ?: 0.0
                                                    else -> 0.0
                                                }
                                                val x = (idx.toFloat() / (samples.size - 1)) * size.width
                                                val y = size.height - ((v / maxVal).toFloat().coerceIn(0f, 1f) * usableH)
                                                Offset(x, y)
                                            }
                                            val curve = buildMonotoneCubicPath(offsets, padTop, size.height)
                                            drawPath(
                                                path = curve,
                                                color = pColor,
                                                style = Stroke(width = 2.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                            )
                                            if (scrubIndex in offsets.indices) {
                                                val pt = offsets[scrubIndex]
                                                drawCircle(surfaceLow, radius = 4.8.dp.toPx(), center = pt)
                                                drawCircle(pColor, radius = 3.2.dp.toPx(), center = pt)
                                            }
                                        }
                                        if (scrubIndex in samples.indices) {
                                            val cx = (scrubIndex.toFloat() / (samples.size - 1)) * size.width
                                            drawLine(crosshairColor.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, size.height), 1.5.dp.toPx())
                                        }
                                    }
                                }
                            }
                        }

                        // Floating M3 Tooltip Pill anchored away from finger / edges
                        if (scrubIndex in samples.indices) {
                            val pt = samples[scrubIndex]
                            val rawX = if (samples.size > 1) {
                                (scrubIndex.toFloat() / (samples.size - 1)) * boxW
                            } else 0f
                            val placeRight = rawX < boxW * 0.52f
                            val tooltipOffsetX = if (placeRight) {
                                (rawX + 12f).coerceAtMost((boxW - 150f).coerceAtLeast(0f))
                            } else {
                                (rawX - 160f).coerceAtLeast(0f)
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.inverseSurface,
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .offset { IntOffset(tooltipOffsetX.roundToInt(), 4) }
                                    .testTag("chart_scrub_tooltip")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = timeFmt.format(Date(pt.timestampMs)),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = TelemetryMonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ).tabularNums(),
                                        color = MaterialTheme.colorScheme.inverseOnSurface
                                    )
                                    if (metric == ChartMetric.TOTAL_W || metric == ChartMetric.STACKED_W) {
                                        val total = if (metric == ChartMetric.STACKED_W) {
                                            activePortList.sumOf { pt.portW[it] ?: 0.0 }
                                        } else pt.totalW
                                        Text(
                                            text = "${String.format(Locale.US, "%.1f", total)} $unit",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = TelemetryMonoFontFamily,
                                                fontWeight = FontWeight.Bold
                                            ).tabularNums(),
                                            color = MaterialTheme.colorScheme.inversePrimary
                                        )
                                    }
                                    if (metric != ChartMetric.TOTAL_W) {
                                        activePortList.forEach { pid ->
                                            val v = when (metric) {
                                                ChartMetric.STACKED_W, ChartMetric.PER_PORT_W -> pt.portW[pid] ?: 0.0
                                                ChartMetric.VOLTAGE_V -> pt.portV[pid] ?: 0.0
                                                ChartMetric.CURRENT_A -> pt.portA[pid] ?: 0.0
                                                ChartMetric.CUMULATIVE_WH -> pt.portWh[pid] ?: 0.0
                                                else -> 0.0
                                            }
                                            if (v > 0.005) {
                                                Text(
                                                    text = "${explicitPortName(pid)}: ${String.format(Locale.US, valFmt, v)} $unit",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFamily = TelemetryMonoFontFamily
                                                    ).tabularNums(),
                                                    color = MaterialTheme.colorScheme.inverseOnSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Time X-Axis
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = timeFmt.format(Date(samples.first().timestampMs)),
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = timeFmt.format(Date(samples[samples.size / 2].timestampMs)),
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = timeFmt.format(Date(samples.last().timestampMs)),
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = stringResource(R.string.analytics_chart_scrub_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Interactive port filter legend for multi-port metrics
                if (metric != ChartMetric.TOTAL_W) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.analytics_port_filter_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (portId in 1..4) {
                                val selected = portId in visiblePorts
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val next = if (selected && visiblePorts.size > 1) {
                                            visiblePorts - portId
                                        } else {
                                            visiblePorts + portId
                                        }
                                        visiblePorts = next
                                    },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(semantic.colorForPort(portId))
                                        )
                                    },
                                    label = { Text(explicitPortName(portId)) },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("chart_port_filter_$portId")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pixel Battery-style horizontal energy distribution bar across physical ports.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PixelEnergyBreakdownCard(
    ports: Map<Int, PortTelemetry>,
    activeSessions: Map<Int, ChargeSessionRecord>,
    completedSessions: List<ChargeSessionRecord>,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val unitWh = stringResource(R.string.unit_wh)

    val portTotals = remember(ports, activeSessions, completedSessions) {
        (1..4).associateWith { pid ->
            val fromHistory = completedSessions.filter { it.portId == pid }.sumOf { it.whAccumulated }
            val fromActive = activeSessions[pid]?.whAccumulated ?: (ports[pid]?.cumulativeWh ?: 0.0)
            (fromHistory + fromActive).coerceAtLeast(0.0)
        }
    }
    val grandTotal = portTotals.values.sum()
    if (grandTotal <= 0.01) return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("analytics_energy_breakdown_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.analytics_breakdown_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.analytics_breakdown_total_fmt, grandTotal),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = TelemetryMonoFontFamily
                    ).tabularNums(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                (1..4).forEach { pid ->
                    val wh = portTotals[pid] ?: 0.0
                    val share = (wh / grandTotal).toFloat()
                    if (share > 0.01f) {
                        Box(
                            modifier = Modifier
                                .weight(share.coerceAtLeast(0.03f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(semantic.colorForPort(pid))
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (1..4).forEach { pid ->
                    val wh = portTotals[pid] ?: 0.0
                    if (wh > 0.01) {
                        val pct = ((wh / grandTotal) * 100.0).roundToInt()
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(semantic.colorForPort(pid))
                                )
                                Text(
                                    text = "${explicitPortName(pid)} · $pct% (${String.format(Locale.US, "%.1f", wh)} $unitWh)",
                                    style = MaterialTheme.typography.labelSmall.tabularNums()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
