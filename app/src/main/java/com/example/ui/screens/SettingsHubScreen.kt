package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.notifications.ChargingNotificationManager
import com.example.cuktech.prefs.StartDestination
import com.example.cuktech.protocol.CuktechProtocol
import com.example.ui.components.EncryptedBackupExportDialog
import com.example.ui.components.EncryptedBackupRestoreDialog
import com.example.ui.components.GitHubUpdateDialog
import com.example.ui.components.MiHomeSwitchDialog
import com.example.ui.components.SoftResetConfirmDialog
import com.example.ui.components.formatMinuteOfDay
import com.example.ui.components.localizedTimeoutName
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.DashboardViewModel
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

    val isConnected = authState == CuktechAuthState.AUTHENTICATED

    var showSoftResetDialog by remember { mutableStateOf(false) }
    var showMiHomeDialog by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showBackupExportDialog by remember { mutableStateOf(false) }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isCheckingUpdates by remember { mutableStateOf(false) }

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
                Toast.makeText(context, "Backup failed: ${err.message}", Toast.LENGTH_LONG).show()
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
                Toast.makeText(context, "Restore failed: ${err.message}", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, "Xiaomi Mi Home app not installed", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showMiHomeDialog = false }
        )
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text(stringResource(R.string.settings_sign_out_creds)) },
            text = { Text("Removes Xiaomi session & stored device BLE keys from Android Keystore.") },
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
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
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
                        Toast.makeText(context, "Update check failed: ${err.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDownloadAndVerify = { viewModel.downloadAndVerifyLatestApk() },
            onResetDownloadState = { viewModel.resetUpdateDownloadState() },
            onDismiss = { showUpdateDialog = false }
        )
    }

    fun copyToClipboard(label: String, value: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cb?.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "Copied: $label", Toast.LENGTH_SHORT).show()
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = prefs.startDestination == StartDestination.OVERVIEW,
                    onClick = { viewModel.updateStartDestination(StartDestination.OVERVIEW) },
                    label = { Text(stringResource(R.string.settings_start_overview)) },
                    modifier = Modifier.testTag("start_screen_overview_chip")
                )
                FilterChip(
                    selected = prefs.startDestination == StartDestination.GATEWAY,
                    onClick = { viewModel.updateStartDestination(StartDestination.GATEWAY) },
                    label = { Text(stringResource(R.string.settings_start_gateway)) },
                    modifier = Modifier.testTag("start_screen_gateway_chip")
                )
            }
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
                Text(stringResource(R.string.settings_orientation_lock), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = settings.orientationLock,
                    onCheckedChange = { viewModel.setOrientationLock(it) },
                    enabled = isConnected
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
                        label = { Text("${days}d", style = MaterialTheme.typography.labelMedium.tabularNums()) }
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
                        label = { Text("${sec}s", style = MaterialTheme.typography.labelMedium.tabularNums()) }
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
                        Toast.makeText(context, "Tariff saved", Toast.LENGTH_SHORT).show()
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

            Text(stringResource(R.string.settings_completion_hysteresis), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 60, 120, 180).forEach { sec ->
                    FilterChip(
                        selected = prefs.completionHysteresisSec == sec,
                        onClick = { savePolicy(hyst = sec) },
                        label = { Text("${sec}s", style = MaterialTheme.typography.labelMedium.tabularNums()) }
                    )
                }
            }

            Text(stringResource(R.string.settings_completion_threshold), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5, 1.0, 2.0, 3.0).forEach { watts ->
                    FilterChip(
                        selected = kotlin.math.abs(prefs.completionPowerThresholdW - watts) < 0.05,
                        onClick = { savePolicy(thresh = watts) },
                        label = { Text("${watts} W", style = MaterialTheme.typography.labelMedium.tabularNums()) }
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
                            label = { Text("${mins}m", style = MaterialTheme.typography.labelMedium.tabularNums()) }
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

        // 5. Xiaomi Account, Multi-Device & Updates
        SettingsSectionCard(
            title = stringResource(R.string.settings_section_cloud),
            icon = Icons.Default.AccountCircle
        ) {
            if (!keystoreHealthy && keystoreError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = keystoreError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenXiaomiLogin,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_xiaomi_login), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (accountSession != null) {
                    FilledTonalButton(
                        onClick = {
                            viewModel.syncXiaomiDevices { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.settings_sync_devices))
                    }
                }
            }

            if (savedDevices.isNotEmpty()) {
                savedDevices.forEach { dev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenSearchPair,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_scan_pair), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { showMiHomeDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.settings_mi_home_switch), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (accountSession != null || savedDevices.isNotEmpty()) {
                TextButton(
                    onClick = { showSignOutConfirm = true },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.settings_sign_out_creds), color = MaterialTheme.colorScheme.error)
                }
            }

            Surface(
                color = if (updateAvailable) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                shape = RoundedCornerShape(14.dp),
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = if (updateAvailable) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        Column {
                            Text(
                                text = if (updateAvailable) {
                                    stringResource(R.string.settings_update_available)
                                } else {
                                    stringResource(R.string.settings_check_updates)
                                },
                                style = MaterialTheme.typography.titleSmall,
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
                                    Toast.makeText(context, "Update check failed: ${err.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { viewModel.toggleRevealToken() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("reveal_token_button")
                        ) {
                            Icon(
                                imageVector = if (revealedToken != null) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Reveal Token"
                            )
                        }
                        FilledTonalButton(
                            onClick = { copyToClipboard("BLEBridge Token", viewModel.currentSecretToken()) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("copy_token_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_copy))
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.rotateAutomationToken()
                                Toast.makeText(context, "Token rotated", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("rotate_token_button")
                        ) {
                            Text(stringResource(R.string.settings_token_rotate))
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
                        Text(if (isServerRunning) "Stop" else "Start")
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
                            "${stringResource(R.string.settings_ha_token_label)} (Configured)"
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
                            contentDescription = "Toggle Token Visibility"
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
                    Toast.makeText(context, "Webhook configuration saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_save))
            }
        }

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
