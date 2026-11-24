package com.example.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BleBridgeService
import com.example.BuildConfig
import com.example.R
import com.example.cuktech.automation.LocalAutomationEngine
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.notifications.ChargingNotificationManager
import com.example.cuktech.prefs.StartDestination
import com.example.cuktech.protocol.CuktechProtocol
import com.example.ui.components.AutomationRuleEditorSheet
import com.example.ui.components.EncryptedBackupExportDialog
import com.example.ui.components.EncryptedBackupRestoreDialog
import com.example.ui.components.GitHubUpdateDialog
import com.example.ui.components.MiHomeSwitchDialog
import com.example.ui.components.PortManualBottomSheet
import com.example.ui.components.SoftResetConfirmDialog
import com.example.ui.components.explicitPortName
import com.example.ui.components.formatMinuteOfDay
import com.example.ui.components.localizedScenarioName
import com.example.ui.components.localizedTimeoutName
import com.example.ui.theme.ExpressiveMotion
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.DashboardViewModel
import com.example.widget.CuktechGlanceCompactReceiver
import com.example.widget.CuktechGlanceControlReceiver
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsHubScreen(
    viewModel: DashboardViewModel,
    onOpenXiaomiLogin: () -> Unit,
    onOpenSearchPair: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRequestStartTcpServer: (Int) -> Unit,
    onRequestStopTcpServer: () -> Unit,
    onDisconnectForMiHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val accountSession by viewModel.accountSession.collectAsStateWithLifecycle()
    val savedDevices by viewModel.savedDevices.collectAsStateWithLifecycle()
    val activeCred by viewModel.activeCred.collectAsStateWithLifecycle()
    val keystoreHealthy by viewModel.keystoreHealthy.collectAsStateWithLifecycle()
    val keystoreError by viewModel.keystoreError.collectAsStateWithLifecycle()

    val telemetryMaster by viewModel.telemetryMasterEnabled.collectAsStateWithLifecycle()
    val logPhoneCharging by viewModel.logOnlyWhenPhoneCharging.collectAsStateWithLifecycle()
    val pauseLocked by viewModel.pausePollingWhenScreenLocked.collectAsStateWithLifecycle()
    val autoReconnect by viewModel.isAutoReconnectConfigured.collectAsStateWithLifecycle()

    val automationConfig by viewModel.automationConfig.collectAsStateWithLifecycle()
    val revealedToken by viewModel.revealedToken.collectAsStateWithLifecycle()
    val haConfig by viewModel.haConfig.collectAsStateWithLifecycle()
    val haError by viewModel.haLastError.collectAsStateWithLifecycle()
    val isServerRunning by viewModel.isServerRunning.collectAsStateWithLifecycle()
    val serverPort by viewModel.serverPort.collectAsStateWithLifecycle()
    val wifiIp by viewModel.wifiIp.collectAsStateWithLifecycle()
    val connectedClients by viewModel.connectedClients.collectAsStateWithLifecycle()
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
    val releaseUpdateInfo by viewModel.releaseUpdateInfo.collectAsStateWithLifecycle()
    val updateDownloadState by viewModel.updateDownloadState.collectAsStateWithLifecycle()
    val automationRules by viewModel.automationRules.collectAsStateWithLifecycle()

    val isConnected = authState == CuktechAuthState.AUTHENTICATED

    var showSoftResetDialog by remember { mutableStateOf(false) }
    var showMiHomeDialog by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showBackupExportDialog by remember { mutableStateOf(false) }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var showRuleEditor by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AutomationRuleEntity?>(null) }
    var showXiaomiDevicesSpoiler by remember { mutableStateOf(false) }
    var showPortManualSheet by remember { mutableStateOf(false) }

    var pendingExportPass by remember { mutableStateOf<CharArray?>(null) }
    var pendingExportSecrets by remember { mutableStateOf(false) }
    var pendingRestorePass by remember { mutableStateOf<CharArray?>(null) }
    var pendingRestoreReplace by remember { mutableStateOf(false) }

    val safCreateBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pass = pendingExportPass
        pendingExportPass = null
        if (uri == null || pass == null) {
            if (pass != null) Arrays.fill(pass, '\u0000')
            return@rememberLauncherForActivityResult
        }
        viewModel.exportEncryptedBackup(uri, pass, pendingExportSecrets) { res ->
            res.onSuccess {
                Toast.makeText(context, context.getString(R.string.backup_success_export), Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    context.getString(R.string.backup_failed_fmt, err.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val safOpenBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val pass = pendingRestorePass
        pendingRestorePass = null
        if (uri == null || pass == null) {
            if (pass != null) Arrays.fill(pass, '\u0000')
            return@rememberLauncherForActivityResult
        }
        viewModel.restoreEncryptedBackup(uri, pass, pendingRestoreReplace) { res ->
            res.onSuccess { detail ->
                Toast.makeText(context, "${context.getString(R.string.backup_success_restore)} ($detail)", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    context.getString(R.string.restore_failed_fmt, err.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    var tariffRateText by remember(prefs.tariffRatePerKwh) {
        mutableStateOf(
            if (prefs.tariffRatePerKwh > 0.0) String.format(Locale.US, "%.4f", prefs.tariffRatePerKwh).trimEnd('0').trimEnd('.') else "0"
        )
    }
    var tariffNightRateText by remember(prefs.tariffNightRatePerKwh) {
        mutableStateOf(
            if (prefs.tariffNightRatePerKwh > 0.0) String.format(Locale.US, "%.4f", prefs.tariffNightRatePerKwh).trimEnd('0').trimEnd('.') else "0"
        )
    }
    var tariffCurrencyText by remember(prefs.currencyCode) { mutableStateOf(prefs.currencyCode) }
    var tcpPortText by remember(automationConfig.tcpPort) { mutableStateOf(automationConfig.tcpPort.toString()) }

    var haEnabledDraft by remember(haConfig.haWebhookEnabled) { mutableStateOf(haConfig.haWebhookEnabled) }
    var haUrlDraft by remember(haConfig.haWebhookUrl) { mutableStateOf(haConfig.haWebhookUrl) }
    var haTokenDraft by remember { mutableStateOf("") }
    var haTokenVisible by remember { mutableStateOf(false) }

    val isBatteryWhitelisted = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true
    }

    if (showPortManualSheet) {
        PortManualBottomSheet(
            onDismiss = { showPortManualSheet = false }
        )
    }

    if (showSoftResetDialog) {
        SoftResetConfirmDialog(
            onConfirm = {
                showSoftResetDialog = false
                viewModel.executeSoftReset()
            },
            onDismiss = { showSoftResetDialog = false }
        )
    }

    if (showMiHomeDialog) {
        MiHomeSwitchDialog(
            onConfirm = {
                showMiHomeDialog = false
                onDisconnectForMiHome()
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.xiaomi.smarthome")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                } else {
                    Toast.makeText(context, context.getString(R.string.toast_mi_home_missing), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showMiHomeDialog = false }
        )
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text(stringResource(R.string.signout_confirm_title)) },
            text = { Text(stringResource(R.string.signout_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutConfirm = false
                        viewModel.cuktechManager.credentialStore.logoutAndWipeAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showBackupExportDialog) {
        EncryptedBackupExportDialog(
            onLaunchSafSave = { passChars, secrets ->
                showBackupExportDialog = false
                pendingExportPass = passChars
                pendingExportSecrets = secrets
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
                safCreateBackupLauncher.launch("cuktech10_$stamp.cukbackup")
            },
            onDismiss = { showBackupExportDialog = false }
        )
    }

    if (showBackupRestoreDialog) {
        EncryptedBackupRestoreDialog(
            onLaunchSafOpen = { passChars, replace ->
                showBackupRestoreDialog = false
                pendingRestorePass = passChars
                pendingRestoreReplace = replace
                safOpenBackupLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { showBackupRestoreDialog = false }
        )
    }

    if (showUpdateDialog) {
        GitHubUpdateDialog(
            releaseInfo = releaseUpdateInfo,
            downloadState = updateDownloadState,
            isChecking = isCheckingUpdates,
            onCheckNow = {
                isCheckingUpdates = true
                viewModel.checkForUpdates { res ->
                    isCheckingUpdates = false
                    res.onFailure { err ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_update_check_failed_fmt, err.message ?: ""),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onDownloadAndVerify = { viewModel.downloadAndVerifyLatestApk() },
            onResetDownloadState = { viewModel.resetUpdateDownloadState() },
            onDismiss = { showUpdateDialog = false }
        )
    }

    if (showRuleEditor) {
        AutomationRuleEditorSheet(
            existingRule = editingRule,
            onSave = { rule ->
                showRuleEditor = false
                editingRule = null
                viewModel.saveAutomationRule(rule)
            },
            onDismiss = {
                showRuleEditor = false
                editingRule = null
            }
        )
    }

    fun copyToClipboard(label: String, value: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cb?.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, context.getString(R.string.toast_copied_fmt, label), Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Diagnostics Quick Entry at top for Gateway visibility
        Card(
            onClick = onOpenDiagnostics,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_diagnostics_button")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_open_diagnostics), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_open_diagnostics_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }

        // 2. Appearance, Language & Default Start Screen
        SettingsSectionCard(
            title = stringResource(R.string.settings_section_appearance),
            icon = Icons.Default.Palette
        ) {
            Text(stringResource(R.string.settings_app_language), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = prefs.appLanguage == "system",
                    onClick = { viewModel.updateAppLanguage("system") },
                    label = { Text(stringResource(R.string.settings_lang_system)) },
                    modifier = Modifier.testTag("lang_chip_system")
                )
                FilterChip(
                    selected = prefs.appLanguage == "en",
                    onClick = { viewModel.updateAppLanguage("en") },
                    label = { Text(stringResource(R.string.settings_lang_en)) },
                    modifier = Modifier.testTag("lang_chip_en")
                )
                FilterChip(
                    selected = prefs.appLanguage == "ru",
                    onClick = { viewModel.updateAppLanguage("ru") },
                    label = { Text(stringResource(R.string.settings_lang_ru)) },
                    modifier = Modifier.testTag("lang_chip_ru")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Text(stringResource(R.string.settings_theme_mode), style = MaterialTheme.typography.labelLarge)
            val isAmoled = prefs.darkPalette == "AMOLED_BLACK"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = prefs.themeMode == "system",
                    onClick = { viewModel.updateTheme("system", isAmoled) },
                    label = { Text(stringResource(R.string.settings_theme_system)) }
                )
                FilterChip(
                    selected = prefs.themeMode == "light",
                    onClick = { viewModel.updateTheme("light", isAmoled) },
                    label = { Text(stringResource(R.string.settings_theme_light)) }
                )
                FilterChip(
                    selected = prefs.themeMode == "dark",
                    onClick = { viewModel.updateTheme("dark", isAmoled) },
                    label = { Text(stringResource(R.string.settings_theme_dark)) }
                )
            }

            SettingsSwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                checked = prefs.useDynamicColor,
                onCheckedChange = { viewModel.updateDynamicColor(it) },
                tag = "dynamic_color_switch"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_amoled_black), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = isAmoled,
                    onCheckedChange = { viewModel.updateTheme(prefs.themeMode, it) },
                    modifier = Modifier.testTag("amoled_black_switch")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Text(stringResource(R.string.settings_start_screen), style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(R.string.settings_start_screen_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = prefs.startDestination == StartDestination.OVERVIEW,
                    onClick = { viewModel.updateStartDestination(StartDestination.OVERVIEW) },
                    label = { Text(stringResource(R.string.nav_overview)) },
                    modifier = Modifier.testTag("start_screen_overview_chip")
                )
                FilterChip(
                    selected = prefs.startDestination == StartDestination.PORTS,
                    onClick = { viewModel.updateStartDestination(StartDestination.PORTS) },
                    label = { Text(stringResource(R.string.nav_ports)) },
                    modifier = Modifier.testTag("start_screen_ports_chip")
                )
                FilterChip(
                    selected = prefs.startDestination == StartDestination.ANALYTICS,
                    onClick = { viewModel.updateStartDestination(StartDestination.ANALYTICS) },
                    label = { Text(stringResource(R.string.nav_analytics)) },
                    modifier = Modifier.testTag("start_screen_analytics_chip")
                )
                FilterChip(
                    selected = prefs.startDestination == StartDestination.SETTINGS || prefs.startDestination == StartDestination.GATEWAY,
                    onClick = { viewModel.updateStartDestination(StartDestination.SETTINGS) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                    modifier = Modifier.testTag("start_screen_gateway_chip")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            OutlinedButton(
                onClick = { viewModel.replayOnboarding() },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("replay_onboarding_button")
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_replay_onboarding),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = { showPortManualSheet = true },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("open_port_manual_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_port_manual_btn),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(R.string.settings_port_manual_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 3. Charger Display & Hardware Controls (100% of AD1204 PIIDs)
        SettingsSectionCard(
            title = stringResource(R.string.settings_section_hardware),
            icon = Icons.Default.DisplaySettings
        ) {
            Text(stringResource(R.string.settings_screen_timeout), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(4, 5, 1, 2, 3).forEach { code ->
                    FilterChip(
                        selected = settings.screenTimeout == code,
                        onClick = { viewModel.setScreenTimeout(code) },
                        enabled = isConnected,
                        label = { Text(localizedTimeoutName(code)) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_idle_screen_off), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_idle_screen_off_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.idleScreenOff,
                    onCheckedChange = { viewModel.setIdleScreenOff(it) },
                    enabled = isConnected
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_orientation_lock), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_orientation_lock_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.orientationLock,
                    onCheckedChange = { viewModel.setOrientationLock(it) },
                    enabled = isConnected,
                    modifier = Modifier.testTag("orientation_lock_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { viewModel.rotateScreen180() },
                    enabled = isConnected,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_rotate_180), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { showSoftResetDialog = true },
                    enabled = isConnected,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_soft_reset), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Text(stringResource(R.string.settings_lcd_page), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { page ->
                    FilterChip(
                        selected = settings.activeLcdPage == page,
                        onClick = { viewModel.gotoLcdPage(page) },
                        enabled = isConnected,
                        label = { Text("$page", style = MaterialTheme.typography.labelMedium.tabularNums()) }
                    )
                }
            }

            Text(stringResource(R.string.settings_hw_language), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CuktechProtocol.LANGUAGE_OPTIONS.forEach { (code, name) ->
                    FilterChip(
                        selected = settings.language == code,
                        onClick = { viewModel.setChargerLanguage(code) },
                        enabled = isConnected,
                        label = { Text(name) }
                    )
                }
            }
        }

        // 4. Data Retention, Stitch Grace & Electricity Tariff
        SettingsSectionCard(
            title = stringResource(R.string.settings_section_retention),
            icon = Icons.Default.Refresh
        ) {
            val unitDays = stringResource(R.string.unit_d)
            val unitSec = stringResource(R.string.unit_s)
            Text(stringResource(R.string.settings_retention_days), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(7, 14, 30, 60, 90, 180).forEach { days ->
                    FilterChip(
                        selected = prefs.retentionDays == days,
                        onClick = { viewModel.updateRetentionDays(days) },
                        label = { Text("$days $unitDays", style = MaterialTheme.typography.labelMedium.tabularNums()) }
                    )
                }
            }

            Text(stringResource(R.string.settings_stitch_grace), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0, 30, 60, 120, 300).forEach { sec ->
                    FilterChip(
                        selected = prefs.sessionStitchGraceSec == sec,
                        onClick = { viewModel.updateStitchGraceSec(sec) },
                        label = { Text("$sec $unitSec", style = MaterialTheme.typography.labelMedium.tabularNums()) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = tariffRateText,
                    onValueChange = { tariffRateText = it },
                    label = {
                        Text(
                            if (prefs.touEnabled) {
                                stringResource(R.string.settings_tariff_day_rate)
                            } else {
                                stringResource(R.string.settings_tariff_rate)
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = tariffCurrencyText,
                    onValueChange = { tariffCurrencyText = it.take(5) },
                    label = { Text(stringResource(R.string.settings_tariff_currency)) },
                    singleLine = true,
                    modifier = Modifier.weight(0.8f)
                )
                FilledTonalButton(
                    onClick = {
                        val dayParsed = tariffRateText.replace(",", ".").toDoubleOrNull() ?: 0.0
                        val nightParsed = tariffNightRateText.replace(",", ".").toDoubleOrNull() ?: dayParsed
                        viewModel.updateTouTariff(
                            touEnabled = prefs.touEnabled,
                            dayRatePerKwh = dayParsed,
                            nightRatePerKwh = nightParsed,
                            currencyCode = tariffCurrencyText,
                            dayStartMinute = prefs.tariffDayStartMinute,
                            nightStartMinute = prefs.tariffNightStartMinute
                        )
                        Toast.makeText(context, context.getString(R.string.toast_tariff_saved), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }

            SettingsSwitchRow(
                title = stringResource(R.string.settings_tou_enable),
                checked = prefs.touEnabled,
                onCheckedChange = { enabled ->
                    val dayParsed = tariffRateText.replace(",", ".").toDoubleOrNull() ?: prefs.tariffRatePerKwh
                    val nightParsed = tariffNightRateText.replace(",", ".").toDoubleOrNull() ?: prefs.tariffNightRatePerKwh
                    viewModel.updateTouTariff(
                        touEnabled = enabled,
                        dayRatePerKwh = dayParsed,
                        nightRatePerKwh = nightParsed,
                        currencyCode = tariffCurrencyText,
                        dayStartMinute = prefs.tariffDayStartMinute,
                        nightStartMinute = prefs.tariffNightStartMinute
                    )
                },
                tag = "tou_tariff_switch"
            )

            if (prefs.touEnabled) {
                OutlinedTextField(
                    value = tariffNightRateText,
                    onValueChange = { tariffNightRateText = it },
                    label = { Text(stringResource(R.string.settings_tariff_night_rate)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.settings_tariff_day_start), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6 * 60, 7 * 60, 8 * 60).forEach { m ->
                        FilterChip(
                            selected = prefs.tariffDayStartMinute == m,
                            onClick = {
                                viewModel.updateTouTariff(
                                    touEnabled = true,
                                    dayRatePerKwh = prefs.tariffRatePerKwh,
                                    nightRatePerKwh = prefs.tariffNightRatePerKwh,
                                    currencyCode = prefs.currencyCode,
                                    dayStartMinute = m,
                                    nightStartMinute = prefs.tariffNightStartMinute
                                )
                            },
                            label = { Text(formatMinuteOfDay(m), style = MaterialTheme.typography.labelMedium.tabularNums()) }
                        )
                    }
                }
                Text(stringResource(R.string.settings_tariff_night_start), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(22 * 60, 23 * 60, 0).forEach { m ->
                        FilterChip(
                            selected = prefs.tariffNightStartMinute == m,
                            onClick = {
                                viewModel.updateTouTariff(
                                    touEnabled = true,
                                    dayRatePerKwh = prefs.tariffRatePerKwh,
                                    nightRatePerKwh = prefs.tariffNightRatePerKwh,
                                    currencyCode = prefs.currencyCode,
                                    dayStartMinute = prefs.tariffDayStartMinute,
                                    nightStartMinute = m
                                )
                            },
                            label = { Text(formatMinuteOfDay(m), style = MaterialTheme.typography.labelMedium.tabularNums()) }
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_tariff_snapshot_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 4b. Notifications, Charging Completion & Android 16 Live Updates
        SettingsSectionCard(
            title = stringResource(R.string.settings_section_notifications),
            icon = Icons.Default.NotificationsActive
        ) {
            fun savePolicy(
                hyst: Int = prefs.completionHysteresisSec,
                thresh: Double = prefs.completionPowerThresholdW,
                quiet: Boolean = prefs.quietHoursEnabled,
                qStart: Int = prefs.quietHoursStartMinute,
                qEnd: Int = prefs.quietHoursEndMinute,
                live: Boolean = prefs.liveUpdatesEnabled,
                sound: Boolean = prefs.completionSoundEnabled,
                vibrate: Boolean = prefs.completionVibrationEnabled,
                repeat: Boolean = prefs.completionRepeatEnabled,
                repeatInt: Int = prefs.completionRepeatIntervalMin,
                maxRep: Int = prefs.completionMaxRepeats
            ) {
                viewModel.updateNotificationPolicy(
                    hysteresisSec = hyst,
                    thresholdW = thresh,
                    quietHoursEnabled = quiet,
                    quietStartMin = qStart,
                    quietEndMin = qEnd,
                    liveUpdatesEnabled = live,
                    soundEnabled = sound,
                    vibrationEnabled = vibrate,
                    repeatEnabled = repeat,
                    repeatIntervalMin = repeatInt,
                    maxRepeats = maxRep
                )
            }

            SettingsSwitchRow(
                title = stringResource(R.string.settings_live_updates_enable),
                checked = prefs.liveUpdatesEnabled,
                onCheckedChange = { savePolicy(live = it) },
                tag = "live_updates_switch"
            )

            val isAndroid16 = Build.VERSION.SDK_INT >= 36
            val promotedOk = remember(context, isAndroid16) {
                ChargingNotificationManager.canPostPromotedNotifications(context)
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when {
                            !isAndroid16 -> stringResource(R.string.settings_promoted_status_legacy)
                            promotedOk -> stringResource(R.string.settings_promoted_status_granted)
                            else -> stringResource(R.string.settings_promoted_status_denied)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            !isAndroid16 -> MaterialTheme.colorScheme.onSurfaceVariant
                            promotedOk -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        text = stringResource(R.string.settings_live_updates_pixel_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isAndroid16) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(ChargingNotificationManager.buildPromotedNotificationsSettingsIntent(context))
                                }
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("promoted_notif_settings_button")
                        ) {
                            Text(stringResource(R.string.settings_promoted_allow_btn))
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            val notifSec = stringResource(R.string.unit_s)
            val notifW = stringResource(R.string.unit_w)
            val notifMin = stringResource(R.string.unit_m)
            Text(stringResource(R.string.settings_completion_hysteresis), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 60, 120, 180).forEach { sec ->
                    FilterChip(
                        selected = prefs.completionHysteresisSec == sec,
                        onClick = { savePolicy(hyst = sec) },
                        label = { Text("$sec $notifSec", style = MaterialTheme.typography.labelMedium.tabularNums()) }
                    )
                }
            }

            Text(stringResource(R.string.settings_completion_threshold), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5, 1.0, 2.0, 3.0).forEach { watts ->
                    FilterChip(
                        selected = kotlin.math.abs(prefs.completionPowerThresholdW - watts) < 0.05,
                        onClick = { savePolicy(thresh = watts) },
                        label = { Text("$watts $notifW", style = MaterialTheme.typography.labelMedium.tabularNums()) }
                    )
                }
            }

            SettingsSwitchRow(
                title = stringResource(R.string.settings_completion_sound),
                checked = prefs.completionSoundEnabled,
                onCheckedChange = { savePolicy(sound = it) }
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_completion_vibration),
                checked = prefs.completionVibrationEnabled,
                onCheckedChange = { savePolicy(vibrate = it) }
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_completion_repeat),
                checked = prefs.completionRepeatEnabled,
                onCheckedChange = { savePolicy(repeat = it) }
            )

            if (prefs.completionRepeatEnabled) {
                Text(stringResource(R.string.settings_completion_repeat_interval), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 5, 10, 15).forEach { mins ->
                        FilterChip(
                            selected = prefs.completionRepeatIntervalMin == mins,
                            onClick = { savePolicy(repeatInt = mins) },
                            label = { Text("$mins $notifMin", style = MaterialTheme.typography.labelMedium.tabularNums()) }
                        )
                    }
                }
                Text(stringResource(R.string.settings_completion_repeat_count), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3, 5).forEach { cnt ->
                        FilterChip(
                            selected = prefs.completionMaxRepeats == cnt,
                            onClick = { savePolicy(maxRep = cnt) },
                            label = { Text("${cnt}×", style = MaterialTheme.typography.labelMedium.tabularNums()) }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            SettingsSwitchRow(
                title = stringResource(R.string.settings_quiet_hours_enable),
                checked = prefs.quietHoursEnabled,
                onCheckedChange = { savePolicy(quiet = it) },
                tag = "quiet_hours_switch"
            )

            if (prefs.quietHoursEnabled) {
                Text(stringResource(R.string.settings_quiet_hours_start), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(22 * 60, 23 * 60, 0).forEach { m ->
                        FilterChip(
                            selected = prefs.quietHoursStartMinute == m,
                            onClick = { savePolicy(qStart = m) },
                            label = { Text(formatMinuteOfDay(m), style = MaterialTheme.typography.labelMedium.tabularNums()) }
                        )
                    }
                }
                Text(stringResource(R.string.settings_quiet_hours_end), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6 * 60, 7 * 60, 8 * 60, 9 * 60).forEach { m ->
                        FilterChip(
                            selected = prefs.quietHoursEndMinute == m,
                            onClick = { savePolicy(qEnd = m) },
                            label = { Text(formatMinuteOfDay(m), style = MaterialTheme.typography.labelMedium.tabularNums()) }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            SettingsSwitchRow(
                title = stringResource(R.string.settings_threshold_alerts_enable),
                checked = prefs.thresholdAlertsEnabled,
                onCheckedChange = { enabled ->
                    viewModel.updateThresholdAlerts(
                        enabled = enabled,
                        highLoadWatts = prefs.highLoadAlertWatts,
                        lowPowerDropWatts = prefs.lowPowerDropAlertWatts
                    )
                },
                tag = "threshold_alerts_switch"
            )

            AnimatedVisibility(visible = prefs.thresholdAlertsEnabled) {
                var highLoadDraft by remember(prefs.highLoadAlertWatts) {
                    mutableFloatStateOf(prefs.highLoadAlertWatts.toFloat().coerceIn(40f, 120f))
                }
                var lowDropDraft by remember(prefs.lowPowerDropAlertWatts) {
                    mutableFloatStateOf(prefs.lowPowerDropAlertWatts.toFloat().coerceIn(0.5f, 15f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.settings_threshold_high_load_fmt, highLoadDraft),
                        style = MaterialTheme.typography.labelLarge.tabularNums()
                    )
                    Slider(
                        value = highLoadDraft,
                        onValueChange = { highLoadDraft = it },
                        onValueChangeFinished = {
                            viewModel.updateThresholdAlerts(
                                enabled = prefs.thresholdAlertsEnabled,
                                highLoadWatts = highLoadDraft.toDouble(),
                                lowPowerDropWatts = lowDropDraft.toDouble()
                            )
                        },
                        valueRange = 40f..120f,
                        steps = 15,
                        modifier = Modifier.testTag("threshold_high_load_slider")
                    )

                    Text(
                        text = stringResource(R.string.settings_threshold_low_drop_fmt, lowDropDraft),
                        style = MaterialTheme.typography.labelLarge.tabularNums()
                    )
                    Slider(
                        value = lowDropDraft,
                        onValueChange = { lowDropDraft = it },
                        onValueChangeFinished = {
                            viewModel.updateThresholdAlerts(
                                enabled = prefs.thresholdAlertsEnabled,
                                highLoadWatts = highLoadDraft.toDouble(),
                                lowPowerDropWatts = lowDropDraft.toDouble()
                            )
                        },
                        valueRange = 0.5f..15f,
                        modifier = Modifier.testTag("threshold_low_drop_slider")
                    )
                }
            }
        }

        // 4c. Encrypted SAF Backup & Restore (.cukbackup)
        SettingsSectionCard(
            title = stringResource(R.string.settings_section_backup),
            icon = Icons.Default.Lock
        ) {
            Text(
                text = stringResource(R.string.settings_backup_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showBackupExportDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("saf_backup_export_button")
                ) {
                    Text(stringResource(R.string.settings_backup_export_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { showBackupRestoreDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("saf_backup_restore_button")
                ) {
                    Text(stringResource(R.string.settings_backup_restore_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // 4d. Local Automation Rules Engine
        SettingsSectionCard(
            title = stringResource(R.string.settings_section_automation),
            icon = Icons.Default.Bolt
        ) {
            Column(
                modifier = Modifier.testTag("settings_automation_section"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.automation_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val tplIdleName = stringResource(R.string.automation_tpl_idle_off_name)
                val tplNightName = stringResource(R.string.automation_tpl_night_mode_name)
                val tplLaptopName = stringResource(R.string.automation_tpl_laptop_burst_name)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AssistChip(
                        onClick = {
                            viewModel.saveAutomationRule(
                                AutomationRuleEntity(
                                    name = tplIdleName,
                                    enabled = true,
                                    triggerType = LocalAutomationEngine.TRIGGER_LOW_POWER_CUTOFF,
                                    portId = 1,
                                    thresholdWatts = 1.5,
                                    holdSeconds = 180,
                                    actionType = LocalAutomationEngine.ACTION_PORT_OFF,
                                    cooldownMinutes = 15,
                                    createdAtMs = System.currentTimeMillis()
                                )
                            )
                        },
                        label = { Text(stringResource(R.string.automation_tpl_idle_off)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("automation_tpl_idle_off_chip")
                    )
                    AssistChip(
                        onClick = {
                            viewModel.saveAutomationRule(
                                AutomationRuleEntity(
                                    name = tplNightName,
                                    enabled = true,
                                    triggerType = LocalAutomationEngine.TRIGGER_DAILY_SCHEDULE,
                                    minuteOfDay = 23 * 60,
                                    daysMask = 0b1111111,
                                    actionType = LocalAutomationEngine.ACTION_SET_SCENE_MODE,
                                    actionSceneMode = 2,
                                    cooldownMinutes = 60,
                                    createdAtMs = System.currentTimeMillis()
                                )
                            )
                        },
                        label = { Text(stringResource(R.string.automation_tpl_night_mode)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("automation_tpl_night_mode_chip")
                    )
                    AssistChip(
                        onClick = {
                            viewModel.saveAutomationRule(
                                AutomationRuleEntity(
                                    name = tplLaptopName,
                                    enabled = true,
                                    triggerType = LocalAutomationEngine.TRIGGER_HIGH_LOAD_SCENE,
                                    portId = 1,
                                    thresholdWatts = 65.0,
                                    holdSeconds = 15,
                                    actionType = LocalAutomationEngine.ACTION_SET_SCENE_MODE,
                                    actionSceneMode = 4,
                                    cooldownMinutes = 15,
                                    createdAtMs = System.currentTimeMillis()
                                )
                            )
                        },
                        label = { Text(stringResource(R.string.automation_tpl_laptop_burst)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("automation_tpl_laptop_burst_chip")
                    )
                }

                if (automationRules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.automation_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("automation_empty_text")
                    )
                } else {
                    automationRules.forEach { rule ->
                        val triggerSummary = when (rule.triggerType) {
                            LocalAutomationEngine.TRIGGER_LOW_POWER_CUTOFF ->
                                "${explicitPortName(rule.portId)} < ${String.format(Locale.US, "%.1f", rule.thresholdWatts)} W (${rule.holdSeconds}s)"
                            LocalAutomationEngine.TRIGGER_DAILY_SCHEDULE ->
                                String.format(Locale.US, "%02d:%02d", rule.minuteOfDay / 60, rule.minuteOfDay % 60)
                            else ->
                                "${explicitPortName(rule.portId)} >= ${String.format(Locale.US, "%.0f", rule.thresholdWatts)} W (${rule.holdSeconds}s)"
                        }
                        val actionSummary = when (rule.actionType) {
                            LocalAutomationEngine.ACTION_PORT_OFF ->
                                "${stringResource(R.string.automation_act_port_off)} (${explicitPortName(rule.portId)})"
                            else ->
                                localizedScenarioName(rule.actionSceneMode)
                        }
                        val lastFiredStr = if (rule.lastTriggeredMs > 0L) {
                            val stamp = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(rule.lastTriggeredMs))
                            stringResource(R.string.automation_last_fired_fmt, stamp)
                        } else {
                            stringResource(R.string.automation_never_fired)
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingRule = rule
                                    showRuleEditor = true
                                }
                                .testTag("automation_rule_card_${rule.id}")
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
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = rule.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$triggerSummary → $actionSummary",
                                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = lastFiredStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { viewModel.toggleAutomationRuleEnabled(rule.id, it) },
                                    modifier = Modifier.testTag("automation_rule_switch_${rule.id}")
                                )
                                IconButton(
                                    onClick = { viewModel.deleteAutomationRule(rule.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("automation_rule_delete_${rule.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = stringResource(R.string.action_clear),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                FilledTonalButton(
                    onClick = {
                        editingRule = null
                        showRuleEditor = true
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("automation_add_rule_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.automation_add_rule))
                }
            }
        }

        // 4e. Home Screen Widgets Gallery & Shape Customizer
        SettingsSectionCard(
            title = stringResource(R.string.settings_widget_gallery_title),
            icon = Icons.Default.Widgets
        ) {
            Column(
                modifier = Modifier.testTag("settings_widget_gallery_card"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_widget_gallery_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                fun pinWidget(receiverClass: Class<*>) {
                    val mgr = AppWidgetManager.getInstance(context)
                    if (mgr != null && mgr.isRequestPinAppWidgetSupported) {
                        val ok = mgr.requestPinAppWidget(ComponentName(context, receiverClass), null, null)
                        Toast.makeText(
                            context,
                            context.getString(if (ok) R.string.widget_pin_requested else R.string.widget_pin_unsupported),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.widget_pin_unsupported),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                // Control Panel (4x2) Preview + Pin
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.widget_preview_control_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "USB-C1 · USB-C2 · USB-C3 · USB-A",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { pinWidget(CuktechGlanceControlReceiver::class.java) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("widget_pin_control_button")
                        ) {
                            Text(stringResource(R.string.widget_pin_btn))
                        }
                    }
                }

                // Compact Grid (2x2) Preview + Pin
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.widget_preview_compact_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "USB-C1..A · 2×2",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { pinWidget(CuktechGlanceCompactReceiver::class.java) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("widget_pin_compact_button")
                        ) {
                            Text(stringResource(R.string.widget_pin_btn))
                        }
                    }
                }
            }
        }

        // 5. Xiaomi Account, Multi-Device & Updates
        SettingsSectionCard(
            title = stringResource(R.string.settings_section_cloud),
            icon = Icons.Default.AccountCircle
        ) {
            if (!keystoreHealthy && keystoreError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = keystoreError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenXiaomiLogin,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_xiaomi_login),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (accountSession != null) {
                    FilledTonalButton(
                        onClick = {
                            viewModel.syncXiaomiDevices { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_sync_devices),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (savedDevices.isNotEmpty()) {
                val spoilerArrowRotation by animateFloatAsState(
                    targetValue = if (showXiaomiDevicesSpoiler) 180f else 0f,
                    animationSpec = ExpressiveMotion.spatialSpring(),
                    label = "xiaomi_spoiler_chevron"
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showXiaomiDevicesSpoiler = !showXiaomiDevicesSpoiler }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .testTag("xiaomi_devices_spoiler"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.xiaomi_devices_spoiler_fmt, savedDevices.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                activeCred?.let { cur ->
                                    Text(
                                        text = "${cur.name} · ${cur.mac}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = TelemetryMonoFontFamily
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.rotate(spoilerArrowRotation)
                            )
                        }
                        AnimatedVisibility(
                            visible = showXiaomiDevicesSpoiler,
                            enter = expandVertically(ExpressiveMotion.spatialSpring()) + fadeIn(),
                            exit = shrinkVertically(ExpressiveMotion.spatialSpring()) + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                savedDevices.forEach { dev ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { viewModel.cuktechManager.credentialStore.selectActiveDevice(dev.mac) }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = activeCred?.mac.equals(dev.mac, ignoreCase = true),
                                            onClick = { viewModel.cuktechManager.credentialStore.selectActiveDevice(dev.mac) }
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(dev.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${dev.mac} · [${dev.region.uppercase(Locale.ROOT)}]",
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenSearchPair,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_scan_pair),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = { showMiHomeDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_mi_home_switch),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (accountSession != null || savedDevices.isNotEmpty()) {
                TextButton(
                    onClick = { showSignOutConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_sign_out_creds),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Surface(
                color = if (updateAvailable) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showUpdateDialog = true
                        if (releaseUpdateInfo == null && !isCheckingUpdates) {
                            isCheckingUpdates = true
                            viewModel.checkForUpdates { isCheckingUpdates = false }
                        }
                    }
                    .testTag("github_updates_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (updateAvailable) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = if (updateAvailable) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (updateAvailable) {
                                    stringResource(R.string.settings_update_available)
                                } else {
                                    stringResource(R.string.settings_check_updates)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (updateAvailable) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = stringResource(R.string.settings_current_version_fmt, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.bodySmall.tabularNums(),
                                color = if (updateAvailable) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            showUpdateDialog = true
                            isCheckingUpdates = true
                            viewModel.checkForUpdates { res ->
                                isCheckingUpdates = false
                                res.onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_update_check_failed_fmt, err.message ?: ""),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("check_updates_button")
                    ) {
                        Text(stringResource(R.string.settings_check_updates_now))
                    }
                }
            }
        }

        // 6. Telemetry Policies & Keepalive
        SettingsSectionCard(
            title = stringResource(R.string.settings_section_telemetry_policies),
            icon = Icons.Default.BatterySaver
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_master_telemetry),
                checked = telemetryMaster,
                onCheckedChange = { viewModel.setTelemetryMasterEnabled(it) }
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_log_phone_charging),
                checked = logPhoneCharging,
                onCheckedChange = { viewModel.setLogOnlyWhenPhoneCharging(it) }
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_pause_screen_locked),
                checked = pauseLocked,
                onCheckedChange = { viewModel.setPausePollingWhenScreenLocked(it) }
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_reconnect),
                checked = autoReconnect,
                onCheckedChange = { viewModel.setAutoReconnectConfigured(it) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_battery_whitelist), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (isBatteryWhitelisted) {
                            stringResource(R.string.settings_battery_whitelisted)
                        } else {
                            stringResource(R.string.settings_battery_optimized)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isBatteryWhitelisted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                if (!isBatteryWhitelisted) {
                    FilledTonalButton(
                        onClick = { viewModel.bleDiagnostics.requestIgnoreBatteryOptimizations() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_apply))
                    }
                }
            }

            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.settings_dontkillmyapp))
            }
        }

        // 7. Security, Token Validation & External Automation (Opt-in by default!)
        SettingsSectionCard(
            title = stringResource(R.string.settings_section_security),
            icon = Icons.Default.Security
        ) {
            Text(
                text = stringResource(R.string.settings_security_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Install Secret Token Display/Reveal/Copy/Rotate
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.settings_token_label), style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = revealedToken ?: automationConfig.authTokenPreview,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = TelemetryMonoFontFamily),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.toggleRevealToken() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("reveal_token_button")
                        ) {
                            Icon(
                                imageVector = if (revealedToken != null) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.cd_reveal_token)
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                copyToClipboard(
                                    context.getString(R.string.settings_token_copied_label),
                                    viewModel.currentSecretToken()
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("copy_token_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.action_copy),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.rotateAutomationToken()
                                Toast.makeText(context, context.getString(R.string.toast_token_rotated), Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("rotate_token_button")
                        ) {
                            Text(
                                text = stringResource(R.string.settings_token_rotate),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            SettingsSwitchRow(
                title = stringResource(R.string.settings_tasker_opt_in),
                checked = automationConfig.taskerEnabled,
                onCheckedChange = { viewModel.setTaskerEnabled(it) },
                tag = "opt_in_tasker_switch"
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_adb_opt_in),
                checked = automationConfig.adbBroadcastEnabled,
                onCheckedChange = { viewModel.setAdbBroadcastEnabled(it) },
                tag = "opt_in_adb_switch"
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_tcp_opt_in),
                checked = automationConfig.tcpGatewayEnabled,
                onCheckedChange = { viewModel.setTcpGatewayEnabled(it) },
                tag = "opt_in_tcp_switch"
            )

            // TCP Socket Server Control (available when TCP gateway opt-in is enabled)
            if (automationConfig.tcpGatewayEnabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(
                    text = "${if (isServerRunning) stringResource(R.string.settings_tcp_running) else stringResource(R.string.settings_tcp_stopped)} · ${if (automationConfig.tcpAllowLanBind) wifiIp else "127.0.0.1"}:$serverPort",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = TelemetryMonoFontFamily)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = tcpPortText,
                        onValueChange = { txt ->
                            val clean = txt.filter { it.isDigit() }.take(5)
                            tcpPortText = clean
                            clean.toIntOrNull()?.let { if (it in 1024..65535) viewModel.setTcpPort(it) }
                        },
                        label = { Text(stringResource(R.string.settings_tcp_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (isServerRunning) {
                                onRequestStopTcpServer()
                            } else {
                                val p = tcpPortText.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
                                viewModel.setTcpPort(p)
                                onRequestStartTcpServer(p)
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("toggle_tcp_server_button")
                    ) {
                        Text(stringResource(if (isServerRunning) R.string.action_stop else R.string.action_start))
                    }
                }

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_tcp_lan_bind),
                    checked = automationConfig.tcpAllowLanBind,
                    onCheckedChange = { viewModel.setTcpAllowLanBind(it) }
                )

                if (automationConfig.tcpAllowLanBind) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.settings_tcp_lan_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (connectedClients.isNotEmpty()) {
                    connectedClients.forEach { client ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${client.id} (${client.ip}:${client.port})",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { BleBridgeService.disconnectClientExternal(client.id) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.action_disconnect), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Home Assistant Webhook Configuration
            SettingsSwitchRow(
                title = stringResource(R.string.settings_ha_webhook),
                checked = haEnabledDraft,
                onCheckedChange = { haEnabledDraft = it }
            )
            OutlinedTextField(
                value = haUrlDraft,
                onValueChange = { haUrlDraft = it },
                label = { Text(stringResource(R.string.settings_ha_url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
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
                        if (haConfig.hasBearerToken) {
                            "${stringResource(R.string.settings_ha_token_label)} (${stringResource(R.string.settings_ha_token_configured)})"
                        } else {
                            stringResource(R.string.settings_ha_token_label)
                        }
                    )
                },
                visualTransformation = if (haTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { haTokenVisible = !haTokenVisible }) {
                        Icon(
                            imageVector = if (haTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(R.string.cd_toggle_password)
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (!haError.isNullOrBlank()) {
                Text(haError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FilledTonalButton(
                onClick = {
                    viewModel.updateHomeAssistantConfig(
                        enabled = haEnabledDraft,
                        url = haUrlDraft,
                        bearerToken = haTokenDraft.ifBlank { null }
                    )
                    haTokenDraft = ""
                    Toast.makeText(context, context.getString(R.string.toast_webhook_saved), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_save))
            }
        }

        Text(
            text = stringResource(R.string.settings_about_author_fmt, BuildConfig.APPLICATION_ID),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .testTag("settings_author_footer")
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier
        )
    }
}
