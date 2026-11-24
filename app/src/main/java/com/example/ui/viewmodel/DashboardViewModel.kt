package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BleBridgeService
import com.example.R
import com.example.ui.i18n.resolveLocale
import com.example.ui.i18n.withAppLocale
import com.example.cuktech.automation.ChargerCommand
import com.example.cuktech.automation.CommandDispatcher
import com.example.cuktech.automation.CommandResult
import com.example.cuktech.automation.CommandSource
import com.example.cuktech.ble.CuktechBleManager
import com.example.cuktech.cloud.XiaomiCloudClient
import com.example.cuktech.db.CuktechPersistence
import com.example.cuktech.db.room.AutomationRuleEntity
import com.example.cuktech.db.room.ChargingProfileEntity
import com.example.cuktech.db.room.DeviceAliasEntity
import com.example.cuktech.db.room.PortDevicePresetEntity
import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.diagnostics.BleDiagnostics
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.integrations.CloudIntegrations
import com.example.cuktech.integrations.IntegrationConfig
import com.example.cuktech.notifications.AnomalyAlert
import com.example.cuktech.notifications.ChargerEvent
import com.example.cuktech.power.ChargerSummaryCalculator
import com.example.cuktech.power.DailyWhBucket
import com.example.cuktech.power.EtaEstimate
import com.example.cuktech.power.EtaEstimator
import com.example.cuktech.power.PeriodSummarySnapshot
import com.example.cuktech.power.PortCableDiagnostic
import com.example.cuktech.power.PowerBudgetCalculator
import com.example.cuktech.power.PowerBudgetSnapshot
import com.example.cuktech.power.SummaryPeriodMode
import com.example.cuktech.prefs.AppPreferences
import com.example.cuktech.prefs.AppPreferencesRepository
import com.example.cuktech.prefs.StartDestination
import com.example.cuktech.protocol.C3ASharedBusState
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.protocol.PortTelemetryDecoder
import com.example.cuktech.security.CuktechDeviceCredentials
import com.example.cuktech.security.SafEncryptedBackupManager
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
import java.io.File
import java.util.Arrays
import java.util.Calendar
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

    private var replayJob: Job? = null

    init {
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
        viewModelScope.launch {
            val startMs = (session.startTimeSec * 1000.0).toLong() - 5000L
            val endMs = ((session.endTimeSec ?: (System.currentTimeMillis() / 1000.0)) * 1000.0).toLong() + 5000L
            val rows = withContext(Dispatchers.IO) {
                persistence.getTelemetrySamplesSince(startMs, 10_000)
                    .filter { it.portId == session.portId && it.timestampMs in startMs..endMs }
                    .sortedBy { it.timestampMs }
            }
            _replayState.value = SessionReplayState(
                session = session,
                samples = rows,
                cursorIndex = 0,
                isPlaying = false,
                isLoading = false
            )
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
            IntegrationConfig(
                haWebhookEnabled = enabled,
                haWebhookUrl = url,
                hasBearerToken = haConfig.value.hasBearerToken
            ),
            bearerToken = bearerToken
        )
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

    override fun onCleared() {
        _revealedToken.value = null
        replayJob?.cancel()
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
