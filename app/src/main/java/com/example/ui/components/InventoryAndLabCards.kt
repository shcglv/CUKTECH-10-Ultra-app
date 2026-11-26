package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.db.room.CableInventoryEntity
import com.example.cuktech.db.room.CableLabTestEntity
import com.example.cuktech.db.room.DeviceInventoryEntity
import com.example.cuktech.power.CableLabRunnerState
import com.example.cuktech.power.ChargerPassportSummary
import com.example.cuktech.power.DeviceMatchSuggestion
import com.example.cuktech.power.PhoneBatterySnapshot
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.util.Locale

@Composable
fun PhoneBatteryCalibrationCard(
    snapshot: PhoneBatterySnapshot,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("phone_battery_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        imageVector = Icons.Default.BatteryChargingFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.phone_batt_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                FilledTonalButton(
                    onClick = onRefresh,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("phone_battery_refresh_btn")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.phone_batt_refresh_btn))
                }
            }

            Text(
                text = stringResource(
                    R.string.phone_batt_soc_fmt,
                    snapshot.socPct,
                    snapshot.voltageV,
                    snapshot.currentMa
                ),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = TelemetryMonoFontFamily
                ).tabularNums()
            )
            Text(
                text = stringResource(
                    R.string.phone_batt_power_temp_fmt,
                    snapshot.batteryPowerW,
                    snapshot.tempC,
                    snapshot.pluggedSource
                ),
                style = MaterialTheme.typography.bodySmall.tabularNums(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val matchedPort = snapshot.matchedPortId
            val effPct = snapshot.estimatedEfficiencyPct
            Surface(
                color = if (matchedPort != null && effPct != null) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (matchedPort != null && effPct != null) {
                        stringResource(R.string.phone_batt_eff_fmt, explicitPortName(matchedPort), effPct)
                    } else {
                        stringResource(R.string.phone_batt_unplugged)
                    },
                    style = MaterialTheme.typography.labelMedium.tabularNums(),
                    color = if (matchedPort != null && effPct != null) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(10.dp)
                )
            }

            Text(
                text = stringResource(R.string.phone_batt_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceInventoryCard(
    devices: List<DeviceInventoryEntity>,
    suggestion: DeviceMatchSuggestion?,
    onConfirmSuggestion: (DeviceMatchSuggestion) -> Unit,
    onSaveDevice: (DeviceInventoryEntity) -> Unit,
    onDeleteDevice: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AddDeviceInventoryDialog(
            onSave = { entity ->
                showAddDialog = false
                onSaveDevice(entity)
            },
            onDismiss = { showAddDialog = false }
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("device_inventory_card")
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.DevicesOther,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.device_inv_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("add_device_inventory_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.device_inv_add_btn))
                }
            }

            if (suggestion != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("device_suggestion_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    R.string.device_suggest_title_fmt,
                                    suggestion.device.name,
                                    suggestion.matchConfidencePct
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = stringResource(
                                    R.string.device_suggest_detail_fmt,
                                    suggestion.session.peakWatts,
                                    suggestion.session.whAccumulated,
                                    explicitPortName(suggestion.session.portId),
                                    suggestion.session.protocolUsed
                                ),
                                style = MaterialTheme.typography.bodySmall.tabularNums(),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Button(
                            onClick = { onConfirmSuggestion(suggestion) },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("device_suggestion_confirm_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.device_suggest_confirm_btn))
                        }
                    }
                }
            }

            if (devices.isEmpty()) {
                Text(
                    text = stringResource(R.string.device_inv_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                devices.forEach { dev ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dev.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.device_inv_meta_fmt,
                                        dev.category,
                                        dev.ratedWh,
                                        dev.ratedMaxW,
                                        dev.preferredProtocol
                                    ),
                                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(
                                        R.string.device_inv_stats_fmt,
                                        dev.totalRecordedWh,
                                        dev.sessionCount
                                    ),
                                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { onDeleteDevice(dev.id) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDeviceInventoryDialog(
    onSave: (DeviceInventoryEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("PHONE") }
    var ratedWhText by remember { mutableStateOf("20") }
    var ratedMaxWText by remember { mutableStateOf("45") }
    var protocol by remember { mutableStateOf("PD") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("add_device_dialog")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.device_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.device_dialog_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("device_name_input")
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "PHONE" to R.string.device_cat_phone,
                    "LAPTOP" to R.string.device_cat_laptop,
                    "TABLET" to R.string.device_cat_tablet,
                    "POWERBANK" to R.string.device_cat_powerbank
                ).forEach { (key, labelRes) ->
                    FilterChip(
                        selected = category == key,
                        onClick = { category = key },
                        label = { Text(stringResource(labelRes)) }
                    )
                }
            }
            OutlinedTextField(
                value = ratedWhText,
                onValueChange = { ratedWhText = it },
                label = { Text(stringResource(R.string.device_dialog_wh_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("device_wh_input")
            )
            OutlinedTextField(
                value = ratedMaxWText,
                onValueChange = { ratedMaxWText = it },
                label = { Text(stringResource(R.string.device_dialog_maxw_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("device_maxw_input")
            )
            OutlinedTextField(
                value = protocol,
                onValueChange = { protocol = it },
                label = { Text(stringResource(R.string.device_dialog_proto_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val cleanName = name.trim().ifBlank { "Device" }
                        val wh = ratedWhText.replace(',', '.').toDoubleOrNull()?.coerceIn(1.0, 500.0) ?: 20.0
                        val maxW = ratedMaxWText.replace(',', '.').toDoubleOrNull()?.coerceIn(1.0, 240.0) ?: 45.0
                        onSave(
                            DeviceInventoryEntity(
                                name = cleanName,
                                category = category,
                                ratedWh = wh,
                                ratedMaxW = maxW,
                                preferredProtocol = protocol.trim().ifBlank { "PD" }
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("save_device_btn")
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CableInventoryAndLabCard(
    cables: List<CableInventoryEntity>,
    tests: List<CableLabTestEntity>,
    runnerState: CableLabRunnerState,
    lastNoticeCode: String?,
    onSaveCable: (CableInventoryEntity) -> Unit,
    onDeleteCable: (Long) -> Unit,
    onDeleteTest: (Long) -> Unit,
    onStartGuidedTest: (Int, CableInventoryEntity?) -> Unit,
    onCancelGuidedTest: () -> Unit,
    onAnalyzeRecent: (Int, CableInventoryEntity?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddCableDialog by remember { mutableStateOf(false) }
    var selectedPortId by remember { mutableStateOf(1) }
    var selectedCableId by remember { mutableStateOf(0L) }

    val selectedCable = remember(cables, selectedCableId) {
        cables.firstOrNull { it.id == selectedCableId }
    }

    if (showAddCableDialog) {
        AddCableInventoryDialog(
            onSave = { entity ->
                showAddCableDialog = false
                onSaveCable(entity)
            },
            onDismiss = { showAddCableDialog = false }
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("cable_lab_card")
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cable,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.cable_lab_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                FilledTonalButton(
                    onClick = { showAddCableDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("add_cable_inventory_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.cable_lab_add_btn))
                }
            }

            if (cables.isEmpty()) {
                Text(
                    text = stringResource(R.string.cable_lab_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                cables.forEach { cable ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cable.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.cable_lab_meta_fmt,
                                        cable.connectorType,
                                        cable.ratedAmps,
                                        cable.lengthCm,
                                        if (cable.hasEmarker) "Yes" else "No"
                                    ),
                                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (cable.lastMeasuredMohm != null) {
                                    Text(
                                        text = stringResource(
                                            R.string.cable_lab_last_fmt,
                                            cable.lastMeasuredMohm,
                                            cable.lastUncertaintyMohm ?: 0,
                                            cable.lastSagMv ?: 0,
                                            cable.testCount
                                        ),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = TelemetryMonoFontFamily
                                        ).tabularNums(),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDeleteCable(cable.id) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // Port selector chips for Cable Lab probe
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1, 2, 3, 4).forEach { pid ->
                    FilterChip(
                        selected = selectedPortId == pid,
                        onClick = { selectedPortId = pid },
                        label = { Text(explicitPortName(pid)) },
                        modifier = Modifier.testTag("cable_lab_port_$pid")
                    )
                }
            }

            // Optional cable selector chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCableId == 0L,
                    onClick = { selectedCableId = 0L },
                    label = { Text(stringResource(R.string.cable_lab_adhoc)) }
                )
                cables.forEach { cable ->
                    FilterChip(
                        selected = selectedCableId == cable.id,
                        onClick = { selectedCableId = cable.id },
                        label = { Text(cable.name) }
                    )
                }
            }

            if (runnerState.isRunning) {
                LinearProgressIndicator(
                    progress = {
                        (runnerState.collectedCount.toFloat() / runnerState.targetCount.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (runnerState.isRunning) onCancelGuidedTest()
                        else onStartGuidedTest(selectedPortId, selectedCable)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("cable_lab_start_btn")
                ) {
                    Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (runnerState.isRunning) {
                            stringResource(R.string.cable_lab_cancel_btn, runnerState.collectedCount, runnerState.targetCount)
                        } else {
                            stringResource(R.string.cable_lab_start_btn)
                        }
                    )
                }
                OutlinedButton(
                    onClick = { onAnalyzeRecent(selectedPortId, selectedCable) },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("cable_lab_analyze_recent_btn")
                ) {
                    Text(stringResource(R.string.cable_lab_analyze_recent_btn))
                }
            }

            if (!lastNoticeCode.isNullOrBlank()) {
                val isOk = lastNoticeCode == "OK"
                val noticeMsg = when (lastNoticeCode) {
                    "OK" -> stringResource(R.string.cable_lab_ok_msg)
                    "PPS_UNSUPPORTED" -> stringResource(R.string.cable_lab_err_pps)
                    "LOW_CURRENT" -> stringResource(R.string.cable_lab_err_low_current)
                    "NO_FIXED_RAIL" -> stringResource(R.string.cable_lab_err_no_rail)
                    else -> stringResource(R.string.cable_lab_err_few_samples)
                }
                Surface(
                    color = if (isOk) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cable_lab_notice_banner")
                ) {
                    Text(
                        text = noticeMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOk) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.cable_lab_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (tests.isNotEmpty()) {
                HorizontalDivider()
                tests.take(6).forEach { test ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.cable_lab_result_row_fmt,
                                    test.cableName,
                                    explicitPortName(test.portId),
                                    test.nominalRailV,
                                    test.avgCurrentA,
                                    test.estimatedMohm,
                                    test.uncertaintyMohm,
                                    test.confidence
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onDeleteTest(test.id) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = stringResource(R.string.action_delete),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCableInventoryDialog(
    onSave: (CableInventoryEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var connectorType by remember { mutableStateOf("C_TO_C") }
    var ampsText by remember { mutableStateOf("5") }
    var lengthText by remember { mutableStateOf("100") }
    var hasEmarker by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("add_cable_dialog")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.cable_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.cable_dialog_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cable_name_input")
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("C_TO_C", "A_TO_C", "C_TO_LIGHTNING").forEach { type ->
                    FilterChip(
                        selected = connectorType == type,
                        onClick = { connectorType = type },
                        label = { Text(type) }
                    )
                }
            }
            OutlinedTextField(
                value = ampsText,
                onValueChange = { ampsText = it },
                label = { Text(stringResource(R.string.cable_dialog_amps_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = lengthText,
                onValueChange = { lengthText = it },
                label = { Text(stringResource(R.string.cable_dialog_length_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = hasEmarker,
                    onCheckedChange = { hasEmarker = it }
                )
                Text(
                    text = stringResource(R.string.cable_dialog_emarker),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val cleanName = name.trim().ifBlank { "USB-C Cable" }
                        val amps = ampsText.toIntOrNull()?.coerceIn(1, 12) ?: 5
                        val length = lengthText.toIntOrNull()?.coerceIn(10, 500) ?: 100
                        onSave(
                            CableInventoryEntity(
                                name = cleanName,
                                connectorType = connectorType,
                                ratedAmps = amps,
                                lengthCm = length,
                                hasEmarker = hasEmarker
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("save_cable_btn")
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
fun ChargerPassportAndResearchCard(
    passport: ChargerPassportSummary,
    onBuildAnonymizedJson: () -> String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .testTag("charger_passport_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Badge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.passport_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = stringResource(
                    R.string.passport_header_fmt,
                    passport.chargerName,
                    passport.locationContext,
                    passport.mainsVoltageMode
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = stringResource(
                    R.string.passport_stats_fmt,
                    passport.totalSessions,
                    passport.totalDeliveredWh,
                    passport.recordedCableTests
                ),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = TelemetryMonoFontFamily
                ).tabularNums()
            )

            passport.avgCableLoopMohm?.let { avgMohm ->
                Text(
                    text = stringResource(R.string.passport_avg_mohm_fmt, avgMohm),
                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalButton(
                onClick = {
                    val json = onBuildAnonymizedJson()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("CuktechAnonymizedLab", json))
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_json_copied),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("export_anonymized_lab_btn")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.passport_export_btn))
            }

            Text(
                text = stringResource(R.string.passport_export_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
