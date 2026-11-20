package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BleBridgeService
import com.example.R
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.DashboardViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current
    val currentLevel by viewModel.currentLogLevel.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val diagStatus by viewModel.diagStatus.collectAsStateWithLifecycle()
    val anomalyAlerts by viewModel.anomalyAlerts.collectAsStateWithLifecycle()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    fun copyText(label: String, text: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cb?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied: $label", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("diagnostics_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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

        if (anomalyAlerts.isNotEmpty()) {
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

        // 1. BLE & Keystore Stack Diagnostics
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
                        onClick = { viewModel.runDiagnostics() },
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
                            onClick = { copyText("BLE Diagnostics", diagStatus.lastDiagnosticOutput) },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy), modifier = Modifier.size(16.dp))
                        }
                        OutlinedButton(
                            onClick = { viewModel.clearDiagnostics() },
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

        // 2. Sanitized Event Console (Levels 1-3)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
                                copyText("Console Logs", raw)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                        }
                        IconButton(
                            onClick = { viewModel.clearConsoleLogs() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = currentLevel == BleBridgeService.Companion.LogLevel.LEVEL1,
                        onClick = { viewModel.setConsoleLogLevel(BleBridgeService.Companion.LogLevel.LEVEL1) },
                        label = { Text(stringResource(R.string.diag_level_1)) },
                        modifier = Modifier.testTag("diag_level_1")
                    )
                    FilterChip(
                        selected = currentLevel == BleBridgeService.Companion.LogLevel.LEVEL2,
                        onClick = { viewModel.setConsoleLogLevel(BleBridgeService.Companion.LogLevel.LEVEL2) },
                        label = { Text(stringResource(R.string.diag_level_2)) },
                        modifier = Modifier.testTag("diag_level_2")
                    )
                    FilterChip(
                        selected = currentLevel == BleBridgeService.Companion.LogLevel.LEVEL3,
                        onClick = { viewModel.setConsoleLogLevel(BleBridgeService.Companion.LogLevel.LEVEL3) },
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
}
