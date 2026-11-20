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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
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
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.prefs.StartDestination
import com.example.ui.components.BluetoothOffDialog
import com.example.ui.components.GitHubUpdateDialog
import com.example.ui.cuktech.CuktechDiscoveryPairingDialog
import com.example.ui.cuktech.XiaomiAccountLoginDialog
import com.example.ui.navigation.AppDestination
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.OverviewScreen
import com.example.ui.screens.PortsScreen
import com.example.ui.screens.SettingsHubScreen
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuktechAppRoot(
    viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModel.provideFactory(
            LocalContext.current.applicationContext as Application
        )
    ),
    pendingNavTab: MutableState<String?> = remember { mutableStateOf(null) },
    initialDestinationOverride: AppDestination? = null,
    requestPermissionsOnLaunch: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    val semantic = LocalSemanticColors.current

    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val totalWatts by viewModel.totalPowerWatts.collectAsStateWithLifecycle()
    val isCooldown by viewModel.isDisconnectCooldown.collectAsStateWithLifecycle()
    val undoAvailability by viewModel.undoAvailability.collectAsStateWithLifecycle()
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
    val releaseUpdateInfo by viewModel.releaseUpdateInfo.collectAsStateWithLifecycle()
    val updateDownloadState by viewModel.updateDownloadState.collectAsStateWithLifecycle()

    val startDestination = remember(initialDestinationOverride) {
        initialDestinationOverride ?: when (pendingNavTab.value) {
            "PORTS" -> AppDestination.PORTS
            "ANALYTICS" -> AppDestination.ANALYTICS
            "SETTINGS" -> AppDestination.SETTINGS
            "LIVE", "OVERVIEW" -> AppDestination.OVERVIEW
            else -> if (prefs.startDestination == StartDestination.GATEWAY) {
                AppDestination.SETTINGS
            } else {
                AppDestination.OVERVIEW
            }
        }
    }

    var showPairingDialog by remember { mutableStateOf(false) }
    var showXiaomiLoginDialog by remember { mutableStateOf(false) }
    var showBluetoothOffDialog by remember { mutableStateOf(false) }
    var showTopbarUpdateDialog by remember { mutableStateOf(false) }
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

    LaunchedEffect(requestPermissionsOnLaunch) {
        val allOk = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allOk) {
            permissionsGranted = true
        } else if (requestPermissionsOnLaunch) {
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
            context.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
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

    LaunchedEffect(pendingNavTab.value) {
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

    // Bind Snackbar strictly to CommandDispatcher.undoAvailability (cleared automatically on safety cutoffs!)
    val undoActionText = stringResource(R.string.action_undo)
    LaunchedEffect(undoAvailability) {
        val currentUndo = undoAvailability
        snackbarHostState.currentSnackbarData?.dismiss()
        if (currentUndo != null) {
            val dismissJob = launch {
                delay(20_000L)
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            val result = snackbarHostState.showSnackbar(
                message = currentUndo.label,
                actionLabel = undoActionText,
                duration = SnackbarDuration.Indefinite
            )
            dismissJob.cancel()
            if (result == SnackbarResult.ActionPerformed) {
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

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = AppDestination.fromRoute(navBackStackEntry?.destination?.route)

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
                    "Synced ${list.size} device(s) from Xiaomi Cloud"
                } else {
                    "Xiaomi account session saved"
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        val statusDotColor = when {
            isCooldown -> semantic.warning
            authState == CuktechAuthState.AUTHENTICATED -> semantic.success
            authState == CuktechAuthState.AUTH_FAILED -> MaterialTheme.colorScheme.error
            authState == CuktechAuthState.DISCONNECTED -> MaterialTheme.colorScheme.outline
            else -> semantic.warning
        }

        val statusSubtitle = when {
            isCooldown -> stringResource(R.string.status_cooldown)
            authState == CuktechAuthState.AUTHENTICATED ->
                "${String.format(Locale.US, "%.1f", totalWatts)} W · ${stringResource(R.string.status_connected)}"
            authState == CuktechAuthState.SCANNING -> stringResource(R.string.status_scanning)
            authState == CuktechAuthState.AUTH_FAILED -> stringResource(R.string.status_auth_failed)
            authState == CuktechAuthState.DISCONNECTED -> stringResource(R.string.status_disconnected)
            else -> stringResource(R.string.status_connecting)
        }

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
                            onClick = { navigateTopLevel(dest) },
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
                            title = {
                                Column {
                                    Text(
                                        text = "CUKTECH 10 Ultra",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
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
                                                fontFamily = if (authState == CuktechAuthState.AUTHENTICATED) {
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
                                        onClick = { showTopbarUpdateDialog = true },
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
                                        navController.navigate(AppDestination.DIAGNOSTICS.route) {
                                            launchSingleTop = true
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("topbar_diagnostics_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.BugReport,
                                        contentDescription = stringResource(R.string.nav_diagnostics),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
                                    onClick = { navigateTopLevel(dest) },
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
                    NavHost(
                        navController = navController,
                        startDestination = startDestination.route,
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
