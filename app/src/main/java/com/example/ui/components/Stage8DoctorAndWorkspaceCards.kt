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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Workspaces
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.power.CustomKpiAndWorkspaceEngine
import com.example.cuktech.power.EvaluatedCustomKpi
import com.example.cuktech.power.KpiFormulaType
import com.example.cuktech.power.PowerDoctorGrade
import com.example.cuktech.power.PowerDoctorReport
import com.example.cuktech.power.WorkspacePreset
import com.example.cuktech.security.ShareableConfigBundle
import com.example.cuktech.security.ShareableConfigImportSelection
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums

@Composable
private fun doctorKeyLabel(key: String): String = when (key) {
    "doctor_cat_link" -> stringResource(R.string.doctor_cat_link)
    "doctor_cat_cable" -> stringResource(R.string.doctor_cat_cable)
    "doctor_cat_stability" -> stringResource(R.string.doctor_cat_stability)
    "doctor_cat_relay" -> stringResource(R.string.doctor_cat_relay)
    "doctor_find_blind_gaps" -> stringResource(R.string.doctor_find_blind_gaps)
    "doctor_find_weak_rssi" -> stringResource(R.string.doctor_find_weak_rssi)
    "doctor_find_cable_sag" -> stringResource(R.string.doctor_find_cable_sag)
    "doctor_find_cable_mohm" -> stringResource(R.string.doctor_find_cable_mohm)
    "doctor_find_anomalies" -> stringResource(R.string.doctor_find_anomalies)
    "doctor_find_cmd_rejects" -> stringResource(R.string.doctor_find_cmd_rejects)
    "doctor_find_nominal" -> stringResource(R.string.doctor_find_nominal)
    "doctor_act_bridge" -> stringResource(R.string.doctor_act_bridge)
    "doctor_act_closer" -> stringResource(R.string.doctor_act_closer)
    "doctor_act_swap_cable" -> stringResource(R.string.doctor_act_swap_cable)
    "doctor_act_cable_lab" -> stringResource(R.string.doctor_act_cable_lab)
    "doctor_act_inspect_blackbox" -> stringResource(R.string.doctor_act_inspect_blackbox)
    "doctor_act_check_lockout" -> stringResource(R.string.doctor_act_check_lockout)
    else -> stringResource(R.string.doctor_act_none)
}

@Composable
fun PowerDoctorCard(
    report: PowerDoctorReport,
    onBuildSupportBundle: () -> String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val semantic = LocalSemanticColors.current
    val gradeColor = when (report.grade) {
        PowerDoctorGrade.EXCELLENT -> semantic.success
        PowerDoctorGrade.GOOD -> MaterialTheme.colorScheme.primary
        PowerDoctorGrade.ATTENTION -> MaterialTheme.colorScheme.tertiary
        PowerDoctorGrade.CRITICAL -> MaterialTheme.colorScheme.error
    }
    val gradeLabel = when (report.grade) {
        PowerDoctorGrade.EXCELLENT -> stringResource(R.string.doctor_grade_excellent)
        PowerDoctorGrade.GOOD -> stringResource(R.string.doctor_grade_good)
        PowerDoctorGrade.ATTENTION -> stringResource(R.string.doctor_grade_attention)
        PowerDoctorGrade.CRITICAL -> stringResource(R.string.doctor_grade_critical)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("power_doctor_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = gradeColor)
                    Text(
                        text = stringResource(R.string.doctor_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    color = gradeColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.doctor_score_fmt, report.overallScore, gradeLabel),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = TelemetryMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ).tabularNums(),
                        color = gradeColor,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("power_doctor_score_badge")
                    )
                }
            }

            LinearProgressIndicator(
                progress = { (report.overallScore / 100f).coerceIn(0f, 1f) },
                color = gradeColor,
                modifier = Modifier.fillMaxWidth()
            )

            report.categories.forEach { cat ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${doctorKeyLabel(cat.titleKey)} (${cat.weightPct}%)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${cat.score}/100 · ${cat.detailText}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (cat.score / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            report.findings.forEach { finding ->
                val badgeColor = when (finding.severity) {
                    "CRITICAL" -> MaterialTheme.colorScheme.error
                    "WARN" -> MaterialTheme.colorScheme.tertiary
                    else -> semantic.success
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = doctorKeyLabel(finding.titleKey),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeColor,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = finding.metricDetail,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = doctorKeyLabel(finding.actionKey),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            FilledTonalButton(
                onClick = {
                    val bundle = onBuildSupportBundle()
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText("CuktechSupportBundle", bundle))
                    Toast.makeText(context, context.getString(R.string.toast_support_bundle_copied), Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("doctor_copy_support_bundle_btn")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.doctor_copy_support_bundle_btn),
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun kpiFormulaLabel(formulaType: KpiFormulaType): String = when (formulaType) {
    KpiFormulaType.ACTIVE_LOAD_PCT -> stringResource(R.string.kpi_formula_active_load_pct)
    KpiFormulaType.HOURLY_COST_RATE -> stringResource(R.string.kpi_formula_hourly_cost)
    KpiFormulaType.AVERAGE_SESSION_WH -> stringResource(R.string.kpi_formula_avg_session_wh)
    KpiFormulaType.PEAK_HEADROOM_W -> stringResource(R.string.kpi_formula_peak_headroom_w)
    KpiFormulaType.MULTI_PORT_SHARE_PCT -> stringResource(R.string.kpi_formula_multi_port_share)
    KpiFormulaType.DAILY_TARGET_PROGRESS -> stringResource(R.string.kpi_formula_daily_target)
}

@Composable
private fun kpiErrorText(errKey: String): String = when (errKey) {
    "kpi_err_title_empty" -> stringResource(R.string.kpi_err_title_empty)
    "kpi_err_unit_empty" -> stringResource(R.string.kpi_err_unit_empty)
    "kpi_err_percent_required" -> stringResource(R.string.kpi_err_percent_required)
    "kpi_err_watts_required" -> stringResource(R.string.kpi_err_watts_required)
    "kpi_err_wh_required" -> stringResource(R.string.kpi_err_wh_required)
    "kpi_err_cost_rate_required" -> stringResource(R.string.kpi_err_cost_rate_required)
    else -> errKey
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceAndCustomKpisCard(
    activeWorkspaceId: String,
    kpis: List<EvaluatedCustomKpi>,
    currencyCode: String,
    onSelectWorkspace: (String) -> Unit,
    onAddKpi: (title: String, formula: KpiFormulaType, unit: String, target: Double) -> String?,
    onDeleteKpi: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddSheet by remember { mutableStateOf(false) }

    if (showAddSheet) {
        AddCustomKpiSheet(
            currencyCode = currencyCode,
            onSave = { title, formula, unit, target ->
                val err = onAddKpi(title, formula, unit, target)
                if (err == null) showAddSheet = false
                err
            },
            onDismiss = { showAddSheet = false }
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("workspace_kpi_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Workspaces, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.workspace_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(
                    onClick = { showAddSheet = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("kpi_add_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.kpi_add_btn),
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    WorkspacePreset.ALL to R.string.workspace_all,
                    WorkspacePreset.HOME to R.string.workspace_home,
                    WorkspacePreset.OFFICE to R.string.workspace_office,
                    WorkspacePreset.TRAVEL to R.string.workspace_travel,
                    WorkspacePreset.LAB to R.string.workspace_lab
                ).forEach { (ws, labelRes) ->
                    FilterChip(
                        selected = activeWorkspaceId.equals(ws.id, ignoreCase = true),
                        onClick = { onSelectWorkspace(ws.id) },
                        label = { Text(stringResource(labelRes)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("workspace_chip_${ws.id.lowercase()}")
                    )
                }
            }

            if (kpis.isEmpty()) {
                Text(
                    text = stringResource(R.string.kpi_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.kpi_section_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                kpis.forEach { kpi ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_kpi_item_${kpi.definition.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(kpi.definition.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = kpiFormulaLabel(kpi.definition.formulaType),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = kpi.formattedValue,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = TelemetryMonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ).tabularNums(),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    onClick = { onDeleteKpi(kpi.definition.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("kpi_delete_${kpi.definition.id}")
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = stringResource(R.string.action_clear),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            kpi.progressRatio?.let { ratio ->
                                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddCustomKpiSheet(
    currencyCode: String,
    onSave: (title: String, formula: KpiFormulaType, unit: String, target: Double) -> String?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var titleDraft by remember { mutableStateOf("") }
    var formulaDraft by remember { mutableStateOf(KpiFormulaType.ACTIVE_LOAD_PCT) }
    var unitDraft by remember { mutableStateOf(CustomKpiAndWorkspaceEngine.defaultUnitForFormula(KpiFormulaType.ACTIVE_LOAD_PCT, currencyCode)) }
    var targetDraft by remember { mutableStateOf("100") }
    var errorKey by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("add_custom_kpi_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.kpi_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = titleDraft,
                onValueChange = { titleDraft = it; errorKey = null },
                label = { Text(stringResource(R.string.kpi_title_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("kpi_title_input")
            )
            Text(stringResource(R.string.kpi_formula_label), style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                KpiFormulaType.entries.forEach { f ->
                    FilterChip(
                        selected = formulaDraft == f,
                        onClick = {
                            formulaDraft = f
                            unitDraft = CustomKpiAndWorkspaceEngine.defaultUnitForFormula(f, currencyCode)
                            errorKey = null
                        },
                        label = { Text(kpiFormulaLabel(f), maxLines = 1) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("kpi_formula_chip_${f.name.lowercase()}")
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = unitDraft,
                    onValueChange = {
                        unitDraft = it
                        errorKey = CustomKpiAndWorkspaceEngine.validateUnitCompatibility(formulaDraft, it)
                    },
                    label = { Text(stringResource(R.string.kpi_unit_label)) },
                    isError = errorKey != null,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("kpi_unit_input")
                )
                OutlinedTextField(
                    value = targetDraft,
                    onValueChange = { targetDraft = it },
                    label = { Text(stringResource(R.string.kpi_target_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            errorKey?.let { key ->
                Text(
                    text = kpiErrorText(key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("kpi_unit_error_text")
                )
            }
            Button(
                onClick = {
                    val target = targetDraft.replace(',', '.').toDoubleOrNull() ?: 100.0
                    errorKey = onSave(titleDraft, formulaDraft, unitDraft, target)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("kpi_save_btn")
            ) {
                Text(stringResource(R.string.kpi_save_btn))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigShareAndImportSheet(
    onBuildConfigJson: () -> String,
    onPreviewConfig: (String) -> Result<ShareableConfigBundle>,
    onImportConfig: (ShareableConfigBundle, ShareableConfigImportSelection, (Int) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rawJsonDraft by remember { mutableStateOf("") }
    var previewBundle by remember { mutableStateOf<ShareableConfigBundle?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(ShareableConfigImportSelection()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("config_share_sheet")
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
                Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.config_share_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                text = stringResource(R.string.config_share_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FilledTonalButton(
                onClick = {
                    val json = onBuildConfigJson()
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText("CuktechConfig", json))
                    Toast.makeText(context, context.getString(R.string.toast_config_share_copied), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("config_share_copy_btn")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.config_share_copy_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            OutlinedTextField(
                value = rawJsonDraft,
                onValueChange = { rawJsonDraft = it; previewError = null },
                label = { Text(stringResource(R.string.config_share_paste_label)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("config_share_paste_input")
            )

            OutlinedButton(
                onClick = {
                    val res = onPreviewConfig(rawJsonDraft)
                    res.onSuccess {
                        previewBundle = it
                        previewError = null
                    }.onFailure { err ->
                        previewBundle = null
                        previewError = err.message ?: "Invalid JSON"
                    }
                },
                enabled = rawJsonDraft.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("config_share_preview_btn")
            ) {
                Text(stringResource(R.string.config_share_preview_btn))
            }

            previewError?.let { err ->
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            previewBundle?.let { bundle ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(
                                R.string.config_share_preview_fmt,
                                bundle.profiles.size,
                                bundle.rules.size,
                                bundle.presets.size,
                                bundle.kpis.size
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily).tabularNums(),
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                            Checkbox(checked = selection.includePreferences, onCheckedChange = { selection = selection.copy(includePreferences = it) })
                            Text(stringResource(R.string.config_share_inc_prefs), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                            Checkbox(checked = selection.includeProfiles, onCheckedChange = { selection = selection.copy(includeProfiles = it) })
                            Text(stringResource(R.string.config_share_inc_profiles), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                            Checkbox(checked = selection.includeRules, onCheckedChange = { selection = selection.copy(includeRules = it) })
                            Text(stringResource(R.string.config_share_inc_rules), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                            Checkbox(checked = selection.includePresets, onCheckedChange = { selection = selection.copy(includePresets = it) })
                            Text(stringResource(R.string.config_share_inc_presets), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                            Checkbox(checked = selection.includeKpis, onCheckedChange = { selection = selection.copy(includeKpis = it) })
                            Text(stringResource(R.string.config_share_inc_kpis), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Button(
                    onClick = {
                        onImportConfig(bundle, selection) { count ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_config_imported_fmt, count),
                                Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("config_share_import_btn")
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.config_share_import_btn))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
