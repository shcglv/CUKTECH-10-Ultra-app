package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BleBridgeService
import com.example.R
import com.example.cuktech.security.LogSanitizer
import com.example.ui.components.IntegrityAndRadarCard
import com.example.ui.components.PowerDoctorCard
import com.example.ui.components.explicitPortName
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var backProgress by remember { mutableFloatStateOf(0f) }

    PredictiveBackHandler(enabled = true) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                backProgress = backEvent.progress
            }
            backProgress = 0f
            onNavigateBack()
        } catch (_: CancellationException) {
            backProgress = 0f
        }
    }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val semantic = LocalSemanticColors.current
    val currentLevel by viewModel.currentLogLevel.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val diagStatus by viewModel.diagStatus.collectAsStateWithLifecycle()
    val anomalyAlerts by viewModel.anomalyAlerts.collectAsStateWithLifecycle()
    val wizardSteps by viewModel.connectionWizardSteps.collectAsStateWithLifecycle()
    val integrity by viewModel.integritySnapshot.collectAsStateWithLifecycle()
    val rssiHistory by viewModel.rssiHistory.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val bridgeSyncing by viewModel.bridgeSyncing.collectAsStateWithLifecycle()
    val commandAuditLog by viewModel.commandAuditLog.collectAsStateWithLifecycle()
    val wearSummary by viewModel.commandWearSummary.collectAsStateWithLifecycle()
    val powerDoctorReport by viewModel.powerDoctorReport.collectAsStateWithLifecycle()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    fun copyText(label: String, text: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cb?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, context.getString(R.string.toast_copied_fmt, label), Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1f - (backProgress * 0.10f)
                scaleX = scale
                scaleY = scale
                translationX = backProgress * 44f
                alpha = 1f - (backProgress * 0.12f)
                if (backProgress > 0f) {
                    clip = true
                    shape = RoundedCornerShape((backProgress * 28f).dp)
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("diagnostics_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("diagnostics_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back)
                    )
                }
                Text(
                    text = stringResource(R.string.nav_diagnostics),
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        if (anomalyAlerts.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.diag_anomalies_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        anomalyAlerts.forEach { alert ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = alert.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.dismissAnomalyAlert(alert.id) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.overview_anomaly_dismiss),
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 0. Explainable Power Doctor & Anonymized Support Bundle
        item {
            PowerDoctorCard(
                report = powerDoctorReport,
                onBuildSupportBundle = { viewModel.buildAnonymizedSupportBundle() }
            )
        }

        // 1. 6-Step Connection & Auth Wizard
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("diag_wizard_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.diag_wizard_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    wizardSteps.forEach { step ->
                        val titleText = when (step.titleKey) {
                            "wizard_bt_perm" -> stringResource(R.string.wizard_bt_perm)
                            "wizard_bt_adapter" -> stringResource(R.string.wizard_bt_adapter)
                            "wizard_ble_scan" -> stringResource(R.string.wizard_ble_scan)
                            "wizard_creds_ready" -> stringResource(R.string.wizard_creds_ready)
                            "wizard_gatt_a4" -> stringResource(R.string.wizard_gatt_a4)
                            else -> stringResource(R.string.wizard_miot_session)
                        }
                        val stepColor = when {
                            step.passed -> semantic.success
                            step.active -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    step.passed -> Icons.Default.CheckCircle
                                    step.active -> Icons.Default.RadioButtonChecked
                                    else -> Icons.Default.Pending
                                },
                                contentDescription = null,
                                tint = stepColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${step.stepIndex}. $titleText",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (step.passed || step.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (step.detail.isNotBlank()) {
                                Text(
                                    text = step.detail,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily),
                                    color = stepColor
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Telemetry Integrity & BLE Proximity Radar
        item {
            IntegrityAndRadarCard(
                integrity = integrity,
                rssiHistory = rssiHistory,
                bridgeEnabled = prefs.bridgeSyncEnabled,
                bridgeSyncing = bridgeSyncing,
                onSyncBridgeNow = { viewModel.syncFromBridgeNow() }
            )
        }

        // 3. Command Audit & Relay Wear Counters
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("diag_audit_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.diag_audit_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (pid in 1..4) {
                            val cnt = wearSummary.portToggleCounts[pid] ?: 0
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${explicitPortName(pid)}: $cnt",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.diag_wear_soft_resets_fmt, wearSummary.softResetCount, wearSummary.killswitchCount),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    if (commandAuditLog.isEmpty()) {
                        Text(
                            text = stringResource(R.string.diag_audit_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        commandAuditLog.take(12).forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${timeFmt.format(Date(entry.timestampMs))} · [${entry.source}] ${entry.commandType}" +
                                            if (entry.targetPortId > 0) " P${entry.targetPortId}" else "",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums()
                                    )
                                    if (!entry.accepted && (!entry.errorCode.isNullOrBlank() || entry.summary.isNotBlank())) {
                                        Text(
                                            text = entry.errorCode?.takeIf { it.isNotBlank() }?.let { "$it · ${entry.summary}" } ?: entry.summary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                Text(
                                    text = if (entry.accepted) {
                                        "${stringResource(R.string.diag_audit_reconciled)} (${entry.latencyMs}ms)"
                                    } else {
                                        stringResource(R.string.diag_audit_rejected)
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                                    color = if (entry.accepted) semantic.success else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. BLE & Keystore Stack Diagnostics
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.diag_stack_title), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.runDiagnostics()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("run_stack_diagnostics_button")
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.diag_run_check))
                        }
                        if (diagStatus.lastDiagnosticOutput.isNotBlank()) {
                            OutlinedButton(
                                onClick = { copyText(context.getString(R.string.diag_stack_title), diagStatus.lastDiagnosticOutput) },
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy), modifier = Modifier.size(16.dp))
                            }
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.clearDiagnostics()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.action_clear), modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    if (diagStatus.lastDiagnosticOutput.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                        ) {
                            Text(
                                text = diagStatus.lastDiagnosticOutput,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily),
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // 5. Sanitized Event Console (Levels 1-3)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.diag_console_title), style = MaterialTheme.typography.titleSmall)
                        Row {
                            IconButton(
                                onClick = {
                                    val raw = logs.joinToString("\n") { "${timeFmt.format(Date(it.timestamp))} [${it.level}] ${it.message}" }
                                    copyText(context.getString(R.string.diag_console_title), LogSanitizer.sanitize(raw, maskNetworkIdentifiers = true))
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                            }
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.clearConsoleLogs()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = currentLevel == BleBridgeService.Companion.LogLevel.LEVEL1,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.setConsoleLogLevel(BleBridgeService.Companion.LogLevel.LEVEL1)
                            },
                            label = { Text(stringResource(R.string.diag_level_1)) },
                            modifier = Modifier.testTag("diag_level_1")
                        )
                        FilterChip(
                            selected = currentLevel == BleBridgeService.Companion.LogLevel.LEVEL2,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.setConsoleLogLevel(BleBridgeService.Companion.LogLevel.LEVEL2)
                            },
                            label = { Text(stringResource(R.string.diag_level_2)) },
                            modifier = Modifier.testTag("diag_level_2")
                        )
                        FilterChip(
                            selected = currentLevel == BleBridgeService.Companion.LogLevel.LEVEL3,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.setConsoleLogLevel(BleBridgeService.Companion.LogLevel.LEVEL3)
                            },
                            label = { Text(stringResource(R.string.diag_level_3)) },
                            modifier = Modifier.testTag("diag_level_3")
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (logs.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.diag_no_logs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(logs.asReversed()) { msg ->
                                    Text(
                                        text = "${timeFmt.format(Date(msg.timestamp))} · ${msg.message}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = TelemetryMonoFontFamily
                                        ).tabularNums(),
                                        color = if (msg.level == BleBridgeService.Companion.LogLevel.LEVEL1) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}
