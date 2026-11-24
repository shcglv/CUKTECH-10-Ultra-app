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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.ui.components.ChargingPeriodSummaryCard
import com.example.ui.components.ClearHistoryDialog
import com.example.ui.components.ConsolidatedSessionsTableDialog
import com.example.ui.components.DailyEnergyBarChartCard
import com.example.ui.components.ExportCsvDialog
import com.example.ui.components.ExpressiveTelemetryChartCard
import com.example.ui.components.PixelEnergyBreakdownCard
import com.example.ui.components.PortActivityTimelineCard
import com.example.ui.components.buildMonotoneCubicPath
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AnalyticsScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val chartInterval by viewModel.chartInterval.collectAsStateWithLifecycle()
    val chartMetric by viewModel.chartMetric.collectAsStateWithLifecycle()
    val selectedMetrics by viewModel.selectedMetrics.collectAsStateWithLifecycle()
    val chartSamples by viewModel.chartSamples.collectAsStateWithLifecycle()
    val activeSessions by viewModel.activeSessions.collectAsStateWithLifecycle()
    val completedSessions by viewModel.completedSessions.collectAsStateWithLifecycle()
    val dailyAggregates by viewModel.dailyAggregates.collectAsStateWithLifecycle()
    val telemetryEnabled by viewModel.telemetryMasterEnabled.collectAsStateWithLifecycle()
    val portLoggingMap by viewModel.portLoggingMap.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val replayState by viewModel.replayState.collectAsStateWithLifecycle()
    val ports by viewModel.ports.collectAsStateWithLifecycle()
    val periodSummary by viewModel.periodSummary.collectAsStateWithLifecycle()
    val dailyWhBuckets by viewModel.dailyWhBuckets.collectAsStateWithLifecycle()

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
                    context.getString(if (ok) R.string.toast_csv_ok else R.string.toast_csv_fail),
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
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_saved_fmt, file.name),
                        Toast.LENGTH_LONG
                    ).show()
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

    val todayStartMs = remember(completedSessions.size) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val yesterdayStartMs = todayStartMs - 86_400_000L
    val todayList = remember(completedSessions, todayStartMs) {
        completedSessions.filter { (it.startTimeSec * 1000.0).toLong() >= todayStartMs }
    }
    val yesterdayList = remember(completedSessions, todayStartMs, yesterdayStartMs) {
        completedSessions.filter {
            val ms = (it.startTimeSec * 1000.0).toLong()
            ms in yesterdayStartMs until todayStartMs
        }
    }
    val earlierList = remember(completedSessions, yesterdayStartMs) {
        completedSessions.filter { (it.startTimeSec * 1000.0).toLong() < yesterdayStartMs }
    }

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

        // Next-Gen Interactive Bezier / Stacked Telemetry Chart Card
        item {
            ExpressiveTelemetryChartCard(
                interval = chartInterval,
                metric = chartMetric,
                selectedMetrics = selectedMetrics,
                samples = chartSamples,
                onSelectInterval = { iv ->
                    if (iv == ChartInterval.CUSTOM) {
                        showCustomRangeDialog = true
                    } else {
                        viewModel.selectChartInterval(iv)
                    }
                },
                onSelectMetric = { viewModel.selectChartMetric(it) },
                onToggleMetric = { viewModel.toggleChartMetric(it) }
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

        // Weekly / Monthly Expressive Summary Card
        item {
            ChargingPeriodSummaryCard(
                summary = periodSummary,
                onSelectPeriod = { mode, offset -> viewModel.selectSummaryPeriod(mode, offset) }
            )
        }

        // Last 10 Days Energy Bar Chart
        item {
            DailyEnergyBarChartCard(buckets = dailyWhBuckets)
        }

        // 24h 4-Port Activity Timeline Swimlanes
        item {
            PortActivityTimelineCard(
                activeSessions = activeSessions,
                completedSessions = completedSessions,
                chartSamples = chartSamples
            )
        }

        // Pixel Battery-Style Port Energy Distribution Card
        item {
            PixelEnergyBreakdownCard(
                ports = ports,
                activeSessions = activeSessions,
                completedSessions = completedSessions
            )
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
                                    text = "${String.format(Locale.US, "%.2f", agg.totalWh)} ${stringResource(R.string.unit_wh)}",
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

        // Completed Sessions grouped by Today / Yesterday / Earlier
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
            if (todayList.isNotEmpty()) {
                item(key = "hdr_today") {
                    Text(
                        text = stringResource(R.string.analytics_group_today),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("analytics_group_today")
                    )
                }
                items(todayList, key = { "done_t_${it.id}_${it.startTimeSec}" }) { session ->
                    SessionHistoryCard(
                        session = session,
                        onReplayClick = { viewModel.openSessionReplay(session) }
                    )
                }
            }
            if (yesterdayList.isNotEmpty()) {
                item(key = "hdr_yesterday") {
                    Text(
                        text = stringResource(R.string.analytics_group_yesterday),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("analytics_group_yesterday")
                    )
                }
                items(yesterdayList, key = { "done_y_${it.id}_${it.startTimeSec}" }) { session ->
                    SessionHistoryCard(
                        session = session,
                        onReplayClick = { viewModel.openSessionReplay(session) }
                    )
                }
            }
            if (earlierList.isNotEmpty()) {
                item(key = "hdr_earlier") {
                    Text(
                        text = stringResource(R.string.analytics_group_earlier),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("analytics_group_earlier")
                    )
                }
                items(earlierList, key = { "done_e_${it.id}_${it.startTimeSec}" }) { session ->
                    SessionHistoryCard(
                        session = session,
                        onReplayClick = { viewModel.openSessionReplay(session) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
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
    val activeLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val dateFmt = remember(activeLocale) { SimpleDateFormat("d MMM · HH:mm", activeLocale) }
    val unitW = stringResource(R.string.unit_w)
    val unitWh = stringResource(R.string.unit_wh)

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
                        text = if (session.protocolUsed == "Idle") stringResource(R.string.port_status_idle) else session.protocolUsed,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Text(
                    text = "${String.format(Locale.US, "%.2f", session.whAccumulated)} $unitWh",
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
                        text = "${dateFmt.format(Date((session.startTimeSec * 1000).toLong()))} · ${stringResource(R.string.session_charged_for_fmt, formatDurationHms(session.durationSeconds))}",
                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = buildString {
                            append(stringResource(R.string.session_peak_fmt, session.peakWatts, unitW))
                            append(" · ")
                            append(stringResource(R.string.session_avg_fmt, session.avgWatts, unitW))
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

            val phaseTotalSec = session.fastBoostDurationSec + session.taperDurationSec
            if (phaseTotalSec > 0L) {
                val boostFrac = (session.fastBoostDurationSec.toFloat() / phaseTotalSec).coerceIn(0f, 1f)
                val taperFrac = (session.taperDurationSec.toFloat() / phaseTotalSec).coerceIn(0f, 1f)
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .testTag("session_phase_bar_${session.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (boostFrac > 0.01f) {
                            Box(
                                modifier = Modifier
                                    .weight(boostFrac.coerceAtLeast(0.05f))
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                        if (taperFrac > 0.01f) {
                            Box(
                                modifier = Modifier
                                    .weight(taperFrac.coerceAtLeast(0.05f))
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.tertiary)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.session_phase_boost_fmt, formatDurationHms(session.fastBoostDurationSec)),
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.session_phase_taper_fmt, formatDurationHms(session.taperDurationSec)),
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("session_replay_dialog")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "${stringResource(R.string.replay_title)} · ${explicitPortName(session.portId)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
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
                    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(124.dp)
                            .pointerInput(samples) {
                                if (samples.size < 2) return@pointerInput
                                detectHorizontalDragGestures { change, _ ->
                                    change.consume()
                                    val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    onScrub((frac * samples.lastIndex).roundToInt().coerceIn(0, samples.lastIndex))
                                }
                            }
                            .pointerInput(samples) {
                                if (samples.size < 2) return@pointerInput
                                detectTapGestures { off ->
                                    val frac = (off.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    onScrub((frac * samples.lastIndex).roundToInt().coerceIn(0, samples.lastIndex))
                                }
                            }
                    ) {
                        if (samples.size >= 2) {
                            val padY = 6.dp.toPx()
                            val usableH = (size.height - padY * 2).coerceAtLeast(4f)
                            val offsets = samples.mapIndexed { idx, s ->
                                val x = (idx.toFloat() / (samples.size - 1)) * size.width
                                val y = size.height - padY - ((s.powerW / maxW).toFloat().coerceIn(0f, 1f) * usableH)
                                Offset(x, y)
                            }
                            val curve = buildMonotoneCubicPath(offsets, padY, size.height - padY)
                            val fill = Path().apply {
                                addPath(curve)
                                lineTo(offsets.last().x, size.height)
                                lineTo(offsets.first().x, size.height)
                                close()
                            }
                            drawPath(
                                path = fill,
                                brush = Brush.verticalGradient(
                                    listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0f))
                                )
                            )
                            drawPath(
                                path = curve,
                                color = lineColor,
                                style = Stroke(width = 2.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                            val cursorPt = offsets[replayState.cursorIndex.coerceIn(0, offsets.lastIndex)]
                            drawLine(cursorColor, Offset(cursorPt.x, 0f), Offset(cursorPt.x, size.height), strokeWidth = 2.dp.toPx())
                            drawCircle(surfaceHigh, radius = 5.dp.toPx(), center = cursorPt)
                            drawCircle(cursorColor, radius = 3.5.dp.toPx(), center = cursorPt)
                        }
                    }

                    if (currentSample != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val unitW = stringResource(R.string.unit_w)
                                val unitV = stringResource(R.string.unit_v)
                                val unitA = stringResource(R.string.unit_a)
                                val unitWh = stringResource(R.string.unit_wh)
                                Text(
                                    text = "${tsFmt.format(Date(currentSample.timestampMs))} · ${currentSample.protocolName}",
                                    style = MaterialTheme.typography.labelLarge.tabularNums(),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%.1f %s   ·   %.2f %s   ·   %.2f %s   ·   %.2f %s",
                                        currentSample.powerW,
                                        unitW,
                                        currentSample.voltageV,
                                        unitV,
                                        currentSample.currentA,
                                        unitA,
                                        currentSample.cumulativeWh,
                                        unitWh
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
                Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cd_close))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomChartRangeDialog(
    onSelectHours: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.analytics_interval_custom),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.dialog_custom_range_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf(1L, 3L, 6L, 12L, 24L, 72L).forEach { hrs ->
                OutlinedButton(
                    onClick = { onSelectHours(hrs) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.chart_last_hours_fmt, hrs.toInt()))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
