package com.example.cuktech.automation

import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.energy.PortChargeLimit
import com.example.cuktech.protocol.ChargerSettingsState
import com.example.cuktech.protocol.CuktechProtocol
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.security.AutomationAuthManager
import com.example.cuktech.security.LogSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CommandSource {
    UI,
    WIDGET,
    TASKER,
    ADB_BROADCAST,
    TCP_GATEWAY,
    AUTOMATION_RULE,
    INTERNAL_SAFETY;

    val requiresExternalAuth: Boolean
        get() = this == TASKER || this == ADB_BROADCAST || this == TCP_GATEWAY
}

sealed class ChargerCommand {
    data class SetPortEnabled(val portId: Int, val enabled: Boolean) : ChargerCommand()
    data class SetSceneMode(val mode: Int) : ChargerCommand()
    data class SetScreenTimeout(val timeoutCode: Int) : ChargerCommand()
    data class SetPortCountdown(val portId: Int, val minutes: Int) : ChargerCommand()
    data class SetUsbALowCurrent(val enabled: Boolean) : ChargerCommand()
    data class SetIdleScreenOff(val enabled: Boolean) : ChargerCommand()
    data class SetLanguage(val langCode: Int) : ChargerCommand()
    data class SetOrientationLock(val locked: Boolean) : ChargerCommand()
    data class GotoScreen(val pageIndex: Int) : ChargerCommand()
    object RotateScreen180 : ChargerCommand()
    data class SetPortProtocol(val portId: Int, val protocol: String, val enabled: Boolean) : ChargerCommand()
    data class SetFastChargeProtocols(
        val pd: Boolean,
        val pps: Boolean,
        val ufcs: Boolean,
        val scp: Boolean
    ) : ChargerCommand()
    data class SetPortChargeLimit(
        val portId: Int,
        val whTarget: Double,
        val mode: String = "once",
        val enabled: Boolean = true
    ) : ChargerCommand()
    object SoftReset : ChargerCommand()
    object MasterKillswitch : ChargerCommand()
    object RestoreKillswitch : ChargerCommand()
    object UndoLastCommand : ChargerCommand()

    // Connection & policy controls
    data class ConnectCharger(val mac: String? = null) : ChargerCommand()
    data class DisconnectCharger(val isManual: Boolean = true) : ChargerCommand()
    data class ScanCharger(val durationMs: Long = 10_000L) : ChargerCommand()
    data class SetAutoReconnect(val enabled: Boolean) : ChargerCommand()
    data class SetPortLogging(val portId: Int, val enabled: Boolean) : ChargerCommand()
    data class SetTelemetryEnabled(val enabled: Boolean) : ChargerCommand()
    data class SetLogOnlyWhenPhoneCharging(val enabled: Boolean) : ChargerCommand()
    data class SetPausePollingWhenLocked(val enabled: Boolean) : ChargerCommand()
    data class ApplyChargingProfile(
        val name: String,
        val sceneMode: Int,
        val portSwitchBitmap: Int,
        val screenTimeout: Int,
        val usbALowCurrent: Boolean,
        val idleScreenOff: Boolean,
        val orientationLock: Boolean,
        val whLimitPort1: Double = 0.0,
        val whLimitPort2: Double = 0.0,
        val whLimitPort3: Double = 0.0,
        val whLimitPort4: Double = 0.0
    ) : ChargerCommand()
    object RefreshTelemetry : ChargerCommand()
}

data class KillswitchPortOutcome(
    val portId: Int,
    val wasEnabledBefore: Boolean,
    val attemptedWrite: Boolean,
    val succeeded: Boolean,
    val detail: String
)

data class KillswitchSnapshot(
    val previousBitmap: Int,
    val capturedAtMs: Long,
    val invalidated: Boolean = false,
    val invalidationReason: String? = null
)

sealed interface UndoActionKind {
    data class PortToggle(val portId: Int, val targetEnabled: Boolean) : UndoActionKind
    data class SceneMode(val targetMode: Int) : UndoActionKind
    object ScreenTimeout : UndoActionKind
    data class PortTimer(val portId: Int) : UndoActionKind
    object UsbALowCurrent : UndoActionKind
    object IdleScreenOff : UndoActionKind
    object ChargerLanguage : UndoActionKind
    object OrientationLock : UndoActionKind
    data class ProfileApplied(val profileName: String) : UndoActionKind
    object KillswitchRestore : UndoActionKind
}

data class UndoAvailability(
    val sequenceId: Long,
    val label: String,
    val createdAtMs: Long,
    val portId: Int? = null,
    val undoAction: UndoActionKind? = null
)

sealed class CommandResult {
    abstract val accepted: Boolean
    abstract val message: String

    data class Executed(override val message: String) : CommandResult() {
        override val accepted: Boolean = true
    }

    data class KillswitchExecuted(
        val outcomes: List<KillswitchPortOutcome>,
        val previousBitmap: Int,
        val allSucceeded: Boolean,
        override val message: String
    ) : CommandResult() {
        override val accepted: Boolean = outcomes.any { it.succeeded }
    }

    data class Rejected(
        val code: String,
        override val message: String
    ) : CommandResult() {
        override val accepted: Boolean = false
    }
}

/**
 * Abstraction over CUKTECH 10 Ultra hardware/state operations used by [CommandDispatcher]
 * so all automation channels, UI actions, Killswitch, and Undo execute deterministically
 * and are 100% unit-testable without physical BLE hardware.
 */
interface HardwareCommandAdapter {
    fun isAuthenticated(): Boolean
    fun currentSettings(): ChargerSettingsState
    fun currentPorts(): Map<Int, PortTelemetry>
    fun currentChargeLimits(): Map<Int, PortChargeLimit>
    fun activeSessionsSnapshot(): Map<Int, ChargeSessionRecord>

    suspend fun writePortBitmap(newBitmap: Int): Boolean
    suspend fun writeSceneMode(mode: Int): Boolean
    suspend fun writeScreenTimeout(timeoutCode: Int): Boolean
    suspend fun writePortCountdown(portId: Int, minutes: Int): Boolean
    suspend fun writeUsbALowCurrent(enabled: Boolean): Boolean
    suspend fun writeIdleScreenOff(enabled: Boolean): Boolean
    suspend fun writeLanguage(langCode: Int): Boolean
    suspend fun writeOrientationLock(locked: Boolean): Boolean
    suspend fun writeGotoScreen(pageIndex: Int): Boolean
    suspend fun writeRotateScreen180(): Boolean
    suspend fun writePortProtocol(portId: Int, protocol: String, enabled: Boolean): Boolean
    suspend fun writeFastChargeProtocols(pd: Boolean, pps: Boolean, ufcs: Boolean, scp: Boolean): Boolean
    suspend fun executeSoftReset(): Boolean

    fun applyPortChargeLimit(portId: Int, whTarget: Double, mode: String, enabled: Boolean)
    fun requestConnect(mac: String?)
    fun requestDisconnect(isManual: Boolean)
    fun requestScan(durationMs: Long)
    fun applyAutoReconnect(enabled: Boolean)
    fun applyPortLogging(portId: Int, enabled: Boolean)
    fun applyTelemetryMaster(enabled: Boolean)
    fun applyLogOnlyWhenPhoneCharging(enabled: Boolean)
    fun applyPausePollingWhenLocked(enabled: Boolean)
    fun requestRefreshTelemetry()

    fun finalizeSessionForPortWithReason(portId: Int, reason: String) {}
}

/**
 * Single validated security & safety chokepoint for ALL charger commands regardless of origin
 * (Compose UI, Home Widgets, Tasker plugin, ADB broadcasts, TCP Gateway, local automations).
 *
 * Enforces:
 * - External channel opt-in verification + constant-time token validation + lockout backoff + rate limits
 * - Strict SIID/PIID parameter and range allowlists
 * - Connection/auth state checks
 * - Sequential idempotent Master Killswitch (never claims hardware atomicity) with per-port reporting
 * - Safe Undo that auto-invalidates on subsequent/conflicting commands and NEVER overrides safety limits/timers
 */
class CommandDispatcher(
    private val authManager: AutomationAuthManager,
    private val adapter: HardwareCommandAdapter,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val logger: (isError: Boolean, message: String) -> Unit = { _, _ -> }
) {
    private val executionMutex = Mutex()

    private var commandSequence: Long = 0L
    private var lastSafetyCutoffMs: Long = 0L
    private var lastSafetyCutoffReason: String? = null
    private var pendingUndoAction: UndoInternalEntry? = null

    private val _undoAvailability = MutableStateFlow<UndoAvailability?>(null)
    val undoAvailability: StateFlow<UndoAvailability?> = _undoAvailability.asStateFlow()

    private val _lastKillswitchSnapshot = MutableStateFlow<KillswitchSnapshot?>(null)
    val lastKillswitchSnapshot: StateFlow<KillswitchSnapshot?> = _lastKillswitchSnapshot.asStateFlow()

    private data class UndoInternalEntry(
        val sequenceId: Long,
        val label: String,
        val createdAtMs: Long,
        val portId: Int?,
        val undoAction: UndoActionKind? = null,
        val restoreBlock: suspend () -> Boolean
    )

    /**
     * Called when an automated hardware/software protection triggers (e.g., Wh limit auto-cutoff,
     * port countdown expiry). Immediately invalidates pending Undo & Killswitch restore so protections
     * cannot be bypassed accidentally by Undo/Restore.
     */
    fun notifySafetyCutoffTriggered(portId: Int, reason: String) {
        val now = clockMs()
        lastSafetyCutoffMs = now
        lastSafetyCutoffReason = "Port $portId ($reason)"
        pendingUndoAction = null
        _undoAvailability.value = null
        _lastKillswitchSnapshot.value = _lastKillswitchSnapshot.value?.copy(
            invalidated = true,
            invalidationReason = "Blocked by triggered safety cutoff: $reason"
        )
    }

    suspend fun dispatch(
        source: CommandSource,
        command: ChargerCommand,
        authToken: String? = null,
        clientKey: String = source.name
    ): CommandResult {
        val now = clockMs()

        // 1. Security gate for external automation channels
        if (source.requiresExternalAuth) {
            val secCfg = authManager.config.value
            val channelEnabled = when (source) {
                CommandSource.TASKER -> secCfg.taskerEnabled
                CommandSource.ADB_BROADCAST -> secCfg.adbBroadcastEnabled
                CommandSource.TCP_GATEWAY -> secCfg.tcpGatewayEnabled
                else -> false
            }
            if (!channelEnabled) {
                logSecurityRejection(source, "CHANNEL_DISABLED", "Channel ${source.name} is disabled (opt-in required)")
                return CommandResult.Rejected(
                    code = "CHANNEL_DISABLED",
                    message = "External automation channel ${source.name} is disabled in settings."
                )
            }
            if (authManager.isAuthLockedOut(clientKey, now)) {
                logSecurityRejection(source, "AUTH_LOCKED_OUT", "Source locked out after repeated failures")
                return CommandResult.Rejected(
                    code = "AUTH_LOCKED_OUT",
                    message = "Too many failed authorization attempts; temporarily locked out."
                )
            }
            val tokenValid = authManager.verifyTokenConstantTime(authToken)
            authManager.recordAuthResult(clientKey, tokenValid, now)
            if (!tokenValid) {
                logSecurityRejection(source, "UNAUTHORIZED", "Invalid or missing authorization token")
                return CommandResult.Rejected(
                    code = "UNAUTHORIZED",
                    message = "Missing or invalid automation security token."
                )
            }
        }

        // 2. Rate limiting (external & widget/rule bursts; UI/INTERNAL_SAFETY still checked reasonably)
        if (source != CommandSource.INTERNAL_SAFETY) {
            val limit = if (source.requiresExternalAuth) 20 else 40
            if (!authManager.allowCommandExecution(clientKey, maxPerWindow = limit, windowMs = 10_000L, nowMs = now)) {
                logSecurityRejection(source, "RATE_LIMITED", "Rate limit exceeded for $clientKey")
                return CommandResult.Rejected(
                    code = "RATE_LIMITED",
                    message = "Command rate limit exceeded. Try again shortly."
                )
            }
        }

        // 3. Parameter bounds validation before acquiring execution lock
        validateParameters(command)?.let { err ->
            logger(true, LogSanitizer.sanitize("[Dispatcher] Rejected ${source.name}: $err"))
            return CommandResult.Rejected(code = "INVALID_PARAMS", message = err)
        }

        return executionMutex.withLock {
            executeValidatedLocked(source, command, now)
        }
    }

    private fun validateParameters(command: ChargerCommand): String? = when (command) {
        is ChargerCommand.SetPortEnabled ->
            if (command.portId !in 1..4) "Invalid portId ${command.portId} (must be 1..4)" else null
        is ChargerCommand.SetSceneMode ->
            if (command.mode !in CuktechProtocol.SCENE_MODES.keys) "Invalid scene mode ${command.mode}" else null
        is ChargerCommand.SetScreenTimeout ->
            if (command.timeoutCode !in CuktechProtocol.SCREEN_TIMEOUTS.keys) "Invalid timeout code ${command.timeoutCode}" else null
        is ChargerCommand.SetPortCountdown ->
            if (command.portId !in 1..4 || command.minutes !in 0..1440) "Invalid countdown (${command.portId}, ${command.minutes}m)" else null
        is ChargerCommand.SetLanguage ->
            if (command.langCode !in 0..1) "Invalid language code ${command.langCode}" else null
        is ChargerCommand.GotoScreen ->
            if (command.pageIndex !in 1..5) "Invalid LCD page ${command.pageIndex}" else null
        is ChargerCommand.SetPortProtocol -> {
            val proto = command.protocol.lowercase().trim()
            val valid = when (command.portId) {
                1, 2 -> proto in setOf("pd", "pps", "ufcs")
                3, 4 -> proto in setOf("ufcs", "scp")
                else -> false
            }
            if (!valid) "Unsupported protocol '${command.protocol}' for port ${command.portId}" else null
        }
        is ChargerCommand.SetPortChargeLimit -> {
            when {
                command.portId !in 1..4 -> "Invalid portId ${command.portId}"
                command.whTarget.isNaN() || command.whTarget.isInfinite() -> "Invalid Wh target"
                command.enabled && (command.whTarget <= 0.0 || command.whTarget > 10_000.0) ->
                    "Wh target must be in (0, 10000]"
                command.mode !in setOf("once", "always") -> "Invalid charge limit mode '${command.mode}'"
                else -> null
            }
        }
        is ChargerCommand.SetPortLogging ->
            if (command.portId !in 1..4) "Invalid portId ${command.portId}" else null
        is ChargerCommand.ScanCharger ->
            if (command.durationMs !in 1_000L..60_000L) "Scan duration must be 1..60s" else null
        is ChargerCommand.ApplyChargingProfile -> {
            when {
                command.sceneMode !in CuktechProtocol.SCENE_MODES.keys -> "Invalid profile scene mode"
                command.portSwitchBitmap !in 0..0x0F -> "Invalid profile port bitmap"
                command.screenTimeout !in CuktechProtocol.SCREEN_TIMEOUTS.keys -> "Invalid profile screen timeout"
                else -> null
            }
        }
        else -> null
    }

    private suspend fun executeValidatedLocked(
        source: CommandSource,
        command: ChargerCommand,
        now: Long
    ): CommandResult {
        // Non-GATT / Connection lifecycle commands (available even when BLE is disconnected)
        when (command) {
            is ChargerCommand.ConnectCharger -> {
                adapter.requestConnect(command.mac)
                return CommandResult.Executed("Connection requested")
            }
            is ChargerCommand.DisconnectCharger -> {
                invalidateUndo()
                adapter.requestDisconnect(command.isManual)
                return CommandResult.Executed("Disconnected")
            }
            is ChargerCommand.ScanCharger -> {
                adapter.requestScan(command.durationMs)
                return CommandResult.Executed("BLE scan started")
            }
            is ChargerCommand.SetAutoReconnect -> {
                adapter.applyAutoReconnect(command.enabled)
                return CommandResult.Executed("Auto-reconnect=${command.enabled}")
            }
            is ChargerCommand.SetPortLogging -> {
                adapter.applyPortLogging(command.portId, command.enabled)
                return CommandResult.Executed("Port ${command.portId} logging=${command.enabled}")
            }
            is ChargerCommand.SetTelemetryEnabled -> {
                adapter.applyTelemetryMaster(command.enabled)
                return CommandResult.Executed("Telemetry=${command.enabled}")
            }
            is ChargerCommand.SetLogOnlyWhenPhoneCharging -> {
                adapter.applyLogOnlyWhenPhoneCharging(command.enabled)
                return CommandResult.Executed("LogOnlyWhenCharging=${command.enabled}")
            }
            is ChargerCommand.SetPausePollingWhenLocked -> {
                adapter.applyPausePollingWhenLocked(command.enabled)
                return CommandResult.Executed("PauseWhenLocked=${command.enabled}")
            }
            is ChargerCommand.SetPortChargeLimit -> {
                invalidateUndo()
                adapter.applyPortChargeLimit(command.portId, command.whTarget, command.mode, command.enabled)
                return CommandResult.Executed("Port ${command.portId} charge limit updated")
            }
            else -> Unit
        }

        // All remaining commands mutate or query live authenticated hardware
        if (!adapter.isAuthenticated()) {
            return CommandResult.Rejected(
                code = "NOT_AUTHENTICATED",
                message = "Charger is disconnected or handshake not completed."
            )
        }

        if (command is ChargerCommand.RefreshTelemetry) {
            adapter.requestRefreshTelemetry()
            return CommandResult.Executed("Telemetry refresh queued")
        }

        if (command is ChargerCommand.UndoLastCommand) {
            return executeUndoLocked(now)
        }

        if (command is ChargerCommand.RestoreKillswitch) {
            return executeRestoreKillswitchLocked(now)
        }

        if (command is ChargerCommand.MasterKillswitch) {
            return executeMasterKillswitchLocked(now)
        }

        // Standard single-target hardware commands: check Undo eligibility & execute
        val settingsBefore = adapter.currentSettings()
        val portsBefore = adapter.currentPorts()

        val seq = ++commandSequence
        var nextUndo: UndoInternalEntry? = null
        val ok: Boolean

        when (command) {
            is ChargerCommand.SetPortEnabled -> {
                val prevEnabled = ((settingsBefore.portSwitchBitmap and (1 shl (command.portId - 1))) != 0)
                val targetBitmap = if (command.enabled) {
                    settingsBefore.portSwitchBitmap or (1 shl (command.portId - 1))
                } else {
                    settingsBefore.portSwitchBitmap and (1 shl (command.portId - 1)).inv()
                } and 0x0F
                ok = adapter.writePortBitmap(targetBitmap)
                if (ok && !command.enabled) {
                    adapter.finalizeSessionForPortWithReason(
                        command.portId,
                        if (source == CommandSource.INTERNAL_SAFETY) "WH_LIMIT" else "USER_STOP"
                    )
                }
                if (ok && source != CommandSource.INTERNAL_SAFETY && prevEnabled != command.enabled) {
                    nextUndo = UndoInternalEntry(
                        sequenceId = seq,
                        label = "Undo Port ${command.portId} -> ${if (prevEnabled) "ON" else "OFF"}",
                        createdAtMs = now,
                        portId = command.portId,
                        undoAction = UndoActionKind.PortToggle(command.portId, prevEnabled),
                        restoreBlock = {
                            val curBitmap = adapter.currentSettings().portSwitchBitmap
                            val bit = 1 shl (command.portId - 1)
                            val restoredBitmap = if (prevEnabled) (curBitmap or bit) else (curBitmap and bit.inv())
                            adapter.writePortBitmap(restoredBitmap and 0x0F)
                        }
                    )
                }
            }
            is ChargerCommand.SetSceneMode -> {
                val prevScene = settingsBefore.sceneMode
                ok = adapter.writeSceneMode(command.mode)
                if (ok && prevScene != command.mode) {
                    nextUndo = UndoInternalEntry(
                        sequenceId = seq,
                        label = "Undo Scene -> ${CuktechProtocol.SCENE_MODES[prevScene] ?: prevScene}",
                        createdAtMs = now,
                        portId = null,
                        undoAction = UndoActionKind.SceneMode(prevScene),
                        restoreBlock = { adapter.writeSceneMode(prevScene) }
                    )
                }
            }
            is ChargerCommand.SetScreenTimeout -> {
                val prevTimeout = settingsBefore.screenTimeout
                ok = adapter.writeScreenTimeout(command.timeoutCode)
                if (ok && prevTimeout != command.timeoutCode) {
                    nextUndo = UndoInternalEntry(
                        sequenceId = seq,
                        label = "Undo Screen Timeout",
                        createdAtMs = now,
                        portId = null,
                        undoAction = UndoActionKind.ScreenTimeout,
                        restoreBlock = { adapter.writeScreenTimeout(prevTimeout) }
                    )
                }
            }
            is ChargerCommand.SetPortCountdown -> {
                val prevMins = portsBefore[command.portId]?.countdownCode ?: 0
                ok = adapter.writePortCountdown(command.portId, command.minutes)
                if (ok && prevMins != command.minutes) {
                    nextUndo = UndoInternalEntry(
                        sequenceId = seq,
                        label = "Undo Port ${command.portId} Timer",
                        createdAtMs = now,
                        portId = command.portId,
                        undoAction = UndoActionKind.PortTimer(command.portId),
                        restoreBlock = { adapter.writePortCountdown(command.portId, prevMins) }
                    )
                }
            }
            is ChargerCommand.SetUsbALowCurrent -> {
                val prev = settingsBefore.usbALowCurrent
                ok = adapter.writeUsbALowCurrent(command.enabled)
                if (ok && prev != command.enabled) {
                    nextUndo = UndoInternalEntry(
                        sequenceId = seq,
                        label = "Undo USB-A Low Current",
                        createdAtMs = now,
                        portId = 4,
                        undoAction = UndoActionKind.UsbALowCurrent,
                        restoreBlock = { adapter.writeUsbALowCurrent(prev) }
                    )
                }
            }
            is ChargerCommand.SetIdleScreenOff -> {
                val prev = settingsBefore.idleScreenOff
                ok = adapter.writeIdleScreenOff(command.enabled)
                if (ok && prev != command.enabled) {
                    nextUndo = UndoInternalEntry(
                        sequenceId = seq,
                        label = "Undo Idle Screen Off",
                        createdAtMs = now,
                        portId = null,
                        undoAction = UndoActionKind.IdleScreenOff,
                        restoreBlock = { adapter.writeIdleScreenOff(prev) }
                    )
                }
            }
            is ChargerCommand.SetLanguage -> {
                val prev = settingsBefore.language
                ok = adapter.writeLanguage(command.langCode)
                if (ok && prev != command.langCode) {
                    nextUndo = UndoInternalEntry(
                        sequenceId = seq,
                        label = "Undo Charger Language",
                        createdAtMs = now,
                        portId = null,
                        undoAction = UndoActionKind.ChargerLanguage,
                        restoreBlock = { adapter.writeLanguage(prev) }
                    )
                }
            }
            is ChargerCommand.SetOrientationLock -> {
                val prev = settingsBefore.orientationLock
                ok = adapter.writeOrientationLock(command.locked)
                if (ok && prev != command.locked) {
                    nextUndo = UndoInternalEntry(
                        sequenceId = seq,
                        label = "Undo Orientation Lock",
                        createdAtMs = now,
                        portId = null,
                        undoAction = UndoActionKind.OrientationLock,
                        restoreBlock = { adapter.writeOrientationLock(prev) }
                    )
                }
            }
            is ChargerCommand.GotoScreen -> {
                ok = adapter.writeGotoScreen(command.pageIndex)
            }
            is ChargerCommand.RotateScreen180 -> {
                ok = adapter.writeRotateScreen180()
            }
            is ChargerCommand.SetPortProtocol -> {
                ok = adapter.writePortProtocol(command.portId, command.protocol, command.enabled)
            }
            is ChargerCommand.SetFastChargeProtocols -> {
                ok = adapter.writeFastChargeProtocols(command.pd, command.pps, command.ufcs, command.scp)
            }
            is ChargerCommand.SoftReset -> {
                // Soft reset drops/re-negotiates all power rails; never auto-undo
                ok = adapter.executeSoftReset()
            }
            is ChargerCommand.ApplyChargingProfile -> {
                val sOk = adapter.writeSceneMode(command.sceneMode)
                val pOk = adapter.writePortBitmap(command.portSwitchBitmap and 0x0F)
                val tOk = adapter.writeScreenTimeout(command.screenTimeout)
                val uOk = adapter.writeUsbALowCurrent(command.usbALowCurrent)
                val iOk = adapter.writeIdleScreenOff(command.idleScreenOff)
                val oOk = adapter.writeOrientationLock(command.orientationLock)
                val whTargets = listOf(
                    1 to command.whLimitPort1,
                    2 to command.whLimitPort2,
                    3 to command.whLimitPort3,
                    4 to command.whLimitPort4
                )
                for ((pid, target) in whTargets) {
                    if (target > 0.0) {
                        adapter.applyPortChargeLimit(pid, target, "once", true)
                    }
                }
                ok = sOk && pOk && tOk && uOk && iOk && oOk
                if (ok) {
                    nextUndo = UndoInternalEntry(
                        sequenceId = seq,
                        label = "Undo Profile '${command.name}'",
                        createdAtMs = now,
                        portId = null,
                        undoAction = UndoActionKind.ProfileApplied(command.name),
                        restoreBlock = {
                            adapter.writeSceneMode(settingsBefore.sceneMode) &&
                                adapter.writePortBitmap(settingsBefore.portSwitchBitmap and 0x0F) &&
                                adapter.writeScreenTimeout(settingsBefore.screenTimeout) &&
                                adapter.writeUsbALowCurrent(settingsBefore.usbALowCurrent) &&
                                adapter.writeIdleScreenOff(settingsBefore.idleScreenOff) &&
                                adapter.writeOrientationLock(settingsBefore.orientationLock)
                        }
                    )
                }
            }
            else -> ok = false
        }

        // Any subsequent command clears/replaces previous Undo (and invalidates old killswitch snapshot if ports touched)
        if (ok) {
            pendingUndoAction = nextUndo
            _undoAvailability.value = nextUndo?.let {
                UndoAvailability(it.sequenceId, it.label, it.createdAtMs, it.portId, it.undoAction)
            }
            if (command is ChargerCommand.SetPortEnabled || command is ChargerCommand.SoftReset) {
                _lastKillswitchSnapshot.value = _lastKillswitchSnapshot.value?.copy(
                    invalidated = true,
                    invalidationReason = "Superseded by subsequent port command"
                )
            }
            return CommandResult.Executed("Command executed (${command::class.simpleName})")
        } else {
            return CommandResult.Rejected(
                code = "GATT_WRITE_FAILED",
                message = "Charger did not acknowledge ${command::class.simpleName}"
            )
        }
    }

    /**
     * Controlled, idempotent sequential shutdown across ports 1..4 via PIID 16 bitmap writes.
     * Does NOT claim hardware-level atomicity: reports per-port outcomes accurately even on partial failures.
     */
    private suspend fun executeMasterKillswitchLocked(now: Long): CommandResult {
        val snapshotBitmap = adapter.currentSettings().portSwitchBitmap and 0x0F
        var workingBitmap = snapshotBitmap
        val outcomes = mutableListOf<KillswitchPortOutcome>()

        for (portId in 1..4) {
            val bit = 1 shl (portId - 1)
            val wasEnabled = (snapshotBitmap and bit) != 0
            val nextBitmap = (workingBitmap and bit.inv()) and 0x0F
            val ok = adapter.writePortBitmap(nextBitmap)
            if (ok) {
                workingBitmap = nextBitmap
                adapter.finalizeSessionForPortWithReason(portId, "MASTER_KILLSWITCH")
                outcomes.add(
                    KillswitchPortOutcome(
                        portId = portId,
                        wasEnabledBefore = wasEnabled,
                        attemptedWrite = true,
                        succeeded = true,
                        detail = if (wasEnabled) "Disabled" else "Confirmed OFF"
                    )
                )
            } else {
                outcomes.add(
                    KillswitchPortOutcome(
                        portId = portId,
                        wasEnabledBefore = wasEnabled,
                        attemptedWrite = true,
                        succeeded = false,
                        detail = "GATT write failed/timed out"
                    )
                )
            }
        }

        val allOk = outcomes.all { it.succeeded }
        val anyOk = outcomes.any { it.succeeded }
        if (anyOk) {
            _lastKillswitchSnapshot.value = KillswitchSnapshot(
                previousBitmap = snapshotBitmap,
                capturedAtMs = now,
                invalidated = false
            )
            // Killswitch clears any prior Undo and registers explicit Killswitch restore via snapshot
            val seq = ++commandSequence
            val undoEntry = UndoInternalEntry(
                sequenceId = seq,
                label = "Restore ports from Killswitch snapshot",
                createdAtMs = now,
                portId = null,
                undoAction = UndoActionKind.KillswitchRestore,
                restoreBlock = {
                    val snap = _lastKillswitchSnapshot.value
                    if (snap != null && !snap.invalidated) {
                        adapter.writePortBitmap(snap.previousBitmap and 0x0F)
                    } else {
                        false
                    }
                }
            )
            pendingUndoAction = undoEntry
            _undoAvailability.value = UndoAvailability(seq, undoEntry.label, now, null, undoEntry.undoAction)
        }

        val succeededPorts = outcomes.filter { it.succeeded }.joinToString(",") { "P${it.portId}" }
        val failedPorts = outcomes.filter { !it.succeeded }.joinToString(",") { "P${it.portId}" }
        val msg = if (allOk) {
            "Sequential killswitch disabled all 4 ports (snapshot=0x${snapshotBitmap.toString(16)})."
        } else {
            "Sequential killswitch partial: OK=[$succeededPorts], Failed=[$failedPorts]"
        }
        return CommandResult.KillswitchExecuted(
            outcomes = outcomes,
            previousBitmap = snapshotBitmap,
            allSucceeded = allOk,
            message = msg
        )
    }

    private suspend fun executeRestoreKillswitchLocked(now: Long): CommandResult {
        val snap = _lastKillswitchSnapshot.value
            ?: return CommandResult.Rejected("NO_SNAPSHOT", "No Killswitch snapshot available to restore.")
        if (snap.invalidated) {
            return CommandResult.Rejected(
                "SNAPSHOT_INVALIDATED",
                snap.invalidationReason ?: "Killswitch snapshot is no longer valid."
            )
        }
        if (lastSafetyCutoffMs >= snap.capturedAtMs) {
            return CommandResult.Rejected(
                "SAFETY_LOCKOUT",
                "Cannot restore over triggered protection: ${lastSafetyCutoffReason ?: "safety cutoff"}"
            )
        }
        // Check if any port in snapshot has a triggered Wh limit or expired state
        val limits = adapter.currentChargeLimits()
        for (portId in 1..4) {
            val wasOn = (snap.previousBitmap and (1 shl (portId - 1))) != 0
            val lim = limits[portId]
            if (wasOn && lim != null && lim.triggered) {
                return CommandResult.Rejected(
                    "WH_LIMIT_TRIGGERED",
                    "Port $portId Wh limit triggered; restore blocked for safety."
                )
            }
        }
        val ok = adapter.writePortBitmap(snap.previousBitmap and 0x0F)
        if (ok) {
            _lastKillswitchSnapshot.value = snap.copy(invalidated = true, invalidationReason = "Restored at $now")
            invalidateUndo()
            return CommandResult.Executed("Restored prior port state (0x${snap.previousBitmap.toString(16)})")
        }
        return CommandResult.Rejected("GATT_WRITE_FAILED", "Failed to write restored bitmap")
    }

    private suspend fun executeUndoLocked(now: Long): CommandResult {
        val entry = pendingUndoAction
            ?: return CommandResult.Rejected("NOTHING_TO_UNDO", "No undoable command available.")
        if (entry.sequenceId != commandSequence) {
            invalidateUndo()
            return CommandResult.Rejected("UNDO_SUPERSEDED", "Subsequent command superseded previous action.")
        }
        if (now - entry.createdAtMs > UNDO_WINDOW_MS) {
            invalidateUndo()
            return CommandResult.Rejected("UNDO_EXPIRED", "Undo window expired.")
        }
        if (lastSafetyCutoffMs >= entry.createdAtMs) {
            invalidateUndo()
            return CommandResult.Rejected(
                "SAFETY_LOCKOUT",
                "Undo blocked: ${lastSafetyCutoffReason ?: "safety cutoff triggered"}"
            )
        }
        entry.portId?.let { pid ->
            val lim = adapter.currentChargeLimits()[pid]
            if (lim != null && lim.triggered) {
                invalidateUndo()
                return CommandResult.Rejected("WH_LIMIT_TRIGGERED", "Undo blocked: Port $pid Wh limit triggered.")
            }
        }

        val ok = entry.restoreBlock.invoke()
        invalidateUndo()
        return if (ok) {
            CommandResult.Executed("Undid: ${entry.label}")
        } else {
            CommandResult.Rejected("UNDO_WRITE_FAILED", "Hardware failed to revert previous command.")
        }
    }

    private fun invalidateUndo() {
        pendingUndoAction = null
        _undoAvailability.value = null
    }

    private fun logSecurityRejection(source: CommandSource, code: String, detail: String) {
        logger(true, LogSanitizer.sanitize("[Security][$source][$code] $detail"))
    }

    companion object {
        const val UNDO_WINDOW_MS = 20_000L
    }
}
