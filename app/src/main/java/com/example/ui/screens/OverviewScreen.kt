package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.WorkspaceAndCustomKpisCard
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.power.CableHealthRating
import com.example.cuktech.power.EtaEstimate
import com.example.cuktech.power.PortCableDiagnostic
import com.example.cuktech.protocol.C3ASharedBusState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.telemetry.AwayDeltaSummary
import com.example.cuktech.telemetry.AwayPortDeltaType
import com.example.ui.components.AliasEditDialog
import com.example.ui.components.ExpressiveSparkline
import com.example.ui.components.MultiChargerAndIntegrityStrip
import com.example.ui.components.MultiChargerHubSheet
import com.example.ui.components.PowerBudgetCard
import com.example.ui.components.SaveChargingProfileDialog
import com.example.ui.components.ScenarioSelectionDialog
import com.example.ui.components.TelemetryIntegrityAndRadarSheet
import com.example.ui.components.explicitPortName
import com.example.ui.components.formatDurationHms
import com.example.ui.components.localizedScenarioName
import com.example.ui.components.scenarioIcon
import com.example.ui.cuktech.CuktechChargerVisual
import com.example.ui.i18n.resolveLocale
import com.example.ui.theme.ExpressiveMotion
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.DashboardViewModel
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    viewModel: DashboardViewModel,
    onRequestConnect: (mac: String?) -> Unit,
    onRequestCancelConnect: () -> Unit,
    onRequestDisconnect: () -> Unit,
    onOpenSearchPair: () -> Unit,
    onOpenXiaomiLogin: () -> Unit,
    onNavigateToPort: (Int) -> Unit,
    onNavigateToAnalytics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val isCooldown by viewModel.isDisconnectCooldown.collectAsStateWithLifecycle()
    val devInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val ports by viewModel.ports.collectAsStateWithLifecycle()
    val isSmartCharging by viewModel.isSmartCharging.collectAsStateWithLifecycle()
    val totalWatts by viewModel.totalPowerWatts.collectAsStateWithLifecycle()
    val c3aSharedBus by viewModel.c3aSharedBus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeSessions by viewModel.activeSessions.collectAsStateWithLifecycle()
    val completedSessions by viewModel.completedSessions.collectAsStateWithLifecycle()
    val activeCred by viewModel.activeCred.collectAsStateWithLifecycle()
    val accountSession by viewModel.accountSession.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    val chartHistory by viewModel.powerChartHistory.collectAsStateWithLifecycle()
    val killswitchSnap by viewModel.lastKillswitchSnapshot.collectAsStateWithLifecycle()
    val countdowns by viewModel.countdownRemainingSeconds.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val anomalyAlerts by viewModel.anomalyAlerts.collectAsStateWithLifecycle()
    val powerBudget by viewModel.powerBudget.collectAsStateWithLifecycle()
    val etaEstimates by viewModel.etaEstimates.collectAsStateWithLifecycle()
    val cableDiagnostics by viewModel.cableDiagnostics.collectAsStateWithLifecycle()
    val automationRules by viewModel.automationRules.collectAsStateWithLifecycle()
    val isAutoReconnectConfigured by viewModel.isAutoReconnectConfigured.collectAsStateWithLifecycle()
    val integritySnapshot by viewModel.integritySnapshot.collectAsStateWithLifecycle()
    val rssiHistory by viewModel.rssiHistory.collectAsStateWithLifecycle()
    val sessionGaps by viewModel.sessionGaps.collectAsStateWithLifecycle()
    val chargerNodes by viewModel.chargerNodes.collectAsStateWithLifecycle()
    val savedDevices by viewModel.savedDevices.collectAsStateWithLifecycle()
    val bridgeSyncing by viewModel.bridgeSyncing.collectAsStateWithLifecycle()
    val evaluatedKpis by viewModel.evaluatedKpis.collectAsStateWithLifecycle()
    val fleetSummary by viewModel.fleetSummary.collectAsStateWithLifecycle()
    val awaySummary by viewModel.awaySummary.collectAsStateWithLifecycle()

    var showScenarioDialog by remember { mutableStateOf(false) }
    var editingChargerAlias by remember { mutableStateOf(false) }
    var showSaveProfileDialog by remember { mutableStateOf(false) }
    var showMultiChargerSheet by remember { mutableStateOf(false) }
    var showIntegritySheet by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    val semantic = LocalSemanticColors.current
    val appLocale = resolveLocale(prefs.appLanguage)
    val unitW = stringResource(R.string.unit_w)
    val unitWh = stringResource(R.string.unit_wh)
    val isConnected = authState == CuktechAuthState.AUTHENTICATED
    val chargerAlias = remember(aliases, devInfo.macAddress, activeCred) {
        viewModel.aliasForPort(0, aliases)
    }

    val regionWarning = if (accountSession != null && !accountSession!!.region.equals("cn", ignoreCase = true)) {
        stringResource(R.string.warning_region_non_cn, accountSession!!.region.uppercase(Locale.ROOT))
    } else null

    if (showScenarioDialog) {
        ScenarioSelectionDialog(
            currentMode = settings.sceneMode,
            onSelect = {
                showScenarioDialog = false
                viewModel.setSceneMode(it)
            },
            onDismiss = { showScenarioDialog = false }
        )
    }

    if (editingChargerAlias) {
        AliasEditDialog(
            portId = 0,
            currentAlias = chargerAlias.orEmpty(),
            onSave = {
                editingChargerAlias = false
                viewModel.savePortOrDeviceAlias(0, it)
            },
            onDismiss = { editingChargerAlias = false }
        )
    }

    if (showSaveProfileDialog) {
        SaveChargingProfileDialog(
            onSave = { profileName ->
                showSaveProfileDialog = false
                viewModel.saveCurrentAsProfile(profileName)
            },
            onDismiss = { showSaveProfileDialog = false }
        )
    }

    val activeMacResolved = remember(devInfo.macAddress, activeCred) {
        devInfo.macAddress.takeIf { it.isNotBlank() && it != "--" } ?: activeCred?.mac.orEmpty()
    }
    val activeNodeLabel = remember(chargerAlias, chargerNodes, activeMacResolved, devInfo.name) {
        val node = chargerNodes.firstOrNull { it.deviceMac.equals(activeMacResolved, ignoreCase = true) }
        chargerAlias?.takeIf { it.isNotBlank() }
            ?: node?.locationContext?.takeIf { it.isNotBlank() && !it.equals("Default", ignoreCase = true) }
            ?: node?.name?.takeIf { it.isNotBlank() }
            ?: devInfo.name.ifBlank { "CUKTECH 10 Ultra" }
    }

    if (showMultiChargerSheet) {
        MultiChargerHubSheet(
            savedDevices = savedDevices,
            chargerNodes = chargerNodes,
            activeMac = activeMacResolved,
            onSwitchCharger = { mac ->
                showMultiChargerSheet = false
                viewModel.switchActiveCharger(mac)
            },
            onSaveNodeMeta = { mac, name, location, mains ->
                viewModel.updateChargerNodeMeta(mac, name, location, mains)
            },
            onDismiss = { showMultiChargerSheet = false },
            fleetSummary = fleetSummary
        )
    }

    if (showIntegritySheet) {
        TelemetryIntegrityAndRadarSheet(
            integrity = integritySnapshot,
            rssiHistory = rssiHistory,
            gaps = sessionGaps,
            bridgeEnabled = prefs.bridgeSyncEnabled,
            bridgeSyncing = bridgeSyncing,
            onSyncBridgeNow = { viewModel.syncFromBridgeNow() },
            onDismiss = { showIntegritySheet = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("overview_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!regionWarning.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("overview_region_warning_banner")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = regionWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (activeCred == null || !activeCred!!.hasValidToken) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("overview_missing_token_banner")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.warning_no_token_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(R.string.warning_no_token_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOpenXiaomiLogin()
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("xiaomi_login_banner_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_xiaomi_login),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOpenSearchPair()
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_scan_pair),
                                maxLines = 2,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        anomalyAlerts.forEach { alert ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("anomaly_banner_${alert.id}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    val ctx = LocalContext.current
                    Text(
                        text = alert.localizedSummary(ctx),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.dismissAnomalyAlert(alert.id) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("dismiss_anomaly_${alert.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.overview_anomaly_dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = awaySummary != null,
            enter = expandVertically(ExpressiveMotion.spatialSpring()) + fadeIn(),
            exit = shrinkVertically(ExpressiveMotion.spatialSpring()) + fadeOut()
        ) {
            awaySummary?.let { summary ->
                AwaySummaryCard(
                    summary = summary,
                    onDismiss = { viewModel.dismissAwaySummary() }
                )
            }
        }

        MultiChargerAndIntegrityStrip(
            activeNodeLabel = activeNodeLabel,
            nodesCount = maxOf(chargerNodes.size, savedDevices.size),
            integrity = integritySnapshot,
            onOpenChargerHub = { showMultiChargerSheet = true },
            onOpenIntegritySheet = { showIntegritySheet = true }
        )

        WorkspaceAndCustomKpisCard(
            activeWorkspaceId = prefs.activeWorkspaceId,
            kpis = evaluatedKpis,
            currencyCode = prefs.currencyCode,
            onSelectWorkspace = { viewModel.selectWorkspace(it) },
            onAddKpi = { title, formula, unit, target ->
                viewModel.addCustomKpi(title, formula, unit, target)
            },
            onDeleteKpi = { viewModel.deleteCustomKpi(it) }
        )

        // Unified Charger Visual + Live Output Hero Card
        val heroPulseTransition = rememberInfiniteTransition(label = "hero_power_pulse")
        val heroPulsePhase by heroPulseTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1150, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "heroPulsePhase"
        )
        val hasActiveLoad = isSmartCharging || totalWatts >= 0.85

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("overview_hero_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chargerAlias?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.overview_hero_total_power),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isConnected && devInfo.macAddress.isNotBlank() && devInfo.macAddress != "--") {
                                stringResource(
                                    R.string.overview_mac_rssi_fmt,
                                    devInfo.macAddress,
                                    devInfo.rssi,
                                    devInfo.connectionQualityScore
                                )
                            } else {
                                stringResource(R.string.overview_rated_ceiling)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = if (isConnected) TelemetryMonoFontFamily else MaterialTheme.typography.bodySmall.fontFamily
                            ).tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { editingChargerAlias = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("edit_charger_alias_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.port_edit_alias)
                            )
                        }
                        if (isConnected) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.refreshTelemetry()
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("refresh_telemetry_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.action_refresh_telemetry)
                                )
                            }
                        }
                    }
                }

                // Hardware Faceplate Visual directly on hero card surface (no white inner rectangle)
                CuktechChargerVisual(
                    portsMap = ports,
                    onPortClick = { portId -> onNavigateToPort(portId) },
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                // Live Power Readout with smooth numerical interpolation and clean baseline alignment
                val animatedWatts by animateFloatAsState(
                    targetValue = totalWatts.toFloat(),
                    animationSpec = tween(260, easing = ExpressiveMotion.StandardEasing),
                    label = "hero_watts_anim"
                )
                val wattsFormatted = String.format(appLocale, "%.1f", animatedWatts.toDouble())
                val activeCount = remember(ports, c3aSharedBus) {
                    var cnt = 0
                    if ((ports[1]?.powerW ?: 0.0) > 0.2) cnt++
                    if ((ports[2]?.powerW ?: 0.0) > 0.2) cnt++
                    if (c3aSharedBus.isSharedActive) {
                        cnt++
                    } else {
                        if ((ports[3]?.powerW ?: 0.0) > 0.2) cnt++
                        if ((ports[4]?.powerW ?: 0.0) > 0.2) cnt++
                    }
                    cnt
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        if (hasActiveLoad) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .scale(0.88f + heroPulsePhase * 0.22f)
                                        .clip(CircleShape)
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f * heroPulsePhase)
                                        )
                                )
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.ElectricBolt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Row {
                            Text(
                                text = wattsFormatted,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = TelemetryMonoFontFamily,
                                    fontSize = 44.sp,
                                    lineHeight = 48.sp,
                                    fontWeight = FontWeight.Bold
                                ).tabularNums(),
                                color = if (hasActiveLoad) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .alignByBaseline()
                                    .testTag("hero_total_watts")
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = unitW,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.alignByBaseline()
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    // Top-right slot: clickable Scenario Mode selector pill with dedicated scenario icon
                    Surface(
                        onClick = {
                            if (isConnected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showScenarioDialog = true
                            }
                        },
                        enabled = isConnected,
                        color = if (isConnected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("overview_scenario_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = scenarioIcon(settings.sceneMode),
                                contentDescription = null,
                                tint = if (isConnected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = localizedScenarioName(settings.sceneMode),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = if (isConnected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val totalWhToday = remember(ports, activeSessions) {
                    val liveWh = ports.values.sumOf { it.cumulativeWh }
                    if (liveWh > 0.0) liveWh else activeSessions.values.sumOf { it.whAccumulated }
                }

                // Bottom row: Utilization badge (left) flush & level with Delivered Energy badge (right)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val pct = ((totalWatts / 120.0) * 100.0).toInt().coerceIn(0, 100)
                    Surface(
                        color = if (hasActiveLoad) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (activeCount > 0) {
                                "${stringResource(R.string.overview_active_sessions_fmt, activeCount)} · $pct%"
                            } else {
                                stringResource(R.string.hero_rated_pct_fmt, pct)
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = TelemetryMonoFontFamily,
                                fontWeight = FontWeight.SemiBold
                            ).tabularNums(),
                            color = if (hasActiveLoad) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.overview_session_energy_fmt, totalWhToday),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = TelemetryMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                ).tabularNums(),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }

                        if (prefs.tariffRatePerKwh > 0.0) {
                            val estCost = (totalWhToday / 1000.0) * prefs.tariffRatePerKwh
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.overview_est_cost_fmt, prefs.currencyCode, estCost),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ).tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }

                val validRestore = killswitchSnap != null && killswitchSnap?.invalidated == false
                AnimatedVisibility(
                    visible = validRestore,
                    enter = expandVertically(ExpressiveMotion.spatialSpring()) + fadeIn(),
                    exit = shrinkVertically(ExpressiveMotion.spatialSpring()) + fadeOut()
                ) {
                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.restoreKillswitch()
                        },
                        enabled = isConnected,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("restore_killswitch_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.overview_restore_killswitch))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !isConnected && isAutoReconnectConfigured,
            enter = expandVertically(ExpressiveMotion.spatialSpring()) + fadeIn(),
            exit = shrinkVertically(ExpressiveMotion.spatialSpring()) + fadeOut()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("overview_ble_standby_hint")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.overview_ble_standby_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Live 120W GaN Power Balancer & Per-Port Budget
        PowerBudgetCard(
            budget = powerBudget,
            isConnected = isConnected,
            onPortClick = onNavigateToPort,
            onScenarioClick = { showScenarioDialog = true }
        )

        val enabledRulesCount = remember(automationRules) { automationRules.count { it.enabled } }
        if (enabledRulesCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("overview_automation_badge")
            ) {
                Text(
                    text = stringResource(R.string.overview_automation_active_fmt, enabledRulesCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // Charging Profiles Quick Strip (Built-in + User Custom)
        if (profiles.isNotEmpty() || isConnected) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("overview_profiles_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.overview_profiles_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        AssistChip(
                            onClick = { showSaveProfileDialog = true },
                            enabled = isConnected,
                            label = { Text(stringResource(R.string.overview_profile_save)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("save_profile_chip")
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        profiles.forEach { profile ->
                            val matchesCurrent = isConnected &&
                                settings.sceneMode == profile.sceneMode &&
                                (settings.portSwitchBitmap and 0x0F) == (profile.portSwitchBitmap and 0x0F) &&
                                settings.usbALowCurrent == profile.usbALowCurrent
                            val displayName = when {
                                !profile.isBuiltIn -> profile.name
                                profile.name.startsWith("Workstation") || profile.name.startsWith("Desk Workstation") ->
                                    stringResource(R.string.profile_builtin_workstation)
                                profile.name.startsWith("Single Port") ->
                                    stringResource(R.string.profile_builtin_single)
                                profile.name.startsWith("Night") ->
                                    stringResource(R.string.profile_builtin_night)
                                else -> profile.name
                            }
                            FilterChip(
                                selected = matchesCurrent,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.applyChargingProfile(profile)
                                },
                                enabled = isConnected,
                                label = { Text(displayName) },
                                trailingIcon = if (!profile.isBuiltIn) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.action_delete_profile),
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { viewModel.deleteCustomProfile(profile.id) }
                                        )
                                    }
                                } else null,
                                modifier = Modifier.testTag("profile_chip_${profile.id}")
                            )
                        }
                    }
                }
            }
        }

        // Live 4-Port Summary Strip (explicit USB-C1, USB-C2, USB-C3, USB-A names)
        Text(
            text = stringResource(R.string.overview_live_ports),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                    OverviewPortMiniCard(
                    portId = 1,
                    port = ports[1] ?: PortTelemetry(1, "USB-C1"),
                    alias = viewModel.aliasForPort(1, aliases),
                    countdownSec = countdowns[1],
                    etaEstimate = etaEstimates[1],
                    cableDiag = cableDiagnostics[1],
                    activePeakWatts = activeSessions[1]?.peakWatts,
                    accentColor = semantic.colorForPort(1),
                    onClick = { onNavigateToPort(1) },
                    modifier = Modifier.weight(1f)
                )
                OverviewPortMiniCard(
                    portId = 2,
                    port = ports[2] ?: PortTelemetry(2, "USB-C2"),
                    alias = viewModel.aliasForPort(2, aliases),
                    countdownSec = countdowns[2],
                    etaEstimate = etaEstimates[2],
                    cableDiag = cableDiagnostics[2],
                    activePeakWatts = activeSessions[2]?.peakWatts,
                    accentColor = semantic.colorForPort(2),
                    onClick = { onNavigateToPort(2) },
                    modifier = Modifier.weight(1f)
                )
            }
            if (c3aSharedBus.isSharedActive) {
                OverviewSharedC3ACard(
                    c3aBus = c3aSharedBus,
                    port3 = ports[3] ?: PortTelemetry(3, "USB-C3"),
                    port4 = ports[4] ?: PortTelemetry(4, "USB-A"),
                    alias3 = viewModel.aliasForPort(3, aliases),
                    alias4 = viewModel.aliasForPort(4, aliases),
                    countdownSec = countdowns[3] ?: countdowns[4],
                    accentColor = semantic.colorForPort(3),
                    onClick = { onNavigateToPort(3) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OverviewPortMiniCard(
                        portId = 3,
                        port = ports[3] ?: PortTelemetry(3, "USB-C3"),
                        alias = viewModel.aliasForPort(3, aliases),
                        countdownSec = countdowns[3],
                        etaEstimate = etaEstimates[3],
                        cableDiag = cableDiagnostics[3],
                        activePeakWatts = activeSessions[3]?.peakWatts,
                        accentColor = semantic.colorForPort(3),
                        onClick = { onNavigateToPort(3) },
                        modifier = Modifier.weight(1f)
                    )
                    OverviewPortMiniCard(
                        portId = 4,
                        port = ports[4] ?: PortTelemetry(4, "USB-A"),
                        alias = viewModel.aliasForPort(4, aliases),
                        countdownSec = countdowns[4],
                        etaEstimate = etaEstimates[4],
                        cableDiag = cableDiagnostics[4],
                        activePeakWatts = activeSessions[4]?.peakWatts,
                        accentColor = semantic.colorForPort(4),
                        onClick = { onNavigateToPort(4) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Compact Live Total Power Sparkline
        if (chartHistory.isNotEmpty()) {
            val sparkPts = remember(chartHistory) { chartHistory.takeLast(60) }
            val sparkMinW = remember(sparkPts) { sparkPts.minOfOrNull { it.totalWatts } ?: 0.0 }
            val sparkMaxW = remember(sparkPts) { sparkPts.maxOfOrNull { it.totalWatts } ?: 0.0 }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToAnalytics)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.overview_live_trend),
                            style = MaterialTheme.typography.titleSmall
                        )
                        TextButton(
                            onClick = onNavigateToAnalytics,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.overview_view_analytics))
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .testTag("overview_sparkline_minmax")
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "${stringResource(R.string.overview_port_min_fmt, sparkMinW)} $unitW",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "${stringResource(R.string.overview_port_peak_fmt, sparkMaxW)} $unitW",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    ExpressiveSparkline(
                        points = chartHistory,
                        lineColor = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Latest Charging Session Teaser
        val latestSession = activeSessions.values.firstOrNull() ?: completedSessions.firstOrNull()
        if (latestSession != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToAnalytics)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.overview_recent_session),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${explicitPortName(latestSession.portId)} · ${latestSession.protocolUsed}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "${formatDurationHms(latestSession.durationSeconds)} · ${stringResource(R.string.session_peak_fmt, latestSession.peakWatts, unitW)}",
                            style = MaterialTheme.typography.bodySmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "${String.format(appLocale, "%.2f", latestSession.whAccumulated)} $unitWh",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = TelemetryMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ).tabularNums(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else if (!isConnected) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.overview_no_device_connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun OverviewPortMiniCard(
    portId: Int,
    port: PortTelemetry,
    alias: String?,
    countdownSec: Long?,
    etaEstimate: EtaEstimate?,
    cableDiag: PortCableDiagnostic?,
    activePeakWatts: Double? = null,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = port.powerW > 0.2
    val portLabel = explicitPortName(portId)
    val unitW = stringResource(R.string.unit_w)
    val unitV = stringResource(R.string.unit_v)
    val unitA = stringResource(R.string.unit_a)
    val unitWh = stringResource(R.string.unit_wh)

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = if (active) BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)) else null,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.testTag("overview_port_card_$portId")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (active) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                    }
                    Text(
                        text = portLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (active) accentColor else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = if (active) accentColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    val badgeText = when {
                        !port.enabled -> stringResource(R.string.port_status_disabled)
                        active && port.protocolName != "Idle" -> port.protocolName
                        else -> "${port.maxPowerW} $unitW"
                    }
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = when {
                            !port.enabled -> MaterialTheme.colorScheme.error
                            active -> accentColor
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (!alias.isNullOrBlank()) {
                Text(
                    text = alias,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "${String.format(Locale.getDefault(), "%.1f", port.powerW)} $unitW",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = TelemetryMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ).tabularNums(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    if (active && activePeakWatts != null && activePeakWatts > port.powerW + 0.5) {
                        Text(
                            text = "${stringResource(R.string.overview_port_peak_fmt, activePeakWatts)} $unitW",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = TelemetryMonoFontFamily
                            ).tabularNums(),
                            color = accentColor,
                            maxLines = 1,
                            modifier = Modifier.testTag("overview_port_peak_$portId")
                        )
                    }
                    Text(
                        text = "${String.format(Locale.getDefault(), "%.2f", port.cumulativeWh)} $unitWh",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = TelemetryMonoFontFamily
                        ).tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = when {
                    !port.enabled -> stringResource(R.string.port_status_disabled)
                    active -> "${String.format(Locale.getDefault(), "%.2f", port.voltageV)} $unitV · ${String.format(Locale.getDefault(), "%.2f", port.currentA)} $unitA"
                    countdownSec != null -> stringResource(R.string.port_delay_timer_fmt, formatDurationHms(countdownSec))
                    else -> "${String.format(Locale.getDefault(), "%.1f", port.voltageV)} $unitV · ${stringResource(R.string.port_status_idle)}"
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = TelemetryMonoFontFamily
                ).tabularNums(),
                color = if (!port.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (active && etaEstimate != null) {
                val mins = etaEstimate.estimatedMinutesRemaining
                val etaStr = when {
                    etaEstimate.isNearlyFull -> stringResource(R.string.port_eta_nearly_full)
                    mins == null -> null
                    mins >= 60 -> stringResource(R.string.port_eta_remaining_hm_fmt, mins / 60, mins % 60, etaEstimate.deviceLabel)
                    else -> stringResource(R.string.port_eta_remaining_m_fmt, mins, etaEstimate.deviceLabel)
                }
                if (etaStr != null) {
                    Text(
                        text = etaStr,
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (cableDiag != null && (cableDiag.healthRating == CableHealthRating.MODERATE_SAG || cableDiag.healthRating == CableHealthRating.UNSTABLE)) {
                Text(
                    text = stringResource(R.string.cable_sag_mv_fmt, cableDiag.voltageSagMv),
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewSharedC3ACard(
    c3aBus: C3ASharedBusState,
    port3: PortTelemetry,
    port4: PortTelemetry,
    alias3: String?,
    alias4: String?,
    countdownSec: Long?,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val unitW = stringResource(R.string.unit_w)
    val unitV = stringResource(R.string.unit_v)
    val unitA = stringResource(R.string.unit_a)
    val unitWh = stringResource(R.string.unit_wh)
    val combinedWh = maxOf(port3.cumulativeWh, port4.cumulativeWh)
    val aliasLine = listOfNotNull(alias3?.takeIf { it.isNotBlank() }, alias4?.takeIf { it.isNotBlank() })
        .joinToString(" + ")

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.testTag("overview_port_card_c3a")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Text(
                        text = stringResource(R.string.port_c3a_shared_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.port_c3a_shared_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (aliasLine.isNotBlank()) {
                Text(
                    text = aliasLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${String.format(Locale.getDefault(), "%.1f", c3aBus.sharedPowerW)} $unitW",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = TelemetryMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ).tabularNums(),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${String.format(Locale.getDefault(), "%.2f", c3aBus.voltageV)} $unitV · ${String.format(Locale.getDefault(), "%.2f", c3aBus.currentA)} $unitA · ${String.format(Locale.getDefault(), "%.2f", combinedWh)} $unitWh",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = TelemetryMonoFontFamily
                    ).tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (countdownSec != null) {
                Text(
                    text = stringResource(R.string.port_delay_timer_fmt, formatDurationHms(countdownSec)),
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = accentColor
                )
            }
        }
    }
}

@Composable
private fun AwaySummaryCard(
    summary: AwayDeltaSummary,
    onDismiss: () -> Unit
) {
    val totalMins = (summary.awayDurationSec / 60L).coerceAtLeast(1L).toInt()
    val durationText = if (totalMins >= 60) {
        stringResource(R.string.away_summary_duration_hm_fmt, totalMins / 60, totalMins % 60)
    } else {
        stringResource(R.string.away_summary_duration_min_fmt, totalMins)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("overview_away_summary_card")
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.away_summary_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("away_summary_dismiss_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            if (summary.deltas.isEmpty()) {
                Text(
                    text = stringResource(R.string.away_summary_no_changes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            } else {
                summary.deltas.forEach { d ->
                    val line = when (d.deltaType) {
                        AwayPortDeltaType.STOPPED ->
                            stringResource(R.string.away_summary_stopped_fmt, d.portLabel, d.beforeWatts)
                        AwayPortDeltaType.STARTED ->
                            stringResource(R.string.away_summary_started_fmt, d.portLabel, d.afterWatts)
                        AwayPortDeltaType.STILL_ACTIVE ->
                            stringResource(R.string.away_summary_active_fmt, d.portLabel, d.beforeWatts, d.afterWatts)
                    }
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            if (summary.bridgeSamplesSynced > 0) {
                Text(
                    text = stringResource(R.string.away_summary_bridge_synced_fmt, summary.bridgeSamplesSynced),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
