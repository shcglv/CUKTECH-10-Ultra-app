package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.automation.AutomationSafetyReport
import com.example.cuktech.automation.AutomationSimulationReport
import com.example.cuktech.automation.DeadlineReadinessItem
import com.example.cuktech.automation.DeadlineReadinessStatus
import com.example.cuktech.db.room.DeadlineTaskEntity
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.util.Locale

@Composable
fun AutomationSafetyAndSimulatorCard(
    safetyReport: AutomationSafetyReport,
    simReport: AutomationSimulationReport?,
    onRunSimulation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("automation_safety_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.automation_safety_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = stringResource(R.string.automation_safety_stale_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (safetyReport.conflicts.isEmpty()) {
                Surface(
                    color = semantic.successContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.automation_safety_clean),
                        style = MaterialTheme.typography.labelMedium,
                        color = semantic.onSuccessContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            } else {
                safetyReport.conflicts.forEach { conflict ->
                    val isCrit = conflict.severity == "CRITICAL"
                    Surface(
                        color = if (isCrit) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "${conflict.severity}: ${conflict.ruleNames.joinToString(" ↔ ")}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isCrit) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = conflict.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCrit) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onRunSimulation,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("automation_simulate_btn")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.automation_simulate_btn))
            }

            if (simReport != null) {
                val totalHits = simReport.triggerCountsByRuleId.values.sum()
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("automation_sim_result_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.automation_sim_result_fmt,
                                simReport.evaluatedSamples,
                                simReport.skippedStaleGaps,
                                totalHits
                            ),
                            style = MaterialTheme.typography.labelMedium.tabularNums(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        simReport.events.take(3).forEach { ev ->
                            Text(
                                text = "• ${ev.ruleName} → ${ev.actionSummary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeadlineQueueAndReadinessCard(
    items: List<DeadlineReadinessItem>,
    onSaveTask: (DeadlineTaskEntity) -> Unit,
    onToggleEnabled: (Long, Boolean) -> Unit,
    onDeleteTask: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    var showAddSheet by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("deadline_queue_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.deadline_queue_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                FilledTonalButton(
                    onClick = { showAddSheet = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("add_deadline_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.deadline_add_btn), maxLines = 1)
                }
            }

            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.deadline_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                items.forEach { item ->
                    val statusLabel = when (item.status) {
                        DeadlineReadinessStatus.COMPLETED -> stringResource(R.string.deadline_status_completed)
                        DeadlineReadinessStatus.ON_TRACK -> stringResource(R.string.deadline_status_on_track)
                        DeadlineReadinessStatus.AT_RISK -> stringResource(R.string.deadline_status_at_risk)
                        DeadlineReadinessStatus.WILL_MISS -> stringResource(R.string.deadline_status_will_miss)
                        DeadlineReadinessStatus.PAUSED_NO_LOAD -> stringResource(R.string.deadline_status_paused)
                    }
                    val badgeBg = when (item.status) {
                        DeadlineReadinessStatus.COMPLETED, DeadlineReadinessStatus.ON_TRACK -> semantic.successContainer
                        DeadlineReadinessStatus.AT_RISK -> MaterialTheme.colorScheme.secondaryContainer
                        DeadlineReadinessStatus.WILL_MISS -> MaterialTheme.colorScheme.errorContainer
                        DeadlineReadinessStatus.PAUSED_NO_LOAD -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    val badgeFg = when (item.status) {
                        DeadlineReadinessStatus.COMPLETED, DeadlineReadinessStatus.ON_TRACK -> semantic.onSuccessContainer
                        DeadlineReadinessStatus.AT_RISK -> MaterialTheme.colorScheme.onSecondaryContainer
                        DeadlineReadinessStatus.WILL_MISS -> MaterialTheme.colorScheme.onErrorContainer
                        DeadlineReadinessStatus.PAUSED_NO_LOAD -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("deadline_item_${item.task.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(color = badgeBg, shape = RoundedCornerShape(8.dp)) {
                                        Text(
                                            text = "P${item.task.priority} · $statusLabel",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeFg,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = item.task.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        R.string.deadline_row_meta_fmt,
                                        explicitPortName(item.task.portId),
                                        item.deliveredWh,
                                        item.task.targetWh,
                                        item.currentPortWatts
                                    ),
                                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (item.estimatedMinutesLeft != null) {
                                    Text(
                                        text = stringResource(
                                            R.string.deadline_eta_fmt,
                                            item.estimatedMinutesLeft,
                                            item.availableMinutesLeft
                                        ),
                                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Switch(
                                checked = item.task.enabled,
                                onCheckedChange = { onToggleEnabled(item.task.id, it) }
                            )
                            IconButton(
                                onClick = { onDeleteTask(item.task.id) },
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

    if (showAddSheet) {
        AddDeadlineTaskSheet(
            onSave = { task ->
                onSaveTask(task)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddDeadlineTaskSheet(
    onSave: (DeadlineTaskEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var portId by remember { mutableIntStateOf(1) }
    var targetWhText by remember { mutableStateOf("45") }
    var hoursText by remember { mutableStateOf("2") }
    var priority by remember { mutableIntStateOf(1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("add_deadline_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.deadline_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.deadline_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("deadline_name_input")
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..4).forEach { pid ->
                    FilterChip(
                        selected = portId == pid,
                        onClick = { portId = pid },
                        label = { Text(explicitPortName(pid)) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = targetWhText,
                    onValueChange = { targetWhText = it },
                    label = { Text(stringResource(R.string.deadline_wh_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("deadline_wh_input")
                )
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it },
                    label = { Text(stringResource(R.string.deadline_hours_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("deadline_hours_input")
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1 to "P1", 2 to "P2", 3 to "P3").forEach { (pVal, pLabel) ->
                    FilterChip(
                        selected = priority == pVal,
                        onClick = { priority = pVal },
                        label = { Text(pLabel) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
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
                val fallbackName = "Charge Goal ${explicitPortName(portId)}"
                Button(
                    onClick = {
                        val wh = targetWhText.replace(",", ".").toDoubleOrNull()?.coerceIn(1.0, 500.0) ?: 45.0
                        val hours = hoursText.replace(",", ".").toDoubleOrNull()?.coerceIn(0.1, 48.0) ?: 2.0
                        val now = System.currentTimeMillis()
                        onSave(
                            DeadlineTaskEntity(
                                name = name.trim().ifBlank { fallbackName },
                                portId = portId,
                                targetWh = wh,
                                deadlineEpochMs = now + (hours * 3_600_000.0).toLong(),
                                priority = priority,
                                enabled = true,
                                createdAtMs = now
                            )
                        )
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("save_deadline_btn")
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NfcAndQuickActionsCard(
    lastStatus: String?,
    onExecuteUri: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val presets = listOf(
        "cuktech://action/scene/1" to stringResource(R.string.nfc_preset_night),
        "cuktech://action/scene/3" to stringResource(R.string.nfc_preset_burst),
        "cuktech://action/port/1/toggle" to stringResource(R.string.nfc_preset_c1_toggle),
        "cuktech://action/usb_a_trickle/on" to stringResource(R.string.nfc_preset_usba_trickle)
    )
    var selectedUri by remember { mutableStateOf(presets.first().first) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("nfc_quick_actions_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Nfc,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.nfc_actions_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = stringResource(R.string.nfc_actions_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                presets.forEach { (uri, label) ->
                    FilterChip(
                        selected = selectedUri == uri,
                        onClick = { selectedUri = uri },
                        label = { Text(label) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedUri,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("CUKTECH NFC Action", selectedUri))
                        Toast.makeText(context, selectedUri, Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("nfc_copy_uri_btn")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.nfc_copy_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                    onClick = { onExecuteUri(selectedUri) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("nfc_test_action_btn")
                ) {
                    Text(stringResource(R.string.nfc_test_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (!lastStatus.isNullOrBlank()) {
                Text(
                    text = lastStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
