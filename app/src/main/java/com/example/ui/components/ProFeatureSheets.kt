package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.automation.LocalAutomationEngine
import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.db.room.PortDevicePresetEntity
import com.example.cuktech.power.CableCurrentClass
import com.example.cuktech.power.CableHealthRating
import com.example.cuktech.power.DevicePresetCategory
import com.example.cuktech.power.PortCableDiagnostic
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ModalBottomSheet for assigning battery capacity (defaulting to mAh with 1S/2S/3S at 3.85V,
 * plus category quick presets and optional direct Wh entry) with 1%-step starting SoC.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DevicePresetSheet(
    portId: Int,
    currentPreset: PortDevicePresetEntity?,
    onSave: (presetType: String, capacityMah: Int, cellSeriesCount: Int, label: String, targetWh: Double, initialSocPct: Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultCells = currentPreset?.cellSeriesCount?.coerceIn(1, 3) ?: 1
    val defaultMah = currentPreset?.capacityMah?.takeIf { it > 0 }
        ?: currentPreset?.let { ((it.targetWh * 1000.0) / (3.85 * defaultCells)).roundToInt().coerceIn(100, 50_000) }
        ?: 5000
    val defaultWh = currentPreset?.targetWh?.takeIf { it > 0.5 }
        ?: com.example.cuktech.power.EtaEstimator.whFromMah(defaultMah, defaultCells)

    var selectedCategory by remember {
        mutableStateOf(DevicePresetCategory.fromCode(currentPreset?.presetType))
    }
    // Always default to mAh input mode as requested, while letting user switch to Wh anytime
    var useMahMode by remember { mutableStateOf(true) }
    var capacityMahText by remember { mutableStateOf(defaultMah.toString()) }
    var capacityWhText by remember { mutableStateOf(String.format(Locale.US, "%.1f", defaultWh)) }
    var cellSeriesCount by remember { mutableIntStateOf(defaultCells) }
    var labelText by remember { mutableStateOf(currentPreset?.deviceLabel ?: "") }

    val initPctStart = (currentPreset?.initialSocPct ?: 15).coerceIn(0, 99)
    var initialSocPct by remember { mutableIntStateOf(initPctStart) }
    var initialSocText by remember { mutableStateOf(initPctStart.toString()) }

    val parsedMah = (capacityMahText.toIntOrNull() ?: defaultMah).coerceIn(100, 50_000)
    val parsedWh = (capacityWhText.replace(',', '.').toDoubleOrNull() ?: defaultWh).coerceIn(1.0, 500.0)
    val calculatedWh = if (useMahMode) {
        com.example.cuktech.power.EtaEstimator.whFromMah(parsedMah, cellSeriesCount)
    } else {
        parsedWh
    }
    val equivalentMah = ((parsedWh * 1000.0) / (3.85 * cellSeriesCount)).roundToInt().coerceIn(100, 50_000)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("device_preset_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = stringResource(R.string.preset_sheet_title_fmt, explicitPortName(portId)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Input mode selector: mAh (default) vs Wh
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = useMahMode,
                    onClick = {
                        if (!useMahMode) {
                            capacityMahText = equivalentMah.toString()
                            useMahMode = true
                        }
                    },
                    label = { Text(stringResource(R.string.preset_unit_mah)) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("preset_mode_mah")
                )
                FilterChip(
                    selected = !useMahMode,
                    onClick = {
                        if (useMahMode) {
                            capacityWhText = String.format(
                                Locale.US,
                                "%.1f",
                                com.example.cuktech.power.EtaEstimator.whFromMah(parsedMah, cellSeriesCount)
                            )
                            useMahMode = false
                        }
                    },
                    label = { Text(stringResource(R.string.preset_unit_wh)) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("preset_mode_wh")
                )
            }

            // Quick device category presets (populate mAh, cell count, and Wh cleanly)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val categoryPresets = listOf(
                    Triple(DevicePresetCategory.PHONE, stringResource(R.string.preset_cat_phone), 5000 to 1),
                    Triple(DevicePresetCategory.TABLET, stringResource(R.string.preset_cat_tablet), 7800 to 1),
                    Triple(DevicePresetCategory.HANDHELD, stringResource(R.string.preset_cat_handheld), 6500 to 2),
                    Triple(DevicePresetCategory.LAPTOP, stringResource(R.string.preset_cat_laptop), 6060 to 3),
                    Triple(DevicePresetCategory.CUSTOM, stringResource(R.string.preset_cat_custom), null)
                )
                categoryPresets.forEach { (cat, catLabel, defaults) ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = {
                            selectedCategory = cat
                            if (defaults != null) {
                                val (presetMah, presetCells) = defaults
                                cellSeriesCount = presetCells
                                capacityMahText = presetMah.toString()
                                capacityWhText = String.format(Locale.US, "%.1f", cat.defaultWh)
                            }
                        },
                        label = { Text(catLabel) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("preset_cat_${cat.code.lowercase()}")
                    )
                }
            }

            OutlinedTextField(
                value = labelText,
                onValueChange = { labelText = it },
                label = { Text(stringResource(R.string.preset_label_input)) },
                placeholder = { Text(stringResource(R.string.preset_label_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preset_label_input")
            )

            if (useMahMode) {
                OutlinedTextField(
                    value = capacityMahText,
                    onValueChange = { raw ->
                        capacityMahText = raw.filter { it.isDigit() }.take(5)
                        selectedCategory = DevicePresetCategory.CUSTOM
                    },
                    label = { Text(stringResource(R.string.preset_mah_input)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preset_mah_input")
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.preset_cells_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            1 to stringResource(R.string.preset_cell_1s),
                            2 to stringResource(R.string.preset_cell_2s),
                            3 to stringResource(R.string.preset_cell_3s)
                        ).forEach { (cells, label) ->
                            FilterChip(
                                selected = cellSeriesCount == cells,
                                onClick = { cellSeriesCount = cells },
                                label = { Text(label) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("preset_cell_${cells}s")
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = capacityWhText,
                    onValueChange = { raw ->
                        val cleaned = raw.filter { it.isDigit() || it == '.' || it == ',' }.take(6)
                        capacityWhText = cleaned
                        selectedCategory = DevicePresetCategory.CUSTOM
                    },
                    label = { Text(stringResource(R.string.preset_wh_input)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preset_wh_input")
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (useMahMode) {
                        stringResource(R.string.preset_computed_wh_fmt, calculatedWh)
                    } else {
                        stringResource(R.string.preset_computed_mah_fmt, equivalentMah, cellSeriesCount)
                    },
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = TelemetryMonoFontFamily,
                        fontWeight = FontWeight.SemiBold
                    ).tabularNums(),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("preset_computed_wh_text")
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.preset_init_soc_fmt, initialSocPct),
                        style = MaterialTheme.typography.labelLarge.tabularNums(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = initialSocText,
                        onValueChange = { raw ->
                            val clean = raw.filter { it.isDigit() }.take(2)
                            initialSocText = clean
                            clean.toIntOrNull()?.let { pct ->
                                initialSocPct = pct.coerceIn(0, 99)
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.preset_init_soc_input),
                                maxLines = 1
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .widthIn(min = 128.dp, max = 148.dp)
                            .testTag("preset_init_soc_input")
                    )
                }
                Slider(
                    value = initialSocPct.toFloat(),
                    onValueChange = { v ->
                        val next = v.roundToInt().coerceIn(0, 99)
                        initialSocPct = next
                        initialSocText = next.toString()
                    },
                    valueRange = 0f..99f,
                    steps = 98,
                    modifier = Modifier.testTag("preset_initial_soc_slider")
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.port_eta_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentPreset != null && onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("preset_delete_button")
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val finalMah = if (useMahMode) {
                            (capacityMahText.toIntOrNull() ?: defaultMah).coerceIn(100, 50_000)
                        } else {
                            equivalentMah
                        }
                        val finalWh = if (useMahMode) {
                            com.example.cuktech.power.EtaEstimator.whFromMah(finalMah, cellSeriesCount)
                        } else {
                            parsedWh
                        }
                        val cleanLabel = labelText.trim().ifBlank {
                            if (useMahMode) "$finalMah mAh (${cellSeriesCount}S)" else "${String.format(Locale.US, "%.1f", finalWh)} Wh"
                        }
                        val finalPct = (initialSocText.toIntOrNull() ?: initialSocPct).coerceIn(0, 99)
                        onSave(selectedCategory.code, finalMah, cellSeriesCount, cleanLabel, finalWh, finalPct)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("preset_save_button")
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/**
 * ModalBottomSheet showing real-time cable voltage sag, loop impedance estimate, and E-Marker rating.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CableDiagnosticsSheet(
    diag: PortCableDiagnostic,
    onResetStats: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semantic = LocalSemanticColors.current
    val unitV = stringResource(R.string.unit_v)
    val unitA = stringResource(R.string.unit_a)
    val unitW = stringResource(R.string.unit_w)

    val healthLabel = when (diag.healthRating) {
        CableHealthRating.IDLE -> stringResource(R.string.cable_health_idle)
        CableHealthRating.EXCELLENT -> stringResource(R.string.cable_health_excellent)
        CableHealthRating.MODERATE_SAG -> stringResource(R.string.cable_health_moderate)
        CableHealthRating.UNSTABLE -> stringResource(R.string.cable_health_unstable)
    }
    val healthBg = when (diag.healthRating) {
        CableHealthRating.IDLE -> MaterialTheme.colorScheme.surfaceContainerHighest
        CableHealthRating.EXCELLENT -> semantic.successContainer
        CableHealthRating.MODERATE_SAG -> semantic.warningContainer
        CableHealthRating.UNSTABLE -> MaterialTheme.colorScheme.errorContainer
    }
    val healthFg = when (diag.healthRating) {
        CableHealthRating.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        CableHealthRating.EXCELLENT -> semantic.success
        CableHealthRating.MODERATE_SAG -> semantic.warning
        CableHealthRating.UNSTABLE -> MaterialTheme.colorScheme.onErrorContainer
    }
    val classLabel = when (diag.currentClass) {
        CableCurrentClass.UNKNOWN -> stringResource(R.string.cable_class_unknown)
        CableCurrentClass.STANDARD_3A -> stringResource(R.string.cable_class_3a)
        CableCurrentClass.EMARKER_5A -> stringResource(R.string.cable_class_5a)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("cable_diagnostics_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    Icon(Icons.Default.Cable, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = stringResource(R.string.cable_sheet_title_fmt, explicitPortName(diag.portId)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(color = healthBg, shape = RoundedCornerShape(999.dp)) {
                    Text(
                        text = healthLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = healthFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagRow(
                        label = stringResource(R.string.cable_metric_nominal_rail),
                        value = when {
                            diag.isVariablePps -> stringResource(R.string.cable_pps_variable)
                            diag.nominalRailV != null -> "${String.format(Locale.US, "%.0f", diag.nominalRailV)} $unitV"
                            else -> "--"
                        }
                    )
                    DiagRow(
                        label = stringResource(R.string.cable_metric_measured),
                        value = "${String.format(Locale.US, "%.2f", diag.measuredVoltageV)} $unitV · ${String.format(Locale.US, "%.2f", diag.measuredCurrentA)} $unitA"
                    )
                    DiagRow(
                        label = stringResource(R.string.cable_metric_sag),
                        value = if (diag.isVariablePps || diag.nominalRailV == null) "--" else stringResource(R.string.cable_sag_mv_fmt, diag.voltageSagMv)
                    )
                    DiagRow(
                        label = stringResource(R.string.cable_metric_resistance),
                        value = diag.estimatedResistanceMohm?.let { stringResource(R.string.cable_mohm_fmt, it) } ?: "--"
                    )
                    DiagRow(
                        label = stringResource(R.string.cable_metric_peak),
                        value = "${String.format(Locale.US, "%.2f", diag.peakObservedCurrentA)} $unitA · ${String.format(Locale.US, "%.1f", diag.peakObservedWatts)} $unitW ($classLabel)"
                    )
                    DiagRow(
                        label = stringResource(R.string.cable_metric_alerts),
                        value = diag.renegotiationAlertCount.toString()
                    )
                }
            }

            Text(
                text = stringResource(R.string.cable_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = onResetStats,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("cable_reset_stats_button")
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.cable_reset_stats_btn))
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.cd_close))
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * ModalBottomSheet editor for creating or modifying a Local Automation Rule.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutomationRuleEditorSheet(
    existingRule: AutomationRuleEntity?,
    onSave: (AutomationRuleEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(existingRule?.name ?: "") }
    var triggerType by remember { mutableStateOf(existingRule?.triggerType ?: LocalAutomationEngine.TRIGGER_LOW_POWER_CUTOFF) }
    var portId by remember { mutableIntStateOf(existingRule?.portId ?: 1) }
    var thresholdText by remember {
        mutableStateOf(String.format(Locale.US, "%.1f", existingRule?.thresholdWatts ?: 1.5))
    }
    var holdSeconds by remember { mutableIntStateOf(existingRule?.holdSeconds ?: 180) }
    var hour by remember { mutableIntStateOf((existingRule?.minuteOfDay ?: (23 * 60)) / 60) }
    var minute by remember { mutableIntStateOf((existingRule?.minuteOfDay ?: (23 * 60)) % 60) }
    var daysMask by remember { mutableIntStateOf(existingRule?.daysMask ?: 0b1111111) }
    var actionType by remember { mutableStateOf(existingRule?.actionType ?: LocalAutomationEngine.ACTION_PORT_OFF) }
    var actionSceneMode by remember { mutableIntStateOf(existingRule?.actionSceneMode ?: 4) }
    var cooldownMin by remember { mutableIntStateOf(existingRule?.cooldownMinutes ?: 15) }

    val unitH = stringResource(R.string.unit_h)
    val unitM = stringResource(R.string.unit_m)
    val defaultRuleName = when (triggerType) {
        LocalAutomationEngine.TRIGGER_LOW_POWER_CUTOFF -> stringResource(R.string.automation_tpl_idle_off_name)
        LocalAutomationEngine.TRIGGER_DAILY_SCHEDULE -> stringResource(R.string.automation_tpl_night_mode_name)
        else -> stringResource(R.string.automation_tpl_laptop_burst_name)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("automation_rule_editor_sheet")
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
                text = stringResource(R.string.automation_editor_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.automation_rule_name_label)) },
                placeholder = { Text(defaultRuleName) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("automation_name_input")
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    LocalAutomationEngine.TRIGGER_LOW_POWER_CUTOFF to stringResource(R.string.automation_trig_low_power),
                    LocalAutomationEngine.TRIGGER_DAILY_SCHEDULE to stringResource(R.string.automation_trig_schedule),
                    LocalAutomationEngine.TRIGGER_HIGH_LOAD_SCENE to stringResource(R.string.automation_trig_high_load)
                ).forEach { (code, label) ->
                    FilterChip(
                        selected = triggerType == code,
                        onClick = {
                            triggerType = code
                            if (code == LocalAutomationEngine.TRIGGER_HIGH_LOAD_SCENE) {
                                thresholdText = "65.0"
                                actionType = LocalAutomationEngine.ACTION_SET_SCENE_MODE
                                holdSeconds = 15
                            } else if (code == LocalAutomationEngine.TRIGGER_LOW_POWER_CUTOFF) {
                                thresholdText = "1.5"
                                actionType = LocalAutomationEngine.ACTION_PORT_OFF
                                holdSeconds = 180
                            } else {
                                actionType = LocalAutomationEngine.ACTION_SET_SCENE_MODE
                            }
                        },
                        label = { Text(label) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            if (triggerType != LocalAutomationEngine.TRIGGER_DAILY_SCHEDULE) {
                Text(stringResource(R.string.automation_port_label), style = MaterialTheme.typography.labelLarge)
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

                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { thresholdText = it },
                    label = { Text(stringResource(R.string.automation_threshold_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.automation_hold_label_fmt, holdSeconds),
                    style = MaterialTheme.typography.labelLarge.tabularNums()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 30, 60, 180, 300).forEach { sec ->
                        FilterChip(
                            selected = holdSeconds == sec,
                            onClick = { holdSeconds = sec },
                            label = { Text("$sec ${stringResource(R.string.unit_s)}", style = MaterialTheme.typography.labelMedium.tabularNums()) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.automation_time_label_fmt, hour, minute),
                    style = MaterialTheme.typography.labelLarge.tabularNums()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmoothWheelPicker(
                        values = (0..23).toList(),
                        selectedValue = hour,
                        onValueSelected = { hour = it },
                        formatValue = { "%02d$unitH".format(it) },
                        modifier = Modifier.weight(1f)
                    )
                    SmoothWheelPicker(
                        values = (0..59 step 5).toList(),
                        selectedValue = (minute / 5) * 5,
                        onValueSelected = { minute = it },
                        formatValue = { "%02d$unitM".format(it) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(stringResource(R.string.automation_days_label), style = MaterialTheme.typography.labelLarge)
                val dayLabels = listOf(
                    0 to stringResource(R.string.weekday_mon),
                    1 to stringResource(R.string.weekday_tue),
                    2 to stringResource(R.string.weekday_wed),
                    3 to stringResource(R.string.weekday_thu),
                    4 to stringResource(R.string.weekday_fri),
                    5 to stringResource(R.string.weekday_sat),
                    6 to stringResource(R.string.weekday_sun)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayLabels.forEach { (bit, label) ->
                        val active = (daysMask and (1 shl bit)) != 0
                        FilterChip(
                            selected = active,
                            onClick = {
                                val next = daysMask xor (1 shl bit)
                                if (next != 0) daysMask = next
                            },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = actionType == LocalAutomationEngine.ACTION_PORT_OFF,
                    onClick = { actionType = LocalAutomationEngine.ACTION_PORT_OFF },
                    label = { Text(stringResource(R.string.automation_act_port_off)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = actionType == LocalAutomationEngine.ACTION_SET_SCENE_MODE,
                    onClick = { actionType = LocalAutomationEngine.ACTION_SET_SCENE_MODE },
                    label = { Text(stringResource(R.string.automation_act_set_scene)) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }

            if (actionType == LocalAutomationEngine.ACTION_PORT_OFF && triggerType == LocalAutomationEngine.TRIGGER_DAILY_SCHEDULE) {
                Text(stringResource(R.string.automation_port_label), style = MaterialTheme.typography.labelMedium)
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
            } else if (actionType == LocalAutomationEngine.ACTION_SET_SCENE_MODE) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..4).forEach { mode ->
                        FilterChip(
                            selected = actionSceneMode == mode,
                            onClick = { actionSceneMode = mode },
                            label = { Text(localizedScenarioName(mode)) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.automation_cooldown_label_fmt, cooldownMin),
                style = MaterialTheme.typography.labelLarge.tabularNums()
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 5, 15, 60).forEach { mins ->
                    FilterChip(
                        selected = cooldownMin == mins,
                        onClick = { cooldownMin = mins },
                        label = { Text("$mins $unitM", style = MaterialTheme.typography.labelMedium.tabularNums()) },
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
                Button(
                    onClick = {
                        val thresh = thresholdText.replace(",", ".").toDoubleOrNull()?.coerceIn(0.1, 150.0) ?: 1.5
                        onSave(
                            AutomationRuleEntity(
                                id = existingRule?.id ?: 0L,
                                name = name.trim().ifBlank { defaultRuleName },
                                enabled = existingRule?.enabled ?: true,
                                triggerType = triggerType,
                                portId = portId.coerceIn(1, 4),
                                thresholdWatts = thresh,
                                holdSeconds = holdSeconds.coerceIn(3, 3600),
                                minuteOfDay = (hour * 60 + minute).coerceIn(0, 1439),
                                daysMask = daysMask.coerceIn(1, 0b1111111),
                                actionType = actionType,
                                actionSceneMode = actionSceneMode.coerceIn(1, 4),
                                lastTriggeredMs = existingRule?.lastTriggeredMs ?: 0L,
                                cooldownMinutes = cooldownMin.coerceIn(1, 1440),
                                createdAtMs = existingRule?.createdAtMs ?: System.currentTimeMillis()
                            )
                        )
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("automation_save_button")
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
