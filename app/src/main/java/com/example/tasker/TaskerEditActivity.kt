package com.example.tasker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BleBridgeService
import com.example.R
import com.example.ui.i18n.LocalizedAppContent
import com.example.ui.theme.MyApplicationTheme

class TaskerEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val mgr = BleBridgeService.ensureCuktechManagersInitialized(applicationContext)
        val authMgr = mgr.authManager
        val isTaskerOptInEnabled = authMgr.config.value.taskerEnabled
        val installToken = authMgr.getOrCreateToken()

        // Read previously saved bundle if editing existing Tasker action
        val existingBundle = intent.getBundleExtra("com.twofortyfouram.locale.intent.extra.BUNDLE")
        val initialAction = existingBundle?.getString("ACTION") ?: "CONNECT"
        val initialPort = existingBundle?.getInt("PORT", 1) ?: 1
        val initialState = existingBundle?.getBoolean("STATE", true) ?: true
        val initialMode = existingBundle?.getInt("MODE", 1) ?: 1
        val initialTimeout = existingBundle?.getInt("TIMEOUT", 4) ?: 4
        val initialTimerMins = existingBundle?.getInt("TIMER_MINS", 60) ?: 60
        val initialWhTarget = existingBundle?.getDouble("WH_TARGET", 20.0) ?: 20.0
        val initialLimitMode = existingBundle?.getString("LIMIT_MODE") ?: "once"

        setContent {
            val prefs by mgr.preferencesRepository.preferences.collectAsStateWithLifecycle()
            LocalizedAppContent(appLanguage = prefs.appLanguage) {
                MyApplicationTheme(
                    themeMode = prefs.themeMode,
                    useDynamicColor = prefs.useDynamicColor,
                    useAmoledBlack = prefs.darkPalette == "AMOLED_BLACK"
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                    TaskerEditScreen(
                        isTaskerOptInEnabled = isTaskerOptInEnabled,
                        initialAction = initialAction,
                        initialPort = initialPort,
                        initialState = initialState,
                        initialMode = initialMode,
                        initialTimeout = initialTimeout,
                        initialTimerMins = initialTimerMins,
                        initialWhTarget = initialWhTarget,
                        initialLimitMode = initialLimitMode,
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        onSave = { action, port, state, mode, timeout, timerMins, whTarget, limitMode, blurb ->
                            val resultBundle = Bundle().apply {
                                putString("ACTION", action)
                                putString("AUTH_TOKEN", installToken)
                                putInt("PORT", port)
                                putBoolean("STATE", state)
                                putInt("MODE", mode)
                                putInt("TIMEOUT", timeout)
                                putInt("TIMER_MINS", timerMins)
                                putDouble("WH_TARGET", whTarget)
                                putString("LIMIT_MODE", limitMode)
                            }
                            val resultIntent = Intent().apply {
                                putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", resultBundle)
                                putExtra("com.twofortyfouram.locale.intent.extra.BLURB", blurb)
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        }
                    )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskerEditScreen(
    isTaskerOptInEnabled: Boolean = true,
    initialAction: String,
    initialPort: Int,
    initialState: Boolean,
    initialMode: Int,
    initialTimeout: Int,
    initialTimerMins: Int,
    initialWhTarget: Double = 20.0,
    initialLimitMode: String = "once",
    onCancel: () -> Unit,
    onSave: (action: String, port: Int, state: Boolean, mode: Int, timeout: Int, timerMins: Int, whTarget: Double, limitMode: String, blurb: String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedAction by remember { mutableStateOf(initialAction) }
    var selectedPort by remember { mutableIntStateOf(initialPort) }
    var selectedPortState by remember { mutableStateOf(initialState) }
    var selectedMode by remember { mutableIntStateOf(initialMode) }
    var selectedTimeout by remember { mutableIntStateOf(initialTimeout) }
    var selectedTimerMins by remember { mutableIntStateOf(initialTimerMins) }
    var selectedWhText by remember { mutableStateOf(initialWhTarget.toString()) }
    var selectedLimitMode by remember { mutableStateOf(initialLimitMode) }

    val portNames = listOf(
        1 to stringResource(R.string.port_name_c1),
        2 to stringResource(R.string.port_name_c2),
        3 to stringResource(R.string.port_name_c3),
        4 to stringResource(R.string.port_name_a)
    )
    val modeNames = mapOf(
        1 to stringResource(R.string.scenario_ai),
        2 to stringResource(R.string.scenario_hybrid),
        3 to stringResource(R.string.scenario_single),
        4 to stringResource(R.string.scenario_dual)
    )
    val timeoutNames = mapOf(
        4 to stringResource(R.string.timeout_always),
        5 to stringResource(R.string.timeout_1m),
        1 to stringResource(R.string.timeout_5m),
        2 to stringResource(R.string.timeout_10m),
        3 to stringResource(R.string.timeout_30m)
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasker_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val portLabel = portNames.firstOrNull { it.first == selectedPort }?.second ?: "USB-$selectedPort"
                        val parsedWh = selectedWhText.replace(",", ".").toDoubleOrNull()?.coerceIn(0.0, 10000.0) ?: 20.0
                        val unitWh = context.getString(R.string.unit_wh)
                        val onLbl = context.getString(R.string.tasker_power_on)
                        val offLbl = context.getString(R.string.tasker_power_off)
                        val blurb = when (selectedAction) {
                            "CONNECT" -> "Cuktech: ${context.getString(R.string.tasker_act_connect)}"
                            "DISCONNECT" -> "Cuktech: ${context.getString(R.string.tasker_act_disconnect)}"
                            "SET_PORT" -> "Cuktech: $portLabel ${if (selectedPortState) onLbl else offLbl}"
                            "SET_MODE" -> "Cuktech: ${modeNames[selectedMode] ?: context.getString(R.string.scenario_ai)}"
                            "SET_TIMEOUT" -> "Cuktech: ${context.getString(R.string.tasker_timeout_title)} (${timeoutNames[selectedTimeout] ?: context.getString(R.string.timeout_always)})"
                            "SET_LOW_CURRENT" -> "Cuktech: ${context.getString(R.string.tasker_low_current_title)} (${if (selectedPortState) onLbl else offLbl})"
                            "SET_PORT_TIMER" -> {
                                val tLabel = if (selectedTimerMins == 0) context.getString(R.string.timeout_always) else "${selectedTimerMins}${context.getString(R.string.unit_m)}"
                                "Cuktech: $portLabel ($tLabel)"
                            }
                            "SET_CHARGE_LIMIT" -> "Cuktech: $portLabel ${if (selectedPortState) "${parsedWh}$unitWh ($selectedLimitMode)" else offLbl}"
                            "SOFT_RESET" -> "Cuktech: ${context.getString(R.string.tasker_soft_reset_title)}"
                            "MASTER_KILLSWITCH" -> "Cuktech: ${context.getString(R.string.tasker_act_killswitch)}"
                            "UNDO_LAST_COMMAND" -> "Cuktech: ${context.getString(R.string.tasker_act_undo)}"
                            "SET_IDLE_SCREEN_OFF" -> "Cuktech: ${context.getString(R.string.settings_idle_screen_off)} (${if (selectedPortState) onLbl else offLbl})"
                            "SET_AUTO_CONNECT" -> "Cuktech: ${context.getString(R.string.settings_auto_reconnect)} (${if (selectedPortState) onLbl else offLbl})"
                            "SET_PORT_LOGGING" -> "Cuktech: $portLabel ${context.getString(R.string.tasker_logging_title)} (${if (selectedPortState) onLbl else offLbl})"
                            "SET_TELEMETRY" -> "Cuktech: ${context.getString(R.string.settings_master_telemetry)} (${if (selectedPortState) onLbl else offLbl})"
                            "SET_LOG_WHEN_CHARGING" -> "Cuktech: ${context.getString(R.string.settings_log_phone_charging)} (${if (selectedPortState) onLbl else offLbl})"
                            "SET_PAUSE_WHEN_LOCKED" -> "Cuktech: ${context.getString(R.string.settings_pause_screen_locked)} (${if (selectedPortState) onLbl else offLbl})"
                            else -> "Cuktech"
                        }
                        onSave(
                            selectedAction,
                            selectedPort,
                            selectedPortState,
                            selectedMode,
                            selectedTimeout,
                            selectedTimerMins,
                            parsedWh,
                            selectedLimitMode,
                            blurb
                        )
                    }) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_save), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isTaskerOptInEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.tasker_disabled_warning),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Text(stringResource(R.string.tasker_select_action), fontWeight = FontWeight.Bold, fontSize = 16.sp)

            val actions = listOf(
                "CONNECT" to stringResource(R.string.tasker_act_connect),
                "DISCONNECT" to stringResource(R.string.tasker_act_disconnect),
                "SET_AUTO_CONNECT" to stringResource(R.string.tasker_act_auto_connect),
                "SET_PORT" to stringResource(R.string.tasker_act_set_port),
                "SET_MODE" to stringResource(R.string.tasker_act_set_mode),
                "SET_TIMEOUT" to stringResource(R.string.tasker_act_set_timeout),
                "SET_IDLE_SCREEN_OFF" to stringResource(R.string.tasker_act_idle_off),
                "SET_LOW_CURRENT" to stringResource(R.string.tasker_act_low_current),
                "SET_PORT_TIMER" to stringResource(R.string.tasker_act_port_timer),
                "SET_CHARGE_LIMIT" to stringResource(R.string.tasker_act_charge_limit),
                "SOFT_RESET" to stringResource(R.string.tasker_act_soft_reset),
                "MASTER_KILLSWITCH" to stringResource(R.string.tasker_act_killswitch),
                "UNDO_LAST_COMMAND" to stringResource(R.string.tasker_act_undo),
                "SET_PORT_LOGGING" to stringResource(R.string.tasker_act_port_logging),
                "SET_TELEMETRY" to stringResource(R.string.tasker_act_telemetry),
                "SET_LOG_WHEN_CHARGING" to stringResource(R.string.tasker_act_log_charging),
                "SET_PAUSE_WHEN_LOCKED" to stringResource(R.string.tasker_act_pause_locked)
            )

            actions.forEach { (key, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedAction = key
                        }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (selectedAction == key),
                        onClick = { selectedAction = key }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, fontSize = 14.sp)
                }
            }

            HorizontalDivider()

            if (selectedAction == "SET_PORT") {
                Text(stringResource(R.string.tasker_select_port), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    portNames.forEach { (pId, name) ->
                        FilterChip(
                            selected = (selectedPort == pId),
                            onClick = { selectedPort = pId },
                            label = { Text(name) }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.tasker_port_state), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text(stringResource(R.string.tasker_power_on)) }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text(stringResource(R.string.tasker_power_off)) }
                    )
                }
            }

            if (selectedAction == "SET_MODE") {
                Text(stringResource(R.string.tasker_scenario_title), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val modes = listOf(
                    1 to (stringResource(R.string.scenario_ai) to stringResource(R.string.scenario_ai_desc)),
                    2 to (stringResource(R.string.scenario_hybrid) to stringResource(R.string.scenario_hybrid_desc)),
                    3 to (stringResource(R.string.scenario_single) to stringResource(R.string.scenario_single_desc)),
                    4 to (stringResource(R.string.scenario_dual) to stringResource(R.string.scenario_dual_desc))
                )
                modes.forEach { (mId, pair) ->
                    val (title, subtitle) = pair
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedMode = mId }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (selectedMode == mId), onClick = { selectedMode = mId })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (selectedAction == "SET_TIMEOUT") {
                Text(stringResource(R.string.tasker_timeout_title), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val timeouts = listOf(
                    4 to stringResource(R.string.timeout_always),
                    5 to stringResource(R.string.timeout_1m),
                    1 to stringResource(R.string.timeout_5m),
                    2 to stringResource(R.string.timeout_10m),
                    3 to stringResource(R.string.timeout_30m)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    timeouts.forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedTimeout = code }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (selectedTimeout == code), onClick = { selectedTimeout = code })
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            }

            if (selectedAction == "SET_IDLE_SCREEN_OFF") {
                Text(stringResource(R.string.settings_idle_screen_off), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    stringResource(R.string.tasker_idle_off_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text(stringResource(R.string.action_enable)) }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text(stringResource(R.string.action_disable)) }
                    )
                }
            }

            if (selectedAction == "SET_LOW_CURRENT") {
                Text(stringResource(R.string.tasker_low_current_title), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    stringResource(R.string.port_usba_low_current_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text(stringResource(R.string.tasker_low_current_enable)) }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text(stringResource(R.string.action_disable)) }
                    )
                }
            }

            if (selectedAction == "SET_PORT_TIMER") {
                Text(stringResource(R.string.tasker_select_port), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    portNames.forEach { (pId, name) ->
                        FilterChip(
                            selected = (selectedPort == pId),
                            onClick = { selectedPort = pId },
                            label = { Text(name) }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.tasker_timer_duration_title), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val presets = listOf(
                    0 to stringResource(R.string.tasker_timer_preset_always),
                    15 to stringResource(R.string.tasker_timer_preset_15m),
                    30 to stringResource(R.string.tasker_timer_preset_30m),
                    60 to stringResource(R.string.tasker_timer_preset_1h),
                    120 to stringResource(R.string.tasker_timer_preset_2h),
                    240 to stringResource(R.string.tasker_timer_preset_4h)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { (mins, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedTimerMins = mins }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (selectedTimerMins == mins), onClick = { selectedTimerMins = mins })
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            }

            if (selectedAction == "SOFT_RESET") {
                Text(stringResource(R.string.tasker_soft_reset_title), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    stringResource(R.string.dialog_soft_reset_body),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (selectedAction == "SET_AUTO_CONNECT") {
                Text(stringResource(R.string.settings_auto_reconnect), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    stringResource(R.string.tasker_auto_connect_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text(stringResource(R.string.action_enable)) }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text(stringResource(R.string.action_disable)) }
                    )
                }
            }

            if (selectedAction == "SET_PORT_LOGGING") {
                Text(stringResource(R.string.tasker_select_port), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    portNames.forEach { (pId, name) ->
                        FilterChip(
                            selected = (selectedPort == pId),
                            onClick = { selectedPort = pId },
                            label = { Text(name) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.tasker_logging_title), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text(stringResource(R.string.tasker_logging_on)) }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text(stringResource(R.string.tasker_logging_off)) }
                    )
                }
            }

            if (selectedAction == "SET_TELEMETRY") {
                Text(stringResource(R.string.settings_master_telemetry), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    stringResource(R.string.tasker_telemetry_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text(stringResource(R.string.tasker_telemetry_on)) }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text(stringResource(R.string.tasker_telemetry_pause)) }
                    )
                }
            }

            if (selectedAction == "SET_LOG_WHEN_CHARGING") {
                Text(stringResource(R.string.settings_log_phone_charging), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    stringResource(R.string.tasker_log_charging_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text(stringResource(R.string.action_enable)) }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text(stringResource(R.string.action_disable)) }
                    )
                }
            }

            if (selectedAction == "SET_CHARGE_LIMIT") {
                Text(stringResource(R.string.tasker_select_port), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    portNames.forEach { (pId, name) ->
                        FilterChip(
                            selected = (selectedPort == pId),
                            onClick = { selectedPort = pId },
                            label = { Text(name) }
                        )
                    }
                }
                OutlinedTextField(
                    value = selectedWhText,
                    onValueChange = { selectedWhText = it },
                    label = { Text(stringResource(R.string.tasker_limit_target_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedLimitMode == "once",
                        onClick = { selectedLimitMode = "once" },
                        label = { Text(stringResource(R.string.action_once)) }
                    )
                    FilterChip(
                        selected = selectedLimitMode == "always",
                        onClick = { selectedLimitMode = "always" },
                        label = { Text(stringResource(R.string.action_always)) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text(stringResource(R.string.tasker_limit_enable)) }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text(stringResource(R.string.tasker_limit_disable)) }
                    )
                }
            }

            if (selectedAction == "SET_PAUSE_WHEN_LOCKED") {
                Text(stringResource(R.string.settings_pause_screen_locked), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    stringResource(R.string.tasker_pause_locked_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = selectedPortState,
                        onClick = { selectedPortState = true },
                        label = { Text(stringResource(R.string.tasker_pause_locked_on)) }
                    )
                    FilterChip(
                        selected = !selectedPortState,
                        onClick = { selectedPortState = false },
                        label = { Text(stringResource(R.string.tasker_pause_locked_off)) }
                    )
                }
            }
        }
    }
}
