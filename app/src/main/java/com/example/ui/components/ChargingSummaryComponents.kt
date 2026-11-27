package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.power.DailyWhBucket
import com.example.cuktech.power.PeriodSummarySnapshot
import com.example.cuktech.power.SummaryPeriodMode
import com.example.ui.viewmodel.ChartPointSample
import com.example.ui.theme.ExpressiveMotion
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChargingPeriodSummaryCard(
    summary: PeriodSummarySnapshot,
    onSelectPeriod: (SummaryPeriodMode, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val unitWh = stringResource(R.string.unit_wh)
    val unitW = stringResource(R.string.unit_w)

    val rangeFmt = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    val monthFmt = remember { SimpleDateFormat("LLLL yyyy", Locale.getDefault()) }
    val periodLabel = remember(summary.mode, summary.startMs, summary.endMs) {
        if (summary.mode == SummaryPeriodMode.WEEK) {
            "${rangeFmt.format(Date(summary.startMs))} – ${rangeFmt.format(Date(summary.endMs))}"
        } else {
            monthFmt.format(Date(summary.startMs)).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        }
    }

    val scoreFraction by animateFloatAsState(
        targetValue = if (summary.hasData) (summary.qualityScore / 100f).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(550, easing = ExpressiveMotion.StandardEasing),
        label = "summary_score_arc"
    )
    val scoreColor = when {
        !summary.hasData -> MaterialTheme.colorScheme.outlineVariant
        summary.qualityScore >= 85 -> semantic.success
        summary.qualityScore >= 65 -> MaterialTheme.colorScheme.primary
        else -> semantic.warning
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("analytics_summary_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.summary_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = summary.mode == SummaryPeriodMode.WEEK,
                        onClick = { onSelectPeriod(SummaryPeriodMode.WEEK, 0) },
                        label = { Text(stringResource(R.string.summary_period_week)) },
                        modifier = Modifier.testTag("summary_mode_week")
                    )
                    FilterChip(
                        selected = summary.mode == SummaryPeriodMode.MONTH,
                        onClick = { onSelectPeriod(SummaryPeriodMode.MONTH, 0) },
                        label = { Text(stringResource(R.string.summary_period_month)) },
                        modifier = Modifier.testTag("summary_mode_month")
                    )
                }
            }

            // Period navigator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onSelectPeriod(summary.mode, summary.periodOffset - 1) },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("summary_prev_period_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                }
                Text(
                    text = periodLabel,
                    style = MaterialTheme.typography.titleSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = { onSelectPeriod(summary.mode, (summary.periodOffset + 1).coerceAtMost(0)) },
                    enabled = summary.periodOffset < 0,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("summary_next_period_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }

            if (!summary.hasData) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("summary_empty_period")
                ) {
                    Text(
                        text = stringResource(R.string.summary_empty_period),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                // Charging Quality Gauge + Status
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(78.dp)
                                .testTag("summary_quality_gauge")
                        ) {
                            Canvas(modifier = Modifier.size(78.dp)) {
                                val strokePx = 8.dp.toPx()
                                val inset = strokePx / 2f
                                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                                drawArc(
                                    color = trackColor,
                                    startAngle = 140f,
                                    sweepAngle = 260f,
                                    useCenter = false,
                                    topLeft = Offset(inset, inset),
                                    size = arcSize,
                                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                )
                                drawArc(
                                    color = scoreColor,
                                    startAngle = 140f,
                                    sweepAngle = 260f * scoreFraction,
                                    useCenter = false,
                                    topLeft = Offset(inset, inset),
                                    size = arcSize,
                                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                )
                            }
                            Text(
                                text = "${summary.qualityScore}",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = TelemetryMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ).tabularNums(),
                                color = scoreColor
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.summary_score_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = when {
                                    summary.qualityScore >= 85 -> stringResource(R.string.summary_score_excellent)
                                    summary.qualityScore >= 65 -> stringResource(R.string.summary_score_good)
                                    else -> stringResource(R.string.summary_score_fair)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${summary.activeDays} ${stringResource(R.string.unit_d)} · ${summary.sessionCount} ${stringResource(R.string.summary_stat_sessions).lowercase()}",
                                style = MaterialTheme.typography.bodySmall.tabularNums(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 2x3 KPI Metric Grid
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SummaryKpiTile(
                            label = stringResource(R.string.summary_stat_total_wh),
                            value = "${String.format(Locale.US, "%.2f", summary.totalWh)} $unitWh",
                            modifier = Modifier.weight(1f)
                        )
                        SummaryKpiTile(
                            label = stringResource(R.string.summary_stat_peak),
                            value = "${String.format(Locale.US, "%.1f", summary.peakWatts)} / ${String.format(Locale.US, "%.1f", summary.avgWatts)} $unitW",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SummaryKpiTile(
                            label = stringResource(R.string.summary_stat_boost),
                            value = "${summary.fastBoostSharePct}%",
                            modifier = Modifier.weight(1f)
                        )
                        SummaryKpiTile(
                            label = stringResource(R.string.summary_stat_top_port),
                            value = summary.topPortId?.let { explicitPortName(it) } ?: "—",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (summary.totalCost > 0.001) {
                        SummaryKpiTile(
                            label = stringResource(R.string.summary_stat_cost),
                            value = "${String.format(Locale.US, "%.2f", summary.totalCost)} ${summary.currencyCode}",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryKpiTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = TelemetryMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ).tabularNums(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DailyEnergyBarChartCard(
    buckets: List<DailyWhBucket>,
    modifier: Modifier = Modifier
) {
    if (buckets.isEmpty()) return
    val unitWh = stringResource(R.string.unit_wh)
    val unitW = stringResource(R.string.unit_w)
    var selectedIdx by remember(buckets.size) { mutableIntStateOf(buckets.lastIndex) }
    val selected = buckets.getOrNull(selectedIdx.coerceIn(0, buckets.lastIndex))
    val maxWh = remember(buckets) { (buckets.maxOfOrNull { it.totalWh } ?: 5.0).coerceAtLeast(1.0) }
    val avgWh = remember(buckets) {
        val nonZero = buckets.filter { it.totalWh > 0.0 }
        if (nonZero.isNotEmpty()) nonZero.sumOf { it.totalWh } / nonZero.size else 0.0
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("analytics_daily_bars_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.analytics_daily_bars_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (selected != null) {
                    Text(
                        text = "${selected.dateIso} · ${String.format(Locale.US, "%.2f", selected.totalWh)} $unitWh",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = TelemetryMonoFontFamily,
                            fontWeight = FontWeight.SemiBold
                        ).tabularNums(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Interactive 10-day rounded column bars
            val primaryColor = MaterialTheme.colorScheme.primary
            val mutedBarColor = MaterialTheme.colorScheme.surfaceContainerHighest
            val avgLineColor = MaterialTheme.colorScheme.outlineVariant

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
            ) {
                if (avgWh > 0.01) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val frac = (avgWh / maxWh).toFloat().coerceIn(0.05f, 0.95f)
                        val y = size.height * (1f - frac)
                        drawLine(
                            color = avgLineColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    buckets.forEachIndexed { idx, bucket ->
                        val isSel = idx == selectedIdx
                        val frac = (bucket.totalWh / maxWh).toFloat().coerceIn(0f, 1f)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedIdx = idx }
                                .testTag("daily_bar_$idx"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                val heightFrac = if (bucket.totalWh > 0.01) frac.coerceAtLeast(0.08f) else 0.04f
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.72f)
                                        .fillMaxHeight(heightFrac)
                                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                        .background(
                                            when {
                                                bucket.totalWh <= 0.01 -> mutedBarColor
                                                isSel -> primaryColor
                                                else -> primaryColor.copy(alpha = 0.50f)
                                            }
                                        )
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = bucket.dayShortLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            if (selected != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryKpiTile(
                        label = stringResource(R.string.analytics_daily_bars_total),
                        value = "${String.format(Locale.US, "%.2f", selected.totalWh)} $unitWh",
                        modifier = Modifier.weight(1f)
                    )
                    SummaryKpiTile(
                        label = stringResource(R.string.analytics_daily_bars_peak),
                        value = "${String.format(Locale.US, "%.1f", selected.peakWatts)} $unitW",
                        modifier = Modifier.weight(1f)
                    )
                    SummaryKpiTile(
                        label = stringResource(R.string.analytics_daily_bars_sessions),
                        value = "${selected.sessionCount}",
                        modifier = Modifier.weight(0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun PortActivityTimelineCard(
    activeSessions: Map<Int, ChargeSessionRecord>,
    completedSessions: List<ChargeSessionRecord>,
    chartSamples: List<ChartPointSample>,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val nowMs = remember(activeSessions, completedSessions, chartSamples) { System.currentTimeMillis() }
    val windowStartMs = nowMs - 86_400_000L
    val bucketCount = 24
    val bucketMs = 3_600_000L

    // Compute hourly peak intensity (0..1) per port over the trailing 24 hours
    val portBuckets = remember(activeSessions, completedSessions, chartSamples, nowMs) {
        (1..4).associateWith { pid ->
            val wattsPerHour = DoubleArray(bucketCount) { 0.0 }
            val allSess = (completedSessions + activeSessions.values).filter { it.portId == pid }
            for (s in allSess) {
                val sStart = (s.startTimeSec * 1000.0).toLong()
                val sEnd = ((s.endTimeSec ?: (nowMs / 1000.0)) * 1000.0).toLong()
                if (sEnd < windowStartMs || sStart > nowMs) continue
                for (b in 0 until bucketCount) {
                    val bStart = windowStartMs + b * bucketMs
                    val bEnd = bStart + bucketMs
                    if (sEnd >= bStart && sStart <= bEnd) {
                        wattsPerHour[b] = max(wattsPerHour[b], max(s.peakWatts, s.avgWatts))
                    }
                }
            }
            for (pt in chartSamples) {
                if (pt.timestampMs in windowStartMs..nowMs) {
                    val idx = ((pt.timestampMs - windowStartMs) / bucketMs).toInt().coerceIn(0, bucketCount - 1)
                    val w = pt.portW[pid] ?: 0.0
                    if (w > 0.2) wattsPerHour[idx] = max(wattsPerHour[idx], w)
                }
            }
            wattsPerHour
        }
    }

    val hasAnyActivity = remember(portBuckets) {
        portBuckets.values.any { arr -> arr.any { it > 0.2 } }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("analytics_timeline_24h_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.analytics_timeline_24h_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (!hasAnyActivity) {
                Text(
                    text = stringResource(R.string.analytics_timeline_24h_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val emptyTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                val portMaxRated = mapOf(1 to 120.0, 2 to 45.0, 3 to 15.0, 4 to 15.0)
                for (pid in 1..4) {
                    val arr = portBuckets[pid] ?: DoubleArray(bucketCount)
                    val color = semantic.colorForPort(pid)
                    val maxRef = portMaxRated[pid] ?: 100.0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = explicitPortName(pid),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(64.dp),
                            maxLines = 1
                        )
                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .height(16.dp)
                        ) {
                            val gap = 2.dp.toPx()
                            val segW = ((size.width - gap * (bucketCount - 1)) / bucketCount).coerceAtLeast(2f)
                            for (b in 0 until bucketCount) {
                                val w = arr[b]
                                val alpha = if (w <= 0.2) 1f else (0.28f + (w / maxRef).toFloat().coerceIn(0f, 1f) * 0.72f)
                                val segColor = if (w <= 0.2) emptyTrackColor else color.copy(alpha = alpha)
                                drawRoundRect(
                                    color = segColor,
                                    topLeft = Offset(b * (segW + gap), 0f),
                                    size = Size(segW, size.height),
                                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                                )
                            }
                        }
                    }
                }

                val hrFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 72.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = hrFmt.format(Date(windowStartMs)),
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = hrFmt.format(Date(windowStartMs + 12 * bucketMs)),
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = hrFmt.format(Date(nowMs)),
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
