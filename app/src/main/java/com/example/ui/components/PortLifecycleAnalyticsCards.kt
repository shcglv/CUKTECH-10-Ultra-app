package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.power.ComprehensivePortAnalytics
import com.example.cuktech.power.ElectricalHistogramBucket
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PortLifecycleMetricsCard(
    analytics: ComprehensivePortAnalytics,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("lifecycle_ports_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.lifecycle_ports_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (analytics.bridgeSamplesCount > 0 || analytics.directSamplesCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.analytics_source_badge_mixed_fmt,
                                analytics.directSamplesCount,
                                analytics.bridgeSamplesCount
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = TelemetryMonoFontFamily
                            ).tabularNums(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (analytics.portStats.isEmpty() || analytics.monitoredHours <= 0.0) {
                Text(
                    text = stringResource(R.string.lifecycle_ports_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                analytics.portStats.forEach { st ->
                    val accent = semantic.colorForPort(st.portId)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lifecycle_port_row_${st.portId}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${explicitPortName(st.portId)} · ${stringResource(R.string.lifecycle_conn_count_fmt, st.popularityRank, st.connectionCount)}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = accent,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${String.format(Locale.US, "%.2f", st.totalWh)} ${stringResource(R.string.unit_wh)}",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontFamily = TelemetryMonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ).tabularNums()
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.lifecycle_occ_util_eff_fmt,
                                    st.occupancyPct,
                                    st.utilizationPct,
                                    st.efficiencyRatioPct
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums()
                            )
                            Text(
                                text = stringResource(
                                    R.string.lifecycle_idle_fmt,
                                    st.idleAttachedMinutes,
                                    st.idleEnergyWh
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(
                                    R.string.lifecycle_rapid_drops_fmt,
                                    st.rapidReconnectCount,
                                    st.unexpectedDisconnectCount
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (st.unexpectedDisconnectCount > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.lifecycle_ble_gap_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun MultiPortAndHeatmapCard(
    analytics: ComprehensivePortAnalytics,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceBox = MaterialTheme.colorScheme.surfaceContainerHighest
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("lifecycle_heatmap_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.lifecycle_multiport_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            val m = analytics.simultaneousMultiport
            Text(
                text = stringResource(
                    R.string.lifecycle_multiport_row_fmt,
                    m.minutes1Port,
                    m.minutes2Ports,
                    m.minutes3Ports,
                    m.minutes4Ports,
                    m.maxConcurrentPorts
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Text(
                text = stringResource(R.string.lifecycle_heatmap_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.lifecycle_heatmap_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
            ) {
                val rows = 7
                val cols = 6
                val gap = 4.dp.toPx()
                val cellW = (size.width - gap * (cols - 1)) / cols
                val cellH = (size.height - gap * (rows - 1)) / rows
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val intensity = analytics.weeklyHeatmap.getOrNull(r)?.getOrNull(c)?.toFloat() ?: 0f
                        val color = if (intensity <= 0.01f) surfaceBox else primary.copy(alpha = (0.18f + 0.82f * intensity).coerceIn(0f, 1f))
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(c * (cellW + gap), r * (cellH + gap)),
                            size = Size(cellW, cellH),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Text(
                text = stringResource(R.string.lifecycle_hourly_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            val maxHourWh = (analytics.hourlyWhProfile.maxOrNull() ?: 1.0).coerceAtLeast(0.05)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                val count = 24
                val gap = 2.dp.toPx()
                val barW = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2f)
                for (h in 0 until count) {
                    val v = analytics.hourlyWhProfile.getOrElse(h) { 0.0 }
                    val frac = (v / maxHourWh).toFloat().coerceIn(0f, 1f)
                    val bh = (size.height * frac).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        color = if (v > 0.0) primary else surfaceBox,
                        topLeft = Offset(h * (barW + gap), size.height - bh),
                        size = Size(barW, bh),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
            }
        }
    }
}

@Composable
fun PowerBandsAndHistogramsCard(
    analytics: ComprehensivePortAnalytics,
    modifier: Modifier = Modifier
) {
    val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("power_bands_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.power_bands_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.power_plateaus_fmt,
                    analytics.peakHoldMinutes,
                    analytics.plateausCount,
                    analytics.medianWatts,
                    analytics.whPerHourRate
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                color = MaterialTheme.colorScheme.primary
            )

            analytics.powerBands.forEach { band ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(band.label, style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = String.format(Locale.US, "%.0f%% · %.1f m · %.2f Wh", band.sharePct, band.minutes, band.energyWh),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (band.sharePct / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text(stringResource(R.string.power_hist_voltage_title), style = MaterialTheme.typography.titleSmall)
            HistogramRow(buckets = analytics.voltageHistogram)

            Text(stringResource(R.string.power_hist_current_title), style = MaterialTheme.typography.titleSmall)
            HistogramRow(buckets = analytics.currentHistogram)

            if (analytics.topPeaks.isNotEmpty() || analytics.topDips.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(stringResource(R.string.power_peaks_dips_title), style = MaterialTheme.typography.titleSmall)
                (analytics.topPeaks.take(3) + analytics.topDips.take(3)).forEach { ev ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${if (ev.isPeak) "▲" else "▼"} ${explicitPortName(ev.portId)} · ${timeFmt.format(Date(ev.timestampMs))}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = String.format(Locale.US, "%.1fW (%.1fV/%.1fA)", ev.powerW, ev.voltageV, ev.currentA),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                            color = if (ev.isPeak) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistogramRow(buckets: List<ElectricalHistogramBucket>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        buckets.filter { it.sharePct >= 0.5 }.forEach { b ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = b.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily),
                    modifier = Modifier.weight(0.25f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .weight(0.55f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((b.sharePct / 100.0).toFloat().coerceIn(0.02f, 1f))
                            .height(7.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.0f%%", b.sharePct),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                    modifier = Modifier.weight(0.20f)
                )
            }
        }
    }
}

@Composable
fun ProtocolAttributionCard(
    analytics: ComprehensivePortAnalytics,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("power_protocols_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.power_protocols_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.power_protocols_honesty_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (analytics.protocolStats.isEmpty()) {
                Text(
                    text = stringResource(R.string.lifecycle_ports_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                analytics.protocolStats.forEach { p ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = p.protocolName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val badge = if (p.exactHardwareCount >= p.heuristicCount) {
                                        stringResource(R.string.proto_badge_exact_c12)
                                    } else {
                                        stringResource(R.string.proto_badge_heuristic_c3a)
                                    }
                                    Surface(
                                        color = if (p.exactHardwareCount >= p.heuristicCount) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        },
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = String.format(Locale.US, "%.0f%% · %.2f Wh", p.sharePct, p.energyWh),
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%.1f min · Avg %.1f W · Peak %.1f W · Switches %d",
                                    p.minutes,
                                    p.avgWatts,
                                    p.peakWatts,
                                    p.transitionCount
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
