package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.db.room.PortDevicePresetEntity
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.power.CableHealthRating
import com.example.cuktech.power.EtaEstimate
import com.example.cuktech.power.PortCableDiagnostic
import com.example.cuktech.protocol.C3ASharedBusState
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.PortTelemetry
import com.example.ui.components.AliasEditDialog
import com.example.ui.components.CableDiagnosticsSheet
import com.example.ui.components.DevicePresetSheet
import com.example.ui.components.PortChargeLimitDialog
import com.example.ui.components.PortCountdownDialog
import com.example.ui.components.SoftResetConfirmDialog
import com.example.ui.components.WavyLoadProgressBar
import com.example.ui.components.explicitPortName
import com.example.ui.components.formatDurationHms
import com.example.ui.components.portSpecLabel
import com.example.ui.theme.ExpressiveMotion
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
    val c3aSharedBus by viewModel.c3aSharedBus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chargeLimits by viewModel.chargeLimits.collectAsStateWithLifecycle()
    val countdowns by viewModel.countdownRemainingSeconds.collectAsStateWithLifecycle()
    val usbALowRemaining by viewModel.usbALowCurrentRemainingSeconds.collectAsStateWithLifecycle()
    val portLoggingMap by viewModel.portLoggingMap.collectAsStateWithLifecycle()
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    val focusedPort by viewModel.selectedPortFocus.collectAsStateWithLifecycle()
    val presets by viewModel.portDevicePresets.collectAsStateWithLifecycle()
    val etaEstimates by viewModel.etaEstimates.collectAsStateWithLifecycle()
    val cableDiagnostics by viewModel.cableDiagnostics.collectAsStateWithLifecycle()

    val isConnected = authState == CuktechAuthState.AUTHENTICATED
    val haptic = LocalHapticFeedback.current
    val semantic = LocalSemanticColors.current

    var editingLimitPortId by remember { mutableStateOf<Int?>(null) }
    var editingTimerPortId by remember { mutableStateOf<Int?>(null) }
    var editingAliasPortId by remember { mutableStateOf<Int?>(null) }
    var editingPresetPortId by remember { mutableStateOf<Int?>(null) }
    var inspectingCablePortId by remember { mutableStateOf<Int?>(null) }
    var showSoftResetDialog by remember { mutableStateOf(false) }

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

    editingPresetPortId?.let { pid ->
        DevicePresetSheet(
            portId = pid,
            currentPreset = presets[pid],
            onSave = { presetType, capacityMah, cellSeriesCount, label, targetWh, initSoc ->
                editingPresetPortId = null
                viewModel.savePortDevicePreset(
                    portId = pid,
                    presetType = presetType,
                    deviceLabel = label,
                    targetWh = targetWh,
                    initialSocPct = initSoc,
                    capacityMah = capacityMah,
                    cellSeriesCount = cellSeriesCount
                )
            },
            onDelete = {
                editingPresetPortId = null
                viewModel.deletePortDevicePreset(pid)
            },
            onDismiss = { editingPresetPortId = null }
        )
    }

    inspectingCablePortId?.let { pid ->
        val diag = cableDiagnostics[pid]
        if (diag != null) {
            CableDiagnosticsSheet(
                diag = diag,
                onResetStats = { viewModel.resetCableDiagnosticStats(pid) },
                onDismiss = { inspectingCablePortId = null }
            )
        }
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

    val activeDotPulse = rememberInfiniteTransition(label = "port_tab_active_dot")
    val dotAlpha by activeDotPulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )
    val unitW = stringResource(R.string.unit_w)
    val unitV = stringResource(R.string.unit_v)
    val unitA = stringResource(R.string.unit_a)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("ports_screen")
    ) {
        Spacer(Modifier.height(6.dp))

        val tabPortIds = if (c3aSharedBus.isSharedActive) listOf(1, 2, 3) else listOf(1, 2, 3, 4)
        val effectiveFocusedPort = if (c3aSharedBus.isSharedActive && focusedPort == 4) 3 else focusedPort.coerceIn(1, 4)

        // Material 3 Expressive Segmented Port Sub-Tabs (C1 | C2 | C3 | A, or C1 | C2 | C3+A when shared)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ports_tab_row")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabPortIds.forEach { pid ->
                    val isMergedC3A = c3aSharedBus.isSharedActive && pid == 3
                    val selected = effectiveFocusedPort == pid
                    val accent = semantic.colorForPort(pid)
                    val p = ports[pid]
                    val pActive = (p?.powerW ?: 0.0) > 0.2 || isMergedC3A
                    val shortCode = when {
                        isMergedC3A -> stringResource(R.string.port_c3a_short)
                        pid == 1 -> "C1"
                        pid == 2 -> "C2"
                        pid == 3 -> "C3"
                        else -> "A"
                    }
                    val tabWatts = if (isMergedC3A) c3aSharedBus.sharedPowerW else (p?.powerW ?: 0.0)
                    val portTitle = if (isMergedC3A) stringResource(R.string.port_c3a_shared_title) else explicitPortName(pid)
                    val activeCd = if (pActive) {
                        stringResource(R.string.port_tab_active_cd, portTitle)
                    } else {
                        portTitle
                    }
                    val tabBg by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
                        animationSpec = ExpressiveMotion.spatialSpring(),
                        label = "port_tab_bg_$pid"
                    )

                    Surface(
                        color = tabBg,
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = if (selected) 2.dp else 0.dp,
                        modifier = Modifier
                            .weight(if (isMergedC3A) 1.2f else 1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.selectPortFocus(pid)
                            }
                            .semantics { contentDescription = activeCd }
                            .testTag("port_tab_$pid")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (pActive) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(accent.copy(alpha = dotAlpha))
                                    )
                                }
                                Text(
                                    text = shortCode,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                            Text(
                                text = if (pActive) {
                                    "${String.format(Locale.getDefault(), "%.0f", tabWatts)}$unitW"
                                } else {
                                    "—"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                color = if (pActive) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = c3aSharedBus.isSharedActive && effectiveFocusedPort == 3,
            enter = expandVertically(ExpressiveMotion.spatialSpring()) + fadeIn(),
            exit = shrinkVertically(ExpressiveMotion.spatialSpring()) + fadeOut()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag("c3a_shared_banner")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "${stringResource(R.string.port_c3a_shared_badge)} · ${String.format(Locale.getDefault(), "%.1f", c3aSharedBus.sharedPowerW)} $unitW (${String.format(Locale.getDefault(), "%.2f", c3aSharedBus.voltageV)} $unitV · ${String.format(Locale.getDefault(), "%.2f", c3aSharedBus.currentA)} $unitA)",
                            style = MaterialTheme.typography.labelLarge.tabularNums(),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = stringResource(R.string.port_c3a_shared_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState = Pair(effectiveFocusedPort, c3aSharedBus.isSharedActive),
                transitionSpec = {
                    val forward = targetState.first >= initialState.first
                    val slideIn = slideInHorizontally(tween(240, easing = ExpressiveMotion.EmphasizedDecelerate)) { w ->
                        if (forward) (w * 0.12f).toInt() else -(w * 0.12f).toInt()
                    }
                    val slideOut = slideOutHorizontally(tween(170, easing = ExpressiveMotion.EmphasizedAccelerate)) { w ->
                        if (forward) -(w * 0.08f).toInt() else (w * 0.08f).toInt()
                    }
                    ((slideIn + fadeIn(tween(220, easing = ExpressiveMotion.EmphasizedDecelerate))) togetherWith
                        (slideOut + fadeOut(tween(150, easing = ExpressiveMotion.EmphasizedAccelerate)))) using
                        SizeTransform(clip = false)
                },
                label = "port_tab_detail_anim"
            ) { (portId, sharedActive) ->
                if (sharedActive && portId == 3) {
                    val port3 = ports[3] ?: PortTelemetry(3, explicitPortName(3))
                    val port4 = ports[4] ?: PortTelemetry(4, explicitPortName(4))
                    CombinedC3APortDetailCard(
                        port3 = port3,
                        port4 = port4,
                        alias3 = viewModel.aliasForPort(3, aliases),
                        alias4 = viewModel.aliasForPort(4, aliases),
                        settings = settings,
                        isConnected = isConnected,
                        c3aSharedBus = c3aSharedBus,
                        countdown3Sec = countdowns[3],
                        countdown4Sec = countdowns[4],
                        usbALowCurrentRemainingSec = usbALowRemaining,
                        chargeLimit3 = chargeLimits[3],
                        chargeLimit4 = chargeLimits[4],
                        devicePreset3 = presets[3],
                        devicePreset4 = presets[4],
                        eta3 = etaEstimates[3],
                        eta4 = etaEstimates[4],
                        cableDiag3 = cableDiagnostics[3],
                        cableDiag4 = cableDiagnostics[4],
                        isLogging3 = portLoggingMap[3] ?: true,
                        isLogging4 = portLoggingMap[4] ?: true,
                        onTogglePortPower = { pid, enabled -> viewModel.setPortEnabled(pid, enabled) },
                        onOpenTimer = { pid -> editingTimerPortId = pid },
                        onQuickTimer = { pid, mins -> viewModel.setPortCountdown(pid, mins) },
                        onToggleProtocol = { pid, proto, enabled -> viewModel.setPortProtocol(pid, proto, enabled) },
                        onToggleUsbALowCurrent = { enabled -> viewModel.setUsbALowCurrent(enabled) },
                        onOpenChargeLimit = { pid -> editingLimitPortId = pid },
                        onOpenDevicePreset = { pid -> editingPresetPortId = pid },
                        onOpenCableDiag = { pid -> inspectingCablePortId = pid },
                        onToggleLogging = { pid, enabled -> viewModel.setPortLoggingEnabled(pid, enabled) },
                        onEditAlias = { pid -> editingAliasPortId = pid }
                    )
                } else {
                    val port = ports[portId] ?: PortTelemetry(portId, explicitPortName(portId))
                    PortDetailCard(
                        portId = portId,
                        port = port,
                        alias = viewModel.aliasForPort(portId, aliases),
                        settings = settings,
                        isConnected = isConnected,
                        isFocused = true,
                        isSharedC3ABus = false,
                        c3aSharedBus = c3aSharedBus,
                        countdownSec = countdowns[portId],
                        usbALowCurrentRemainingSec = if (portId == 4) usbALowRemaining else null,
                        chargeLimit = chargeLimits[portId],
                        devicePreset = presets[portId],
                        etaEstimate = etaEstimates[portId],
                        cableDiag = cableDiagnostics[portId],
                        isLoggingEnabled = portLoggingMap[portId] ?: true,
                        onTogglePortPower = { enabled -> viewModel.setPortEnabled(portId, enabled) },
                        onOpenTimer = { editingTimerPortId = portId },
                        onQuickTimer = { mins -> viewModel.setPortCountdown(portId, mins) },
                        onToggleProtocol = { proto, enabled -> viewModel.setPortProtocol(portId, proto, enabled) },
                        onToggleUsbALowCurrent = { enabled -> viewModel.setUsbALowCurrent(enabled) },
                        onOpenChargeLimit = { editingLimitPortId = portId },
                        onOpenDevicePreset = { editingPresetPortId = portId },
                        onOpenCableDiag = { inspectingCablePortId = portId },
                        onToggleLogging = { enabled -> viewModel.setPortLoggingEnabled(portId, enabled) },
                        onEditAlias = { editingAliasPortId = portId }
                    )
                }
            }

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
                Text(
                    text = stringResource(R.string.settings_soft_reset),
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
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
    isSharedC3ABus: Boolean,
    c3aSharedBus: C3ASharedBusState,
    countdownSec: Long?,
    usbALowCurrentRemainingSec: Long?,
    chargeLimit: PortChargeLimit?,
    devicePreset: PortDevicePresetEntity?,
    etaEstimate: EtaEstimate?,
    cableDiag: PortCableDiagnostic?,
    isLoggingEnabled: Boolean,
    onTogglePortPower: (Boolean) -> Unit,
    onOpenTimer: () -> Unit,
    onQuickTimer: (Int) -> Unit,
    onToggleProtocol: (String, Boolean) -> Unit,
    onToggleUsbALowCurrent: (Boolean) -> Unit,
    onOpenChargeLimit: () -> Unit,
    onOpenDevicePreset: () -> Unit,
    onOpenCableDiag: () -> Unit,
    onToggleLogging: (Boolean) -> Unit,
    onEditAlias: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val semantic = LocalSemanticColors.current
    val accent = semantic.colorForPort(portId)
    val portName = explicitPortName(portId)
    val specLabel = portSpecLabel(portId)
    val isActive = port.powerW > 0.2

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
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
                    if (isSharedC3ABus) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.port_c3a_shared_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Switch(
                    checked = port.enabled,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTogglePortPower(it)
                    },
                    enabled = isConnected,
                    modifier = Modifier.testTag("port_power_switch_$portId")
                )
            }

            // Live Telemetry Readouts
            val unitW = stringResource(R.string.unit_w)
            val unitV = stringResource(R.string.unit_v)
            val unitA = stringResource(R.string.unit_a)
            val unitWh = stringResource(R.string.unit_wh)
            val displayPowerW = if (isSharedC3ABus) c3aSharedBus.sharedPowerW else port.powerW
            val displayVoltageV = if (isSharedC3ABus) c3aSharedBus.voltageV else port.voltageV
            val displayCurrentA = if (isSharedC3ABus) c3aSharedBus.currentA else port.currentA
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
                        value = "${String.format(Locale.US, "%.1f", displayPowerW)} $unitW",
                        label = if (isSharedC3ABus) {
                            stringResource(R.string.port_c3a_short)
                        } else if (port.protocolName == "Idle") {
                            stringResource(R.string.port_status_idle)
                        } else {
                            port.protocolName
                        },
                        highlightColor = if (isActive || isSharedC3ABus) accent else MaterialTheme.colorScheme.onSurface
                    )
                    MetricItem(
                        value = "${String.format(Locale.US, "%.2f", displayVoltageV)} $unitV",
                        label = stringResource(R.string.port_metric_voltage)
                    )
                    MetricItem(
                        value = "${String.format(Locale.US, "%.2f", displayCurrentA)} $unitA",
                        label = stringResource(R.string.port_metric_current)
                    )
                    MetricItem(
                        value = "${String.format(Locale.US, "%.2f", port.cumulativeWh)} $unitWh",
                        label = stringResource(R.string.port_metric_energy)
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

            // Cable Health & Voltage-Drop Stress Diagnostic Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.cable_diag_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    val healthText = when (cableDiag?.healthRating) {
                        CableHealthRating.EXCELLENT -> stringResource(R.string.cable_health_excellent)
                        CableHealthRating.MODERATE_SAG -> stringResource(R.string.cable_health_moderate)
                        CableHealthRating.UNSTABLE -> stringResource(R.string.cable_health_unstable)
                        else -> stringResource(R.string.cable_health_idle)
                    }
                    val detailText = when {
                        cableDiag == null || !cableDiag.isActive -> healthText
                        cableDiag.isVariablePps -> "$healthText · ${stringResource(R.string.cable_pps_variable)}"
                        cableDiag.nominalRailV != null -> "$healthText · ${stringResource(R.string.cable_sag_mv_fmt, cableDiag.voltageSagMv)}"
                        else -> healthText
                    }
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                        color = if (cableDiag?.healthRating == CableHealthRating.UNSTABLE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                OutlinedButton(
                    onClick = onOpenCableDiag,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("port_cable_diag_button_$portId")
                ) {
                    Icon(Icons.Default.Cable, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_edit))
                }
            }

            // Charging ETA Estimate & Device Battery Preset Row
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.port_eta_title),
                            style = MaterialTheme.typography.labelLarge
                        )
                        val etaText = if (etaEstimate == null || devicePreset == null) {
                            stringResource(R.string.port_eta_no_preset)
                        } else {
                            val mins = etaEstimate.estimatedMinutesRemaining
                            when {
                                etaEstimate.isNearlyFull -> stringResource(R.string.port_eta_nearly_full)
                                mins == null -> stringResource(R.string.port_eta_idle)
                                mins >= 60 -> stringResource(R.string.port_eta_remaining_hm_fmt, mins / 60, mins % 60, etaEstimate.deviceLabel)
                                else -> stringResource(R.string.port_eta_remaining_m_fmt, mins, etaEstimate.deviceLabel)
                            }
                        }
                        Text(
                            text = etaText,
                            style = MaterialTheme.typography.bodySmall.tabularNums(),
                            color = if (etaEstimate != null) accent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = onOpenDevicePreset,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("port_preset_button_$portId")
                    ) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.port_eta_assign_btn))
                    }
                }
                if (etaEstimate != null) {
                    WavyLoadProgressBar(
                        progress = etaEstimate.progressFraction,
                        color = accent,
                        active = isActive
                    )
                    Text(
                        text = stringResource(R.string.port_eta_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

            // 1-Tap Quick Hardware Off-Timer Presets
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.port_quick_timers_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val hasActiveTimer = (countdownSec ?: 0L) > 0L || port.countdownCode > 0
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(15, 30, 60, 120).forEach { mins ->
                        val isPresetSelected = hasActiveTimer && port.countdownCode == mins
                        FilterChip(
                            selected = isPresetSelected,
                            onClick = { onQuickTimer(mins) },
                            enabled = isConnected,
                            label = {
                                Text(
                                    text = stringResource(R.string.port_quick_timer_m_fmt, mins),
                                    style = MaterialTheme.typography.labelMedium.tabularNums()
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("port_quick_timer_${portId}_$mins")
                        )
                    }
                    if (hasActiveTimer) {
                        FilterChip(
                            selected = false,
                            onClick = { onQuickTimer(0) },
                            enabled = isConnected,
                            label = { Text(stringResource(R.string.port_quick_timer_off)) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("port_quick_timer_${portId}_off")
                        )
                    }
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
                            val localizedMode = if (chargeLimit.mode.equals("always", ignoreCase = true)) {
                                stringResource(R.string.action_always)
                            } else {
                                stringResource(R.string.action_once)
                            }
                            stringResource(
                                R.string.port_wh_limit_progress_fmt,
                                used,
                                chargeLimit.whTarget,
                                localizedMode
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
                    WavyLoadProgressBar(
                        progress = chargeLimit.progressFraction(port.cumulativeWh),
                        color = accent,
                        active = isActive
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
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CombinedC3APortDetailCard(
    port3: PortTelemetry,
    port4: PortTelemetry,
    alias3: String?,
    alias4: String?,
    settings: ChargerSettingsState,
    isConnected: Boolean,
    c3aSharedBus: C3ASharedBusState,
    countdown3Sec: Long?,
    countdown4Sec: Long?,
    usbALowCurrentRemainingSec: Long?,
    chargeLimit3: PortChargeLimit?,
    chargeLimit4: PortChargeLimit?,
    devicePreset3: PortDevicePresetEntity?,
    devicePreset4: PortDevicePresetEntity?,
    eta3: EtaEstimate?,
    eta4: EtaEstimate?,
    cableDiag3: PortCableDiagnostic?,
    cableDiag4: PortCableDiagnostic?,
    isLogging3: Boolean,
    isLogging4: Boolean,
    onTogglePortPower: (Int, Boolean) -> Unit,
    onOpenTimer: (Int) -> Unit,
    onQuickTimer: (Int, Int) -> Unit,
    onToggleProtocol: (Int, String, Boolean) -> Unit,
    onToggleUsbALowCurrent: (Boolean) -> Unit,
    onOpenChargeLimit: (Int) -> Unit,
    onOpenDevicePreset: (Int) -> Unit,
    onOpenCableDiag: (Int) -> Unit,
    onToggleLogging: (Int, Boolean) -> Unit,
    onEditAlias: (Int) -> Unit
) {
    val semantic = LocalSemanticColors.current
    val accent = semantic.portC3
    val unitW = stringResource(R.string.unit_w)
    val unitV = stringResource(R.string.unit_v)
    val unitA = stringResource(R.string.unit_a)
    val unitWh = stringResource(R.string.unit_wh)
    val combinedWh = maxOf(port3.cumulativeWh, port4.cumulativeWh)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("port_card_3")
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
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.port_c3a_shared_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = accent
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.port_c3a_shared_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Unified Shared 5V Rail Telemetry
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
                        value = "${String.format(Locale.US, "%.1f", c3aSharedBus.sharedPowerW)} $unitW",
                        label = stringResource(R.string.port_c3a_short),
                        highlightColor = accent
                    )
                    MetricItem(
                        value = "${String.format(Locale.US, "%.2f", c3aSharedBus.voltageV)} $unitV",
                        label = stringResource(R.string.port_metric_voltage)
                    )
                    MetricItem(
                        value = "${String.format(Locale.US, "%.2f", c3aSharedBus.currentA)} $unitA",
                        label = stringResource(R.string.port_metric_current)
                    )
                    MetricItem(
                        value = "${String.format(Locale.US, "%.2f", combinedWh)} $unitWh",
                        label = stringResource(R.string.port_metric_energy)
                    )
                }
            }

            Text(
                text = stringResource(R.string.port_c3a_individual_controls_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            listOf(3 to port3, 4 to port4).forEach { (pid, port) ->
                val alias = if (pid == 3) alias3 else alias4
                val countdownSec = if (pid == 3) countdown3Sec else countdown4Sec
                val chargeLimit = if (pid == 3) chargeLimit3 else chargeLimit4
                val devicePreset = if (pid == 3) devicePreset3 else devicePreset4
                val etaEstimate = if (pid == 3) eta3 else eta4
                val cableDiag = if (pid == 3) cableDiag3 else cableDiag4
                val isLogging = if (pid == 3) isLogging3 else isLogging4
                val portAccent = semantic.colorForPort(pid)

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("port_card_sub_$pid")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = explicitPortName(pid),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = portAccent,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (!alias.isNullOrBlank()) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = alias,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onEditAlias(pid) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("edit_port_alias_$pid")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.port_edit_alias),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", port.powerW)} $unitW · ${String.format(Locale.US, "%.2f", port.voltageV)} $unitV · ${String.format(Locale.US, "%.2f", port.currentA)} $unitA",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = port.enabled,
                                onCheckedChange = { onTogglePortPower(pid, it) },
                                enabled = isConnected,
                                modifier = Modifier.testTag("port_power_switch_$pid")
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (pid == 3) {
                                ProtocolChip(pid, "UFCS", settings.c3Ufcs, isConnected) { onToggleProtocol(pid, "ufcs", it) }
                                ProtocolChip(pid, "SCP", settings.c3Scp, isConnected) { onToggleProtocol(pid, "scp", it) }
                            } else {
                                ProtocolChip(pid, "UFCS", settings.aUfcs, isConnected) { onToggleProtocol(pid, "ufcs", it) }
                                ProtocolChip(pid, "SCP", settings.aScp, isConnected) { onToggleProtocol(pid, "scp", it) }
                            }
                        }

                        if (pid == 4) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
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
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onOpenCableDiag(pid) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("port_cable_diag_button_$pid")
                            ) {
                                Icon(Icons.Default.Cable, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (cableDiag?.isActive == true) {
                                        stringResource(R.string.cable_sag_mv_fmt, cableDiag.voltageSagMv)
                                    } else {
                                        stringResource(R.string.cable_diag_label)
                                    },
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                            }
                            FilledTonalButton(
                                onClick = { onOpenDevicePreset(pid) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("port_preset_button_$pid")
                            ) {
                                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = devicePreset?.deviceLabel ?: stringResource(R.string.port_eta_assign_btn),
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                            }
                            FilledTonalButton(
                                onClick = { onOpenTimer(pid) },
                                enabled = isConnected,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("port_timer_button_$pid")
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (countdownSec != null && countdownSec > 0L) {
                                        formatDurationHms(countdownSec)
                                    } else {
                                        stringResource(R.string.port_set_timer)
                                    },
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                            }
                            OutlinedButton(
                                onClick = { onOpenChargeLimit(pid) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("port_wh_limit_button_$pid")
                            ) {
                                Text(
                                    text = if (chargeLimit != null && chargeLimit.enabled) {
                                        stringResource(R.string.port_wh_limit_progress_fmt, chargeLimit.currentUsedWh(port.cumulativeWh), chargeLimit.whTarget, "")
                                    } else {
                                        stringResource(R.string.port_wh_limit)
                                    },
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // 1-Tap Quick Hardware Off-Timer Presets for sub-port
                        val hasActiveSubTimer = (countdownSec ?: 0L) > 0L || port.countdownCode > 0
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(15, 30, 60, 120).forEach { mins ->
                                FilterChip(
                                    selected = hasActiveSubTimer && port.countdownCode == mins,
                                    onClick = { onQuickTimer(pid, mins) },
                                    enabled = isConnected,
                                    label = {
                                        Text(
                                            text = stringResource(R.string.port_quick_timer_m_fmt, mins),
                                            style = MaterialTheme.typography.labelMedium.tabularNums()
                                        )
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("port_quick_timer_${pid}_$mins")
                                )
                            }
                            if (hasActiveSubTimer) {
                                FilterChip(
                                    selected = false,
                                    onClick = { onQuickTimer(pid, 0) },
                                    enabled = isConnected,
                                    label = { Text(stringResource(R.string.port_quick_timer_off)) },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("port_quick_timer_${pid}_off")
                                )
                            }
                        }

                        if (etaEstimate != null) {
                            WavyLoadProgressBar(
                                progress = etaEstimate.progressFraction,
                                color = portAccent,
                                active = port.powerW > 0.2
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.port_history_logging),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            Switch(
                                checked = isLogging,
                                onCheckedChange = { onToggleLogging(pid, it) },
                                modifier = Modifier.testTag("port_logging_switch_$pid")
                            )
                        }
                    }
                }
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
