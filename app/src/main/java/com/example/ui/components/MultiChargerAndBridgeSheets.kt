package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.db.room.ChargerNodeEntity
import com.example.cuktech.db.room.SessionGapEntity
import com.example.cuktech.power.FleetSummarySnapshot
import com.example.cuktech.security.CuktechDeviceCredentials
import com.example.cuktech.telemetry.ConfidenceLevel
import com.example.cuktech.telemetry.DisconnectRiskLevel
import com.example.cuktech.telemetry.RssiPoint
import com.example.cuktech.telemetry.TelemetryIntegritySnapshot
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun confidenceReasonText(reasonKey: String): String = when (reasonKey) {
    "live_direct" -> stringResource(R.string.integrity_reason_live_direct)
    "bridge_backfilled" -> stringResource(R.string.integrity_reason_bridge_backfilled)
    "telemetry_stale" -> stringResource(R.string.integrity_reason_telemetry_stale)
    "weak_signal" -> stringResource(R.string.integrity_reason_weak_signal)
    "blind_gap_open" -> stringResource(R.string.integrity_reason_blind_gap_open)
    else -> stringResource(R.string.integrity_reason_ble_disconnected)
}

@Composable
fun MultiChargerAndIntegrityStrip(
    activeNodeLabel: String,
    nodesCount: Int,
    integrity: TelemetryIntegritySnapshot,
    onOpenChargerHub: () -> Unit,
    onOpenIntegritySheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val trustBg = when (integrity.confidenceLevel) {
        ConfidenceLevel.HIGH -> semantic.successContainer
        ConfidenceLevel.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer
        ConfidenceLevel.LOW -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val trustFg = when (integrity.confidenceLevel) {
        ConfidenceLevel.HIGH -> semantic.success
        ConfidenceLevel.MEDIUM -> MaterialTheme.colorScheme.onTertiaryContainer
        ConfidenceLevel.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenChargerHub)
                .testTag("overview_multi_charger_chip")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Router,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.overview_multi_charger_strip_fmt, activeNodeLabel, nodesCount.coerceAtLeast(1)),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Surface(
            color = trustBg,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenIntegritySheet)
                .testTag("overview_integrity_chip")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (integrity.confidenceLevel == ConfidenceLevel.HIGH) {
                        Icons.Default.VerifiedUser
                    } else {
                        Icons.Default.SignalCellularAlt
                    },
                    contentDescription = null,
                    tint = trustFg,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.overview_confidence_score_fmt, integrity.confidenceScore),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = TelemetryMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ).tabularNums(),
                    color = trustFg,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiChargerHubSheet(
    savedDevices: List<CuktechDeviceCredentials>,
    chargerNodes: List<ChargerNodeEntity>,
    activeMac: String,
    onSwitchCharger: (mac: String) -> Unit,
    onSaveNodeMeta: (mac: String, name: String, location: String, mainsVoltage: Int) -> Unit,
    onDismiss: () -> Unit,
    fleetSummary: FleetSummarySnapshot? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val nodeMap = remember(chargerNodes) { chargerNodes.associateBy { it.deviceMac.uppercase() } }
    val allMacs = remember(savedDevices, chargerNodes, activeMac) {
        (savedDevices.map { it.mac.uppercase() } +
            chargerNodes.map { it.deviceMac.uppercase() } +
            listOf(activeMac.uppercase()).filter { it.isNotBlank() && it != "--" })
            .distinct()
    }
    var editingMac by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("multi_charger_hub_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.multi_charger_hub_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.multi_charger_hub_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (fleetSummary != null && fleetSummary.nodesCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "%d/%d · %.1f W · %.1f Wh today · %.1f Wh total",
                            fleetSummary.onlineCount,
                            fleetSummary.nodesCount,
                            fleetSummary.totalLiveWatts,
                            fleetSummary.fleetWhToday,
                            fleetSummary.fleetTotalWh
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            if (allMacs.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.multi_charger_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(allMacs, key = { it }) { mac ->
                        val node = nodeMap[mac]
                        val cred = savedDevices.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
                        val isActive = activeMac.equals(mac, ignoreCase = true)
                        val title = node?.name ?: cred?.name ?: "CUKTECH 10 Ultra"
                        val location = node?.locationContext ?: "Default"
                        val mains = node?.mainsVoltageMode ?: 220

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            ),
                            border = if (isActive) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("charger_node_card_$mac")
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "$mac · $location · ${mains}V",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = TelemetryMonoFontFamily
                                            ).tabularNums(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isActive) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.multi_charger_active_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    } else {
                                        FilledTonalButton(
                                            onClick = { onSwitchCharger(mac) },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("switch_charger_btn_$mac")
                                        ) {
                                            Text(stringResource(R.string.multi_charger_connect_btn))
                                        }
                                    }
                                }

                                if (editingMac == mac) {
                                    var draftName by remember(mac) { mutableStateOf(title) }
                                    var draftLoc by remember(mac) { mutableStateOf(location) }
                                    var draftMains by remember(mac) { mutableIntStateOf(mains) }

                                    OutlinedTextField(
                                        value = draftName,
                                        onValueChange = { draftName = it },
                                        label = { Text(stringResource(R.string.action_edit)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = draftLoc,
                                        onValueChange = { draftLoc = it },
                                        label = { Text(stringResource(R.string.multi_charger_location_label)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = draftMains == 220,
                                            onClick = { draftMains = 220 },
                                            label = { Text(stringResource(R.string.multi_charger_mains_220)) }
                                        )
                                        FilterChip(
                                            selected = draftMains == 110,
                                            onClick = { draftMains = 110 },
                                            label = { Text(stringResource(R.string.multi_charger_mains_110)) }
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = { editingMac = null },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text(stringResource(R.string.action_cancel))
                                        }
                                        Button(
                                            onClick = {
                                                onSaveNodeMeta(mac, draftName, draftLoc, draftMains)
                                                editingMac = null
                                            },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text(stringResource(R.string.action_save))
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (node != null && node.lastSeenMs > 0L) {
                                                String.format(Locale.US, "%.1f W · %.2f Wh · %d dBm", node.lastTotalWatts, node.lastTotalWhToday, node.lastRssiDbm)
                                            } else {
                                                "--"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = TelemetryMonoFontFamily
                                            ).tabularNums(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        TextButton(
                                            onClick = { editingMac = mac },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text(stringResource(R.string.action_edit))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

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
fun TelemetryIntegrityAndRadarSheet(
    integrity: TelemetryIntegritySnapshot,
    rssiHistory: List<RssiPoint>,
    gaps: List<SessionGapEntity>,
    bridgeEnabled: Boolean,
    bridgeSyncing: Boolean,
    onSyncBridgeNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tsFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("integrity_radar_sheet")
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.integrity_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                IntegrityAndRadarCard(
                    integrity = integrity,
                    rssiHistory = rssiHistory,
                    bridgeEnabled = bridgeEnabled,
                    bridgeSyncing = bridgeSyncing,
                    onSyncBridgeNow = onSyncBridgeNow
                )
            }

            item {
                Text(
                    text = stringResource(R.string.integrity_gaps_log_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (gaps.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.integrity_blind_gaps_fmt, 0, 0.0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(gaps.take(12), key = { it.id }) { gap ->
                    val durMin = ((gap.gapEndMs - gap.gapStartMs).coerceAtLeast(0L)) / 60_000.0
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${tsFmt.format(Date(gap.gapStartMs))} – ${tsFmt.format(Date(gap.gapEndMs))} (${String.format(Locale.US, "%.1f", durMin)} min)",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = TelemetryMonoFontFamily
                                    ).tabularNums()
                                )
                                Text(
                                    text = if (gap.wasBackfilledByBridge) {
                                        stringResource(R.string.integrity_gap_backfilled_fmt, gap.backfilledSamplesCount)
                                    } else {
                                        stringResource(R.string.integrity_gap_open_label)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (gap.wasBackfilledByBridge) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.cd_close))
                    }
                }
            }
        }
    }
}

@Composable
fun IntegrityAndRadarCard(
    integrity: TelemetryIntegritySnapshot,
    rssiHistory: List<RssiPoint>,
    bridgeEnabled: Boolean,
    bridgeSyncing: Boolean,
    onSyncBridgeNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val riskText = when (integrity.disconnectRisk) {
        DisconnectRiskLevel.HIGH -> stringResource(R.string.integrity_rssi_risk_high)
        DisconnectRiskLevel.MEDIUM -> stringResource(R.string.integrity_rssi_risk_medium)
        DisconnectRiskLevel.LOW -> stringResource(R.string.integrity_rssi_risk_low)
    }
    val riskColor = when (integrity.disconnectRisk) {
        DisconnectRiskLevel.HIGH -> MaterialTheme.colorScheme.error
        DisconnectRiskLevel.MEDIUM -> MaterialTheme.colorScheme.tertiary
        DisconnectRiskLevel.LOW -> semantic.success
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("integrity_radar_card")
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
                Text(
                    text = stringResource(R.string.integrity_score_title_fmt, integrity.confidenceScore),
                    style = MaterialTheme.typography.titleSmall.tabularNums(),
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = riskColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (integrity.currentRssiDbm != 0) "${integrity.currentRssiDbm} dBm · $riskText" else riskText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = TelemetryMonoFontFamily
                        ).tabularNums(),
                        color = riskColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { (integrity.confidenceScore / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
            )

            Text(
                text = confidenceReasonText(integrity.confidenceReasonKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.integrity_cadence_fmt, integrity.avgSampleIntervalMs, integrity.jitterMs),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums()
            )
            Text(
                text = stringResource(R.string.integrity_uptime_fmt, integrity.uptimePct24h, integrity.reconnectCount24h),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums()
            )
            Text(
                text = stringResource(R.string.integrity_sources_fmt, integrity.directSamplesCount, integrity.bridgeSamplesCount),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                color = MaterialTheme.colorScheme.primary
            )

            if (integrity.openBlindGapsCount > 0) {
                Text(
                    text = stringResource(R.string.integrity_blind_gaps_fmt, integrity.openBlindGapsCount, integrity.totalBlindMinutes24h),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (rssiHistory.size >= 2) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(
                    text = stringResource(R.string.integrity_rssi_radar_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val lineColor = riskColor
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    val pts = rssiHistory.takeLast(60)
                    val offsets = pts.mapIndexed { idx, p ->
                        val x = (idx.toFloat() / (pts.size - 1)) * size.width
                        val norm = ((p.rssiDbm + 100).coerceIn(0, 65) / 65f)
                        val y = size.height - 4.dp.toPx() - norm * (size.height - 8.dp.toPx())
                        Offset(x, y)
                    }
                    val path = Path().apply {
                        offsets.forEachIndexed { idx, off ->
                            if (idx == 0) moveTo(off.x, off.y) else lineTo(off.x, off.y)
                        }
                    }
                    val fill = Path().apply {
                        addPath(path)
                        lineTo(offsets.last().x, size.height)
                        lineTo(offsets.first().x, size.height)
                        close()
                    }
                    drawPath(
                        fill,
                        Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.22f), lineColor.copy(alpha = 0f)))
                    )
                    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                }
            }

            if (bridgeEnabled) {
                FilledTonalButton(
                    onClick = onSyncBridgeNow,
                    enabled = !bridgeSyncing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("integrity_bridge_sync_btn")
                ) {
                    if (bridgeSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.integrity_backfill_btn))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeCompanionSetupSheet(
    scriptText: String,
    onCopyScript: () -> Unit,
    onSaveScriptFile: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("bridge_companion_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.bridge_companion_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.bridge_companion_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = stringResource(R.string.bridge_companion_install_hint),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = TelemetryMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 260.dp)
            ) {
                Box(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = scriptText,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onCopyScript,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("bridge_script_copy_btn")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.bridge_script_copy_btn),
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
                Button(
                    onClick = onSaveScriptFile,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("bridge_script_save_btn")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.bridge_script_save_btn),
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
