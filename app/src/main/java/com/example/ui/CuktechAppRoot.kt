package com.example.ui

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.BleBridgeService
import com.example.R
import com.example.cuktech.automation.UndoActionKind
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.prefs.StartDestination
import com.example.ui.components.BluetoothOffDialog
import com.example.ui.components.EventCenterSheet
import com.example.ui.components.GitHubUpdateDialog
import com.example.ui.components.MasterKillswitchConfirmDialog
import com.example.ui.components.localizedScenarioName
import com.example.ui.cuktech.CuktechDiscoveryPairingDialog
import com.example.ui.cuktech.XiaomiAccountLoginDialog
import com.example.ui.i18n.resolveLocale
import com.example.ui.navigation.AppDestination
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.OverviewScreen
import com.example.ui.screens.PortsScreen
import com.example.ui.screens.SettingsHubScreen
import com.example.ui.theme.ExpressiveMotion
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuktechAppRoot(
    viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModel.provideFactory(
            LocalContext.current.applicationContext as Application
        )
    ),
    pendingNavTab: MutableState<String?> = remember { mutableStateOf(null) },
    pendingNfcUri: MutableState<String?> = remember { mutableStateOf(null) },
    initialDestinationOverride: AppDestination? = null,
    requestPermissionsOnLaunch: Boolean = true,
    showOnboardingOverride: Boolean? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    val semantic = LocalSemanticColors.current

    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val prefsLoaded by viewModel.preferencesLoaded.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val activeCred by viewModel.activeCred.collectAsStateWithLifecycle()
    val isSmartCharging by viewModel.isSmartCharging.collectAsStateWithLifecycle()
    val totalWatts by viewModel.totalPowerWatts.collectAsStateWithLifecycle()
    val isCooldown by viewModel.isDisconnectCooldown.collectAsStateWithLifecycle()
    val undoAvailability by viewModel.undoAvailability.collectAsStateWithLifecycle()
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
    val releaseUpdateInfo by viewModel.releaseUpdateInfo.collectAsStateWithLifecycle()
    val updateDownloadState by viewModel.updateDownloadState.collectAsStateWithLifecycle()
    val chargerEvents by viewModel.chargerEvents.collectAsStateWithLifecycle()
    val unreadEventCount by viewModel.unreadEventCount.collectAsStateWithLifecycle()

    val shouldShowOnboarding = showOnboardingOverride
        ?: (initialDestinationOverride == null && pendingNavTab.value == null && !prefs.hasCompletedOnboarding)

    if (shouldShowOnboarding) {
        OnboardingScreen(
            onFinish = { viewModel.completeOnboarding() }
        )
        return
    }

    fun resolveStartAppDestination(sd: StartDestination): AppDestination = when (sd) {
        StartDestination.OVERVIEW -> AppDestination.OVERVIEW
        StartDestination.PORTS -> AppDestination.PORTS
        StartDestination.ANALYTICS -> AppDestination.ANALYTICS
        StartDestination.SETTINGS, StartDestination.GATEWAY -> AppDestination.SETTINGS
    }

    val startDestination = remember(initialDestinationOverride) {
        initialDestinationOverride ?: when (pendingNavTab.value) {
            "PORTS" -> AppDestination.PORTS
            "ANALYTICS" -> AppDestination.ANALYTICS
            "SETTINGS" -> AppDestination.SETTINGS
            "LIVE", "OVERVIEW" -> AppDestination.OVERVIEW
            else -> resolveStartAppDestination(prefs.startDestination)
        }
    }

    var showPairingDialog by remember { mutableStateOf(false) }
    var showXiaomiLoginDialog by remember { mutableStateOf(false) }
    var showBluetoothOffDialog by remember { mutableStateOf(false) }
    var showTopbarUpdateDialog by remember { mutableStateOf(false) }
    var showKillswitchConfirm by remember { mutableStateOf(false) }
    var showEventCenter by remember { mutableStateOf(false) }
    var isCheckingUpdatesTopbar by remember { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(false) }
    var pendingPermissionCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionsToRequest = remember {
        mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager?.adapter?.isEnabled == true) {
            pendingPermissionCallback?.invoke()
            pendingPermissionCallback = null
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.warning_bluetooth_off_body),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val ok = results.values.all { it }
        permissionsGranted = ok
        if (ok) {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (btManager?.adapter?.isEnabled != true) {
                showBluetoothOffDialog = true
            } else {
                pendingPermissionCallback?.invoke()
                pendingPermissionCallback = null
            }
        } else {
            pendingPermissionCallback = null
        }
    }

    fun ensureBleServiceStartedAndPermissions(onReady: () -> Unit) {
        val allOk = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allOk) {
            pendingPermissionCallback = onReady
            runCatching { permissionLauncher.launch(permissionsToRequest.toTypedArray()) }
            return
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager?.adapter?.isEnabled != true) {
            pendingPermissionCallback = onReady
            showBluetoothOffDialog = true
            return
        }

        permissionsGranted = true
        if (BleBridgeService.getInstance() == null) {
            runCatching {
                val ensureIntent = Intent(context, BleBridgeService::class.java).apply {
                    action = BleBridgeService.ACTION_ENSURE_SERVICE
                }
                context.startService(ensureIntent)
            }
        }
        onReady()
    }

    fun triggerAutoConnectIfConfigured() {
        val mgr = viewModel.cuktechManager
        val isAuto = mgr.isAutoReconnectConfigured.value
        val notManual = !mgr.userManuallyDisconnected
        val targetMac = mgr.lastConnectedMac
            ?: mgr.credentialStore.activeDevice.value?.mac
            ?: mgr.credentialStore.devices.value.firstOrNull { it.hasValidToken }?.mac
            ?: mgr.credentialStore.devices.value.firstOrNull()?.mac

        if (isAuto && notManual && !targetMac.isNullOrBlank() &&
            mgr.authState.value == CuktechAuthState.DISCONNECTED
        ) {
            ensureBleServiceStartedAndPermissions {
                mgr.connectToCharger(targetMac)
            }
        }
    }

    LaunchedEffect(requestPermissionsOnLaunch, shouldShowOnboarding) {
        val allOk = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allOk) {
            permissionsGranted = true
        } else if (requestPermissionsOnLaunch && !shouldShowOnboarding) {
            runCatching { permissionLauncher.launch(permissionsToRequest.toTypedArray()) }
        }
        runCatching { BleBridgeService.checkForUpdates() }
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            triggerAutoConnectIfConfigured()
        }
    }

    // Listen to adapter state changes cleanly while foregrounded
    DisposableEffect(context) {
        val btReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        if (viewModel.authState.value != CuktechAuthState.DISCONNECTED) {
                            viewModel.cuktechManager.disconnectCharger(isManual = false)
                        }
                    } else if (state == BluetoothAdapter.STATE_ON) {
                        val mgr = viewModel.cuktechManager
                        if (mgr.isAutoReconnectConfigured.value && !mgr.userManuallyDisconnected && !mgr.lastConnectedMac.isNullOrBlank()) {
                            scope.launch {
                                delay(1200L)
                                if (mgr.authState.value == CuktechAuthState.DISCONNECTED) {
                                    mgr.connectToCharger(mgr.lastConnectedMac!!)
                                }
                            }
                        }
                    }
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                btReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        onDispose {
            runCatching { context.unregisterReceiver(btReceiver) }
        }
    }

    fun navigateTopLevel(dest: AppDestination) {
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = AppDestination.fromRoute(navBackStackEntry?.destination?.route)

    var appliedColdStartPref by remember { mutableStateOf(false) }
    LaunchedEffect(
        prefsLoaded,
        shouldShowOnboarding,
        initialDestinationOverride,
        pendingNavTab.value,
        navBackStackEntry != null
    ) {
        if (!appliedColdStartPref &&
            navBackStackEntry != null &&
            prefsLoaded &&
            !shouldShowOnboarding &&
            initialDestinationOverride == null &&
            pendingNavTab.value == null
        ) {
            appliedColdStartPref = true
            val desired = resolveStartAppDestination(prefs.startDestination)
            val current = AppDestination.fromRoute(navBackStackEntry?.destination?.route)
            if (current != desired) {
                navigateTopLevel(desired)
            }
        }
    }

    LaunchedEffect(pendingNavTab.value, navBackStackEntry != null) {
        if (navBackStackEntry == null) return@LaunchedEffect
        val target = when (pendingNavTab.value) {
            "LIVE", "OVERVIEW" -> AppDestination.OVERVIEW
            "PORTS" -> AppDestination.PORTS
            "ANALYTICS" -> AppDestination.ANALYTICS
            "SETTINGS" -> AppDestination.SETTINGS
            else -> null
        }
        if (target != null) {
            navigateTopLevel(target)
            pendingNavTab.value = null
        }
    }

    LaunchedEffect(pendingNfcUri.value, shouldShowOnboarding) {
        val uri = pendingNfcUri.value
        if (!uri.isNullOrBlank() && !shouldShowOnboarding) {
            viewModel.executeNfcActionUri(uri)
            pendingNfcUri.value = null
        }
    }

    // Bind Snackbar strictly to CommandDispatcher.undoAvailability (cleared automatically on safety cutoffs!)
    val undoActionText = stringResource(R.string.action_undo)
    val undoMessage = undoAvailability?.let { entry ->
        when (val kind = entry.undoAction) {
            is UndoActionKind.PortToggle -> stringResource(
                if (kind.targetEnabled) R.string.undo_port_on_fmt else R.string.undo_port_off_fmt,
                kind.portId
            )
            is UndoActionKind.SceneMode -> stringResource(
                R.string.undo_scene_fmt,
                localizedScenarioName(kind.targetMode)
            )
            UndoActionKind.ScreenTimeout -> stringResource(R.string.undo_screen_timeout)
            is UndoActionKind.PortTimer -> stringResource(R.string.undo_port_timer_fmt, kind.portId)
            UndoActionKind.UsbALowCurrent -> stringResource(R.string.undo_usba_low_current)
            UndoActionKind.IdleScreenOff -> stringResource(R.string.undo_idle_screen_off)
            UndoActionKind.ChargerLanguage -> stringResource(R.string.undo_charger_language)
            UndoActionKind.OrientationLock -> stringResource(R.string.undo_orientation_lock)
            is UndoActionKind.ProfileApplied -> stringResource(R.string.undo_profile_fmt, kind.profileName)
            UndoActionKind.KillswitchRestore -> stringResource(R.string.undo_killswitch_restore)
            null -> entry.label
        }
    }
    LaunchedEffect(undoAvailability, undoMessage) {
        val currentUndo = undoAvailability
        snackbarHostState.currentSnackbarData?.dismiss()
        if (currentUndo != null && undoMessage != null) {
            val dismissJob = launch {
                delay(20_000L)
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            val result = snackbarHostState.showSnackbar(
                message = undoMessage,
                actionLabel = undoActionText,
                duration = SnackbarDuration.Indefinite
            )
            dismissJob.cancel()
            if (result == SnackbarResult.ActionPerformed) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.undoLastCommand()
            }
        }
    }

    // Command rejections & service feedback
    LaunchedEffect(viewModel) {
        viewModel.uiMessages.collect { msg ->
            if (viewModel.undoAvailability.value == null) {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            } else {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val topLevelItems = remember {
        listOf(
            Triple(AppDestination.OVERVIEW, R.string.nav_overview, Icons.Filled.Dashboard),
            Triple(AppDestination.PORTS, R.string.nav_ports, Icons.Filled.Usb),
            Triple(AppDestination.ANALYTICS, R.string.nav_analytics, Icons.Filled.Analytics),
            Triple(AppDestination.SETTINGS, R.string.nav_settings, Icons.Filled.Settings)
        )
    }

    // Modals preserved from BLEBridge
    if (showXiaomiLoginDialog) {
        XiaomiAccountLoginDialog(
            cloudClient = BleBridgeService.xiaomiCloudClient,
            credentialStore = viewModel.cuktechManager.credentialStore,
            onDismiss = { showXiaomiLoginDialog = false },
            onDevicesSynced = { list ->
                showXiaomiLoginDialog = false
                val text = if (list.isNotEmpty()) {
                    context.getString(R.string.toast_xiaomi_synced_fmt, list.size)
                } else {
                    context.getString(R.string.toast_xiaomi_session_saved)
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showPairingDialog) {
        CuktechDiscoveryPairingDialog(
            bleManager = viewModel.cuktechManager,
            onDismiss = {
                showPairingDialog = false
                viewModel.cuktechManager.stopSearchForCuktechChargers()
            },
            onSelectAndConnect = { dev ->
                showPairingDialog = false
                ensureBleServiceStartedAndPermissions {
                    viewModel.cuktechManager.connectToCharger(dev.mac)
                }
            }
        )
    }

    if (showBluetoothOffDialog) {
        BluetoothOffDialog(
            onEnable = {
                showBluetoothOffDialog = false
                try {
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (_: Exception) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            },
            onDismiss = { showBluetoothOffDialog = false }
        )
    }

    if (showTopbarUpdateDialog) {
        GitHubUpdateDialog(
            releaseInfo = releaseUpdateInfo,
            downloadState = updateDownloadState,
            isChecking = isCheckingUpdatesTopbar,
            onCheckNow = {
                isCheckingUpdatesTopbar = true
                viewModel.checkForUpdates { isCheckingUpdatesTopbar = false }
            },
            onDownloadAndVerify = { viewModel.downloadAndVerifyLatestApk() },
            onResetDownloadState = { viewModel.resetUpdateDownloadState() },
            onDismiss = { showTopbarUpdateDialog = false }
        )
    }

    if (showKillswitchConfirm) {
        MasterKillswitchConfirmDialog(
            onConfirm = {
                showKillswitchConfirm = false
                viewModel.executeMasterKillswitch()
            },
            onDismiss = { showKillswitchConfirm = false }
        )
    }

    if (showEventCenter) {
        EventCenterSheet(
            events = chargerEvents,
            onMarkAllRead = { viewModel.markAllEventsRead() },
            onClearAll = { viewModel.clearChargerEvents() },
            onDismiss = { showEventCenter = false }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        val appLocale = resolveLocale(prefs.appLanguage)
        val unitW = stringResource(R.string.unit_w)

        val statusDotColor by animateColorAsState(
            targetValue = when {
                isCooldown -> semantic.warning
                authState == CuktechAuthState.AUTHENTICATED -> semantic.success
                authState == CuktechAuthState.AUTH_FAILED -> MaterialTheme.colorScheme.error
                authState == CuktechAuthState.DISCONNECTED -> MaterialTheme.colorScheme.outline
                else -> semantic.warning
            },
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "topbar_status_dot"
        )

        val showWattsInTopBar = isSmartCharging || totalWatts >= 0.85
        val statusSubtitle = when {
            isCooldown -> stringResource(R.string.status_cooldown)
            authState == CuktechAuthState.AUTHENTICATED && showWattsInTopBar ->
                "${String.format(appLocale, "%.1f", totalWatts)} $unitW · ${stringResource(R.string.status_connected)}"
            authState == CuktechAuthState.AUTHENTICATED ->
                stringResource(R.string.status_connected)
            authState == CuktechAuthState.SCANNING -> stringResource(R.string.status_scanning)
            authState == CuktechAuthState.AUTH_FAILED -> stringResource(R.string.status_auth_failed)
            authState == CuktechAuthState.DISCONNECTED -> stringResource(R.string.status_disconnected)
            else -> stringResource(R.string.status_connecting)
        }

        val isConnected = authState == CuktechAuthState.AUTHENTICATED
        val isConnectingOrScanning = authState in setOf(
            CuktechAuthState.SCANNING,
            CuktechAuthState.CONNECTING_GATT,
            CuktechAuthState.DISCOVERING_SERVICES,
            CuktechAuthState.PHASE_A_INIT_A4,
            CuktechAuthState.PHASE_B_HANDSHAKE
        )

        Row(modifier = Modifier.fillMaxSize()) {
            if (useRail && currentDest.isTopLevel) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Spacer(Modifier.height(12.dp))
                    topLevelItems.forEach { (dest, labelRes, icon) ->
                        NavigationRailItem(
                            selected = currentDest == dest,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigateTopLevel(dest)
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = {
                                Text(
                                    text = stringResource(labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("nav_${dest.route}")
                        )
                    }
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.testTag("app_snackbar_host")
                    )
                },
                topBar = {
                    if (currentDest != AppDestination.DIAGNOSTICS) {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                titleContentColor = MaterialTheme.colorScheme.onBackground
                            ),
                            navigationIcon = {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showKillswitchConfirm = true
                                    },
                                    enabled = isConnected,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("master_killswitch_button")
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isConnected) {
                                            MaterialTheme.colorScheme.errorContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Filled.PowerSettingsNew,
                                                contentDescription = stringResource(R.string.topbar_killswitch_cd),
                                                tint = if (isConnected) {
                                                    MaterialTheme.colorScheme.onErrorContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                                                },
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            title = {
                                Column {
                                    Text(
                                        text = stringResource(R.string.app_full_name),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(statusDotColor)
                                        )
                                        Text(
                                            text = statusSubtitle,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = if (isConnected) {
                                                    TelemetryMonoFontFamily
                                                } else {
                                                    MaterialTheme.typography.labelSmall.fontFamily
                                                }
                                            ).tabularNums(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (updateAvailable) {
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            showTopbarUpdateDialog = true
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("topbar_update_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CloudDownload,
                                            contentDescription = stringResource(R.string.settings_update_available),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showEventCenter = true
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("topbar_event_center_button")
                                ) {
                                    BadgedBox(
                                        badge = {
                                            if (unreadEventCount > 0) {
                                                Badge(
                                                    modifier = Modifier.testTag("topbar_event_unread_badge")
                                                ) {
                                                    Text(
                                                        text = if (unreadEventCount > 99) "99+" else "$unreadEventCount",
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Notifications,
                                            contentDescription = stringResource(R.string.event_center_cd),
                                            tint = if (unreadEventCount > 0) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .testTag("topbar_connection_pill")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                ensureBleServiceStartedAndPermissions {
                                                    showPairingDialog = true
                                                }
                                            },
                                            modifier = Modifier
                                                .size(44.dp)
                                                .testTag("search_pair_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.BluetoothSearching,
                                                contentDescription = stringResource(R.string.topbar_pairing_cd),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        AnimatedContent(
                                            targetState = Triple(isCooldown, isConnected, isConnectingOrScanning),
                                            transitionSpec = {
                                                (fadeIn(tween(220, easing = ExpressiveMotion.EmphasizedDecelerate)) togetherWith
                                                    fadeOut(tween(150, easing = ExpressiveMotion.EmphasizedAccelerate))) using
                                                    SizeTransform(clip = false)
                                            },
                                            label = "topbar_conn_action"
                                        ) { (cooldown, connected, connecting) ->
                                            when {
                                                cooldown -> {
                                                    FilledTonalButton(
                                                        onClick = {},
                                                        enabled = false,
                                                        shape = CircleShape,
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                                        modifier = Modifier
                                                            .heightIn(min = 40.dp)
                                                            .testTag("connection_disconnect_button")
                                                    ) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(14.dp),
                                                            strokeWidth = 2.dp
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            text = stringResource(R.string.status_cooldown),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                                connected -> {
                                                    FilledTonalButton(
                                                        onClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            viewModel.cuktechManager.disconnectCharger(isManual = true)
                                                        },
                                                        shape = CircleShape,
                                                        colors = ButtonDefaults.filledTonalButtonColors(
                                                            containerColor = semantic.successContainer,
                                                            contentColor = semantic.success
                                                        ),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                                        modifier = Modifier
                                                            .heightIn(min = 40.dp)
                                                            .testTag("connection_disconnect_button")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.LinkOff,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(
                                                            text = stringResource(R.string.topbar_disconnect_short),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                                connecting -> {
                                                    FilledTonalButton(
                                                        onClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            viewModel.cuktechManager.disconnectCharger(isManual = true)
                                                        },
                                                        shape = CircleShape,
                                                        colors = ButtonDefaults.filledTonalButtonColors(
                                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                                        ),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                                        modifier = Modifier
                                                            .heightIn(min = 40.dp)
                                                            .testTag("connection_cancel_button")
                                                    ) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(13.dp),
                                                            strokeWidth = 2.dp
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            text = stringResource(R.string.topbar_cancel_short),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1
                                                        )
                                                        Spacer(Modifier.width(2.dp))
                                                        Icon(
                                                            imageVector = Icons.Filled.Close,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                                else -> {
                                                    Button(
                                                        onClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            ensureBleServiceStartedAndPermissions {
                                                                val mac = activeCred?.mac
                                                                if (!mac.isNullOrBlank()) {
                                                                    viewModel.cuktechManager.connectToCharger(mac)
                                                                } else {
                                                                    showPairingDialog = true
                                                                }
                                                            }
                                                        },
                                                        shape = CircleShape,
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                                        modifier = Modifier
                                                            .heightIn(min = 40.dp)
                                                            .testTag("connection_connect_button")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Bluetooth,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(
                                                            text = stringResource(R.string.topbar_connect_short),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                },
                bottomBar = {
                    if (!useRail && currentDest.isTopLevel) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            topLevelItems.forEach { (dest, labelRes, icon) ->
                                NavigationBarItem(
                                    selected = currentDest == dest,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        navigateTopLevel(dest)
                                    },
                                    icon = { Icon(icon, contentDescription = null) },
                                    label = {
                                        Text(
                                            text = stringResource(labelRes),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.testTag("nav_${dest.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val topLevelRoutes = remember {
                        setOf(
                            AppDestination.OVERVIEW.route,
                            AppDestination.PORTS.route,
                            AppDestination.ANALYTICS.route,
                            AppDestination.SETTINGS.route
                        )
                    }
                    NavHost(
                        navController = navController,
                        startDestination = startDestination.route,
                        enterTransition = {
                            with(ExpressiveMotion) { resolveEnter(topLevelRoutes) }
                        },
                        exitTransition = {
                            with(ExpressiveMotion) { resolveExit(topLevelRoutes) }
                        },
                        popEnterTransition = {
                            with(ExpressiveMotion) { resolvePopEnter(topLevelRoutes) }
                        },
                        popExitTransition = {
                            with(ExpressiveMotion) { resolvePopExit(topLevelRoutes) }
                        },
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxSize()
                    ) {
                        composable(AppDestination.OVERVIEW.route) {
                            OverviewScreen(
                                viewModel = viewModel,
                                onRequestConnect = { mac ->
                                    ensureBleServiceStartedAndPermissions {
                                        viewModel.cuktechManager.connectToCharger(mac)
                                    }
                                },
                                onRequestCancelConnect = {
                                    viewModel.cuktechManager.disconnectCharger(isManual = true)
                                },
                                onRequestDisconnect = {
                                    viewModel.cuktechManager.disconnectCharger(isManual = true)
                                },
                                onOpenSearchPair = {
                                    ensureBleServiceStartedAndPermissions {
                                        showPairingDialog = true
                                    }
                                },
                                onOpenXiaomiLogin = {
                                    showXiaomiLoginDialog = true
                                },
                                onNavigateToPort = { portId ->
                                    viewModel.selectPortFocus(portId)
                                    navigateTopLevel(AppDestination.PORTS)
                                },
                                onNavigateToAnalytics = {
                                    navigateTopLevel(AppDestination.ANALYTICS)
                                }
                            )
                        }

                        composable(AppDestination.PORTS.route) {
                            PortsScreen(viewModel = viewModel)
                        }

                        composable(AppDestination.ANALYTICS.route) {
                            AnalyticsScreen(viewModel = viewModel)
                        }

                        composable(AppDestination.SETTINGS.route) {
                            SettingsHubScreen(
                                viewModel = viewModel,
                                onOpenXiaomiLogin = { showXiaomiLoginDialog = true },
                                onOpenSearchPair = {
                                    ensureBleServiceStartedAndPermissions {
                                        showPairingDialog = true
                                    }
                                },
                                onOpenDiagnostics = {
                                    navController.navigate(AppDestination.DIAGNOSTICS.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onRequestStartTcpServer = { port ->
                                    val startIntent = Intent(context, BleBridgeService::class.java).apply {
                                        action = BleBridgeService.ACTION_START_SERVER
                                        putExtra(BleBridgeService.EXTRA_PORT, port)
                                    }
                                    runCatching { context.startService(startIntent) }
                                },
                                onRequestStopTcpServer = {
                                    val stopIntent = Intent(context, BleBridgeService::class.java).apply {
                                        action = BleBridgeService.ACTION_STOP_SERVER
                                    }
                                    runCatching { context.startService(stopIntent) }
                                },
                                onDisconnectForMiHome = {
                                    if (viewModel.authState.value == CuktechAuthState.AUTHENTICATED) {
                                        viewModel.cuktechManager.disconnectCharger(isManual = true)
                                        BleBridgeService.getInstance()?.closeGattClean()
                                    }
                                    val stopIntent = Intent(context, BleBridgeService::class.java).apply {
                                        action = BleBridgeService.ACTION_STOP_SERVER
                                    }
                                    runCatching { context.startService(stopIntent) }
                                }
                            )
                        }

                        composable(AppDestination.DIAGNOSTICS.route) {
                            DiagnosticsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
