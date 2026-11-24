package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun explicitPortName(portId: Int): String = when (portId) {
    1 -> stringResource(R.string.port_name_c1)
    2 -> stringResource(R.string.port_name_c2)
    3 -> stringResource(R.string.port_name_c3)
    4 -> stringResource(R.string.port_name_a)
    else -> "Port $portId"
}

@Composable
fun portSpecLabel(portId: Int): String = when (portId) {
    1 -> stringResource(R.string.port_spec_c1)
    2 -> stringResource(R.string.port_spec_c2)
    3 -> stringResource(R.string.port_spec_c3)
    4 -> stringResource(R.string.port_spec_a)
    else -> ""
}

@Composable
fun localizedScenarioName(mode: Int): String = when (mode) {
    1 -> stringResource(R.string.scenario_ai)
    2 -> stringResource(R.string.scenario_hybrid)
    3 -> stringResource(R.string.scenario_single)
    4 -> stringResource(R.string.scenario_dual)
    else -> stringResource(R.string.scenario_ai)
}

@Composable
fun localizedTimeoutName(code: Int): String = when (code) {
    4 -> stringResource(R.string.timeout_always)
    5 -> stringResource(R.string.timeout_1m)
    1 -> stringResource(R.string.timeout_5m)
    2 -> stringResource(R.string.timeout_10m)
    3 -> stringResource(R.string.timeout_30m)
    else -> stringResource(R.string.timeout_5m)
}

@Composable
fun formatDurationHms(totalSec: Long): String {
    val clean = totalSec.coerceAtLeast(0L)
    val h = clean / 3600L
    val m = (clean % 3600L) / 60L
    val s = clean % 60L
    return if (h > 0L) {
        stringResource(R.string.time_h_m_s_fmt, h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

@Composable
fun SmoothWheelPicker(
    values: List<Int>,
    selectedValue: Int,
    onValueSelected: (Int) -> Unit,
    formatValue: (Int) -> String = { it.toString() },
    modifier: Modifier = Modifier
) {
    val itemHeight = 40.dp
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = values.indexOf(selectedValue).coerceAtLeast(0)
    )
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    val centerIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2
            layoutInfo.visibleItemsInfo.minByOrNull { item ->
                kotlin.math.abs((item.offset + item.size / 2) - viewportCenter)
            }?.index ?: 0
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && values.isNotEmpty()) {
            val idx = centerIndex.coerceIn(0, values.lastIndex)
            if (values[idx] != selectedValue) {
                onValueSelected(values[idx])
            }
        }
    }

    Box(
        modifier = modifier.height(itemHeight * 3),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
        )

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(values) { index, value ->
                val isCenter = index == centerIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatValue(value),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = TelemetryMonoFontFamily,
                            fontSize = if (isCenter) 20.sp else 15.sp,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Medium
                        ).tabularNums(),
                        color = if (isCenter) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MasterKillswitchConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ElectricBolt, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.dialog_killswitch_title)) },
        text = { Text(stringResource(R.string.dialog_killswitch_body)) },
        confirmButton = {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_killswitch_button")
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun SoftResetConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
        title = { Text(stringResource(R.string.dialog_soft_reset_title)) },
        text = { Text(stringResource(R.string.dialog_soft_reset_body)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun MiHomeSwitchDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_mi_home_title)) },
        text = { Text(stringResource(R.string.dialog_mi_home_body)) },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun BluetoothOffDialog(
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BluetoothDisabled, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.warning_bluetooth_off_title)) },
        text = { Text(stringResource(R.string.warning_bluetooth_off_body)) },
        confirmButton = {
            Button(onClick = onEnable, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_turn_on_bluetooth))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortChargeLimitDialog(
    portId: Int,
    currentLimit: PortChargeLimit?,
    onSave: (whTarget: Double, mode: String, enabled: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var targetText by remember {
        mutableStateOf(
            if (currentLimit != null && currentLimit.whTarget > 0.0) {
                String.format(Locale.US, "%.1f", currentLimit.whTarget)
            } else "20.0"
        )
    }
    var selectedMode by remember { mutableStateOf(currentLimit?.mode ?: "once") }
    val portName = explicitPortName(portId)

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.dialog_wh_limit_title, portName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = targetText,
                onValueChange = { targetText = it },
                label = { Text(stringResource(R.string.dialog_wh_limit_target)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedMode == "once",
                    onClick = { selectedMode = "once" },
                    label = { Text(stringResource(R.string.action_once)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = selectedMode == "always",
                    onClick = { selectedMode = "always" },
                    label = { Text(stringResource(R.string.action_always)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
            Text(
                text = if (selectedMode == "once") {
                    stringResource(R.string.dialog_wh_mode_once)
                } else {
                    stringResource(R.string.dialog_wh_mode_always)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (currentLimit?.enabled == true) {
                    TextButton(
                        onClick = { onSave(0.0, selectedMode, false) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val parsed = targetText.replace(",", ".").toDoubleOrNull() ?: 20.0
                        onSave(parsed.coerceIn(0.1, 9999.0), selectedMode, true)
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortCountdownDialog(
    portId: Int,
    initialMinutes: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cleanInit = initialMinutes.coerceIn(1, 1439)
    var selectedHours by remember { mutableIntStateOf(cleanInit / 60) }
    var selectedMinutes by remember { mutableIntStateOf(cleanInit % 60) }
    val portName = explicitPortName(portId)
    val unitH = stringResource(R.string.unit_h)
    val unitM = stringResource(R.string.unit_m)

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "${stringResource(R.string.port_delay_off_timer)} · $portName",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmoothWheelPicker(
                    values = (0..23).toList(),
                    selectedValue = selectedHours,
                    onValueSelected = { selectedHours = it },
                    formatValue = { "${it}$unitH" },
                    modifier = Modifier.weight(1f)
                )
                SmoothWheelPicker(
                    values = (0..59).toList(),
                    selectedValue = selectedMinutes,
                    onValueSelected = { selectedMinutes = it },
                    formatValue = { "%02d$unitM".format(it) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 120).forEach { mins ->
                    FilterChip(
                        selected = (selectedHours * 60 + selectedMinutes) == mins,
                        onClick = {
                            selectedHours = mins / 60
                            selectedMinutes = mins % 60
                        },
                        label = {
                            Text(
                                if (mins >= 60) "${mins / 60}$unitH" else "${mins}$unitM",
                                style = MaterialTheme.typography.labelMedium.tabularNums()
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (initialMinutes > 0) {
                    TextButton(
                        onClick = { onSave(0) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave((selectedHours * 60 + selectedMinutes).coerceAtLeast(1)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            }
        }
    }
}

@Composable
fun AliasEditDialog(
    portId: Int,
    currentAlias: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var aliasInput by remember { mutableStateOf(currentAlias) }
    val titleTarget = if (portId == 0) "CUKTECH 10 Ultra" else explicitPortName(portId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.port_alias_dialog_title, titleTarget)) },
        text = {
            OutlinedTextField(
                value = aliasInput,
                onValueChange = { aliasInput = it },
                placeholder = { Text(stringResource(R.string.port_alias_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onSave(aliasInput) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (currentAlias.isNotBlank()) {
                    TextButton(onClick = { onSave("") }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

fun scenarioIcon(mode: Int): ImageVector = when (mode) {
    1 -> Icons.Default.AutoAwesome
    2 -> Icons.Default.MergeType
    3 -> Icons.Default.Bolt
    4 -> Icons.Default.CallSplit
    else -> Icons.Default.AutoAwesome
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioSelectionDialog(
    currentMode: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scenarios = listOf(
        1 to (stringResource(R.string.scenario_ai) to stringResource(R.string.scenario_ai_desc)),
        2 to (stringResource(R.string.scenario_hybrid) to stringResource(R.string.scenario_hybrid_desc)),
        3 to (stringResource(R.string.scenario_single) to stringResource(R.string.scenario_single_desc)),
        4 to (stringResource(R.string.scenario_dual) to stringResource(R.string.scenario_dual_desc))
    )
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
                text = stringResource(R.string.overview_scenario_mode),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            scenarios.forEach { (id, pair) ->
                val isSelected = currentMode == id
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { onSelect(id) }
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = scenarioIcon(id),
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pair.first, style = MaterialTheme.typography.titleSmall)
                            Text(pair.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(selected = isSelected, onClick = { onSelect(id) })
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportCsvDialog(
    onExportDownloads: (CuktechPersistence.ExportTimeframe, CuktechPersistence.ExportScope) -> Unit,
    onExportSaf: (CuktechPersistence.ExportTimeframe, CuktechPersistence.ExportScope) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var timeframe by remember { mutableStateOf(CuktechPersistence.ExportTimeframe.ALL_TIME) }
    var scope by remember { mutableStateOf(CuktechPersistence.ExportScope.ALL) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
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
                text = stringResource(R.string.dialog_export_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            CuktechPersistence.ExportTimeframe.entries.forEach { tf ->
                val tfLabel = when (tf) {
                    CuktechPersistence.ExportTimeframe.CURRENT_SESSION -> stringResource(R.string.export_tf_session)
                    CuktechPersistence.ExportTimeframe.LAST_1_HOUR -> stringResource(R.string.export_tf_1h)
                    CuktechPersistence.ExportTimeframe.LAST_24_HOURS -> stringResource(R.string.export_tf_24h)
                    CuktechPersistence.ExportTimeframe.LAST_7_DAYS -> stringResource(R.string.export_tf_7d)
                    CuktechPersistence.ExportTimeframe.ALL_TIME -> stringResource(R.string.export_tf_all)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { timeframe = tf }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = timeframe == tf, onClick = { timeframe = tf })
                    Spacer(Modifier.width(6.dp))
                    Text(tfLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
            CuktechPersistence.ExportScope.entries.forEach { sc ->
                val scLabel = when (sc) {
                    CuktechPersistence.ExportScope.ALL -> stringResource(R.string.export_sc_all)
                    CuktechPersistence.ExportScope.SESSIONS_ONLY -> stringResource(R.string.export_sc_sessions)
                    CuktechPersistence.ExportScope.TELEMETRY_ONLY -> stringResource(R.string.export_sc_telemetry)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { scope = sc }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = scope == sc, onClick = { scope = sc })
                    Spacer(Modifier.width(6.dp))
                    Text(scLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onExportDownloads(timeframe, scope) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.dialog_export_downloads))
                }
                Button(
                    onClick = { onExportSaf(timeframe, scope) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.dialog_export_saf))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearHistoryDialog(
    onConfirm: (CuktechPersistence.ClearHistoryOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedOption by remember { mutableStateOf(CuktechPersistence.ClearHistoryOption.OLDER_7D) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.dialog_clear_history_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            CuktechPersistence.ClearHistoryOption.entries.forEach { opt ->
                val optLabel = when (opt) {
                    CuktechPersistence.ClearHistoryOption.KEEP_ACTIVE -> stringResource(R.string.clear_opt_keep_active)
                    CuktechPersistence.ClearHistoryOption.OLDER_24H -> stringResource(R.string.clear_opt_24h)
                    CuktechPersistence.ClearHistoryOption.OLDER_7D -> stringResource(R.string.clear_opt_7d)
                    CuktechPersistence.ClearHistoryOption.OLDER_30D -> stringResource(R.string.clear_opt_30d)
                    CuktechPersistence.ClearHistoryOption.CLEAR_ALL -> stringResource(R.string.clear_opt_all)
                }
                val optDesc = when (opt) {
                    CuktechPersistence.ClearHistoryOption.KEEP_ACTIVE -> stringResource(R.string.clear_opt_keep_active_desc)
                    CuktechPersistence.ClearHistoryOption.OLDER_24H -> stringResource(R.string.clear_opt_24h_desc)
                    CuktechPersistence.ClearHistoryOption.OLDER_7D -> stringResource(R.string.clear_opt_7d_desc)
                    CuktechPersistence.ClearHistoryOption.OLDER_30D -> stringResource(R.string.clear_opt_30d_desc)
                    CuktechPersistence.ClearHistoryOption.CLEAR_ALL -> stringResource(R.string.clear_opt_all_desc)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedOption = opt }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedOption == opt, onClick = { selectedOption = opt })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(optLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(optDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(selectedOption) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsolidatedSessionsTableDialog(
    sessions: List<ChargeSessionRecord>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val fmt = remember(activeLocale) { SimpleDateFormat("MM-dd HH:mm", activeLocale) }
    val unitW = stringResource(R.string.unit_w)
    val unitWh = stringResource(R.string.unit_wh)
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
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.analytics_view_table),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sessions.forEach { s ->
                    val portTitle = explicitPortName(s.portId)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "$portTitle · ${fmt.format(Date((s.startTimeSec * 1000).toLong()))}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = "${s.protocolUsed} · ${formatDurationHms(s.durationSeconds)} · ${stringResource(R.string.session_peak_fmt, s.peakWatts, unitW)}",
                                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${String.format(Locale.US, "%.2f", s.whAccumulated)} $unitWh",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cd_close))
                }
            }
        }
    }
}
