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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

private enum class ChartVisualStyle {
    CURVE,
    BARS
}

/**
 * Next-gen Analytics telemetry card with smooth Bezier curves, interactive bar columns,
 * simultaneous multi-metric overlay (e.g. V + A + Wh + W), stacked area/bar mode (`STACKED_W`),
 * Min/Max/Avg analytics, single-row port chips, and touch scrubbing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpressiveTelemetryChartCard(
    interval: ChartInterval,
    metric: ChartMetric = ChartMetric.TOTAL_W,
    selectedMetrics: Set<ChartMetric> = setOf(metric),
    samples: List<ChartPointSample>,
    onSelectInterval: (ChartInterval) -> Unit,
    onSelectMetric: (ChartMetric) -> Unit = {},
    onToggleMetric: (ChartMetric) -> Unit = onSelectMetric,
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
    var visualStyle by remember { mutableStateOf(ChartVisualStyle.CURVE) }
    var scrubIndex by remember(samples, selectedMetrics, interval) { mutableIntStateOf(-1) }

    LaunchedEffect(scrubIndex) {
        if (scrubIndex >= 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val effectiveMetrics = remember(selectedMetrics, metric) {
        val raw = selectedMetrics.ifEmpty { setOf(metric) }
        when {
            ChartMetric.PROTOCOLS in raw -> listOf(ChartMetric.PROTOCOLS)
            ChartMetric.STACKED_W in raw -> listOf(ChartMetric.STACKED_W)
            else -> listOf(
                ChartMetric.TOTAL_W,
                ChartMetric.PER_PORT_W,
                ChartMetric.VOLTAGE_V,
                ChartMetric.CURRENT_A,
                ChartMetric.CUMULATIVE_WH
            ).filter { it in raw }.ifEmpty { listOf(ChartMetric.TOTAL_W) }
        }
    }
    val primaryMetric = effectiveMetrics.first()
    val isMultiMetric = effectiveMetrics.size > 1
    val activePortList = remember(visiblePorts) {
        listOf(1, 2, 3, 4).filter { it in visiblePorts }.ifEmpty { listOf(1, 2, 3, 4) }
    }

    val unitW = stringResource(R.string.unit_w)
    val unitV = stringResource(R.string.unit_v)
    val unitA = stringResource(R.string.unit_a)
    val unitWh = stringResource(R.string.unit_wh)

    @Composable
    fun metricLabel(m: ChartMetric): String = when (m) {
        ChartMetric.TOTAL_W -> stringResource(R.string.analytics_metric_total_w)
        ChartMetric.STACKED_W -> stringResource(R.string.analytics_metric_stacked_w)
        ChartMetric.PER_PORT_W -> stringResource(R.string.analytics_metric_per_port_w)
        ChartMetric.VOLTAGE_V -> stringResource(R.string.analytics_metric_voltage_v)
        ChartMetric.CURRENT_A -> stringResource(R.string.analytics_metric_current_a)
        ChartMetric.CUMULATIVE_WH -> stringResource(R.string.analytics_metric_cum_wh)
        ChartMetric.PROTOCOLS -> stringResource(R.string.analytics_metric_protocols)
    }

    fun metricUnit(m: ChartMetric): String = when (m) {
        ChartMetric.TOTAL_W, ChartMetric.STACKED_W, ChartMetric.PER_PORT_W -> unitW
        ChartMetric.VOLTAGE_V -> unitV
        ChartMetric.CURRENT_A -> unitA
        ChartMetric.CUMULATIVE_WH -> unitWh
        ChartMetric.PROTOCOLS -> ""
    }

    fun metricFormat(m: ChartMetric): String = when (m) {
        ChartMetric.CURRENT_A, ChartMetric.CUMULATIVE_WH -> "%.2f"
        else -> "%.1f"
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    fun colorForCurveMetric(m: ChartMetric): Color = when (m) {
        ChartMetric.TOTAL_W -> primaryColor
        ChartMetric.PER_PORT_W -> secondaryColor
        ChartMetric.VOLTAGE_V -> semantic.warning
        ChartMetric.CURRENT_A -> tertiaryColor
        ChartMetric.CUMULATIVE_WH -> semantic.success
        else -> primaryColor
    }

    fun aggregateMetricValue(s: ChartPointSample, m: ChartMetric): Double = when (m) {
        ChartMetric.TOTAL_W -> s.totalW
        ChartMetric.STACKED_W, ChartMetric.PER_PORT_W -> activePortList.sumOf { (s.portW[it] ?: 0.0).coerceAtLeast(0.0) }
        ChartMetric.VOLTAGE_V -> activePortList.mapNotNull { pid -> s.portV[pid]?.takeIf { it > 0.1 } }.maxOrNull() ?: 0.0
        ChartMetric.CURRENT_A -> activePortList.sumOf { (s.portA[it] ?: 0.0).coerceAtLeast(0.0) }
        ChartMetric.CUMULATIVE_WH -> activePortList.sumOf { (s.portWh[it] ?: 0.0).coerceAtLeast(0.0) }
        ChartMetric.PROTOCOLS -> 0.0
    }

    fun peakForMetric(m: ChartMetric): Double {
        val rawPeak = when (m) {
            ChartMetric.TOTAL_W -> samples.maxOfOrNull { it.totalW } ?: 5.0
            ChartMetric.STACKED_W -> samples.maxOfOrNull { s -> activePortList.sumOf { (s.portW[it] ?: 0.0).coerceAtLeast(0.0) } } ?: 5.0
            ChartMetric.PER_PORT_W -> if (isMultiMetric) {
                samples.maxOfOrNull { aggregateMetricValue(it, m) } ?: 5.0
            } else {
                samples.maxOfOrNull { s -> activePortList.maxOfOrNull { s.portW[it] ?: 0.0 } ?: 0.0 } ?: 5.0
            }
            ChartMetric.VOLTAGE_V -> if (isMultiMetric) {
                samples.maxOfOrNull { aggregateMetricValue(it, m) } ?: 5.0
            } else {
                samples.maxOfOrNull { s -> activePortList.maxOfOrNull { s.portV[it] ?: 0.0 } ?: 0.0 } ?: 5.0
            }
            ChartMetric.CURRENT_A -> if (isMultiMetric) {
                samples.maxOfOrNull { aggregateMetricValue(it, m) } ?: 0.5
            } else {
                samples.maxOfOrNull { s -> activePortList.maxOfOrNull { s.portA[it] ?: 0.0 } ?: 0.0 } ?: 0.5
            }
            ChartMetric.CUMULATIVE_WH -> if (isMultiMetric) {
                samples.maxOfOrNull { aggregateMetricValue(it, m) } ?: 0.2
            } else {
                samples.maxOfOrNull { s -> activePortList.maxOfOrNull { s.portWh[it] ?: 0.0 } ?: 0.0 } ?: 0.2
            }
            ChartMetric.PROTOCOLS -> 1.0
        }
        val minFloor = when (m) {
            ChartMetric.CURRENT_A -> 0.5
            ChartMetric.CUMULATIVE_WH -> 0.2
            ChartMetric.VOLTAGE_V -> 5.0
            else -> 5.0
        }
        return rawPeak.coerceAtLeast(minFloor) * 1.12
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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

            // All 7 telemetry metrics toggleable via FlowRow (multi-metric overlay supported for curve metrics)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val metricOrder = listOf(
                        ChartMetric.TOTAL_W,
                        ChartMetric.STACKED_W,
                        ChartMetric.PER_PORT_W,
                        ChartMetric.VOLTAGE_V,
                        ChartMetric.CURRENT_A,
                        ChartMetric.CUMULATIVE_WH,
                        ChartMetric.PROTOCOLS
                    )
                    metricOrder.forEach { m ->
                        val isSelected = m in effectiveMetrics
                        FilterChip(
                            selected = isSelected,
                            onClick = { onToggleMetric(m) },
                            leadingIcon = if (isMultiMetric && isSelected && m in listOf(
                                    ChartMetric.TOTAL_W,
                                    ChartMetric.PER_PORT_W,
                                    ChartMetric.VOLTAGE_V,
                                    ChartMetric.CURRENT_A,
                                    ChartMetric.CUMULATIVE_WH
                                )
                            ) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(colorForCurveMetric(m))
                                    )
                                }
                            } else null,
                            label = { Text(metricLabel(m)) },
                            modifier = Modifier.testTag("chart_metric_${m.name.lowercase()}")
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.analytics_multi_metric_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            fun sampleStatValue(s: ChartPointSample, m: ChartMetric): Double = when (m) {
                ChartMetric.TOTAL_W -> s.totalW
                ChartMetric.STACKED_W -> activePortList.sumOf { (s.portW[it] ?: 0.0).coerceAtLeast(0.0) }
                ChartMetric.PER_PORT_W -> if (isMultiMetric) aggregateMetricValue(s, m) else activePortList.maxOfOrNull { s.portW[it] ?: 0.0 } ?: 0.0
                ChartMetric.VOLTAGE_V -> if (isMultiMetric) aggregateMetricValue(s, m) else activePortList.maxOfOrNull { s.portV[it] ?: 0.0 } ?: 0.0
                ChartMetric.CURRENT_A -> if (isMultiMetric) aggregateMetricValue(s, m) else activePortList.maxOfOrNull { s.portA[it] ?: 0.0 } ?: 0.0
                ChartMetric.CUMULATIVE_WH -> if (isMultiMetric) aggregateMetricValue(s, m) else activePortList.maxOfOrNull { s.portWh[it] ?: 0.0 } ?: 0.0
                ChartMetric.PROTOCOLS -> 0.0
            }

            val statSeries = remember(samples, primaryMetric, activePortList, isMultiMetric) {
                samples.map { sampleStatValue(it, primaryMetric) }
            }
            val statMin = remember(statSeries) { statSeries.minOrNull() ?: 0.0 }
            val statMax = remember(statSeries) { statSeries.maxOrNull() ?: 0.0 }
            val statAvg = remember(statSeries) {
                if (statSeries.isNotEmpty()) statSeries.sum() / statSeries.size else 0.0
            }

            if (primaryMetric != ChartMetric.PROTOCOLS) {
                val pUnit = metricUnit(primaryMetric)
                val pFmt = metricFormat(primaryMetric)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (samples.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag("chart_min_max_pills")
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.chart_pill_min_fmt,
                                        "${String.format(Locale.US, pFmt, statMin)} $pUnit"
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = TelemetryMonoFontFamily
                                    ).tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.chart_pill_max_fmt,
                                        "${String.format(Locale.US, pFmt, statMax)} $pUnit"
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = TelemetryMonoFontFamily
                                    ).tabularNums(),
                                    color = colorForCurveMetric(primaryMetric),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = visualStyle == ChartVisualStyle.CURVE,
                            onClick = { visualStyle = ChartVisualStyle.CURVE },
                            label = { Text(stringResource(R.string.chart_style_curve)) },
                            modifier = Modifier.testTag("chart_style_curve")
                        )
                        FilterChip(
                            selected = visualStyle == ChartVisualStyle.BARS,
                            onClick = { visualStyle = ChartVisualStyle.BARS },
                            label = { Text(stringResource(R.string.chart_style_bars)) },
                            modifier = Modifier.testTag("chart_style_bars")
                        )
                    }
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
            } else if (primaryMetric == ChartMetric.PROTOCOLS) {
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
                val metricPeaks = remember(samples, effectiveMetrics, activePortList) {
                    effectiveMetrics.associateWith { peakForMetric(it) }
                }
                val leftAxisMetric = effectiveMetrics.first()
                val leftMax = metricPeaks[leftAxisMetric] ?: 10.0
                val rightAxisMetric = if (effectiveMetrics.size == 2) effectiveMetrics[1] else null
                val rightMax = rightAxisMetric?.let { metricPeaks[it] ?: 10.0 }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Left Y-Axis: exact units for 1–2 metrics, normalized % for 3+ overlaid metrics
                    Column(
                        modifier = Modifier.height(148.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (effectiveMetrics.size >= 3) {
                            Text("100%", style = MaterialTheme.typography.labelSmall.tabularNums(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("50%", style = MaterialTheme.typography.labelSmall.tabularNums(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("0%", style = MaterialTheme.typography.labelSmall.tabularNums(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val unit = metricUnit(leftAxisMetric)
                            val fmt = metricFormat(leftAxisMetric)
                            val labelColor = if (isMultiMetric) colorForCurveMetric(leftAxisMetric) else MaterialTheme.colorScheme.onSurfaceVariant
                            Text(
                                text = "${String.format(Locale.US, fmt, leftMax)} $unit",
                                style = MaterialTheme.typography.labelSmall.tabularNums(),
                                color = labelColor
                            )
                            Text(
                                text = "${String.format(Locale.US, fmt, leftMax / 2.0)} $unit",
                                style = MaterialTheme.typography.labelSmall.tabularNums(),
                                color = labelColor
                            )
                            Text(
                                text = "0 $unit",
                                style = MaterialTheme.typography.labelSmall.tabularNums(),
                                color = labelColor
                            )
                        }
                    }

                    val totalLineColor = MaterialTheme.colorScheme.primary
                    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    val crosshairColor = MaterialTheme.colorScheme.outline
                    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow

                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .height(148.dp)
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

                            if (samples.isNotEmpty() && statAvg > 0.01 && !isMultiMetric) {
                                val avgY = size.height - ((statAvg / leftMax).toFloat().coerceIn(0f, 1f) * usableH)
                                drawLine(
                                    color = colorForCurveMetric(primaryMetric).copy(alpha = 0.55f),
                                    start = Offset(0f, avgY),
                                    end = Offset(size.width, avgY),
                                    strokeWidth = 1.4.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                                )
                            }

                            if (visualStyle == ChartVisualStyle.BARS && samples.isNotEmpty()) {
                                val barCount = samples.size.coerceAtMost(32)
                                val bucketIndices = (0 until barCount).map { b ->
                                    if (barCount == 1) 0 else ((b.toFloat() / (barCount - 1)) * samples.lastIndex).roundToInt().coerceIn(0, samples.lastIndex)
                                }
                                val slotW = size.width / barCount.coerceAtLeast(1)
                                val gapPx = (slotW * 0.22f).coerceIn(1.5.dp.toPx(), 5.dp.toPx())
                                val barW = (slotW - gapPx).coerceAtLeast(3f)
                                val cornerR = CornerRadius(4.dp.toPx(), 4.dp.toPx())

                                if (isMultiMetric) {
                                    val subCount = effectiveMetrics.size.coerceAtLeast(1)
                                    val subW = ((barW - (subCount - 1) * 1.5.dp.toPx()) / subCount).coerceAtLeast(2f)
                                    bucketIndices.forEachIndexed { bIdx, sIdx ->
                                        val s = samples[sIdx]
                                        val baseX = bIdx * slotW + gapPx / 2f
                                        effectiveMetrics.forEachIndexed { mIdx, m ->
                                            val mMax = (metricPeaks[m] ?: 10.0).coerceAtLeast(0.01)
                                            val v = aggregateMetricValue(s, m)
                                            val h = ((v / mMax).toFloat().coerceIn(0f, 1f) * usableH).coerceAtLeast(if (v > 0.01) 3f else 0f)
                                            if (h > 0f) {
                                                drawRoundRect(
                                                    color = colorForCurveMetric(m),
                                                    topLeft = Offset(baseX + mIdx * (subW + 1.5.dp.toPx()), size.height - h),
                                                    size = Size(subW, h),
                                                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                                )
                                            }
                                        }
                                    }
                                } else if (primaryMetric == ChartMetric.STACKED_W) {
                                    bucketIndices.forEachIndexed { bIdx, sIdx ->
                                        val s = samples[sIdx]
                                        val x = bIdx * slotW + gapPx / 2f
                                        var yBottom = size.height
                                        for (portId in activePortList.sortedDescending()) {
                                            val w = (s.portW[portId] ?: 0.0).coerceAtLeast(0.0)
                                            val segH = ((w / leftMax).toFloat().coerceIn(0f, 1f) * usableH)
                                            if (segH > 1f) {
                                                val top = (yBottom - segH).coerceAtLeast(padTop)
                                                drawRoundRect(
                                                    color = semantic.colorForPort(portId),
                                                    topLeft = Offset(x, top),
                                                    size = Size(barW, yBottom - top),
                                                    cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())
                                                )
                                                yBottom = top
                                            }
                                        }
                                    }
                                } else {
                                    val barColor = colorForCurveMetric(primaryMetric)
                                    bucketIndices.forEachIndexed { bIdx, sIdx ->
                                        val s = samples[sIdx]
                                        val v = sampleStatValue(s, primaryMetric)
                                        val h = ((v / leftMax).toFloat().coerceIn(0f, 1f) * usableH).coerceAtLeast(if (v > 0.01) 3f else 0f)
                                        if (h > 0f) {
                                            val isScrubbed = scrubIndex in samples.indices && kotlin.math.abs(sIdx - scrubIndex) <= (samples.size / barCount.coerceAtLeast(1)).coerceAtLeast(1)
                                            drawRoundRect(
                                                color = if (isScrubbed) barColor else barColor.copy(alpha = 0.82f),
                                                topLeft = Offset(bIdx * slotW + gapPx / 2f, size.height - h),
                                                size = Size(barW, h),
                                                cornerRadius = cornerR
                                            )
                                        }
                                    }
                                }

                                if (scrubIndex in samples.indices) {
                                    val cx = if (samples.size > 1) {
                                        (scrubIndex.toFloat() / (samples.size - 1)) * size.width
                                    } else size.width / 2f
                                    drawLine(crosshairColor.copy(alpha = 0.65f), Offset(cx, 0f), Offset(cx, size.height), 1.5.dp.toPx())
                                }
                            } else if (samples.size >= 2) {
                                if (isMultiMetric) {
                                    for (m in effectiveMetrics) {
                                        val mColor = colorForCurveMetric(m)
                                        val mMax = (metricPeaks[m] ?: 10.0).coerceAtLeast(0.01)
                                        val offsets = samples.mapIndexed { idx, s ->
                                            val v = aggregateMetricValue(s, m)
                                            val x = (idx.toFloat() / (samples.size - 1)) * size.width
                                            val y = size.height - ((v / mMax).toFloat().coerceIn(0f, 1f) * usableH)
                                            Offset(x, y)
                                        }
                                        val curve = buildMonotoneCubicPath(offsets, padTop, size.height)
                                        drawPath(
                                            path = curve,
                                            color = mColor,
                                            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                        )
                                        if (scrubIndex in offsets.indices) {
                                            val pt = offsets[scrubIndex]
                                            drawCircle(surfaceLow, radius = 5.dp.toPx(), center = pt)
                                            drawCircle(mColor, radius = 3.4.dp.toPx(), center = pt)
                                        }
                                    }
                                    if (scrubIndex in samples.indices) {
                                        val cx = (scrubIndex.toFloat() / (samples.size - 1)) * size.width
                                        drawLine(crosshairColor.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, size.height), 1.5.dp.toPx())
                                    }
                                } else {
                                    when (primaryMetric) {
                                        ChartMetric.TOTAL_W -> {
                                            val offsets = samples.mapIndexed { idx, s ->
                                                val x = (idx.toFloat() / (samples.size - 1)) * size.width
                                                val y = size.height - ((s.totalW / leftMax).toFloat().coerceIn(0f, 1f) * usableH)
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
                                                    val y = size.height - ((nextTotals[idx] / leftMax).toFloat().coerceIn(0f, 1f) * usableH)
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
                                                    val cy = size.height - ((cumulative / leftMax).toFloat().coerceIn(0f, 1f) * usableH)
                                                    drawCircle(surfaceLow, radius = 5.dp.toPx(), center = Offset(cx, cy))
                                                    drawCircle(semantic.colorForPort(portId), radius = 3.5.dp.toPx(), center = Offset(cx, cy))
                                                }
                                            }
                                        }

                                        else -> {
                                            for (portId in activePortList) {
                                                val pColor = semantic.colorForPort(portId)
                                                val offsets = samples.mapIndexed { idx, s ->
                                                    val v = when (primaryMetric) {
                                                        ChartMetric.PER_PORT_W -> s.portW[portId] ?: 0.0
                                                        ChartMetric.VOLTAGE_V -> s.portV[portId] ?: 0.0
                                                        ChartMetric.CURRENT_A -> s.portA[portId] ?: 0.0
                                                        ChartMetric.CUMULATIVE_WH -> s.portWh[portId] ?: 0.0
                                                        else -> 0.0
                                                    }
                                                    val x = (idx.toFloat() / (samples.size - 1)) * size.width
                                                    val y = size.height - ((v / leftMax).toFloat().coerceIn(0f, 1f) * usableH)
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
                        }

                        // Floating M3 Tooltip Pill anchored away from finger / edges
                        if (scrubIndex in samples.indices) {
                            val pt = samples[scrubIndex]
                            val rawX = if (samples.size > 1) {
                                (scrubIndex.toFloat() / (samples.size - 1)) * boxW
                            } else 0f
                            val placeRight = rawX < boxW * 0.52f
                            val tooltipOffsetX = if (placeRight) {
                                (rawX + 12f).coerceAtMost((boxW - 155f).coerceAtLeast(0f))
                            } else {
                                (rawX - 165f).coerceAtLeast(0f)
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
                                    if (isMultiMetric) {
                                        effectiveMetrics.forEach { m ->
                                            val v = aggregateMetricValue(pt, m)
                                            val unit = metricUnit(m)
                                            val fmt = metricFormat(m)
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(colorForCurveMetric(m))
                                                )
                                                Text(
                                                    text = "${metricLabel(m)}: ${String.format(Locale.US, fmt, v)} $unit",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFamily = TelemetryMonoFontFamily,
                                                        fontWeight = FontWeight.SemiBold
                                                    ).tabularNums(),
                                                    color = MaterialTheme.colorScheme.inverseOnSurface
                                                )
                                            }
                                        }
                                    } else {
                                        val unit = metricUnit(primaryMetric)
                                        val valFmt = metricFormat(primaryMetric)
                                        if (primaryMetric == ChartMetric.TOTAL_W || primaryMetric == ChartMetric.STACKED_W) {
                                            val total = if (primaryMetric == ChartMetric.STACKED_W) {
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
                                        if (primaryMetric != ChartMetric.TOTAL_W) {
                                            activePortList.forEach { pid ->
                                                val v = when (primaryMetric) {
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

                    // Dual Y-Axis on the right when exactly 2 metrics are overlaid
                    if (rightAxisMetric != null && rightMax != null) {
                        val rUnit = metricUnit(rightAxisMetric)
                        val rFmt = metricFormat(rightAxisMetric)
                        val rColor = colorForCurveMetric(rightAxisMetric)
                        Column(
                            modifier = Modifier.height(148.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = "${String.format(Locale.US, rFmt, rightMax)} $rUnit",
                                style = MaterialTheme.typography.labelSmall.tabularNums(),
                                color = rColor
                            )
                            Text(
                                text = "${String.format(Locale.US, rFmt, rightMax / 2.0)} $rUnit",
                                style = MaterialTheme.typography.labelSmall.tabularNums(),
                                color = rColor
                            )
                            Text(
                                text = "0 $rUnit",
                                style = MaterialTheme.typography.labelSmall.tabularNums(),
                                color = rColor
                            )
                        }
                    }
                }

                // Multi-metric colored legend when 2+ parameters are overlaid
                if (isMultiMetric) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chart_multi_metric_legend"),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        effectiveMetrics.forEach { m ->
                            val peak = metricPeaks[m] ?: 0.0
                            val unit = metricUnit(m)
                            val fmt = metricFormat(m)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(colorForCurveMetric(m))
                                )
                                Text(
                                    text = "${metricLabel(m)} (${String.format(Locale.US, fmt, peak / 1.12)} $unit)",
                                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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

                // Bottom Average / Minimum / Maximum stats strip
                val pUnit = metricUnit(primaryMetric)
                val pFmt = metricFormat(primaryMetric)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chart_stats_row")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chart_stat_avg),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${String.format(Locale.US, pFmt, statAvg)} $pUnit",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = TelemetryMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ).tabularNums()
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chart_stat_min),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${String.format(Locale.US, pFmt, statMin)} $pUnit",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = TelemetryMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ).tabularNums()
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chart_stat_max),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${String.format(Locale.US, pFmt, statMax)} $pUnit",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = TelemetryMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ).tabularNums(),
                                color = colorForCurveMetric(primaryMetric)
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.analytics_chart_scrub_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Single-line compact C1 | C2 | C3 | A port filter chips
                if (effectiveMetrics != listOf(ChartMetric.TOTAL_W)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.analytics_port_filter_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (portId in 1..4) {
                                val selected = portId in visiblePorts
                                val fullPortCd = explicitPortName(portId)
                                val shortLabel = when (portId) {
                                    1 -> stringResource(R.string.port_short_c1)
                                    2 -> stringResource(R.string.port_short_c2)
                                    3 -> stringResource(R.string.port_short_c3)
                                    else -> stringResource(R.string.port_short_a)
                                }
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
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(semantic.colorForPort(portId))
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = shortLabel,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Clip
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .semantics { contentDescription = fullPortCd }
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
