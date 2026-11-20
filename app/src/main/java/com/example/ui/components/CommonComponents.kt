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
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.security.CuktechDeviceCredentials
import com.example.ui.theme.LocalSemanticColors
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

fun formatDurationHms(totalSec: Long): String {
    val clean = totalSec.coerceAtLeast(0L)
    val h = clean / 3600L
    val m = (clean % 3600L) / 60L
    val s = clean % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%dh %02dm %02ds", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

@Composable
fun ConnectionStatusCard(
    authState: CuktechAuthState,
    isDisconnectCooldown: Boolean,
    deviceName: String,
    macAddress: String,
    rssi: Int,
    qualityScore: Int,
    activeCred: CuktechDeviceCredentials?,
    regionWarning: String?,
    onConnectClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onOpenSearchPair: () -> Unit,
    onOpenXiaomiLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val isConnected = authState == CuktechAuthState.AUTHENTICATED
    val isBusy = authState == CuktechAuthState.CONNECTING_GATT ||
        authState == CuktechAuthState.DISCOVERING_SERVICES ||
        authState == CuktechAuthState.PHASE_A_INIT_A4 ||
        authState == CuktechAuthState.PHASE_B_HANDSHAKE ||
        authState == CuktechAuthState.SCANNING

    val statusColor = when {
        isConnected -> semantic.success
        isBusy -> semantic.warning
        authState == CuktechAuthState.AUTH_FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    val statusText = when {
        isDisconnectCooldown -> stringResource(R.string.status_cooldown)
        authState == CuktechAuthState.AUTHENTICATED -> stringResource(R.string.status_connected)
        authState == CuktechAuthState.SCANNING -> stringResource(R.string.status_scanning)
        authState == CuktechAuthState.AUTH_FAILED -> stringResource(R.string.status_auth_failed)
        isBusy -> stringResource(R.string.status_connecting)
        else -> stringResource(R.string.status_disconnected)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("connection_status_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName.ifBlank { "CUKTECH 10 Ultra" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(statusText)
                            if (macAddress.isNotBlank() && macAddress != "--") {
                                append(" · ")
                                append(macAddress)
                            }
                            if (isConnected) {
                                append(" · ")
                                append("${rssi} dBm (${qualityScore}%)")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    OutlinedButton(
                        onClick = onCancelClick,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("connection_cancel_button")
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                } else if (isConnected) {
                    FilledTonalButton(
                        onClick = onDisconnectClick,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("connection_disconnect_button")
                    ) {
                        Text(stringResource(R.string.action_disconnect))
                    }
                } else {
                    Button(
                        onClick = onConnectClick,
                        enabled = !isDisconnectCooldown,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("connection_connect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_connect))
                    }
                }

                FilledTonalButton(
                    onClick = onOpenSearchPair,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("search_pair_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = stringResource(R.string.action_scan_pair),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (regionWarning != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
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

        if (activeCred == null || !activeCred.hasValidToken) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.warning_no_token_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = stringResource(R.string.warning_no_token_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                        )
                    }
                    TextButton(
                        onClick = onOpenXiaomiLogin,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("xiaomi_login_banner_button")
                    ) {
                        Text(stringResource(R.string.action_xiaomi_login))
                    }
                }
            }
        }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ElectricBolt, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.dialog_killswitch_title)) },
        text = { Text(stringResource(R.string.dialog_killswitch_body)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
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

@Composable
fun PortChargeLimitDialog(
    portId: Int,
    currentLimit: PortChargeLimit?,
    onSave: (whTarget: Double, mode: String, enabled: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var targetText by remember {
        mutableStateOf(
            if (currentLimit != null && currentLimit.whTarget > 0.0) {
                String.format(Locale.US, "%.1f", currentLimit.whTarget)
            } else "20.0"
        )
    }
    var selectedMode by remember { mutableStateOf(currentLimit?.mode ?: "once") }
    val portName = explicitPortName(portId)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_wh_limit_title, portName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        label = { Text("Once") }
                    )
                    FilterChip(
                        selected = selectedMode == "always",
                        onClick = { selectedMode = "always" },
                        label = { Text("Always") }
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = targetText.replace(",", ".").toDoubleOrNull() ?: 20.0
                    onSave(parsed.coerceIn(0.1, 9999.0), selectedMode, true)
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = {
            Row {
                if (currentLimit?.enabled == true) {
                    TextButton(
                        onClick = { onSave(0.0, selectedMode, false) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

@Composable
fun PortCountdownDialog(
    portId: Int,
    initialMinutes: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val cleanInit = initialMinutes.coerceIn(1, 1439)
    var selectedHours by remember { mutableIntStateOf(cleanInit / 60) }
    var selectedMinutes by remember { mutableIntStateOf(cleanInit % 60) }
    val portName = explicitPortName(portId)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.port_delay_off_timer)} · $portName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmoothWheelPicker(
                        values = (0..23).toList(),
                        selectedValue = selectedHours,
                        onValueSelected = { selectedHours = it },
                        formatValue = { "${it}h" },
                        modifier = Modifier.weight(1f)
                    )
                    SmoothWheelPicker(
                        values = (0..59).toList(),
                        selectedValue = selectedMinutes,
                        onValueSelected = { selectedMinutes = it },
                        formatValue = { "%02dm".format(it) },
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
                                    if (mins >= 60) "${mins / 60}h" else "${mins}m",
                                    style = MaterialTheme.typography.labelMedium.tabularNums()
                                )
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave((selectedHours * 60 + selectedMinutes).coerceAtLeast(1)) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = {
            Row {
                if (initialMinutes > 0) {
                    TextButton(
                        onClick = { onSave(0) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
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

@Composable
fun ScenarioSelectionDialog(
    currentMode: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scenarios = listOf(
        1 to (stringResource(R.string.scenario_ai) to stringResource(R.string.scenario_ai_desc)),
        2 to (stringResource(R.string.scenario_hybrid) to stringResource(R.string.scenario_hybrid_desc)),
        3 to (stringResource(R.string.scenario_single) to stringResource(R.string.scenario_single_desc)),
        4 to (stringResource(R.string.scenario_dual) to stringResource(R.string.scenario_dual_desc))
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.overview_scenario_mode)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                scenarios.forEach { (id, pair) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.RadioButton) { onSelect(id) }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentMode == id, onClick = { onSelect(id) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(pair.first, style = MaterialTheme.typography.titleSmall)
                            Text(pair.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun ExportCsvDialog(
    onExportDownloads: (CuktechPersistence.ExportTimeframe, CuktechPersistence.ExportScope) -> Unit,
    onExportSaf: (CuktechPersistence.ExportTimeframe, CuktechPersistence.ExportScope) -> Unit,
    onDismiss: () -> Unit
) {
    var timeframe by remember { mutableStateOf(CuktechPersistence.ExportTimeframe.ALL_TIME) }
    var scope by remember { mutableStateOf(CuktechPersistence.ExportScope.ALL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_export_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CuktechPersistence.ExportTimeframe.entries.forEach { tf ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { timeframe = tf }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = timeframe == tf, onClick = { timeframe = tf })
                        Spacer(Modifier.width(6.dp))
                        Text(tf.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
                CuktechPersistence.ExportScope.entries.forEach { sc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { scope = sc }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = scope == sc, onClick = { scope = sc })
                        Spacer(Modifier.width(6.dp))
                        Text(sc.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onExportSaf(timeframe, scope) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.dialog_export_saf))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onExportDownloads(timeframe, scope) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.dialog_export_downloads))
            }
        }
    )
}

@Composable
fun ClearHistoryDialog(
    onConfirm: (CuktechPersistence.ClearHistoryOption) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedOption by remember { mutableStateOf(CuktechPersistence.ClearHistoryOption.OLDER_7D) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_clear_history_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CuktechPersistence.ClearHistoryOption.entries.forEach { opt ->
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
                            Text(opt.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(opt.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedOption) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_clear))
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
fun ConsolidatedSessionsTableDialog(
    sessions: List<ChargeSessionRecord>,
    onDismiss: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.analytics_view_table),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sessions.forEach { s ->
                        val portTitle = explicitPortName(s.portId)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(10.dp),
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
                                        text = "${s.protocolUsed} · ${formatDurationHms(s.durationSeconds)} · Peak ${String.format(Locale.US, "%.1f", s.peakWatts)}W",
                                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "${String.format(Locale.US, "%.2f", s.whAccumulated)} Wh",
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
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.cd_close))
                    }
                }
            }
        }
    }
}
