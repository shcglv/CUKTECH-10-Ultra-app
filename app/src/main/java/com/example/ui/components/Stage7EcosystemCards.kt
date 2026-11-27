package com.example.ui.components

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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.integrations.IntegrationConfig
import com.example.cuktech.integrations.MqttConnectionState
import com.example.cuktech.integrations.MqttConnectionStatus
import com.example.cuktech.power.FleetSummarySnapshot
import com.example.cuktech.power.MultiChargerFleetEngine
import com.example.cuktech.security.WebDavConfig
import com.example.cuktech.security.WebDavSyncStatus
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.util.Locale

@Composable
fun MultiChargerFleetCard(
    fleet: FleetSummarySnapshot,
    onSwitchCharger: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    var idxA by remember(fleet.nodes.size) { mutableIntStateOf(0) }
    var idxB by remember(fleet.nodes.size) { mutableIntStateOf(if (fleet.nodes.size > 1) 1 else 0) }
    val nodeA = fleet.nodes.getOrNull(idxA)
    val nodeB = fleet.nodes.getOrNull(idxB)
    val benchmark = remember(nodeA, nodeB) { MultiChargerFleetEngine.compareNodes(nodeA, nodeB) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("fleet_summary_card")
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = stringResource(R.string.fleet_summary_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.fleet_nodes_online_fmt, fleet.onlineCount, fleet.nodesCount),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.fleet_summary_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FleetMetricTile(
                    label = stringResource(R.string.fleet_live_total_label),
                    value = String.format(Locale.US, "%.1f W", fleet.totalLiveWatts),
                    modifier = Modifier.weight(1f)
                )
                FleetMetricTile(
                    label = stringResource(R.string.fleet_today_wh_label),
                    value = String.format(Locale.US, "%.1f Wh", fleet.fleetWhToday),
                    modifier = Modifier.weight(1f)
                )
                FleetMetricTile(
                    label = stringResource(R.string.fleet_all_time_label),
                    value = String.format(Locale.US, "%.1f Wh", fleet.fleetTotalWh),
                    modifier = Modifier.weight(1f)
                )
            }

            fleet.nodes.forEach { node ->
                Surface(
                    color = if (node.isActiveTarget) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    border = if (node.isActiveTarget) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("fleet_node_row_${node.deviceMac}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${node.name} (${node.locationContext} · ${node.mainsVoltageMode}V)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(
                                    R.string.fleet_node_row_fmt,
                                    if (node.isOnline) stringResource(R.string.fleet_node_online) else stringResource(R.string.fleet_node_offline),
                                    node.liveWatts,
                                    node.whToday,
                                    node.totalHistoricalWh
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                                color = if (node.isOnline) semantic.success else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!node.isActiveTarget) {
                            TextButton(
                                onClick = { onSwitchCharger(node.deviceMac) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.multi_charger_connect_btn))
                            }
                        }
                    }
                }
            }

            if (fleet.nodes.size >= 2) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(
                    text = stringResource(R.string.fleet_benchmark_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.fleet_benchmark_node_a), style = MaterialTheme.typography.labelSmall)
                    fleet.nodes.forEachIndexed { i, n ->
                        FilterChip(
                            selected = idxA == i,
                            onClick = { idxA = i },
                            label = { Text(n.locationContext.ifBlank { n.name }, maxLines = 1) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.fleet_benchmark_node_b), style = MaterialTheme.typography.labelSmall)
                    fleet.nodes.forEachIndexed { i, n ->
                        FilterChip(
                            selected = idxB == i,
                            onClick = { idxB = i },
                            label = { Text(n.locationContext.ifBlank { n.name }, maxLines = 1) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
                if (benchmark != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fleet_benchmark_result")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.fleet_benchmark_delta_fmt,
                                    benchmark.deltaLiveWatts,
                                    benchmark.deltaWhToday,
                                    benchmark.deltaTotalWh
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums()
                            )
                            Text(
                                text = stringResource(
                                    R.string.fleet_benchmark_mains_note,
                                    benchmark.nodeA.mainsVoltageMode,
                                    benchmark.nodeA.ratedMaxW,
                                    benchmark.nodeB.mainsVoltageMode,
                                    benchmark.nodeB.ratedMaxW
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FleetMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = TelemetryMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ).tabularNums(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MqttAndWebhookSettingsCard(
    config: IntegrationConfig,
    mqttStatus: MqttConnectionStatus,
    lastError: String?,
    onSaveConfig: (config: IntegrationConfig, bearerToken: String?, mqttPassword: String?) -> Unit,
    onReconnectMqtt: () -> Unit,
    onPublishHaDiscovery: () -> Unit,
    modifier: Modifier = Modifier
) {
    var haEnabledDraft by remember(config.haWebhookEnabled) { mutableStateOf(config.haWebhookEnabled) }
    var haUrlDraft by remember(config.haWebhookUrl) { mutableStateOf(config.haWebhookUrl) }
    var haTokenDraft by remember { mutableStateOf("") }
    var haTokenVisible by remember { mutableStateOf(false) }
    var eventWebhooksDraft by remember(config.eventWebhooksEnabled) { mutableStateOf(config.eventWebhooksEnabled) }

    var mqttEnabledDraft by remember(config.mqttEnabled) { mutableStateOf(config.mqttEnabled) }
    var mqttUrlDraft by remember(config.mqttBrokerUrl) { mutableStateOf(config.mqttBrokerUrl) }
    var mqttClientIdDraft by remember(config.mqttClientId) { mutableStateOf(config.mqttClientId) }
    var mqttBaseTopicDraft by remember(config.mqttBaseTopic) { mutableStateOf(config.mqttBaseTopic) }
    var mqttUserDraft by remember(config.mqttUsername) { mutableStateOf(config.mqttUsername) }
    var mqttPassDraft by remember { mutableStateOf("") }
    var mqttPassVisible by remember { mutableStateOf(false) }
    var haDiscoveryDraft by remember(config.haDiscoveryEnabled) { mutableStateOf(config.haDiscoveryEnabled) }

    val mqttStateText = when (mqttStatus.state) {
        MqttConnectionState.CONNECTED -> stringResource(R.string.settings_mqtt_state_connected)
        MqttConnectionState.CONNECTING -> stringResource(R.string.settings_mqtt_state_connecting)
        MqttConnectionState.ERROR -> stringResource(R.string.settings_mqtt_state_error)
        MqttConnectionState.DISCONNECTED -> stringResource(R.string.settings_mqtt_state_disconnected)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Home Assistant Webhook Section
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_ha_webhook), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = haEnabledDraft,
                onCheckedChange = { haEnabledDraft = it },
                modifier = Modifier.testTag("ha_webhook_switch")
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_event_webhooks), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = eventWebhooksDraft,
                onCheckedChange = { eventWebhooksDraft = it },
                modifier = Modifier.testTag("event_webhooks_switch")
            )
        }
        OutlinedTextField(
            value = haUrlDraft,
            onValueChange = { haUrlDraft = it },
            label = { Text(stringResource(R.string.settings_ha_url_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ha_webhook_url_input")
        )
        if (haUrlDraft.trim().lowercase().startsWith("http://")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.settings_ha_http_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        OutlinedTextField(
            value = haTokenDraft,
            onValueChange = { haTokenDraft = it },
            label = {
                Text(
                    if (config.hasBearerToken) {
                        "${stringResource(R.string.settings_ha_token_label)} (${stringResource(R.string.settings_ha_token_configured)})"
                    } else {
                        stringResource(R.string.settings_ha_token_label)
                    }
                )
            },
            visualTransformation = if (haTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { haTokenVisible = !haTokenVisible }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (haTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.cd_toggle_password)
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // MQTT 3.1.1 + Home Assistant Auto Discovery Section
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.settings_mqtt_section_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_mqtt_enable), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = mqttEnabledDraft,
                onCheckedChange = { mqttEnabledDraft = it },
                modifier = Modifier.testTag("mqtt_enable_switch")
            )
        }
        Text(
            text = stringResource(
                R.string.settings_mqtt_status_fmt,
                mqttStateText,
                (mqttStatus.lastError ?: mqttStatus.brokerLabel).ifBlank { "--" }
            ),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily),
            color = if (mqttStatus.state == MqttConnectionState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("mqtt_status_text")
        )
        OutlinedTextField(
            value = mqttUrlDraft,
            onValueChange = { mqttUrlDraft = it },
            label = { Text(stringResource(R.string.settings_mqtt_broker_label)) },
            placeholder = { Text("mqtt://192.168.1.10:1883") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("mqtt_broker_input")
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = mqttBaseTopicDraft,
                onValueChange = { mqttBaseTopicDraft = it },
                label = { Text(stringResource(R.string.settings_mqtt_base_topic_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = mqttClientIdDraft,
                onValueChange = { mqttClientIdDraft = it },
                label = { Text(stringResource(R.string.settings_mqtt_client_id_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = mqttUserDraft,
                onValueChange = { mqttUserDraft = it },
                label = { Text(stringResource(R.string.settings_mqtt_user_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = mqttPassDraft,
                onValueChange = { mqttPassDraft = it },
                label = {
                    Text(
                        if (config.hasMqttPassword) {
                            "${stringResource(R.string.settings_mqtt_pass_label)} (${stringResource(R.string.settings_ha_token_configured)})"
                        } else {
                            stringResource(R.string.settings_mqtt_pass_label)
                        }
                    )
                },
                visualTransformation = if (mqttPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { mqttPassVisible = !mqttPassVisible }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (mqttPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(R.string.cd_toggle_password)
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_mqtt_ha_discovery), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = haDiscoveryDraft,
                onCheckedChange = { haDiscoveryDraft = it },
                modifier = Modifier.testTag("mqtt_ha_discovery_switch")
            )
        }

        if (!lastError.isNullOrBlank()) {
            Text(lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    onSaveConfig(
                        config.copy(
                            haWebhookEnabled = haEnabledDraft,
                            haWebhookUrl = haUrlDraft,
                            eventWebhooksEnabled = eventWebhooksDraft,
                            mqttEnabled = mqttEnabledDraft,
                            mqttBrokerUrl = mqttUrlDraft,
                            mqttClientId = mqttClientIdDraft,
                            mqttBaseTopic = mqttBaseTopicDraft,
                            mqttUsername = mqttUserDraft,
                            haDiscoveryEnabled = haDiscoveryDraft
                        ),
                        haTokenDraft.ifBlank { null },
                        mqttPassDraft.ifBlank { null }
                    )
                    haTokenDraft = ""
                    mqttPassDraft = ""
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("integrations_save_btn")
            ) {
                Text(
                    text = stringResource(R.string.action_save),
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
            }
            OutlinedButton(
                onClick = onReconnectMqtt,
                enabled = mqttEnabledDraft,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("mqtt_reconnect_btn")
            ) {
                Text(
                    text = stringResource(R.string.settings_mqtt_reconnect_btn),
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
            }
            OutlinedButton(
                onClick = onPublishHaDiscovery,
                enabled = mqttEnabledDraft && haDiscoveryDraft,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("mqtt_publish_discovery_btn")
            ) {
                Text(
                    text = stringResource(R.string.settings_mqtt_publish_discovery_btn),
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBackupSheet(
    config: WebDavConfig,
    status: WebDavSyncStatus,
    onSaveConfig: (WebDavConfig, String?) -> Unit,
    onTestConnection: (url: String, user: String, pass: String?) -> Unit,
    onUploadBackup: (url: String, user: String, pass: String?, filename: String, passphrase: CharArray, includeSecrets: Boolean) -> Unit,
    onRestoreBackup: (url: String, user: String, pass: String?, filename: String, passphrase: CharArray, replaceExisting: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var urlDraft by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var userDraft by remember(config.username) { mutableStateOf(config.username) }
    var passDraft by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }
    var filenameDraft by remember(config.remoteFilename) { mutableStateOf(config.remoteFilename) }
    var backupPassphrase by remember { mutableStateOf("") }
    var passphraseVisible by remember { mutableStateOf(false) }
    var includeSecrets by remember { mutableStateOf(false) }
    var replaceExisting by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("webdav_backup_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.webdav_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                text = stringResource(R.string.webdav_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text(stringResource(R.string.webdav_url_label)) },
                placeholder = { Text("https://cloud.example.com/remote.php/dav/files/user/Cuktech") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("webdav_url_input")
            )
            if (urlDraft.trim().lowercase().startsWith("http://")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.webdav_http_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = userDraft,
                    onValueChange = { userDraft = it },
                    label = { Text(stringResource(R.string.webdav_user_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = passDraft,
                    onValueChange = { passDraft = it },
                    label = {
                        Text(
                            if (config.hasPassword) {
                                "${stringResource(R.string.webdav_pass_label)} (${stringResource(R.string.settings_ha_token_configured)})"
                            } else {
                                stringResource(R.string.webdav_pass_label)
                            }
                        )
                    },
                    visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passVisible = !passVisible }, modifier = Modifier.size(48.dp)) {
                            Icon(if (passVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = stringResource(R.string.cd_toggle_password))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = filenameDraft,
                onValueChange = { filenameDraft = it },
                label = { Text(stringResource(R.string.webdav_filename_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = backupPassphrase,
                onValueChange = { backupPassphrase = it },
                label = { Text(stringResource(R.string.webdav_passphrase_label)) },
                visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passphraseVisible = !passphraseVisible }, modifier = Modifier.size(48.dp)) {
                        Icon(if (passphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = stringResource(R.string.cd_toggle_password))
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("webdav_passphrase_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
                Text(stringResource(R.string.webdav_include_secrets), style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = replaceExisting, onCheckedChange = { replaceExisting = it })
                Text(stringResource(R.string.webdav_replace_existing), style = MaterialTheme.typography.bodySmall)
            }

            if (!status.lastMessage.isNullOrBlank()) {
                Text(
                    text = status.lastMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("webdav_status_msg")
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        onSaveConfig(
                            config.copy(
                                enabled = urlDraft.isNotBlank(),
                                baseUrl = urlDraft,
                                username = userDraft,
                                remoteFilename = filenameDraft
                            ),
                            passDraft.ifBlank { null }
                        )
                        passDraft = ""
                    },
                    enabled = !status.busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("webdav_save_btn")
                ) {
                    Text(stringResource(R.string.action_save))
                }
                OutlinedButton(
                    onClick = { onTestConnection(urlDraft, userDraft, passDraft.ifBlank { null }) },
                    enabled = !status.busy && urlDraft.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("webdav_test_btn")
                ) {
                    Text(stringResource(R.string.webdav_test_btn))
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val chars = backupPassphrase.toCharArray()
                        onUploadBackup(urlDraft, userDraft, passDraft.ifBlank { null }, filenameDraft, chars, includeSecrets)
                    },
                    enabled = !status.busy && urlDraft.isNotBlank() && backupPassphrase.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("webdav_upload_btn")
                ) {
                    if (status.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.webdav_upload_btn),
                            maxLines = 2,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        val chars = backupPassphrase.toCharArray()
                        onRestoreBackup(urlDraft, userDraft, passDraft.ifBlank { null }, filenameDraft, chars, replaceExisting)
                    },
                    enabled = !status.busy && urlDraft.isNotBlank() && backupPassphrase.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("webdav_restore_btn")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.webdav_restore_btn),
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
