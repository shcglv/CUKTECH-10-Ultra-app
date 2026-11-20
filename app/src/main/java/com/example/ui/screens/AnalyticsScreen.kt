package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.ui.components.ClearHistoryDialog
import com.example.ui.components.ConsolidatedSessionsTableDialog
import com.example.ui.components.ExportCsvDialog
import com.example.ui.components.explicitPortName
import com.example.ui.components.formatDurationHms
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.ChartInterval
import com.example.ui.viewmodel.ChartMetric
import com.example.ui.viewmodel.ChartPointSample
import com.example.ui.viewmodel.DashboardViewModel
import com.example.ui.viewmodel.SessionReplayState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AnalyticsScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val chartInterval by viewModel.chartInterval.collectAsStateWithLifecycle()
    val chartMetric by viewModel.chartMetric.collectAsStateWithLifecycle()
    val chartSamples by viewModel.chartSamples.collectAsStateWithLifecycle()
    val activeSessions by viewModel.activeSessions.collectAsStateWithLifecycle()
    val completedSessions by viewModel.completedSessions.collectAsStateWithLifecycle()
    val dailyAggregates by viewModel.dailyAggregates.collectAsStateWithLifecycle()
    val telemetryEnabled by viewModel.telemetryMasterEnabled.collectAsStateWithLifecycle()
    val portLoggingMap by viewModel.portLoggingMap.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val replayState by viewModel.replayState.collectAsStateWithLifecycle()

    var showExportDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showSummaryTable by remember { mutableStateOf(false) }
    var showCustomRangeDialog by remember { mutableStateOf(false) }

    var pendingSafTimeframe by remember { mutableStateOf(CuktechPersistence.ExportTimeframe.ALL_TIME) }
    var pendingSafScope by remember { mutableStateOf(CuktechPersistence.ExportScope.ALL) }

    val safCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportCsvToSafUri(uri, pendingSafTimeframe, pendingSafScope) { ok ->
                Toast.makeText(
                    context,
                    if (ok) "CSV exported successfully" else "CSV export failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    if (showExportDialog) {
        ExportCsvDialog(
            onExportDownloads = { tf, sc ->
                showExportDialog = false
                viewModel.exportCsvToDownloads(tf, sc) { file ->
                    Toast.makeText(context, "Saved: ${file.name}", Toast.LENGTH_LONG).show()
                }
            },
            onExportSaf = { tf, sc ->
                showExportDialog = false
                pendingSafTimeframe = tf
                pendingSafScope = sc
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                safCsvLauncher.launch("cuktech10_history_$stamp.csv")
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (showClearDialog) {
        ClearHistoryDialog(
            onConfirm = { option ->
                showClearDialog = false
                viewModel.clearHistory(option)
            },
            onDismiss = { showClearDialog = false }
        )
    }

    if (showSummaryTable) {
        ConsolidatedSessionsTableDialog(
            sessions = activeSessions.values.toList() + completedSessions,
            onDismiss = { showSummaryTable = false }
        )
    }

    if (showCustomRangeDialog) {
        CustomChartRangeDialog(
            onSelectHours = { hoursBack ->
                showCustomRangeDialog = false
                val now = System.currentTimeMillis()
                viewModel.setCustomChartRange(now - hoursBack * 3_600_000L, now)
            },
            onDismiss = { showCustomRangeDialog = false }
        )
    }

    if (replayState.session != null) {
        SessionReplayDialog(
            replayState = replayState,
            onScrub = { viewModel.scrubReplayTo(it) },
            onTogglePlay = { viewModel.toggleReplayPlayback() },
            onDismiss = { viewModel.closeSessionReplay() }
        )
    }

    val allPortsLoggingDisabled = portLoggingMap.values.none { it }
    val showLoggingPausedNotice = !telemetryEnabled || allPortsLoggingDisabled

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("analytics_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(6.dp)) }

        if (showLoggingPausedNotice) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = stringResource(R.string.analytics_logging_paused_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Separated Multi-Interval & Single-Unit Chart Card
        item {
            TelemetryChartCard(
                interval = chartInterval,
                metric = chartMetric,
                samples = chartSamples,
                onSelectInterval = { iv ->
                    if (iv == ChartInterval.CUSTOM) {
                        showCustomRangeDialog = true
                    } else {
                        viewModel.selectChartInterval(iv)
                    }
                },
                onSelectMetric = { viewModel.selectChartMetric(it) }
            )
        }

        // Action Bar: Summary Table, Export CSV, Clear History
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { showSummaryTable = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("analytics_summary_table_button"),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.analytics_view_table), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                    onClick = { showExportDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("analytics_export_csv_button"),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.analytics_export_csv), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("analytics_clear_history_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.analytics_clear_history),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Archived Daily Energy Aggregates (> retentionDays)
        if (dailyAggregates.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.analytics_daily_aggregates, prefs.retentionDays),
                            style = MaterialTheme.typography.titleSmall
                        )
                        dailyAggregates.take(7).forEach { agg ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${agg.dateIso} · ${explicitPortName(agg.portId)} (${agg.sessionCount})",
                                    style = MaterialTheme.typography.bodySmall.tabularNums()
                                )
                                Text(
                                    text = String.format(Locale.US, "%.2f Wh", agg.totalWh),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = TelemetryMonoFontFamily
                                    ).tabularNums(),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Active Sessions
        if (activeSessions.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.analytics_active_sessions),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(activeSessions.values.toList(), key = { "active_${it.portId}" }) { session ->
                SessionHistoryCard(
                    session = session,
                    onReplayClick = { viewModel.openSessionReplay(session) }
                )
            }
        }

        // Completed Sessions
        item {
            Text(
                text = stringResource(R.string.analytics_completed_sessions),
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (completedSessions.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.analytics_no_sessions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(completedSessions, key = { "done_${it.id}_${it.startTimeSec}" }) { session ->
                SessionHistoryCard(
                    session = session,
                    onReplayClick = { viewModel.openSessionReplay(session) }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TelemetryChartCard(
    interval: ChartInterval,
    metric: ChartMetric,
    samples: List<ChartPointSample>,
    onSelectInterval: (ChartInterval) -> Unit,
    onSelectMetric: (ChartMetric) -> Unit
) {
    val semantic = LocalSemanticColors.current
    val timeFmt = remember(interval) {
        SimpleDateFormat(
            if (interval == ChartInterval.SEC_60 || interval == ChartInterval.MIN_5) "HH:mm:ss" else "HH:mm",
            Locale.US
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
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

            // Separated Y-axis metric selector row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val metricLabels = listOf(
                    ChartMetric.TOTAL_W to stringResource(R.string.analytics_metric_total_w),
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
                // Protocol Timeline Lanes per physical port
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (portId in 1..4) {
                        val color = semantic.colorForPort(portId)
                        val latestProto = samples.lastOrNull()?.portProtocol?.get(portId) ?: "Idle"
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
                    ChartMetric.TOTAL_W, ChartMetric.PER_PORT_W -> "W"
                    ChartMetric.VOLTAGE_V -> "V"
                    ChartMetric.CURRENT_A -> "A"
                    ChartMetric.CUMULATIVE_WH -> "Wh"
                    ChartMetric.PROTOCOLS -> ""
                }

                val maxVal = remember(samples, metric) {
                    val peak = when (metric) {
                        ChartMetric.TOTAL_W -> samples.maxOfOrNull { it.totalW } ?: 10.0
                        ChartMetric.PER_PORT_W -> samples.maxOfOrNull { it.portW.values.maxOrNull() ?: 0.0 } ?: 10.0
                        ChartMetric.VOLTAGE_V -> samples.maxOfOrNull { it.portV.values.maxOrNull() ?: 0.0 } ?: 20.0
                        ChartMetric.CURRENT_A -> samples.maxOfOrNull { it.portA.values.maxOrNull() ?: 0.0 } ?: 5.0
                        ChartMetric.CUMULATIVE_WH -> samples.maxOfOrNull { it.portWh.values.maxOrNull() ?: 0.0 } ?: 1.0
                        ChartMetric.PROTOCOLS -> 1.0
                    }
                    peak.coerceAtLeast(1.0) * 1.1
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        modifier = Modifier.height(170.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${String.format(Locale.US, "%.1f", maxVal)} $unit",
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${String.format(Locale.US, "%.1f", maxVal / 2.0)} $unit",
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
                    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(170.dp)
                    ) {
                        drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                        drawLine(gridColor, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.dp.toPx())
                        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())

                        if (samples.size >= 2) {
                            if (metric == ChartMetric.TOTAL_W) {
                                val path = Path()
                                samples.forEachIndexed { idx, s ->
                                    val x = (idx.toFloat() / (samples.size - 1)) * size.width
                                    val y = size.height - ((s.totalW / maxVal).toFloat().coerceIn(0f, 1f) * size.height)
                                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(
                                    path = path,
                                    color = totalLineColor,
                                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            } else {
                                for (portId in 1..4) {
                                    val pColor = semantic.colorForPort(portId)
                                    val path = Path()
                                    samples.forEachIndexed { idx, s ->
                                        val v = when (metric) {
                                            ChartMetric.PER_PORT_W -> s.portW[portId] ?: 0.0
                                            ChartMetric.VOLTAGE_V -> s.portV[portId] ?: 0.0
                                            ChartMetric.CURRENT_A -> s.portA[portId] ?: 0.0
                                            ChartMetric.CUMULATIVE_WH -> s.portWh[portId] ?: 0.0
                                            else -> 0.0
                                        }
                                        val x = (idx.toFloat() / (samples.size - 1)) * size.width
                                        val y = size.height - ((v / maxVal).toFloat().coerceIn(0f, 1f) * size.height)
                                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                    }
                                    drawPath(
                                        path = path,
                                        color = pColor,
                                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
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

                if (metric != ChartMetric.TOTAL_W) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        for (portId in 1..4) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(semantic.colorForPort(portId))
                                )
                                Text(explicitPortName(portId), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHistoryCard(
    session: ChargeSessionRecord,
    onReplayClick: () -> Unit
) {
    val semantic = LocalSemanticColors.current
    val accent = semantic.colorForPort(session.portId)
    val portLabel = explicitPortName(session.portId)
    val dateFmt = remember { SimpleDateFormat("MMM dd · HH:mm", Locale.US) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_card_${session.id}_${session.portId}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = accent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = portLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = session.protocolUsed,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Text(
                    text = "${String.format(Locale.US, "%.2f", session.whAccumulated)} Wh",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = TelemetryMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ).tabularNums(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${dateFmt.format(Date((session.startTimeSec * 1000).toLong()))} · ${formatDurationHms(session.durationSeconds)}",
                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = buildString {
                            append("Peak ${String.format(Locale.US, "%.1f", session.peakWatts)}W · Avg ${String.format(Locale.US, "%.1f", session.avgWatts)}W")
                            if (session.computedCost > 0.0) {
                                append(" · ${String.format(Locale.US, "%.2f", session.computedCost)} ${session.currencyCode}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (session.gapDurationSec > 0L || !session.endReason.isNullOrBlank() || session.isPaused) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            if (session.isPaused) {
                                StatusBadge(stringResource(R.string.session_paused), MaterialTheme.colorScheme.tertiaryContainer)
                            }
                            if (session.gapDurationSec > 0L) {
                                StatusBadge(
                                    stringResource(R.string.session_gap_fmt, session.gapDurationSec),
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            }
                            session.endReason?.let { reason ->
                                StatusBadge(reason, MaterialTheme.colorScheme.secondaryContainer)
                            }
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onReplayClick,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("replay_session_button_${session.id}")
                ) {
                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.analytics_replay))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, containerColor: Color) {
    Surface(color = containerColor, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.tabularNums(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun SessionReplayDialog(
    replayState: SessionReplayState,
    onScrub: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onDismiss: () -> Unit
) {
    val session = replayState.session ?: return
    val currentSample = replayState.currentSample
    val tsFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("session_replay_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${stringResource(R.string.replay_title)} · ${explicitPortName(session.portId)}",
                    style = MaterialTheme.typography.titleMedium
                )

                when {
                    replayState.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    replayState.samples.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.replay_no_samples),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        val samples = replayState.samples
                        val maxW = (samples.maxOfOrNull { it.powerW } ?: 10.0).coerceAtLeast(5.0)
                        val lineColor = MaterialTheme.colorScheme.primary
                        val cursorColor = MaterialTheme.colorScheme.error

                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                        ) {
                            if (samples.size >= 2) {
                                val path = Path()
                                samples.forEachIndexed { idx, s ->
                                    val x = (idx.toFloat() / (samples.size - 1)) * size.width
                                    val y = size.height - ((s.powerW / maxW).toFloat().coerceIn(0f, 1f) * (size.height - 8.dp.toPx())) - 4.dp.toPx()
                                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(
                                    path = path,
                                    color = lineColor,
                                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                                val cursorX = (replayState.cursorIndex.toFloat() / (samples.size - 1)) * size.width
                                drawLine(cursorColor, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 2.dp.toPx())
                            }
                        }

                        if (currentSample != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "${tsFmt.format(Date(currentSample.timestampMs))} · ${currentSample.protocolName}",
                                        style = MaterialTheme.typography.labelLarge.tabularNums(),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "%.1f W   ·   %.2f V   ·   %.2f A   ·   %.2f Wh",
                                            currentSample.powerW,
                                            currentSample.voltageV,
                                            currentSample.currentA,
                                            currentSample.cumulativeWh
                                        ),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = TelemetryMonoFontFamily,
                                            fontWeight = FontWeight.SemiBold
                                        ).tabularNums(),
                                        modifier = Modifier.testTag("replay_sample_readout")
                                    )
                                }
                            }
                        }

                        if (samples.size > 1) {
                            Slider(
                                value = replayState.cursorIndex.toFloat(),
                                onValueChange = { onScrub(it.toInt()) },
                                valueRange = 0f..(samples.lastIndex).toFloat(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("replay_scrubber_slider")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = onTogglePlay,
                                enabled = samples.size > 1,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("replay_play_pause_button")
                            ) {
                                Icon(
                                    imageVector = if (replayState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (replayState.isPlaying) {
                                        stringResource(R.string.replay_pause)
                                    } else {
                                        stringResource(R.string.replay_play)
                                    }
                                )
                            }
                            Text(
                                text = "${replayState.cursorIndex + 1} / ${samples.size}",
                                style = MaterialTheme.typography.labelMedium.tabularNums()
                            )
                        }
                    }
                }

                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.cd_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomChartRangeDialog(
    onSelectHours: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.analytics_interval_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1L to "1h", 3L to "3h", 6L to "6h", 12L to "12h", 24L to "24h", 72L to "72h").forEach { (hrs, label) ->
                    OutlinedButton(
                        onClick = { onSelectHours(hrs) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Last $label")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
