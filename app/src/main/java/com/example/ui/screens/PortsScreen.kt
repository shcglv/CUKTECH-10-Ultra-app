package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import com.example.ui.components.AliasEditDialog
import com.example.ui.components.PortChargeLimitDialog
import com.example.ui.components.PortCountdownDialog
import com.example.ui.components.SoftResetConfirmDialog
import com.example.ui.components.explicitPortName
import com.example.ui.components.formatDurationHms
import com.example.ui.components.portSpecLabel
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.DashboardViewModel
import java.util.Locale

@Composable
fun PortsScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val ports by viewModel.ports.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chargeLimits by viewModel.chargeLimits.collectAsStateWithLifecycle()
    val countdowns by viewModel.countdownRemainingSeconds.collectAsStateWithLifecycle()
    val usbALowRemaining by viewModel.usbALowCurrentRemainingSeconds.collectAsStateWithLifecycle()
    val portLoggingMap by viewModel.portLoggingMap.collectAsStateWithLifecycle()
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    val focusedPort by viewModel.selectedPortFocus.collectAsStateWithLifecycle()

    val isConnected = authState == CuktechAuthState.AUTHENTICATED
    val listState = rememberLazyListState()

    var editingLimitPortId by remember { mutableStateOf<Int?>(null) }
    var editingTimerPortId by remember { mutableStateOf<Int?>(null) }
    var editingAliasPortId by remember { mutableStateOf<Int?>(null) }
    var showSoftResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(focusedPort) {
        val index = (focusedPort - 1).coerceIn(0, 3)
        listState.animateScrollToItem(index)
    }

    editingLimitPortId?.let { pid ->
        PortChargeLimitDialog(
            portId = pid,
            currentLimit = chargeLimits[pid],
            onSave = { whTarget, mode, enabled ->
                editingLimitPortId = null
                viewModel.setPortChargeLimit(pid, whTarget, mode, enabled)
            },
            onDismiss = { editingLimitPortId = null }
        )
    }

    editingTimerPortId?.let { pid ->
        val curMins = ports[pid]?.countdownCode ?: 0
        PortCountdownDialog(
            portId = pid,
            initialMinutes = curMins,
            onSave = { mins ->
                editingTimerPortId = null
                viewModel.setPortCountdown(pid, mins)
            },
            onDismiss = { editingTimerPortId = null }
        )
    }

    editingAliasPortId?.let { pid ->
        AliasEditDialog(
            portId = pid,
            currentAlias = viewModel.aliasForPort(pid, aliases).orEmpty(),
            onSave = { name ->
                editingAliasPortId = null
                viewModel.savePortOrDeviceAlias(pid, name)
            },
            onDismiss = { editingAliasPortId = null }
        )
    }

    if (showSoftResetDialog) {
        SoftResetConfirmDialog(
            onConfirm = {
                showSoftResetDialog = false
                viewModel.executeSoftReset()
            },
            onDismiss = { showSoftResetDialog = false }
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("ports_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(6.dp)) }

        items(listOf(1, 2, 3, 4), key = { it }) { portId ->
            val port = ports[portId] ?: PortTelemetry(portId, explicitPortName(portId))
            PortDetailCard(
                portId = portId,
                port = port,
                alias = viewModel.aliasForPort(portId, aliases),
                settings = settings,
                isConnected = isConnected,
                isFocused = focusedPort == portId,
                countdownSec = countdowns[portId],
                usbALowCurrentRemainingSec = if (portId == 4) usbALowRemaining else null,
                chargeLimit = chargeLimits[portId],
                isLoggingEnabled = portLoggingMap[portId] ?: true,
                onTogglePortPower = { enabled -> viewModel.setPortEnabled(portId, enabled) },
                onOpenTimer = { editingTimerPortId = portId },
                onToggleProtocol = { proto, enabled -> viewModel.setPortProtocol(portId, proto, enabled) },
                onToggleUsbALowCurrent = { enabled -> viewModel.setUsbALowCurrent(enabled) },
                onOpenChargeLimit = { editingLimitPortId = portId },
                onToggleLogging = { enabled -> viewModel.setPortLoggingEnabled(portId, enabled) },
                onEditAlias = { editingAliasPortId = portId }
            )
        }

        item {
            OutlinedButton(
                onClick = { showSoftResetDialog = true },
                enabled = isConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("soft_reset_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_soft_reset))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortDetailCard(
    portId: Int,
    port: PortTelemetry,
    alias: String?,
    settings: ChargerSettingsState,
    isConnected: Boolean,
    isFocused: Boolean,
    countdownSec: Long?,
    usbALowCurrentRemainingSec: Long?,
    chargeLimit: PortChargeLimit?,
    isLoggingEnabled: Boolean,
    onTogglePortPower: (Boolean) -> Unit,
    onOpenTimer: () -> Unit,
    onToggleProtocol: (String, Boolean) -> Unit,
    onToggleUsbALowCurrent: (Boolean) -> Unit,
    onOpenChargeLimit: () -> Unit,
    onToggleLogging: (Boolean) -> Unit,
    onEditAlias: () -> Unit
) {
    val semantic = LocalSemanticColors.current
    val accent = semantic.colorForPort(portId)
    val portName = explicitPortName(portId)
    val specLabel = portSpecLabel(portId)
    val isActive = port.powerW > 0.2

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = when {
            isFocused -> BorderStroke(1.5.dp, accent)
            isActive -> BorderStroke(1.dp, accent.copy(alpha = 0.4f))
            else -> null
        },
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("port_card_$portId")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row: Physical port name + Spec + Alias + Power Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = portName,
                            style = MaterialTheme.typography.titleLarge,
                            color = accent
                        )
                        if (!alias.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = alias,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onEditAlias,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("edit_port_alias_$portId")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.port_edit_alias),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = specLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = port.enabled,
                    onCheckedChange = onTogglePortPower,
                    enabled = isConnected,
                    modifier = Modifier.testTag("port_power_switch_$portId")
                )
            }

            // Live Telemetry Readouts
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricItem(
                        value = String.format(Locale.US, "%.1f W", port.powerW),
                        label = port.protocolName,
                        highlightColor = if (isActive) accent else MaterialTheme.colorScheme.onSurface
                    )
                    MetricItem(
                        value = String.format(Locale.US, "%.2f V", port.voltageV),
                        label = "Voltage"
                    )
                    MetricItem(
                        value = String.format(Locale.US, "%.2f A", port.currentA),
                        label = "Current"
                    )
                    MetricItem(
                        value = String.format(Locale.US, "%.2f Wh", port.cumulativeWh),
                        label = "Energy"
                    )
                }
            }

            // PDO caps on C1/C2
            if (portId in 1..2 && port.pdoCaps != null) {
                Text(
                    text = stringResource(R.string.port_pdo_caps_label, port.pdoCaps.formatSummary()),
                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Per-Port Real Supported Protocol Switches
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.port_fast_charge_protocols),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    when (portId) {
                        1 -> {
                            ProtocolChip(portId, "PD", settings.c1Pd, isConnected) { onToggleProtocol("pd", it) }
                            ProtocolChip(portId, "PPS", settings.c1Pps, isConnected) { onToggleProtocol("pps", it) }
                            ProtocolChip(portId, "UFCS", settings.c1Ufcs, isConnected) { onToggleProtocol("ufcs", it) }
                        }
                        2 -> {
                            ProtocolChip(portId, "PD", settings.c2Pd, isConnected) { onToggleProtocol("pd", it) }
                            ProtocolChip(portId, "PPS", settings.c2Pps, isConnected) { onToggleProtocol("pps", it) }
                            ProtocolChip(portId, "UFCS", settings.c2Ufcs, isConnected) { onToggleProtocol("ufcs", it) }
                        }
                        3 -> {
                            ProtocolChip(portId, "UFCS", settings.c3Ufcs, isConnected) { onToggleProtocol("ufcs", it) }
                            ProtocolChip(portId, "SCP", settings.c3Scp, isConnected) { onToggleProtocol("scp", it) }
                        }
                        4 -> {
                            ProtocolChip(portId, "UFCS", settings.aUfcs, isConnected) { onToggleProtocol("ufcs", it) }
                            ProtocolChip(portId, "SCP", settings.aScp, isConnected) { onToggleProtocol("scp", it) }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // USB-A Low-Current Mode (4h) strictly for port 4
            if (portId == 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.port_usba_low_current),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = usbALowCurrentRemainingSec?.let { formatDurationHms(it) }
                                ?: stringResource(R.string.port_usba_low_current_desc),
                            style = MaterialTheme.typography.bodySmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.usbALowCurrent,
                        onCheckedChange = onToggleUsbALowCurrent,
                        enabled = isConnected,
                        modifier = Modifier.testTag("usba_low_current_switch")
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }

            // Delay Poweroff Timer Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.port_delay_off_timer),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = if (countdownSec != null && countdownSec > 0L) {
                            stringResource(R.string.port_delay_timer_fmt, formatDurationHms(countdownSec))
                        } else {
                            stringResource(R.string.port_delay_timer_always)
                        },
                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                        color = if (countdownSec != null && countdownSec > 0L) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onOpenTimer,
                    enabled = isConnected,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("port_timer_button_$portId")
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.port_set_timer))
                }
            }

            // Wh Energy Cutoff Limit Row
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.port_wh_limit),
                            style = MaterialTheme.typography.labelLarge
                        )
                        val limitDesc = if (chargeLimit != null && chargeLimit.enabled) {
                            val used = chargeLimit.currentUsedWh(port.cumulativeWh)
                            stringResource(
                                R.string.port_wh_limit_progress_fmt,
                                used,
                                chargeLimit.whTarget,
                                chargeLimit.mode
                            )
                        } else {
                            stringResource(R.string.port_wh_limit_none)
                        }
                        Text(
                            text = limitDesc,
                            style = MaterialTheme.typography.bodySmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenChargeLimit,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("port_wh_limit_button_$portId")
                    ) {
                        Text(stringResource(R.string.action_edit))
                    }
                }
                if (chargeLimit != null && chargeLimit.enabled) {
                    LinearProgressIndicator(
                        progress = { chargeLimit.progressFraction(port.cumulativeWh) },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent
                    )
                }
            }

            // History & Wh Logging Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.port_history_logging),
                    style = MaterialTheme.typography.labelLarge
                )
                Switch(
                    checked = isLoggingEnabled,
                    onCheckedChange = onToggleLogging,
                    modifier = Modifier.testTag("port_logging_switch_$portId")
                )
            }
        }
    }
}

@Composable
private fun ProtocolChip(
    portId: Int,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        enabled = enabled,
        label = { Text(label) },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("port_${portId}_proto_${label.lowercase()}")
    )
}

@Composable
private fun MetricItem(
    value: String,
    label: String,
    highlightColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = TelemetryMonoFontFamily,
                fontWeight = FontWeight.Bold
            ).tabularNums(),
            color = highlightColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
