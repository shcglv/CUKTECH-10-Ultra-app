package com.example.ui.viewmodel

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BleBridgeService
import com.example.R
import com.example.ui.i18n.resolveLocale
import com.example.ui.i18n.withAppLocale
import com.example.cuktech.automation.AutomationSafetyAndDeadlineEngine
import com.example.cuktech.automation.AutomationSafetyReport
import com.example.cuktech.automation.AutomationSimulationReport
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandDispatcher
import com.example.cuktech.automation.CommandResult
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.automation.DeadlineReadinessItem
import com.example.cuktech.ble.CuktechAuthState
import com.example.cuktech.ble.CuktechBleManager
import com.example.cuktech.bridge.BridgeDiscoveryHelper
import com.example.cuktech.bridge.BridgeStatusResult
import com.example.cuktech.bridge.BridgeSyncClient
import com.example.cuktech.bridge.DiscoveredBridgeEndpoint
import com.example.cuktech.bridge.WifiContextSnapshot
import com.example.cuktech.cloud.XiaomiCloudClient
import com.example.cuktech.db.CsvImportResult
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.db.room.BleLinkEventEntity
import com.example.cuktech.db.room.CableInventoryEntity
import com.example.cuktech.db.room.CableLabTestEntity
import com.example.cuktech.db.room.ChargerNodeEntity
import com.example.cuktech.db.room.ChargingProfileEntity
import com.example.cuktech.db.room.CommandAuditEntity
import com.example.cuktech.db.room.DeadlineTaskEntity
import com.example.cuktech.db.room.DeviceAliasEntity
import com.example.cuktech.db.room.DeviceInventoryEntity
import com.example.cuktech.db.room.IncidentBlackBoxEntity
import com.example.cuktech.db.room.PortDevicePresetEntity
import com.example.cuktech.db.room.ScenarioObservationEntity
import com.example.cuktech.db.room.SessionAnnotationEntity
import com.example.cuktech.db.room.SessionGapEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.diagnostics.BleDiagnostics
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.integrations.CloudIntegrations
import com.example.cuktech.integrations.IntegrationConfig
import com.example.cuktech.integrations.MqttConnectionStatus
import com.example.cuktech.notifications.AnomalyAlert
import com.example.cuktech.notifications.ChargerEvent
import com.example.cuktech.power.CableLabEvaluationResult
import com.example.cuktech.power.CableLabRunnerState
import com.example.cuktech.power.CableLabSampleInput
import com.example.cuktech.power.ChargerPassportSummary
import com.example.cuktech.power.ChargerSummaryCalculator
import com.example.cuktech.power.ComparisonAlignmentMode
import com.example.cuktech.power.ComprehensivePortAnalytics
import com.example.cuktech.power.CustomKpiAndWorkspaceEngine
import com.example.cuktech.power.CustomKpiDefinition
import com.example.cuktech.power.DailyWhBucket
import com.example.cuktech.power.DeviceMatchSuggestion
import com.example.cuktech.power.EtaEstimate
import com.example.cuktech.power.EtaEstimator
import com.example.cuktech.power.EvaluatedCustomKpi
import com.example.cuktech.power.FleetSummarySnapshot
import com.example.cuktech.power.InventoryAndCableLabEngine
import com.example.cuktech.power.KpiFormulaType
import com.example.cuktech.power.LoadSimulationResult
import com.example.cuktech.power.MultiChargerFleetEngine
import com.example.cuktech.power.PeriodSummarySnapshot
import com.example.cuktech.power.PhoneBatteryMonitor
import com.example.cuktech.power.PhoneBatterySnapshot
import com.example.cuktech.power.PortCableDiagnostic
import com.example.cuktech.power.PortLifecycleAnalyzer
import com.example.cuktech.power.PowerBudgetCalculator
import com.example.cuktech.power.PowerBudgetSnapshot
import com.example.cuktech.power.PowerDoctorEngine
import com.example.cuktech.power.PowerDoctorReport
import com.example.cuktech.power.ScenarioAndPdoEngine
import com.example.cuktech.power.ScenarioAndPdoSnapshot
import com.example.cuktech.power.SessionComparisonResult
import com.example.cuktech.power.SessionDeepAnalysis
import com.example.cuktech.power.SessionDeepAnalyzer
import com.example.cuktech.power.SessionPersonalRecords
import com.example.cuktech.power.SummaryPeriodMode
import com.example.cuktech.prefs.AppPreferences
import com.example.cuktech.prefs.AppPreferencesRepository
import com.example.cuktech.prefs.StartDestination
import com.example.cuktech.protocol.C3ASharedBusState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.protocol.PortTelemetryDecoder
import com.example.cuktech.security.ConfigShareAndSupportBundle
import com.example.cuktech.security.CuktechDeviceCredentials
import com.example.cuktech.security.SafEncryptedBackupManager
import com.example.cuktech.security.ShareableConfigBundle
import com.example.cuktech.security.ShareableConfigImportSelection
import com.example.cuktech.security.WebDavBackupClient
import com.example.cuktech.security.WebDavConfig
import com.example.cuktech.security.WebDavSyncStatus
import com.example.cuktech.telemetry.AwayDeltaSummary
import com.example.cuktech.telemetry.ConnectionWizardStep
import com.example.cuktech.telemetry.RssiPoint
import com.example.cuktech.telemetry.TelemetryIntegritySnapshot
import com.example.cuktech.updater.ReleaseUpdateInfo
import com.example.cuktech.updater.UpdateDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Arrays
import java.util.Calendar
import java.util.UUID
import kotlin.math.abs

enum class ChartInterval {
    SEC_60,
    MIN_5,
    MIN_30,
    SESSION,
    TODAY,
    CUSTOM
}

enum class ChartMetric {
    TOTAL_W,
    STACKED_W,
    PER_PORT_W,
    VOLTAGE_V,
    CURRENT_A,
    CUMULATIVE_WH,
    PROTOCOLS
}

data class ChartPointSample(
    val timestampMs: Long,
    val totalW: Double,
    val portW: Map<Int, Double>,
    val portV: Map<Int, Double>,
    val portA: Map<Int, Double>,
    val portWh: Map<Int, Double>,
    val portProtocol: Map<Int, String>
)

data class SessionReplayState(
    val session: ChargeSessionRecord? = null,
    val samples: List<TelemetrySampleEntity> = emptyList(),
    val cursorIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false
) {
    val currentSample: TelemetrySampleEntity?
        get() = samples.getOrNull(cursorIndex.coerceIn(0, (samples.size - 1).coerceAtLeast(0)))
}

data class CommandWearSummary(
    val portToggleCounts: Map<Int, Int> = emptyMap(),
    val softResetCount: Int = 0,
    val killswitchCount: Int = 0,
    val totalExecuted: Int = 0,
    val totalRejected: Int = 0
)

class DashboardViewModel @JvmOverloads constructor(
    application: Application,
    val cuktechManager: CuktechBleManager = BleBridgeService.ensureCuktechManagersInitialized(application),
    val bleDiagnostics: BleDiagnostics = BleBridgeService.bleDiagnostics!!,
    val cloudIntegrations: CloudIntegrations = BleBridgeService.cloudIntegrations!!,
    val dispatcher: CommandDispatcher = cuktechManager.commandDispatcher
) : AndroidViewModel(application) {

    val persistence: CuktechPersistence get() = cuktechManager.persistence
    val preferencesRepository: AppPreferencesRepository get() = cuktechManager.preferencesRepository
    val cloudClient: XiaomiCloudClient by lazy { XiaomiCloudClient() }
    val webDavBackupClient: WebDavBackupClient by lazy {
        WebDavBackupClient(getApplication(), cuktechManager.credentialStore)
    }

    val preferences: StateFlow<AppPreferences> = preferencesRepository.preferences
    val preferencesLoaded: StateFlow<Boolean> = preferencesRepository.isLoaded
    val authState = cuktechManager.authState
    val isScanning = cuktechManager.isScanning
    val statusMessage = cuktechManager.statusMessage
    val discoveredDevices = cuktechManager.discoveredDevices
    val deviceInfo = cuktechManager.deviceInfo
    val ports = cuktechManager.ports
    val isSmartCharging = cuktechManager.isSmartCharging
    val totalPowerWatts = cuktechManager.totalPowerWatts
    val settings = cuktechManager.settings
    val powerChartHistory = cuktechManager.powerChartHistory
    val activeSessions = cuktechManager.activeSessions
    val completedSessions = cuktechManager.completedSessions
    val chargeLimits = cuktechManager.chargeLimits
    val countdownRemainingSeconds = cuktechManager.countdownRemainingSeconds
    val usbALowCurrentRemainingSeconds = cuktechManager.usbALowCurrentRemainingSeconds
    val telemetryMasterEnabled = cuktechManager.telemetryMasterEnabled
    val logOnlyWhenPhoneCharging = cuktechManager.logOnlyWhenPhoneCharging
    val pausePollingWhenScreenLocked = cuktechManager.pausePollingWhenScreenLocked
    val portLoggingMap = cuktechManager.portLoggingMap
    val isAutoReconnectConfigured = cuktechManager.isAutoReconnectConfigured
    val isDisconnectCooldown = cuktechManager.isDisconnectCooldown
    val undoAvailability = dispatcher.undoAvailability
    val lastKillswitchSnapshot = dispatcher.lastKillswitchSnapshot

    val accountSession = cuktechManager.credentialStore.session
    val savedDevices = cuktechManager.credentialStore.devices
    val activeCred = cuktechManager.credentialStore.activeDevice
    val keystoreHealthy = cuktechManager.credentialStore.keystoreHealthy
    val keystoreError = cuktechManager.credentialStore.keystoreError

    val automationConfig = cuktechManager.authManager.config
    val haConfig = cloudIntegrations.config
    val haLastError = cloudIntegrations.lastError
    val diagStatus = bleDiagnostics.status

    val isServerRunning = BleBridgeService.isServerRunning
    val serverPort = BleBridgeService.serverPort
    val wifiIp = BleBridgeService.wifiIp
    val connectedClients = BleBridgeService.connectedClients
    val bleConnectionState = BleBridgeService.bleConnectionState
    val bleDeviceAddress = BleBridgeService.bleDeviceAddress
    val currentLogLevel = BleBridgeService.logLevel
    val logs = BleBridgeService.logs
    val updateAvailable = BleBridgeService.updateAvailable
    val anomalyAlerts: StateFlow<List<AnomalyAlert>> = cuktechManager.anomalyAlerts
    val chargerEvents: StateFlow<List<ChargerEvent>> = cuktechManager.eventLog.events
    val unreadEventCount: StateFlow<Int> = cuktechManager.eventLog.unreadCount
    val releaseUpdateInfo: StateFlow<ReleaseUpdateInfo?> = BleBridgeService.releaseUpdateInfo
    val updateDownloadState: StateFlow<UpdateDownloadState> = BleBridgeService.updateDownloadState

    val dailyAggregates = persistence.observeDailyAggregates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aliases: StateFlow<List<DeviceAliasEntity>> = persistence.observeAliases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profiles = persistence.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val portDevicePresets: StateFlow<Map<Int, PortDevicePresetEntity>> =
        persistence.observePortDevicePresets()
            .let { flow ->
                kotlinx.coroutines.flow.flow {
                    flow.collect { list -> emit(list.associateBy { it.portId }) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val automationRules: StateFlow<List<AutomationRuleEntity>> = persistence.observeAutomationRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val automationSafetyReport: StateFlow<AutomationSafetyReport> = automationRules
        .map { AutomationSafetyAndDeadlineEngine.detectConflictsAndSafety(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutomationSafetyReport())

    private val _automationSimResult = MutableStateFlow<AutomationSimulationReport?>(null)
    val automationSimResult: StateFlow<AutomationSimulationReport?> = _automationSimResult.asStateFlow()

    val deadlineTasks: StateFlow<List<DeadlineTaskEntity>> = persistence.observeDeadlineTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deadlineReadiness: StateFlow<List<DeadlineReadinessItem>> = combine(
        deadlineTasks,
        ports,
        activeSessions
    ) { tasks, pMap, sessMap ->
        AutomationSafetyAndDeadlineEngine.evaluateDeadlineQueue(tasks, pMap, sessMap)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _nfcLastStatus = MutableStateFlow<String?>(null)
    val nfcLastStatus: StateFlow<String?> = _nfcLastStatus.asStateFlow()

    val integritySnapshot: StateFlow<TelemetryIntegritySnapshot> = cuktechManager.integritySnapshot
    val wifiContext: StateFlow<WifiContextSnapshot> = cuktechManager.wifiContext
    val awaySummary: StateFlow<AwayDeltaSummary?> = cuktechManager.awaySummary
    val bridgeSyncing: StateFlow<Boolean> = cuktechManager.bridgeSyncing
    val bridgeLastError: StateFlow<String?> = cuktechManager.bridgeLastError

    val rssiHistory: StateFlow<List<RssiPoint>> = integritySnapshot
        .map { cuktechManager.integrityEngine.getRssiHistory() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessionGaps: StateFlow<List<SessionGapEntity>> = persistence.observeSessionGaps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val commandAuditLog: StateFlow<List<CommandAuditEntity>> = persistence.observeCommandAudit()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bleLinkEvents: StateFlow<List<BleLinkEventEntity>> = persistence.observeBleLinkEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chargerNodes: StateFlow<List<ChargerNodeEntity>> = persistence.observeChargerNodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scenarioObservations: StateFlow<List<ScenarioObservationEntity>> = persistence.observeScenarioObservations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessionAnnotations: StateFlow<List<SessionAnnotationEntity>> = persistence.observeAllSessionAnnotations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val incidentBlackBoxes: StateFlow<List<IncidentBlackBoxEntity>> = persistence.observeIncidentBlackBoxes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessionPersonalRecords: StateFlow<SessionPersonalRecords> = completedSessions
        .map { SessionDeepAnalyzer.computePersonalRecords(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionPersonalRecords())

    val deviceInventory: StateFlow<List<DeviceInventoryEntity>> = persistence.observeDeviceInventory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cableInventory: StateFlow<List<CableInventoryEntity>> = persistence.observeCableInventory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cableLabTests: StateFlow<List<CableLabTestEntity>> = persistence.observeCableLabTests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _phoneBattery = MutableStateFlow(
        runCatching { PhoneBatteryMonitor.readSnapshot(application, ports.value) }
            .getOrDefault(PhoneBatterySnapshot())
    )
    val phoneBattery: StateFlow<PhoneBatterySnapshot> = _phoneBattery.asStateFlow()

    private val _cableLabRunner = MutableStateFlow(CableLabRunnerState())
    val cableLabRunner: StateFlow<CableLabRunnerState> = _cableLabRunner.asStateFlow()

    private val _cableLabLastNotice = MutableStateFlow<String?>(null)
    val cableLabLastNotice: StateFlow<String?> = _cableLabLastNotice.asStateFlow()

    val suggestedDeviceMatch: StateFlow<DeviceMatchSuggestion?> = combine(
        completedSessions,
        deviceInventory
    ) { completed, devices ->
        InventoryAndCableLabEngine.suggestMatchingDevice(completed.firstOrNull(), devices)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val chargerPassport: StateFlow<ChargerPassportSummary> = combine(
        chargerNodes,
        deviceInfo,
        completedSessions,
        activeSessions,
        cableLabTests
    ) { nodes, info, completed, active, tests ->
        val activeMac = info.macAddress.takeIf { it.isNotBlank() && it != "--" } ?: activeCred.value?.mac.orEmpty()
        val node = nodes.firstOrNull { it.deviceMac.equals(activeMac, ignoreCase = true) } ?: nodes.firstOrNull()
        InventoryAndCableLabEngine.buildChargerPassport(
            activeNode = node,
            sessions = active.values.toList() + completed,
            cableTests = tests
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        InventoryAndCableLabEngine.buildChargerPassport(null, emptyList(), emptyList())
    )

    val commandWearSummary: StateFlow<CommandWearSummary> = commandAuditLog
        .map { entries ->
            val toggles = mutableMapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0)
            var softResets = 0
            var killswitches = 0
            var execCount = 0
            var rejCount = 0
            for (e in entries) {
                if (e.accepted) {
                    execCount++
                    when (e.commandType) {
                        "SetPortEnabled" -> if (e.targetPortId in 1..4) {
                            toggles[e.targetPortId] = (toggles[e.targetPortId] ?: 0) + 1
                        }
                        "SoftReset" -> softResets++
                        "MasterKillswitch" -> {
                            killswitches++
                            for (pid in 1..4) toggles[pid] = (toggles[pid] ?: 0) + 1
                        }
                    }
                } else {
                    rejCount++
                }
            }
            CommandWearSummary(
                portToggleCounts = toggles,
                softResetCount = softResets,
                killswitchCount = killswitches,
                totalExecuted = execCount,
                totalRejected = rejCount
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CommandWearSummary())

    val connectionWizardSteps: StateFlow<List<ConnectionWizardStep>> = combine(
        authState,
        discoveredDevices,
        activeCred,
        diagStatus
    ) { auth, discovered, cred, _ ->
        val ctx = getApplication<Application>()
        val hasBtPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        val btAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val btEnabled = btAdapter?.isEnabled == true
        cuktechManager.integrityEngine.buildConnectionWizardSteps(
            hasBtPerms = hasBtPerms,
            btEnabled = btEnabled,
            discoveredCount = discovered.size,
            hasActiveCred = cred?.hasValidToken == true,
            authStateName = auth.name
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _bridgeStatusResult = MutableStateFlow<BridgeStatusResult?>(null)
    val bridgeStatusResult: StateFlow<BridgeStatusResult?> = _bridgeStatusResult.asStateFlow()

    private val _analyticsWindowDays = MutableStateFlow(7)
    val analyticsWindowDays: StateFlow<Int> = _analyticsWindowDays.asStateFlow()

    private val _portLifecycleAnalytics = MutableStateFlow(ComprehensivePortAnalytics())
    val portLifecycleAnalytics: StateFlow<ComprehensivePortAnalytics> = _portLifecycleAnalytics.asStateFlow()

    private val _scenarioAndPdoSnapshot = MutableStateFlow(ScenarioAndPdoSnapshot())
    val scenarioAndPdoSnapshot: StateFlow<ScenarioAndPdoSnapshot> = _scenarioAndPdoSnapshot.asStateFlow()

    private val _simSceneMode = MutableStateFlow(1)
    val simSceneMode: StateFlow<Int> = _simSceneMode.asStateFlow()

    private val _simPortsMask = MutableStateFlow(0x03)
    val simPortsMask: StateFlow<Int> = _simPortsMask.asStateFlow()

    val loadSimulationResult: StateFlow<LoadSimulationResult> = combine(
        _simSceneMode,
        _simPortsMask,
        scenarioObservations,
        chargerNodes,
        deviceInfo
    ) { mode, mask, obs, nodes, info ->
        val activeMac = info.macAddress.takeIf { it.isNotBlank() && it != "--" } ?: activeCred.value?.mac.orEmpty()
        val mainsMode = nodes.firstOrNull { it.deviceMac.equals(activeMac, ignoreCase = true) }?.mainsVoltageMode ?: 220
        val ratedMax = if (mainsMode == 110) 110.0 else 120.0
        ScenarioAndPdoEngine.simulateLoad(
            sceneMode = mode,
            activeMask = mask,
            ratedMainsMaxW = ratedMax,
            observations = obs
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ScenarioAndPdoEngine.simulateLoad(sceneMode = 1, activeMask = 0x03)
    )

    private val _replayDeepAnalysis = MutableStateFlow<SessionDeepAnalysis?>(null)
    val replayDeepAnalysis: StateFlow<SessionDeepAnalysis?> = _replayDeepAnalysis.asStateFlow()

    private val _replayGaps = MutableStateFlow<List<SessionGapEntity>>(emptyList())
    val replayGaps: StateFlow<List<SessionGapEntity>> = _replayGaps.asStateFlow()

    private val _comparisonSessionAId = MutableStateFlow<Long?>(null)
    val comparisonSessionAId: StateFlow<Long?> = _comparisonSessionAId.asStateFlow()

    private val _comparisonSessionBId = MutableStateFlow<Long?>(null)
    val comparisonSessionBId: StateFlow<Long?> = _comparisonSessionBId.asStateFlow()

    private val _comparisonAlignment = MutableStateFlow(ComparisonAlignmentMode.BY_TIME)
    val comparisonAlignment: StateFlow<ComparisonAlignmentMode> = _comparisonAlignment.asStateFlow()

    private val _sessionComparisonResult = MutableStateFlow(SessionComparisonResult())
    val sessionComparisonResult: StateFlow<SessionComparisonResult> = _sessionComparisonResult.asStateFlow()

    private val _summaryPeriodMode = MutableStateFlow(SummaryPeriodMode.WEEK)
    val summaryPeriodMode: StateFlow<SummaryPeriodMode> = _summaryPeriodMode.asStateFlow()

    private val _summaryPeriodOffset = MutableStateFlow(0)
    val summaryPeriodOffset: StateFlow<Int> = _summaryPeriodOffset.asStateFlow()

    val periodSummary: StateFlow<PeriodSummarySnapshot> = combine(
        _summaryPeriodMode,
        _summaryPeriodOffset,
        completedSessions,
        activeSessions,
        dailyAggregates,
        preferences
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val mode = values[0] as SummaryPeriodMode
        val offset = values[1] as Int
        @Suppress("UNCHECKED_CAST")
        val completed = values[2] as List<ChargeSessionRecord>
        @Suppress("UNCHECKED_CAST")
        val active = values[3] as Map<Int, ChargeSessionRecord>
        @Suppress("UNCHECKED_CAST")
        val aggs = values[4] as List<com.example.cuktech.db.room.DailyEnergyAggregateEntity>
        val curPrefs = values[5] as AppPreferences
        ChargerSummaryCalculator.computeSummary(
            mode = mode,
            periodOffset = offset,
            completedSessions = completed,
            activeSessions = active,
            dailyAggregates = aggs,
            anomalyCountInPeriod = anomalyAlerts.value.size,
            defaultCurrency = curPrefs.currencyCode
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ChargerSummaryCalculator.computeSummary(
            mode = SummaryPeriodMode.WEEK,
            periodOffset = 0,
            completedSessions = completedSessions.value,
            activeSessions = activeSessions.value,
            dailyAggregates = dailyAggregates.value
        )
    )

    val dailyWhBuckets: StateFlow<List<DailyWhBucket>> = combine(
        completedSessions,
        activeSessions,
        dailyAggregates,
        preferences
    ) { completed, active, aggs, curPrefs ->
        ChargerSummaryCalculator.computeRecentDailyBuckets(
            daysCount = 10,
            completedSessions = completed,
            activeSessions = active,
            dailyAggregates = aggs,
            locale = resolveLocale(curPrefs.appLanguage)
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ChargerSummaryCalculator.computeRecentDailyBuckets(
            daysCount = 10,
            completedSessions = completedSessions.value,
            activeSessions = activeSessions.value,
            dailyAggregates = dailyAggregates.value
        )
    )

    val cableDiagnostics: StateFlow<Map<Int, PortCableDiagnostic>> = cuktechManager.cableDiagnostics

    val powerBudget: StateFlow<PowerBudgetSnapshot> = combine(ports, settings) { portsMap, s ->
        PowerBudgetCalculator.calculate(portsMap, s.sceneMode)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        PowerBudgetCalculator.calculate(ports.value, settings.value.sceneMode)
    )

    val c3aSharedBus: StateFlow<C3ASharedBusState> = ports
        .map { PortTelemetryDecoder.resolveC3ASharedBus(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            PortTelemetryDecoder.resolveC3ASharedBus(ports.value)
        )

    val etaEstimates: StateFlow<Map<Int, EtaEstimate>> = combine(
        portDevicePresets,
        ports,
        activeSessions
    ) { presetsMap, portsMap, sessionsMap ->
        buildMap {
            for ((portId, preset) in presetsMap) {
                val p = portsMap[portId]
                val sessWh = sessionsMap[portId]?.whAccumulated ?: (p?.cumulativeWh ?: 0.0)
                put(portId, EtaEstimator.estimate(preset, p?.powerW ?: 0.0, sessWh))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _selectedPortFocus = MutableStateFlow(1)
    val selectedPortFocus: StateFlow<Int> = _selectedPortFocus.asStateFlow()

    private val _chartInterval = MutableStateFlow(ChartInterval.MIN_5)
    val chartInterval: StateFlow<ChartInterval> = _chartInterval.asStateFlow()

    private val _selectedMetrics = MutableStateFlow(setOf(ChartMetric.TOTAL_W))
    val selectedMetrics: StateFlow<Set<ChartMetric>> = _selectedMetrics.asStateFlow()

    val chartMetric: StateFlow<ChartMetric> = _selectedMetrics
        .map { it.firstOrNull() ?: ChartMetric.TOTAL_W }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChartMetric.TOTAL_W)

    private val _customRangeStartMs = MutableStateFlow(System.currentTimeMillis() - 3_600_000L)
    val customRangeStartMs: StateFlow<Long> = _customRangeStartMs.asStateFlow()

    private val _customRangeEndMs = MutableStateFlow(System.currentTimeMillis())
    val customRangeEndMs: StateFlow<Long> = _customRangeEndMs.asStateFlow()

    private val _chartSamples = MutableStateFlow<List<ChartPointSample>>(emptyList())
    val chartSamples: StateFlow<List<ChartPointSample>> = _chartSamples.asStateFlow()

    private val _replayState = MutableStateFlow(SessionReplayState())
    val replayState: StateFlow<SessionReplayState> = _replayState.asStateFlow()

    private val _revealedToken = MutableStateFlow<String?>(null)
    val revealedToken: StateFlow<String?> = _revealedToken.asStateFlow()

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    // --- Stage 7 & Stage 8 Flows: MQTT, Bridge Discovery, WebDAV, Fleet, Power Doctor, Workspaces/KPIs ---

    val mqttStatus: StateFlow<MqttConnectionStatus> = cloudIntegrations.mqttStatus

    private val _discoveredBridges = MutableStateFlow<List<DiscoveredBridgeEndpoint>>(emptyList())
    val discoveredBridges: StateFlow<List<DiscoveredBridgeEndpoint>> = _discoveredBridges.asStateFlow()

    private val _isDiscoveringBridges = MutableStateFlow(false)
    val isDiscoveringBridges: StateFlow<Boolean> = _isDiscoveringBridges.asStateFlow()

    private val _bridgeRemoteCmdStatus = MutableStateFlow<String?>(null)
    val bridgeRemoteCmdStatus: StateFlow<String?> = _bridgeRemoteCmdStatus.asStateFlow()

    private val _webDavConfig = MutableStateFlow(webDavBackupClient.loadConfig())
    val webDavConfig: StateFlow<WebDavConfig> = _webDavConfig.asStateFlow()

    private val _webDavStatus = MutableStateFlow(WebDavSyncStatus())
    val webDavStatus: StateFlow<WebDavSyncStatus> = _webDavStatus.asStateFlow()

    val fleetSummary: StateFlow<FleetSummarySnapshot> = combine(
        savedDevices,
        chargerNodes,
        completedSessions,
        activeSessions,
        dailyAggregates,
        ports,
        authState,
        preferences,
        activeCred,
        deviceInfo
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val devs = arr[0] as List<CuktechDeviceCredentials>
        @Suppress("UNCHECKED_CAST")
        val nodes = arr[1] as List<ChargerNodeEntity>
        @Suppress("UNCHECKED_CAST")
        val completed = arr[2] as List<ChargeSessionRecord>
        @Suppress("UNCHECKED_CAST")
        val active = arr[3] as Map<Int, ChargeSessionRecord>
        @Suppress("UNCHECKED_CAST")
        val aggs = arr[4] as List<com.example.cuktech.db.room.DailyEnergyAggregateEntity>
        @Suppress("UNCHECKED_CAST")
        val portsMap = arr[5] as Map<Int, PortTelemetry>
        val auth = arr[6] as CuktechAuthState
        val curPrefs = arr[7] as AppPreferences
        val cred = arr[8] as CuktechDeviceCredentials?
        val info = arr[9] as com.example.cuktech.ble.ChargerDeviceInfo
        val resolvedMac = info.macAddress.takeIf { it.isNotBlank() && it != "--" } ?: cred?.mac.orEmpty()
        MultiChargerFleetEngine.buildFleetSummary(
            savedDevices = devs,
            chargerNodes = nodes,
            completedSessions = completed,
            activeSessions = active,
            dailyAggregates = aggs,
            activeMac = resolvedMac,
            activeLivePorts = portsMap,
            isActiveOnline = auth == CuktechAuthState.AUTHENTICATED,
            currencyCode = curPrefs.currencyCode
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FleetSummarySnapshot())

    val powerDoctorReport: StateFlow<PowerDoctorReport> = combine(
        integritySnapshot,
        cableDiagnostics,
        cableLabTests,
        anomalyAlerts,
        commandWearSummary
    ) { integrity, cables, tests, alerts, wear ->
        PowerDoctorEngine.evaluate(
            integrity = integrity,
            cableDiagnostics = cables,
            cableLabTests = tests,
            anomalyAlerts = alerts,
            wearSummary = wear
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PowerDoctorReport())

    val evaluatedKpis: StateFlow<List<EvaluatedCustomKpi>> = combine(
        preferences,
        ports,
        completedSessions,
        dailyWhBuckets,
        chargerNodes,
        deviceInfo,
        activeCred
    ) { arr ->
        val curPrefs = arr[0] as AppPreferences
        @Suppress("UNCHECKED_CAST")
        val portsMap = arr[1] as Map<Int, PortTelemetry>
        @Suppress("UNCHECKED_CAST")
        val completed = arr[2] as List<ChargeSessionRecord>
        @Suppress("UNCHECKED_CAST")
        val buckets = arr[3] as List<DailyWhBucket>
        @Suppress("UNCHECKED_CAST")
        val nodes = arr[4] as List<ChargerNodeEntity>
        val info = arr[5] as com.example.cuktech.ble.ChargerDeviceInfo
        val cred = arr[6] as CuktechDeviceCredentials?
        val activeMac = info.macAddress.takeIf { it.isNotBlank() && it != "--" } ?: cred?.mac.orEmpty()
        val mains = nodes.firstOrNull { it.deviceMac.equals(activeMac, ignoreCase = true) }?.mainsVoltageMode ?: 220
        val ratedMaxW = if (mains == 110) 110.0 else 120.0
        val todayWh = buckets.lastOrNull()?.totalWh ?: 0.0
        val defs = CustomKpiAndWorkspaceEngine.deserializeKpis(curPrefs.customKpisJson)
        CustomKpiAndWorkspaceEngine.evaluateKpis(
            definitions = defs,
            ports = portsMap,
            ratedMaxW = ratedMaxW,
            effectiveTariffPerKwh = curPrefs.resolveEffectiveTariffPerKwh(),
            currencyCode = curPrefs.currencyCode,
            completedSessions = completed,
            todayWh = todayWh
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var replayJob: Job? = null
    private var cableLabJob: Job? = null

    init {
        cuktechManager.refreshIntegritySnapshot()
        viewModelScope.launch {
            combine(
                _chartInterval,
                _customRangeStartMs,
                _customRangeEndMs,
                powerChartHistory,
                activeSessions
            ) { interval, customStart, customEnd, liveChart, activeSess ->
                ChartQueryRequest(interval, customStart, customEnd, liveChart.size, activeSess.size)
            }.collect { req ->
                reloadChartSeries(req)
            }
        }
        viewModelScope.launch {
            combine(
                _analyticsWindowDays,
                completedSessions,
                activeSessions,
                sessionGaps,
                powerChartHistory
            ) { days, completed, active, gaps, live ->
                AnalyticsQueryRequest(days, completed.size, active.size, gaps.size, live.size)
            }.collect { req ->
                recomputePortLifecycleAnalytics(req.days)
            }
        }
    }

    private data class AnalyticsQueryRequest(
        val days: Int,
        val completedCount: Int,
        val activeCount: Int,
        val gapsCount: Int,
        val liveCount: Int
    )

    private suspend fun recomputePortLifecycleAnalytics(days: Int) {
        val nowMs = System.currentTimeMillis()
        val windowStart = nowMs - (days.coerceIn(1, 30).toLong() * 86_400_000L)
        val activeMac = deviceInfo.value.macAddress.takeIf { it.isNotBlank() && it != "--" }
            ?: activeCred.value?.mac
        val (lifecycleRes, scenarioRes) = withContext(Dispatchers.Default) {
            val samples = persistence.getTelemetrySamplesSince(windowStart, 18_000)
            val allSessions = activeSessions.value.values.toList() + completedSessions.value
            val gaps = persistence.getSessionGapsSince(windowStart, 500)
            val obs = persistence.getScenarioObservationsSince(windowStart, 10_000)
            val lifecycle = PortLifecycleAnalyzer.analyze(
                samples = samples,
                sessions = allSessions,
                gaps = gaps,
                windowStartMs = windowStart,
                windowEndMs = nowMs,
                deviceMacFilter = activeMac
            )
            val scenarioPdo = ScenarioAndPdoEngine.analyze(
                ports = ports.value,
                settings = settings.value,
                samples = samples,
                observations = obs
            )
            lifecycle to scenarioPdo
        }
        _portLifecycleAnalytics.value = lifecycleRes
        _scenarioAndPdoSnapshot.value = scenarioRes
    }

    private data class ChartQueryRequest(
        val interval: ChartInterval,
        val customStartMs: Long,
        val customEndMs: Long,
        val liveCount: Int,
        val activeSessionCount: Int
    )

    private suspend fun reloadChartSeries(req: ChartQueryRequest) {
        val now = System.currentTimeMillis()
        val (sinceMs, untilMs) = when (req.interval) {
            ChartInterval.SEC_60 -> (now - 60_000L) to now
            ChartInterval.MIN_5 -> (now - 300_000L) to now
            ChartInterval.MIN_30 -> (now - 1_800_000L) to now
            ChartInterval.SESSION -> {
                val earliestActive = activeSessions.value.values.minOfOrNull { (it.startTimeSec * 1000.0).toLong() }
                val latestCompleted = completedSessions.value.firstOrNull()?.let { (it.startTimeSec * 1000.0).toLong() }
                val start = earliestActive ?: latestCompleted ?: (now - 3_600_000L)
                start to now
            }
            ChartInterval.TODAY -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis to now
            }
            ChartInterval.CUSTOM -> req.customStartMs to req.customEndMs.coerceAtLeast(req.customStartMs + 1000L)
        }

        val samples = withContext(Dispatchers.IO) {
            val dbRows = persistence.getTelemetrySamplesSince(sinceMs, limit = 12_000)
                .filter { it.timestampMs in sinceMs..untilMs }
            val groupedByBucket = linkedMapOf<Long, MutableList<TelemetrySampleEntity>>()
            for (row in dbRows) {
                val bucket = (row.timestampMs / 2000L) * 2000L
                groupedByBucket.getOrPut(bucket) { mutableListOf() }.add(row)
            }

            val merged = groupedByBucket.entries.sortedBy { it.key }.map { (ts, rows) ->
                val pW = mutableMapOf<Int, Double>()
                val pV = mutableMapOf<Int, Double>()
                val pA = mutableMapOf<Int, Double>()
                val pWh = mutableMapOf<Int, Double>()
                val pProto = mutableMapOf<Int, String>()
                for (r in rows) {
                    pW[r.portId] = r.powerW
                    pV[r.portId] = r.voltageV
                    pA[r.portId] = r.currentA
                    pWh[r.portId] = r.cumulativeWh
                    pProto[r.portId] = r.protocolName
                }
                val mergedPortsForBucket = pW.mapValues { (pid, w) ->
                    PortTelemetry(
                        portId = pid,
                        portLabel = "",
                        voltageV = pV[pid] ?: 5.0,
                        currentA = pA[pid] ?: 0.0,
                        powerW = w,
                        enabled = true
                    )
                }
                ChartPointSample(
                    timestampMs = ts,
                    totalW = PortTelemetryDecoder.computeEffectiveTotalWatts(mergedPortsForBucket),
                    portW = pW,
                    portV = pV,
                    portA = pA,
                    portWh = pWh,
                    portProtocol = pProto
                )
            }.toMutableList()

            if (req.interval == ChartInterval.SEC_60 || req.interval == ChartInterval.MIN_5) {
                val livePoints = powerChartHistory.value.filter { it.timestampMs in sinceMs..untilMs }
                val curPorts = ports.value
                for (lp in livePoints) {
                    val alreadyClose = merged.any { abs(it.timestampMs - lp.timestampMs) < 2500L }
                    if (!alreadyClose) {
                        merged.add(
                            ChartPointSample(
                                timestampMs = lp.timestampMs,
                                totalW = lp.totalWatts,
                                portW = lp.portWatts,
                                portV = lp.portVolts.ifEmpty { curPorts.mapValues { it.value.voltageV } },
                                portA = lp.portAmps.ifEmpty { curPorts.mapValues { it.value.currentA } },
                                portWh = lp.portWh.ifEmpty { curPorts.mapValues { it.value.cumulativeWh } },
                                portProtocol = lp.portProtocols.ifEmpty { curPorts.mapValues { it.value.protocolName } }
                            )
                        )
                    }
                }
                merged.sortBy { it.timestampMs }
            }

            if (merged.size > 180) {
                val step = (merged.size / 180).coerceAtLeast(1)
                merged.filterIndexed { index, _ -> index % step == 0 || index == merged.lastIndex }
            } else {
                merged
            }
        }
        _chartSamples.value = samples
    }

    fun selectPortFocus(portId: Int) {
        if (portId in 1..4) {
            _selectedPortFocus.value = portId
        }
    }

    fun selectChartInterval(interval: ChartInterval) {
        _chartInterval.value = interval
    }

    fun setCustomChartRange(startMs: Long, endMs: Long) {
        _customRangeStartMs.value = startMs
        _customRangeEndMs.value = endMs
        _chartInterval.value = ChartInterval.CUSTOM
    }

    fun selectChartMetric(metric: ChartMetric) {
        _selectedMetrics.value = setOf(metric)
    }

    fun toggleChartMetric(metric: ChartMetric) {
        if (metric == ChartMetric.STACKED_W || metric == ChartMetric.PROTOCOLS) {
            _selectedMetrics.value = setOf(metric)
            return
        }
        val base = _selectedMetrics.value - ChartMetric.STACKED_W - ChartMetric.PROTOCOLS
        val updated = if (metric in base) {
            if (base.size > 1) base - metric else base
        } else {
            base + metric
        }
        _selectedMetrics.value = updated.ifEmpty { setOf(metric) }
    }

    // --- Session Replay ---

    fun openSessionReplay(session: ChargeSessionRecord) {
        replayJob?.cancel()
        _replayState.value = SessionReplayState(session = session, isLoading = true)
        _replayDeepAnalysis.value = null
        _replayGaps.value = emptyList()
        viewModelScope.launch {
            val startMs = (session.startTimeSec * 1000.0).toLong() - 5000L
            val endMs = ((session.endTimeSec ?: (System.currentTimeMillis() / 1000.0)) * 1000.0).toLong() + 5000L
            val loaded = withContext(Dispatchers.IO) {
                val allInWindow = persistence.getTelemetrySamplesSince(startMs, 12_000)
                    .filter { it.timestampMs in startMs..endMs }
                    .sortedBy { it.timestampMs }
                val portRows = allInWindow.filter { it.portId == session.portId }
                val gaps = persistence.getSessionGapsSince(startMs, 200)
                    .filter { it.gapEndMs >= startMs && it.gapStartMs <= endMs }
                val deep = SessionDeepAnalyzer.analyzeSession(
                    session = session,
                    portSamples = portRows,
                    allPortSamplesInWindow = allInWindow,
                    gaps = gaps,
                    peerSessions = completedSessions.value
                )
                Triple(portRows, gaps, deep)
            }
            _replayState.value = SessionReplayState(
                session = session,
                samples = loaded.first,
                cursorIndex = 0,
                isPlaying = false,
                isLoading = false
            )
            _replayGaps.value = loaded.second
            _replayDeepAnalysis.value = loaded.third
        }
    }

    fun scrubReplayTo(index: Int) {
        val current = _replayState.value
        if (current.samples.isEmpty()) return
        _replayState.value = current.copy(
            cursorIndex = index.coerceIn(0, current.samples.lastIndex)
        )
    }

    fun toggleReplayPlayback() {
        val current = _replayState.value
        if (current.samples.size <= 1) return
        if (current.isPlaying) {
            replayJob?.cancel()
            _replayState.value = current.copy(isPlaying = false)
            return
        }
        val startAt = if (current.cursorIndex >= current.samples.lastIndex) 0 else current.cursorIndex
        _replayState.value = current.copy(cursorIndex = startAt, isPlaying = true)
        replayJob = viewModelScope.launch {
            while (isActive) {
                delay(450L)
                val state = _replayState.value
                if (!state.isPlaying || state.samples.isEmpty()) break
                if (state.cursorIndex >= state.samples.lastIndex) {
                    _replayState.value = state.copy(isPlaying = false)
                    break
                } else {
                    _replayState.value = state.copy(cursorIndex = state.cursorIndex + 1)
                }
            }
        }
    }

    fun closeSessionReplay() {
        replayJob?.cancel()
        _replayState.value = SessionReplayState()
        _replayDeepAnalysis.value = null
        _replayGaps.value = emptyList()
    }

    // --- Hardware & Dispatcher Commands ---

    fun setPortEnabled(portId: Int, enabled: Boolean) {
        dispatchUiCommand(ChargerCommand.SetPortEnabled(portId, enabled))
    }

    fun executeMasterKillswitch() {
        viewModelScope.launch {
            val res = dispatcher.dispatch(CommandSource.UI, ChargerCommand.MasterKillswitch)
            _uiMessages.tryEmit(res.message)
        }
    }

    fun restoreKillswitch() {
        viewModelScope.launch {
            val res = dispatcher.dispatch(CommandSource.UI, ChargerCommand.RestoreKillswitch)
            _uiMessages.tryEmit(res.message)
        }
    }

    fun undoLastCommand() {
        viewModelScope.launch {
            val res = dispatcher.dispatch(CommandSource.UI, ChargerCommand.UndoLastCommand)
            if (!res.accepted) {
                _uiMessages.tryEmit(res.message)
            }
        }
    }

    fun setSceneMode(mode: Int) {
        dispatchUiCommand(ChargerCommand.SetSceneMode(mode))
    }

    fun setScreenTimeout(timeoutCode: Int) {
        dispatchUiCommand(ChargerCommand.SetScreenTimeout(timeoutCode))
    }

    fun setIdleScreenOff(enabled: Boolean) {
        dispatchUiCommand(ChargerCommand.SetIdleScreenOff(enabled))
    }

    fun setChargerLanguage(langCode: Int) {
        dispatchUiCommand(ChargerCommand.SetLanguage(langCode))
    }

    fun setOrientationLock(locked: Boolean) {
        dispatchUiCommand(ChargerCommand.SetOrientationLock(locked))
    }

    fun gotoLcdPage(pageIndex: Int) {
        dispatchUiCommand(ChargerCommand.GotoScreen(pageIndex))
    }

    fun rotateScreen180() {
        dispatchUiCommand(ChargerCommand.RotateScreen180)
    }

    fun setPortCountdown(portId: Int, minutes: Int) {
        dispatchUiCommand(ChargerCommand.SetPortCountdown(portId, minutes))
    }

    fun setUsbALowCurrent(enabled: Boolean) {
        dispatchUiCommand(ChargerCommand.SetUsbALowCurrent(enabled))
    }

    fun setPortProtocol(portId: Int, protocol: String, enabled: Boolean) {
        dispatchUiCommand(ChargerCommand.SetPortProtocol(portId, protocol, enabled))
    }

    fun setPortChargeLimit(portId: Int, whTarget: Double, mode: String, enabled: Boolean) {
        dispatchUiCommand(ChargerCommand.SetPortChargeLimit(portId, whTarget, mode, enabled))
    }

    fun executeSoftReset() {
        dispatchUiCommand(ChargerCommand.SoftReset)
    }

    fun setPortLoggingEnabled(portId: Int, enabled: Boolean) {
        dispatchUiCommand(ChargerCommand.SetPortLogging(portId, enabled))
    }

    fun setTelemetryMasterEnabled(enabled: Boolean) {
        dispatchUiCommand(ChargerCommand.SetTelemetryEnabled(enabled))
    }

    fun setLogOnlyWhenPhoneCharging(enabled: Boolean) {
        dispatchUiCommand(ChargerCommand.SetLogOnlyWhenPhoneCharging(enabled))
    }

    fun setPausePollingWhenScreenLocked(enabled: Boolean) {
        dispatchUiCommand(ChargerCommand.SetPausePollingWhenLocked(enabled))
    }

    fun setAutoReconnectConfigured(enabled: Boolean) {
        dispatchUiCommand(ChargerCommand.SetAutoReconnect(enabled))
    }

    fun refreshTelemetry() {
        dispatchUiCommand(ChargerCommand.RefreshTelemetry)
    }

    fun dismissAnomalyAlert(id: String) {
        cuktechManager.dismissAnomalyAlert(id)
    }

    fun applyChargingProfile(profile: ChargingProfileEntity) {
        dispatchUiCommand(
            ChargerCommand.ApplyChargingProfile(
                name = profile.name,
                sceneMode = profile.sceneMode,
                portSwitchBitmap = profile.portSwitchBitmap,
                screenTimeout = profile.screenTimeout,
                usbALowCurrent = profile.usbALowCurrent,
                idleScreenOff = profile.idleScreenOff,
                orientationLock = profile.orientationLock,
                whLimitPort1 = profile.whLimitPort1,
                whLimitPort2 = profile.whLimitPort2,
                whLimitPort3 = profile.whLimitPort3,
                whLimitPort4 = profile.whLimitPort4
            )
        )
    }

    fun saveCurrentAsProfile(name: String) {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return
        val s = settings.value
        val limits = chargeLimits.value
        val entity = ChargingProfileEntity(
            name = cleanName,
            sceneMode = s.sceneMode,
            portSwitchBitmap = s.portSwitchBitmap,
            screenTimeout = s.screenTimeout,
            usbALowCurrent = s.usbALowCurrent,
            idleScreenOff = s.idleScreenOff,
            orientationLock = s.orientationLock,
            whLimitPort1 = limits[1]?.takeIf { it.enabled }?.whTarget ?: 0.0,
            whLimitPort2 = limits[2]?.takeIf { it.enabled }?.whTarget ?: 0.0,
            whLimitPort3 = limits[3]?.takeIf { it.enabled }?.whTarget ?: 0.0,
            whLimitPort4 = limits[4]?.takeIf { it.enabled }?.whTarget ?: 0.0,
            isBuiltIn = false,
            createdAtMs = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            persistence.saveProfile(entity)
        }
    }

    fun deleteCustomProfile(profileId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.deleteCustomProfile(profileId)
        }
    }

    // --- Pro Features: Device Presets (ETA), Cable Diagnostics, Local Automation Rules ---

    fun savePortDevicePreset(
        portId: Int,
        presetType: String,
        deviceLabel: String,
        targetWh: Double,
        initialSocPct: Int,
        capacityMah: Int = ((targetWh * 1000.0) / 3.85).toInt().coerceIn(100, 50_000),
        cellSeriesCount: Int = 1
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.savePortDevicePreset(
                PortDevicePresetEntity(
                    portId = portId.coerceIn(1, 4),
                    presetType = presetType,
                    deviceLabel = deviceLabel.trim().take(32),
                    targetWh = targetWh.coerceIn(1.0, 500.0),
                    initialSocPct = initialSocPct.coerceIn(0, 99),
                    capacityMah = capacityMah.coerceIn(100, 50_000),
                    cellSeriesCount = cellSeriesCount.coerceIn(1, 3),
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun deletePortDevicePreset(portId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.deletePortDevicePreset(portId)
        }
    }

    fun resetCableDiagnosticStats(portId: Int) {
        cuktechManager.cableDiagnosticEngine.resetPortStats(portId)
    }

    fun saveAutomationRule(rule: AutomationRuleEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.saveAutomationRule(rule)
        }
    }

    fun toggleAutomationRuleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.setAutomationRuleEnabled(id, enabled)
        }
    }

    fun deleteAutomationRule(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.deleteAutomationRule(id)
        }
    }

    private fun dispatchUiCommand(command: ChargerCommand) {
        viewModelScope.launch {
            val res = dispatcher.dispatch(CommandSource.UI, command)
            if (res is CommandResult.Rejected) {
                _uiMessages.tryEmit(res.message)
            } else if (res is CommandResult.Executed && command is ChargerCommand.ApplyChargingProfile) {
                _uiMessages.tryEmit(res.message)
            }
        }
    }

    // --- Preferences, Tariff, Notifications & Localization ---

    fun updateAppLanguage(lang: String) {
        viewModelScope.launch { preferencesRepository.updateLanguage(lang) }
    }

    fun updateTheme(themeMode: String, amoledBlack: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateTheme(
                themeMode = themeMode,
                darkPalette = if (amoledBlack) "AMOLED_BLACK" else "CUKTECH_CYAN"
            )
        }
    }

    fun updateDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.updateDynamicColor(enabled) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { preferencesRepository.setOnboardingCompleted(true) }
    }

    fun replayOnboarding() {
        viewModelScope.launch { preferencesRepository.setOnboardingCompleted(false) }
    }

    fun updateStartDestination(dest: StartDestination) {
        viewModelScope.launch { preferencesRepository.updateStartDestination(dest) }
    }

    fun updateRetentionDays(days: Int) {
        viewModelScope.launch {
            preferencesRepository.updateRetentionDays(days)
            withContext(Dispatchers.IO) {
                persistence.archiveOldSessions(days)
                cuktechManager.reloadSessionsFromDb()
            }
        }
    }

    fun updateStitchGraceSec(seconds: Int) {
        viewModelScope.launch { preferencesRepository.updateSessionStitchGraceSec(seconds) }
    }

    fun updateTariff(ratePerKwh: Double, currencyCode: String) {
        viewModelScope.launch { preferencesRepository.updateTariff(ratePerKwh, currencyCode) }
    }

    fun updateTouTariff(
        touEnabled: Boolean,
        dayRatePerKwh: Double,
        nightRatePerKwh: Double,
        currencyCode: String,
        dayStartMinute: Int,
        nightStartMinute: Int
    ) {
        viewModelScope.launch {
            preferencesRepository.updateTouTariff(
                touEnabled = touEnabled,
                dayRatePerKwh = dayRatePerKwh,
                nightRatePerKwh = nightRatePerKwh,
                currencyCode = currencyCode,
                dayStartMinute = dayStartMinute,
                nightStartMinute = nightStartMinute
            )
        }
    }

    fun updateNotificationPolicy(
        hysteresisSec: Int,
        thresholdW: Double,
        quietHoursEnabled: Boolean,
        quietStartMin: Int,
        quietEndMin: Int,
        liveUpdatesEnabled: Boolean,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
        repeatEnabled: Boolean,
        repeatIntervalMin: Int,
        maxRepeats: Int
    ) {
        viewModelScope.launch {
            preferencesRepository.updateNotificationPolicy(
                hysteresisSec = hysteresisSec,
                thresholdW = thresholdW,
                quietHoursEnabled = quietHoursEnabled,
                quietStartMin = quietStartMin,
                quietEndMin = quietEndMin,
                liveUpdatesEnabled = liveUpdatesEnabled,
                soundEnabled = soundEnabled,
                vibrationEnabled = vibrationEnabled,
                repeatEnabled = repeatEnabled,
                repeatIntervalMin = repeatIntervalMin,
                maxRepeats = maxRepeats
            )
        }
    }

    fun updateThresholdAlerts(enabled: Boolean, highLoadWatts: Double, lowPowerDropWatts: Double) {
        viewModelScope.launch {
            preferencesRepository.updateThresholdAlerts(enabled, highLoadWatts, lowPowerDropWatts)
        }
    }

    fun selectSummaryPeriod(mode: SummaryPeriodMode, offset: Int) {
        _summaryPeriodMode.value = mode
        _summaryPeriodOffset.value = offset.coerceAtMost(0)
    }

    fun markAllEventsRead() {
        cuktechManager.eventLog.markAllRead()
    }

    fun clearChargerEvents() {
        cuktechManager.eventLog.clearAll()
    }

    fun savePortOrDeviceAlias(portId: Int, aliasName: String) {
        val mac = deviceInfo.value.macAddress.takeIf { it.isNotBlank() && it != "--" }
            ?: activeCred.value?.mac.orEmpty()
        if (mac.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            persistence.saveAlias(deviceMac = mac, portId = portId, aliasName = aliasName)
        }
    }

    fun aliasForPort(portId: Int, aliasesList: List<DeviceAliasEntity> = aliases.value): String? {
        val mac = deviceInfo.value.macAddress.takeIf { it.isNotBlank() && it != "--" }
            ?: activeCred.value?.mac.orEmpty()
        if (mac.isBlank()) return aliasesList.firstOrNull { it.portId == portId }?.aliasName
        return aliasesList.firstOrNull {
            it.deviceMac.equals(mac, ignoreCase = true) && it.portId == portId
        }?.aliasName
    }

    // --- Security, Automation & Cloud ---

    fun setTaskerEnabled(enabled: Boolean) = cuktechManager.authManager.setTaskerEnabled(enabled)
    fun setAdbBroadcastEnabled(enabled: Boolean) = cuktechManager.authManager.setAdbBroadcastEnabled(enabled)
    fun setTcpGatewayEnabled(enabled: Boolean) = cuktechManager.authManager.setTcpGatewayEnabled(enabled)
    fun setTcpAllowLanBind(allow: Boolean) = cuktechManager.authManager.setTcpAllowLanBind(allow)
    fun setTcpPort(port: Int) = cuktechManager.authManager.setTcpPort(port)

    fun toggleRevealToken() {
        _revealedToken.value = if (_revealedToken.value == null) {
            cuktechManager.authManager.getOrCreateToken()
        } else {
            null
        }
    }

    fun currentSecretToken(): String = cuktechManager.authManager.getOrCreateToken()

    fun rotateAutomationToken(): String {
        val next = cuktechManager.authManager.rotateToken()
        if (_revealedToken.value != null) {
            _revealedToken.value = next
        }
        return next
    }

    fun updateHomeAssistantConfig(enabled: Boolean, url: String, bearerToken: String?) {
        cloudIntegrations.updateConfig(
            haConfig.value.copy(
                haWebhookEnabled = enabled,
                haWebhookUrl = url
            ),
            bearerToken = bearerToken
        )
    }

    fun updateIntegrationsConfig(
        config: IntegrationConfig,
        bearerToken: String? = null,
        mqttPassword: String? = null
    ) {
        cloudIntegrations.updateConfig(config, bearerToken = bearerToken, mqttPassword = mqttPassword)
    }

    fun reconnectMqtt() {
        val activeMac = deviceInfo.value.macAddress.takeIf { it.isNotBlank() && it != "--" }
            ?: activeCred.value?.mac.orEmpty()
        val nodeId = if (activeMac.isNotBlank()) "cuktech_${activeMac.replace(":", "").lowercase()}" else "cuktech10_ultra"
        val devName = deviceInfo.value.name.takeIf { it.isNotBlank() } ?: "CUKTECH 10 Ultra"
        cloudIntegrations.connectOrReconnectMqtt(nodeId = nodeId, deviceName = devName)
    }

    fun publishHaDiscovery() {
        val activeMac = deviceInfo.value.macAddress.takeIf { it.isNotBlank() && it != "--" }
            ?: activeCred.value?.mac.orEmpty()
        val nodeId = if (activeMac.isNotBlank()) "cuktech_${activeMac.replace(":", "").lowercase()}" else "cuktech10_ultra"
        val devName = deviceInfo.value.name.takeIf { it.isNotBlank() } ?: "CUKTECH 10 Ultra"
        cloudIntegrations.publishHaDiscoveryNow(nodeId = nodeId, deviceName = devName)
    }

    // --- History Export & Clear ---

    fun exportCsvToDownloads(
        timeframe: CuktechPersistence.ExportTimeframe,
        scope: CuktechPersistence.ExportScope,
        onDone: (File) -> Unit
    ) {
        viewModelScope.launch {
            val activeStartMs = activeSessions.value.values
                .minOfOrNull { (it.startTimeSec * 1000.0).toLong() } ?: 0L
            val file = withContext(Dispatchers.IO) {
                persistence.exportSessionsAndHistoryCsv(timeframe, scope, activeStartMs)
            }
            onDone(file)
        }
    }

    fun exportCsvToSafUri(
        uri: Uri,
        timeframe: CuktechPersistence.ExportTimeframe,
        scope: CuktechPersistence.ExportScope,
        onDone: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val activeStartMs = activeSessions.value.values
                .minOfOrNull { (it.startTimeSec * 1000.0).toLong() } ?: 0L
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { os ->
                        persistence.exportCsvToStream(os, timeframe, scope, activeStartMs)
                    } ?: error("Null output stream")
                    true
                }.getOrDefault(false)
            }
            onDone(ok)
        }
    }

    fun clearHistory(option: CuktechPersistence.ClearHistoryOption) {
        viewModelScope.launch(Dispatchers.IO) {
            cuktechManager.clearHistory(option)
        }
    }

    // --- Diagnostics & Cloud Sync ---

    fun runDiagnostics() {
        viewModelScope.launch {
            bleDiagnostics.runBluetoothStackDiagnostics()
        }
    }

    fun clearDiagnostics() = bleDiagnostics.clearDiagnosticOutput()

    fun setConsoleLogLevel(level: BleBridgeService.Companion.LogLevel) {
        BleBridgeService.setLogLevel(level, getApplication())
        viewModelScope.launch { preferencesRepository.updateConsoleLogLevel(level.name) }
    }

    fun clearConsoleLogs() = BleBridgeService.clearLogs()

    fun syncXiaomiDevices(onDone: (String) -> Unit) {
        val session = accountSession.value ?: return
        viewModelScope.launch {
            val locCtx = getApplication<Application>().withAppLocale(preferences.value.appLanguage)
            val res = cloudClient.fetchDevicesAndBleKeys(session, searchAllRegionsIfEmpty = true)
            res.onSuccess { list ->
                if (list.isNotEmpty()) {
                    cuktechManager.credentialStore.saveDevices(list)
                    onDone(locCtx.getString(R.string.sync_devices_count_fmt, list.size))
                } else {
                    onDone(locCtx.getString(R.string.sync_no_devices))
                }
            }.onFailure { err ->
                onDone(locCtx.getString(R.string.sync_failed_fmt, err.message ?: ""))
            }
        }
    }

    // --- Encrypted SAF Backup & Restore + GitHub Updater ---

    fun exportEncryptedBackup(
        uri: Uri,
        passphrase: CharArray,
        includeSecrets: Boolean,
        onDone: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = persistence.buildBackupPayload(
                        preferencesJson = preferences.value.toJson(),
                        includeSecrets = includeSecrets,
                        devices = if (includeSecrets) savedDevices.value else emptyList()
                    )
                    val os = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("Unable to open destination stream")
                    os.use { stream ->
                        SafEncryptedBackupManager.encryptToStream(payload, passphrase, stream)
                    }
                    Unit
                }
            }
            Arrays.fill(passphrase, '\u0000')
            onDone(result)
        }
    }

    fun restoreEncryptedBackup(
        uri: Uri,
        passphrase: CharArray,
        replaceExisting: Boolean,
        onDone: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val isStream = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("Unable to open backup file")
                    val payload = isStream.use { stream ->
                        SafEncryptedBackupManager.decryptFromStream(stream, passphrase)
                    }
                    persistence.restoreBackupPayload(payload, replaceExisting)
                    preferencesRepository.applyFromBackupJson(payload.preferencesJson)

                    var credsRestored = 0
                    val secretsArr = payload.devicesSecretsArray
                    if (payload.includesSecrets && secretsArr != null && secretsArr.length() > 0) {
                        val importedCreds = mutableListOf<CuktechDeviceCredentials>()
                        for (i in 0 until secretsArr.length()) {
                            val obj = secretsArr.optJSONObject(i) ?: continue
                            val mac = obj.optString("mac", "").trim()
                            if (mac.isBlank()) continue
                            importedCreds.add(
                                CuktechDeviceCredentials(
                                    did = obj.optString("did", ""),
                                    name = obj.optString("name", "CUKTECH 10 Ultra"),
                                    model = obj.optString("model", "njcuk.fitting.ad1204"),
                                    mac = mac,
                                    tokenHex = obj.optString("tokenHex", ""),
                                    bleKeyHex = obj.optString("bleKeyHex", ""),
                                    region = obj.optString("region", "cn")
                                )
                            )
                        }
                        if (importedCreds.isNotEmpty() && cuktechManager.credentialStore.saveDevices(importedCreds)) {
                            credsRestored = importedCreds.size
                        }
                    }

                    cuktechManager.reloadSessionsFromDb()
                    val locCtx = getApplication<Application>().withAppLocale(preferences.value.appLanguage)
                    val sessCount = payload.sessionsArray.length()
                    val profCount = payload.profilesArray.length()
                    if (credsRestored > 0) {
                        locCtx.getString(R.string.restore_summary_with_keys_fmt, sessCount, profCount, credsRestored)
                    } else {
                        locCtx.getString(R.string.restore_summary_fmt, sessCount, profCount)
                    }
                }
            }
            Arrays.fill(passphrase, '\u0000')
            onDone(result)
        }
    }

    fun checkForUpdates(onCompleted: ((Result<ReleaseUpdateInfo>) -> Unit)? = null) {
        BleBridgeService.checkForUpdates(onCompleted)
    }

    fun downloadAndVerifyLatestApk() {
        BleBridgeService.downloadAndVerifyLatestApk(getApplication())
    }

    fun resetUpdateDownloadState() {
        BleBridgeService.resetUpdateDownloadState()
    }

    // --- Hybrid Continuity, Stationary Bridge Sync, Multi-Charger & BLE Exit Policy ---

    fun setAnalyticsWindowDays(days: Int) {
        _analyticsWindowDays.value = days.coerceIn(1, 30)
    }

    fun updateBridgeConfig(enabled: Boolean, url: String, token: String?) {
        viewModelScope.launch {
            val trimmedUrl = url.trim()
            if (enabled && !BridgeSyncClient.isValidBridgeUrl(trimmedUrl)) {
                _bridgeStatusResult.value = BridgeStatusResult(online = false, errorMessage = "Invalid URL")
                return@launch
            }
            preferencesRepository.updateBridgeSettings(enabled, trimmedUrl)
            if (!token.isNullOrBlank()) {
                cuktechManager.credentialStore.putEncryptedSecret("bridge_bearer_token", token.trim())
            } else if (token == "") {
                // Keep existing token if user left field empty
            }
            if (enabled && BridgeSyncClient.isValidBridgeUrl(trimmedUrl)) {
                testBridgeConnection(trimmedUrl, token)
            }
        }
    }

    fun clearBridgeToken() {
        viewModelScope.launch(Dispatchers.IO) {
            cuktechManager.credentialStore.removeEncryptedSecret("bridge_bearer_token")
        }
    }

    fun testBridgeConnection(url: String, overrideToken: String? = null) {
        viewModelScope.launch {
            val token = overrideToken?.takeIf { it.isNotBlank() }
                ?: withContext(Dispatchers.IO) {
                    cuktechManager.credentialStore.getEncryptedSecret("bridge_bearer_token")
                }
            _bridgeStatusResult.value = BridgeSyncClient.fetchStatus(url, token)
        }
    }

    fun syncFromBridgeNow(onDone: ((Result<Int>) -> Unit)? = null) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { cuktechManager.syncFromBridgeNow(silent = false) }
            recomputePortLifecycleAnalytics(_analyticsWindowDays.value)
            onDone?.invoke(res)
        }
    }

    fun updateBleExitAlertPolicy(enabled: Boolean, thresholdDbm: Int) {
        viewModelScope.launch {
            preferencesRepository.updateBleExitAlertPolicy(enabled, thresholdDbm)
            cuktechManager.refreshIntegritySnapshot()
        }
    }

    fun updateChargerNodeMeta(mac: String, name: String, locationContext: String, mainsVoltageMode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val norm = mac.uppercase().trim()
            val cur = persistence.findChargerNode(norm)
            persistence.upsertChargerNode(
                (cur ?: ChargerNodeEntity(deviceMac = norm, name = name)).copy(
                    name = name.trim().ifBlank { cur?.name ?: "CUKTECH 10 Ultra" },
                    locationContext = locationContext.trim().ifBlank { "Default" },
                    mainsVoltageMode = if (mainsVoltageMode == 110) 110 else 220
                )
            )
        }
    }

    fun switchActiveCharger(mac: String) {
        val norm = mac.uppercase().trim()
        cuktechManager.credentialStore.selectActiveDevice(norm)
        viewModelScope.launch {
            dispatcher.dispatch(CommandSource.UI, ChargerCommand.ConnectCharger(norm))
        }
    }

    // --- Stage 3 & Stage 4 Actions: Simulator, Bookmarks/Tags, Two-Session Compare, Black Box ---

    fun setSimulatorConfig(sceneMode: Int, portsMask: Int) {
        _simSceneMode.value = sceneMode.coerceIn(1, 4)
        _simPortsMask.value = portsMask and 0x0F
    }

    fun toggleSimulatorPortBit(portId: Int) {
        if (portId !in 1..4) return
        val bit = 1 shl (portId - 1)
        _simPortsMask.value = _simPortsMask.value xor bit
    }

    fun addReplayBookmark(
        sessionId: Long,
        sample: TelemetrySampleEntity?,
        tagLabel: String,
        noteText: String,
        isFavorite: Boolean = false
    ) {
        val cleanTag = tagLabel.trim().take(32).ifBlank { "Bookmark" }
        val cleanNote = noteText.trim().take(240)
        viewModelScope.launch(Dispatchers.IO) {
            persistence.saveSessionAnnotation(
                SessionAnnotationEntity(
                    sessionId = sessionId,
                    timestampMs = sample?.timestampMs ?: 0L,
                    tagLabel = cleanTag,
                    noteText = cleanNote,
                    isFavorite = isFavorite,
                    voltageV = sample?.voltageV ?: 0.0,
                    currentA = sample?.currentA ?: 0.0,
                    powerW = sample?.powerW ?: 0.0,
                    protocolName = sample?.protocolName.orEmpty(),
                    createdAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteSessionAnnotation(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.deleteSessionAnnotation(id)
        }
    }

    fun selectComparisonSessions(idA: Long?, idB: Long?) {
        _comparisonSessionAId.value = idA
        _comparisonSessionBId.value = idB
        recomputeComparison()
    }

    fun setComparisonAlignment(mode: ComparisonAlignmentMode) {
        _comparisonAlignment.value = mode
        recomputeComparison()
    }

    private fun recomputeComparison() {
        val all = activeSessions.value.values.toList() + completedSessions.value
        val sA = all.firstOrNull { it.id == _comparisonSessionAId.value }
        val sB = all.firstOrNull { it.id == _comparisonSessionBId.value }
        val mode = _comparisonAlignment.value
        viewModelScope.launch(Dispatchers.Default) {
            val samplesA = if (sA != null) {
                val startMs = (sA.startTimeSec * 1000.0).toLong() - 2000L
                val endMs = ((sA.endTimeSec ?: sA.lastSampleTimeSec) * 1000.0).toLong() + 2000L
                persistence.getTelemetrySamplesSince(startMs, 6000)
                    .filter { it.portId == sA.portId && it.timestampMs in startMs..endMs }
            } else emptyList()
            val samplesB = if (sB != null) {
                val startMs = (sB.startTimeSec * 1000.0).toLong() - 2000L
                val endMs = ((sB.endTimeSec ?: sB.lastSampleTimeSec) * 1000.0).toLong() + 2000L
                persistence.getTelemetrySamplesSince(startMs, 6000)
                    .filter { it.portId == sB.portId && it.timestampMs in startMs..endMs }
            } else emptyList()
            _sessionComparisonResult.value = SessionDeepAnalyzer.compareSessions(
                sessionA = sA,
                samplesA = samplesA,
                sessionB = sB,
                samplesB = samplesB,
                alignment = mode
            )
        }
    }

    fun buildAnnotatedReplayJson(session: ChargeSessionRecord, onReady: (String) -> Unit) {
        val state = _replayState.value
        val deep = _replayDeepAnalysis.value
        val gaps = _replayGaps.value
        viewModelScope.launch(Dispatchers.IO) {
            val annotations = persistence.getSessionAnnotationsForSession(session.id)
            val json = SessionDeepAnalyzer.exportAnnotatedReplayJson(
                session = session,
                samples = state.samples,
                gaps = gaps,
                annotations = annotations,
                deepAnalysis = deep
            )
            withContext(Dispatchers.Main) { onReady(json) }
        }
    }

    fun captureManualBlackBox(note: String = "Manual capture") {
        viewModelScope.launch(Dispatchers.IO) {
            cuktechManager.captureManualBlackBox(note)
        }
    }

    // --- Stage 5: Phone Battery Calibration, Device & Cable Inventory, Cable Lab & Passport ---

    fun refreshPhoneBattery() {
        viewModelScope.launch(Dispatchers.Default) {
            _phoneBattery.value = runCatching {
                PhoneBatteryMonitor.readSnapshot(getApplication(), ports.value)
            }.getOrDefault(PhoneBatterySnapshot())
        }
    }

    fun saveDeviceInventory(device: DeviceInventoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.saveDeviceInventory(device)
        }
    }

    fun deleteDeviceInventory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.deleteDeviceInventory(id)
        }
    }

    fun saveCableInventory(cable: CableInventoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.saveCableInventory(cable)
        }
    }

    fun deleteCableInventory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.deleteCableInventory(id)
        }
    }

    fun deleteCableLabTest(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.deleteCableLabTest(id)
        }
    }

    fun confirmSuggestedDeviceMatch(suggestion: DeviceMatchSuggestion) {
        viewModelScope.launch(Dispatchers.IO) {
            val dev = suggestion.device
            val sess = suggestion.session
            val updated = dev.copy(
                assignedPortId = if (dev.assignedPortId == 0) sess.portId else dev.assignedPortId,
                totalRecordedWh = dev.totalRecordedWh + sess.whAccumulated,
                sessionCount = dev.sessionCount + 1,
                lastChargeMs = System.currentTimeMillis()
            )
            persistence.saveDeviceInventory(updated)
            val activeMac = deviceInfo.value.macAddress.takeIf { it.isNotBlank() && it != "--" }
                ?: activeCred.value?.mac.orEmpty()
            if (activeMac.isNotBlank()) {
                persistence.saveAlias(activeMac, sess.portId, dev.name)
            }
            val presetType = when (dev.category.uppercase()) {
                "LAPTOP" -> "LAPTOP_14"
                "PHONE" -> "PHONE"
                "TABLET" -> "TABLET"
                "POWERBANK" -> "POWERBANK"
                else -> "CUSTOM"
            }
            persistence.savePortDevicePreset(
                PortDevicePresetEntity(
                    portId = sess.portId,
                    presetType = presetType,
                    deviceLabel = dev.name,
                    targetWh = dev.ratedWh.coerceIn(1.0, 200.0),
                    initialSocPct = 20,
                    capacityMah = ((dev.ratedWh * 1000.0) / 3.85).toInt().coerceIn(500, 30_000),
                    cellSeriesCount = if (dev.category.equals("LAPTOP", ignoreCase = true)) 3 else 1,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun clearCableLabNotice() {
        _cableLabLastNotice.value = null
    }

    fun analyzeRecentWindowForCableLab(portId: Int, cable: CableInventoryEntity?) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val samples = persistence.getTelemetrySamplesSince(now - 30 * 60_000L, 1000)
            when (val res = InventoryAndCableLabEngine.evaluateFromTelemetryEntities(portId, cable, samples)) {
                is CableLabEvaluationResult.Valid -> {
                    persistence.recordCableLabTest(res.toEntity(now))
                    _cableLabLastNotice.value = "OK"
                }
                is CableLabEvaluationResult.Insufficient -> {
                    _cableLabLastNotice.value = res.reasonCode
                }
            }
        }
    }

    fun startGuidedCableLabTest(portId: Int, cable: CableInventoryEntity?) {
        cableLabJob?.cancel()
        _cableLabLastNotice.value = null
        val targetSamples = 12
        _cableLabRunner.value = CableLabRunnerState(
            isRunning = true,
            portId = portId,
            cableId = cable?.id ?: 0L,
            collectedCount = 0,
            targetCount = targetSamples,
            lastStatusCode = ""
        )
        cableLabJob = viewModelScope.launch(Dispatchers.Default) {
            val collected = mutableListOf<CableLabSampleInput>()
            for (i in 1..targetSamples) {
                if (!isActive) break
                val port = ports.value[portId]
                if (port != null && port.enabled && port.voltageV >= 4.0) {
                    collected += CableLabSampleInput(
                        voltageV = port.voltageV,
                        currentA = port.currentA,
                        protocolName = port.protocolName
                    )
                }
                _cableLabRunner.value = _cableLabRunner.value.copy(collectedCount = i)
                if (i < targetSamples) delay(1000L)
            }
            val result = InventoryAndCableLabEngine.evaluateCableLabSamples(
                portId = portId,
                cableId = cable?.id ?: 0L,
                cableName = cable?.name ?: "Port $portId Probe",
                samples = collected
            )
            when (result) {
                is CableLabEvaluationResult.Valid -> {
                    withContext(Dispatchers.IO) {
                        persistence.recordCableLabTest(result.toEntity())
                    }
                    _cableLabLastNotice.value = "OK"
                    _cableLabRunner.value = _cableLabRunner.value.copy(isRunning = false, lastStatusCode = "OK")
                }
                is CableLabEvaluationResult.Insufficient -> {
                    _cableLabLastNotice.value = result.reasonCode
                    _cableLabRunner.value = _cableLabRunner.value.copy(
                        isRunning = false,
                        lastStatusCode = result.reasonCode
                    )
                }
            }
        }
    }

    fun cancelGuidedCableLabTest() {
        cableLabJob?.cancel()
        _cableLabRunner.value = _cableLabRunner.value.copy(isRunning = false)
    }

    fun buildAnonymizedLabJson(): String {
        return InventoryAndCableLabEngine.exportAnonymizedLabJson(
            passport = chargerPassport.value,
            cableTests = cableLabTests.value,
            cables = cableInventory.value,
            scenarioSnapshot = _scenarioAndPdoSnapshot.value
        )
    }

    // --- Stage 6: Automation Safety Simulation, Deadline Queue & NFC URI Action ---

    fun runAutomationSimulation(hoursBack: Int = 24) {
        viewModelScope.launch(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val samples = persistence.getTelemetrySamplesSince(
                now - hoursBack.coerceIn(1, 168) * 3_600_000L,
                limit = 10_000
            )
            val report = AutomationSafetyAndDeadlineEngine.simulateRulesOnHistory(
                rules = automationRules.value,
                telemetrySamples = samples,
                initialSceneMode = settings.value.sceneMode,
                phoneSocPct = _phoneBattery.value.socPct,
                phoneIsCharging = _phoneBattery.value.isCharging
            )
            _automationSimResult.value = report
        }
    }

    fun saveDeadlineTask(task: DeadlineTaskEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.saveDeadlineTask(task)
        }
    }

    fun toggleDeadlineTaskEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.setDeadlineTaskEnabled(id, enabled)
        }
    }

    fun deleteDeadlineTask(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.deleteDeadlineTask(id)
        }
    }

    fun executeNfcActionUri(rawUri: String) {
        val parsed = AutomationSafetyAndDeadlineEngine.parseNfcActionUri(rawUri, ports.value)
        if (parsed == null) {
            _nfcLastStatus.value = "Invalid URI: $rawUri"
            _uiMessages.tryEmit(_nfcLastStatus.value!!)
            return
        }
        viewModelScope.launch {
            val res = dispatcher.dispatch(
                source = CommandSource.WIDGET,
                command = parsed.command,
                clientKey = "nfc_uri:${parsed.description}"
            )
            val status = "${parsed.description} → ${res.message}"
            _nfcLastStatus.value = status
            _uiMessages.tryEmit(status)
        }
    }

    // --- Stage 7 & Stage 8 Actions: LAN Bridge Discovery & Remote Control, WebDAV, Workspaces/KPIs, Config Share & Support Bundle ---

    fun discoverLanBridges() {
        if (_isDiscoveringBridges.value) return
        _isDiscoveringBridges.value = true
        viewModelScope.launch {
            try {
                _discoveredBridges.value = BridgeDiscoveryHelper.discoverOnLan(getApplication())
            } finally {
                _isDiscoveringBridges.value = false
            }
        }
    }

    fun sendRemoteBridgePortToggle(portId: Int, enabled: Boolean) {
        val cmdJson = JSONObject().apply {
            put("type", "set_port")
            put("port", portId)
            put("enabled", enabled)
        }.toString()
        sendRemoteBridgeCommand(cmdJson, "Port $portId -> ${if (enabled) "ON" else "OFF"}")
    }

    fun sendRemoteBridgeScene(mode: Int) {
        val cmdJson = JSONObject().apply {
            put("type", "set_scene")
            put("mode", mode)
        }.toString()
        sendRemoteBridgeCommand(cmdJson, "Scene -> $mode")
    }

    private fun sendRemoteBridgeCommand(cmdJson: String, label: String) {
        val url = preferences.value.bridgeEndpointUrl
        if (!BridgeSyncClient.isValidBridgeUrl(url)) {
            _bridgeRemoteCmdStatus.value = "Invalid bridge URL"
            return
        }
        val activeMac = deviceInfo.value.macAddress.takeIf { it.isNotBlank() && it != "--" }
            ?: activeCred.value?.mac.orEmpty()
        viewModelScope.launch {
            val token = withContext(Dispatchers.IO) {
                cuktechManager.credentialStore.getEncryptedSecret("bridge_bearer_token")
            }
            val res = BridgeSyncClient.sendRemoteCommand(url, token, activeMac, cmdJson)
            _bridgeRemoteCmdStatus.value = res.fold(
                onSuccess = { "$label OK" },
                onFailure = { "$label FAIL: ${it.message ?: "error"}" }
            )
        }
    }

    fun saveWebDavConfig(config: WebDavConfig, passwordOverride: String?): Result<WebDavConfig> {
        val res = webDavBackupClient.saveConfig(config, passwordOverride)
        res.onSuccess { saved ->
            _webDavConfig.value = saved
            _webDavStatus.value = _webDavStatus.value.copy(lastMessage = "Saved", isError = false)
        }.onFailure { err ->
            _webDavStatus.value = _webDavStatus.value.copy(lastMessage = err.message, isError = true)
        }
        return res
    }

    fun testWebDavConnection(baseUrl: String, username: String, passwordOverride: String?) {
        viewModelScope.launch {
            _webDavStatus.value = _webDavStatus.value.copy(busy = true, lastMessage = null, isError = false)
            val res = webDavBackupClient.testConnection(baseUrl, username, passwordOverride)
            _webDavStatus.value = res.fold(
                onSuccess = { msg ->
                    WebDavSyncStatus(busy = false, lastSuccessMs = System.currentTimeMillis(), lastMessage = msg, isError = false)
                },
                onFailure = { err ->
                    WebDavSyncStatus(busy = false, lastMessage = err.message ?: "Error", isError = true)
                }
            )
        }
    }

    fun uploadEncryptedWebDavBackup(
        baseUrl: String,
        username: String,
        passwordOverride: String?,
        remoteFilename: String,
        passphrase: CharArray,
        includeSecrets: Boolean
    ) {
        viewModelScope.launch {
            _webDavStatus.value = _webDavStatus.value.copy(busy = true, lastMessage = null, isError = false)
            try {
                val payload = withContext(Dispatchers.IO) {
                    persistence.buildBackupPayload(
                        preferencesJson = preferences.value.toJson(),
                        includeSecrets = includeSecrets,
                        devices = if (includeSecrets) savedDevices.value else emptyList()
                    )
                }
                val res = webDavBackupClient.uploadEncryptedBackup(
                    baseUrl = baseUrl,
                    username = username,
                    passwordOverride = passwordOverride,
                    remoteFilename = remoteFilename,
                    payload = payload,
                    backupPassphrase = passphrase
                )
                _webDavStatus.value = res.fold(
                    onSuccess = { bytes ->
                        WebDavSyncStatus(
                            busy = false,
                            lastSuccessMs = System.currentTimeMillis(),
                            lastMessage = "Uploaded $bytes bytes (AES-256-GCM)",
                            isError = false
                        )
                    },
                    onFailure = { err ->
                        WebDavSyncStatus(busy = false, lastMessage = err.message ?: "Upload failed", isError = true)
                    }
                )
            } finally {
                Arrays.fill(passphrase, '\u0000')
            }
        }
    }

    fun restoreEncryptedWebDavBackup(
        baseUrl: String,
        username: String,
        passwordOverride: String?,
        remoteFilename: String,
        passphrase: CharArray,
        replaceExisting: Boolean
    ) {
        viewModelScope.launch {
            _webDavStatus.value = _webDavStatus.value.copy(busy = true, lastMessage = null, isError = false)
            try {
                val res = webDavBackupClient.downloadAndDecryptBackup(
                    baseUrl = baseUrl,
                    username = username,
                    passwordOverride = passwordOverride,
                    remoteFilename = remoteFilename,
                    backupPassphrase = passphrase
                )
                if (res.isSuccess) {
                    val payload = res.getOrThrow()
                    withContext(Dispatchers.IO) {
                        persistence.restoreBackupPayload(payload, replaceExisting)
                        preferencesRepository.applyFromBackupJson(payload.preferencesJson)
                    }
                    cuktechManager.reloadSessionsFromDb()
                    _webDavStatus.value = WebDavSyncStatus(
                        busy = false,
                        lastSuccessMs = System.currentTimeMillis(),
                        lastMessage = "Restored ${payload.sessionsArray.length()} sessions, ${payload.profilesArray.length()} profiles",
                        isError = false
                    )
                } else {
                    _webDavStatus.value = WebDavSyncStatus(
                        busy = false,
                        lastMessage = res.exceptionOrNull()?.message ?: "Restore failed",
                        isError = true
                    )
                }
            } finally {
                Arrays.fill(passphrase, '\u0000')
            }
        }
    }

    fun selectWorkspace(workspaceId: String) {
        viewModelScope.launch {
            preferencesRepository.updateActiveWorkspace(workspaceId)
        }
    }

    fun addCustomKpi(
        title: String,
        formulaType: KpiFormulaType,
        unit: String,
        targetValue: Double
    ): String? {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return "kpi_err_title_empty"
        val unitErr = CustomKpiAndWorkspaceEngine.validateUnitCompatibility(formulaType, unit)
        if (unitErr != null) return unitErr
        val current = CustomKpiAndWorkspaceEngine.deserializeKpis(preferences.value.customKpisJson)
        val updated = current + CustomKpiDefinition(
            id = UUID.randomUUID().toString().take(8),
            title = cleanTitle,
            formulaType = formulaType,
            unit = unit.trim(),
            targetValue = targetValue.coerceAtLeast(0.0)
        )
        viewModelScope.launch {
            preferencesRepository.updateCustomKpisJson(CustomKpiAndWorkspaceEngine.serializeKpis(updated))
        }
        return null
    }

    fun deleteCustomKpi(kpiId: String) {
        val current = CustomKpiAndWorkspaceEngine.deserializeKpis(preferences.value.customKpisJson)
        val updated = current.filterNot { it.id == kpiId }
        viewModelScope.launch {
            preferencesRepository.updateCustomKpisJson(CustomKpiAndWorkspaceEngine.serializeKpis(updated))
        }
    }

    fun buildShareableConfigJson(): String {
        val kpis = CustomKpiAndWorkspaceEngine.deserializeKpis(preferences.value.customKpisJson)
        return ConfigShareAndSupportBundle.buildShareableConfigJson(
            preferences = preferences.value,
            profiles = profiles.value,
            rules = automationRules.value,
            presets = portDevicePresets.value.values.toList(),
            kpis = kpis
        )
    }

    fun previewShareableConfig(rawJson: String): Result<ShareableConfigBundle> {
        return ConfigShareAndSupportBundle.parseShareableConfigJson(rawJson)
    }

    fun importShareableConfig(
        bundle: ShareableConfigBundle,
        selection: ShareableConfigImportSelection,
        onDone: (Int) -> Unit
    ) {
        viewModelScope.launch {
            var count = 0
            if (selection.includePreferences) {
                preferencesRepository.applyFromBackupJson(bundle.preferencesJson)
                count++
            }
            if (selection.includeKpis && bundle.kpis.isNotEmpty()) {
                val existing = CustomKpiAndWorkspaceEngine.deserializeKpis(preferences.value.customKpisJson)
                val merged = (existing + bundle.kpis).distinctBy { "${it.title}_${it.formulaType.name}" }
                preferencesRepository.updateCustomKpisJson(CustomKpiAndWorkspaceEngine.serializeKpis(merged))
                count += bundle.kpis.size
            }
            withContext(Dispatchers.IO) {
                if (selection.includeProfiles) {
                    bundle.profiles.forEach { prof ->
                        persistence.saveProfile(prof.copy(id = 0L))
                        count++
                    }
                }
                if (selection.includeRules) {
                    bundle.rules.forEach { rule ->
                        persistence.saveAutomationRule(rule.copy(id = 0L))
                        count++
                    }
                }
                if (selection.includePresets) {
                    bundle.presets.forEach { preset ->
                        persistence.savePortDevicePreset(preset)
                        count++
                    }
                }
            }
            onDone(count)
        }
    }

    fun buildAnonymizedSupportBundle(): String {
        return ConfigShareAndSupportBundle.buildAnonymizedSupportBundle(
            stackDiagnosticsText = diagStatus.value.lastDiagnosticOutput,
            doctorReport = powerDoctorReport.value,
            integrity = integritySnapshot.value,
            linkEvents = bleLinkEvents.value,
            commandAudit = commandAuditLog.value,
            recentLogLines = logs.value.map { it.message }
        )
    }

    // --- Practical Feature Suite: Away Summary, Wi-Fi Context, Card Customization, CSV History Import ---

    fun dismissAwaySummary() {
        cuktechManager.dismissAwaySummary()
    }

    fun refreshWifiContext() {
        cuktechManager.refreshWifiContext()
    }

    fun bindCurrentWifi(): Boolean {
        val snap = cuktechManager.wifiContextMonitor.refreshNow()
        if (!snap.isOnWifi || snap.currentKey.isBlank()) return false
        val label = snap.displayLabel.ifBlank { "Wi-Fi" }
        viewModelScope.launch {
            preferencesRepository.bindHomeWifi(snap.currentKey, label)
            cuktechManager.wifiContextMonitor.updateBindingPolicy(true, snap.currentKey)
        }
        return true
    }

    fun clearWifiBinding() {
        viewModelScope.launch {
            preferencesRepository.clearHomeWifiBinding()
            cuktechManager.wifiContextMonitor.updateBindingPolicy(false, "")
        }
    }

    fun updateWifiContextPolicy(
        contextEnabled: Boolean,
        bleOnlyAtHome: Boolean,
        leaveAlertEnabled: Boolean,
        leaveThresholdWatts: Double
    ) {
        viewModelScope.launch {
            preferencesRepository.updateWifiContextPolicy(
                contextEnabled = contextEnabled,
                bleOnlyAtHome = bleOnlyAtHome,
                leaveAlertEnabled = leaveAlertEnabled,
                leaveThresholdWatts = leaveThresholdWatts
            )
        }
    }

    fun toggleAnalyticsCardHidden(cardId: String) {
        val cleanId = cardId.trim()
        if (cleanId.isEmpty()) return
        val current = preferences.value.hiddenAnalyticsCardSet().toMutableSet()
        if (!current.add(cleanId)) {
            current.remove(cleanId)
        }
        viewModelScope.launch {
            preferencesRepository.updateHiddenAnalyticsCards(current)
        }
    }

    fun resetHiddenAnalyticsCards() {
        viewModelScope.launch {
            preferencesRepository.updateHiddenAnalyticsCards(emptySet())
        }
    }

    fun importHistoryFromCsvUri(uri: Uri, onDone: (Result<CsvImportResult>) -> Unit) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val csvText = getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("Could not read CSV file")
                    val activeMac = deviceInfo.value.macAddress
                        .takeIf { it.isNotBlank() && it != "--" }
                        ?: activeCred.value?.mac.orEmpty()
                    val result = persistence.importSessionsFromCsv(csvText, activeMac)
                    cuktechManager.reloadSessionsFromDb()
                    cuktechManager.refreshIntegritySnapshot()
                    result
                }
            }
            onDone(res)
        }
    }

    override fun onCleared() {
        _revealedToken.value = null
        replayJob?.cancel()
        cableLabJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DashboardViewModel(application) as T
                }
            }
    }
}
