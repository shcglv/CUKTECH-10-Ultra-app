package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.protocol.PortTelemetry
import com.example.ui.components.AliasEditDialog
import com.example.ui.components.ConnectionStatusCard
import com.example.ui.components.MasterKillswitchConfirmDialog
import com.example.ui.components.SaveChargingProfileDialog
import com.example.ui.components.ScenarioSelectionDialog
import com.example.ui.components.explicitPortName
import com.example.ui.components.formatDurationHms
import com.example.ui.components.localizedScenarioName
import com.example.ui.cuktech.CuktechChargerVisual
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.DashboardViewModel
import java.util.Locale

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
    val totalWatts by viewModel.totalPowerWatts.collectAsStateWithLifecycle()
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

    var showKillswitchConfirm by remember { mutableStateOf(false) }
    var showScenarioDialog by remember { mutableStateOf(false) }
    var editingChargerAlias by remember { mutableStateOf(false) }
    var showSaveProfileDialog by remember { mutableStateOf(false) }

    val semantic = LocalSemanticColors.current
    val isConnected = authState == CuktechAuthState.AUTHENTICATED
    val chargerAlias = remember(aliases, devInfo.macAddress, activeCred) {
        viewModel.aliasForPort(0, aliases)
    }

    val regionWarning = if (accountSession != null && !accountSession!!.region.equals("cn", ignoreCase = true)) {
        stringResource(R.string.warning_region_non_cn, accountSession!!.region.uppercase(Locale.ROOT))
    } else null

    if (showKillswitchConfirm) {
        MasterKillswitchConfirmDialog(
            onConfirm = {
                showKillswitchConfirm = false
                viewModel.executeMasterKillswitch()
            },
            onDismiss = { showKillswitchConfirm = false }
        )
    }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("overview_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ConnectionStatusCard(
            authState = authState,
            isDisconnectCooldown = isCooldown,
            deviceName = chargerAlias ?: devInfo.name,
            macAddress = devInfo.macAddress,
            rssi = devInfo.rssi,
            qualityScore = devInfo.connectionQualityScore,
            activeCred = activeCred,
            regionWarning = regionWarning,
            onConnectClick = { onRequestConnect(activeCred?.mac) },
            onCancelClick = onRequestCancelConnect,
            onDisconnectClick = onRequestDisconnect,
            onOpenSearchPair = onOpenSearchPair,
            onOpenXiaomiLogin = onOpenXiaomiLogin
        )

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
                    Text(
                        text = alert.summary,
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

        // Hero Wattage & Killswitch Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(24.dp),
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
                            text = stringResource(R.string.overview_hero_total_power),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.overview_rated_ceiling),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
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
                                onClick = { viewModel.refreshTelemetry() },
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

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f", totalWatts),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = TelemetryMonoFontFamily,
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Bold
                        ).tabularNums(),
                        color = if (totalWatts > 0.2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("hero_total_watts")
                    )
                    Text(
                        text = "W",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                val totalWhToday = remember(ports, activeSessions) {
                    val liveWh = ports.values.sumOf { it.cumulativeWh }
                    if (liveWh > 0.0) liveWh else activeSessions.values.sumOf { it.whAccumulated }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { if (isConnected) showScenarioDialog = true },
                        label = { Text(localizedScenarioName(settings.sceneMode)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.ElectricBolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.2f Wh", totalWhToday),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = TelemetryMonoFontFamily
                            ).tabularNums(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    if (prefs.tariffRatePerKwh > 0.0) {
                        val estCost = (totalWhToday / 1000.0) * prefs.tariffRatePerKwh
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.overview_est_cost_fmt, prefs.currencyCode, estCost),
                                style = MaterialTheme.typography.labelMedium.tabularNums(),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showKillswitchConfirm = true },
                        enabled = isConnected,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("master_killswitch_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.overview_killswitch),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val validRestore = killswitchSnap != null && killswitchSnap?.invalidated == false
                    if (validRestore) {
                        FilledTonalButton(
                            onClick = { viewModel.restoreKillswitch() },
                            enabled = isConnected,
                            modifier = Modifier
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
        }

        // Interactive Hardware Faceplate
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp)) {
                CuktechChargerVisual(
                    portsMap = ports,
                    modeLabel = localizedScenarioName(settings.sceneMode),
                    onModeClick = { if (isConnected) showScenarioDialog = true },
                    onPortClick = { portId -> onNavigateToPort(portId) }
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
                            FilterChip(
                                selected = matchesCurrent,
                                onClick = { viewModel.applyChargingProfile(profile) },
                                enabled = isConnected,
                                label = { Text(profile.name) },
                                trailingIcon = if (!profile.isBuiltIn) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Delete profile",
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
                    accentColor = semantic.colorForPort(1),
                    onClick = { onNavigateToPort(1) },
                    modifier = Modifier.weight(1f)
                )
                OverviewPortMiniCard(
                    portId = 2,
                    port = ports[2] ?: PortTelemetry(2, "USB-C2"),
                    alias = viewModel.aliasForPort(2, aliases),
                    countdownSec = countdowns[2],
                    accentColor = semantic.colorForPort(2),
                    onClick = { onNavigateToPort(2) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OverviewPortMiniCard(
                    portId = 3,
                    port = ports[3] ?: PortTelemetry(3, "USB-C3"),
                    alias = viewModel.aliasForPort(3, aliases),
                    countdownSec = countdowns[3],
                    accentColor = semantic.colorForPort(3),
                    onClick = { onNavigateToPort(3) },
                    modifier = Modifier.weight(1f)
                )
                OverviewPortMiniCard(
                    portId = 4,
                    port = ports[4] ?: PortTelemetry(4, "USB-A"),
                    alias = viewModel.aliasForPort(4, aliases),
                    countdownSec = countdowns[4],
                    accentColor = semantic.colorForPort(4),
                    onClick = { onNavigateToPort(4) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Compact Live Total Power Sparkline
        if (chartHistory.isNotEmpty()) {
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

                    val lineColor = MaterialTheme.colorScheme.primary
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    ) {
                        val pts = chartHistory.takeLast(60)
                        if (pts.size >= 2) {
                            val maxW = (pts.maxOfOrNull { it.totalWatts } ?: 10.0).coerceAtLeast(10.0)
                            val path = Path()
                            val fillPath = Path()
                            pts.forEachIndexed { i, p ->
                                val x = (i.toFloat() / (pts.size - 1)) * size.width
                                val y = size.height - ((p.totalWatts / maxW).toFloat().coerceIn(0f, 1f) * (size.height - 8.dp.toPx())) - 4.dp.toPx()
                                if (i == 0) {
                                    path.moveTo(x, y)
                                    fillPath.moveTo(x, size.height)
                                    fillPath.lineTo(x, y)
                                } else {
                                    path.lineTo(x, y)
                                    fillPath.lineTo(x, y)
                                }
                            }
                            fillPath.lineTo(size.width, size.height)
                            fillPath.close()
                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0f))
                                )
                            )
                            drawPath(
                                path = path,
                                color = lineColor,
                                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        } else if (pts.size == 1) {
                            val y = size.height / 2f
                            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.5.dp.toPx())
                        }
                    }
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
                            text = "${formatDurationHms(latestSession.durationSeconds)} · Peak ${String.format(Locale.US, "%.1f", latestSession.peakWatts)} W",
                            style = MaterialTheme.typography.bodySmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "${String.format(Locale.US, "%.2f", latestSession.whAccumulated)} Wh",
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
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = port.powerW > 0.2
    val portLabel = explicitPortName(portId)

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
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = portLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (active) accentColor else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${port.maxPowerW}W",
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

            Text(
                text = String.format(Locale.US, "%.1f W", port.powerW),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = TelemetryMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ).tabularNums(),
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = when {
                    !port.enabled -> stringResource(R.string.port_status_disabled)
                    active -> "${String.format(Locale.US, "%.1fV · %.2fA", port.voltageV, port.currentA)} · ${port.protocolName}"
                    countdownSec != null -> stringResource(R.string.port_delay_timer_fmt, formatDurationHms(countdownSec))
                    else -> stringResource(R.string.port_status_idle)
                },
                style = MaterialTheme.typography.bodySmall.tabularNums(),
                color = if (!port.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
