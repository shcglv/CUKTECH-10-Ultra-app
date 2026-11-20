package com.example.cuktech.automation

import com.example.cuktech.ble.CuktechBleManager
import com.example.cuktech.security.AutomationAuthManager
import com.example.cuktech.security.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Hardened TCP JSON Gateway Server replacing raw arbitrary-UUID GATT execution.
 *
 * Security invariants:
 * - Disabled by default (opt-in via `AutomationAuthManager.config.tcpGatewayEnabled`).
 * - Binds strictly to `127.0.0.1` unless `tcpAllowLanBind` is explicitly enabled by user.
 * - Requires explicit `{"cmd":"auth","token":"<token>"}` handshake within 5,000ms before
 *   accepting any telemetry query or control command.
 * - Rejects legacy arbitrary GATT read/write/scan/connect/reset commands.
 * - Caps concurrent connections (`MAX_CLIENTS = 4`) and input line size (`MAX_LINE_BYTES = 8192`).
 * - Routes every high-level charger command through [CommandDispatcher].
 */
class TcpGatewayServer(
    private val authManager: AutomationAuthManager,
    private val dispatcher: CommandDispatcher,
    private val bleManagerProvider: () -> CuktechBleManager?,
    private val onClientListChanged: (List<ClientConnectionMeta>) -> Unit,
    private val logger: (isError: Boolean, message: String) -> Unit
) {
    data class ClientConnectionMeta(
        val id: String,
        val ip: String,
        val port: Int,
        val authenticated: Boolean,
        val connectedAtMs: Long
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val clients = ConcurrentHashMap<String, ClientSession>()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var boundHost: String = LOOPBACK_HOST
        private set

    @Volatile
    var boundPort: Int = 8080
        private set

    fun start(portOverride: Int? = null): Boolean {
        val cfg = authManager.config.value
        if (!cfg.tcpGatewayEnabled) {
            logger(true, "TCP Gateway disabled: enable opt-in TCP Gateway toggle in Security settings.")
            return false
        }
        if (isRunning) {
            logger(false, "TCP Gateway already listening on $boundHost:$boundPort")
            return true
        }
        val port = (portOverride ?: cfg.tcpPort).coerceIn(1024, 65535)
        val bindAddrStr = if (cfg.tcpAllowLanBind) "0.0.0.0" else LOOPBACK_HOST
        val inetAddr = InetAddress.getByName(bindAddrStr)

        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(inetAddr, port), 16)
            serverSocket = ss
            boundHost = bindAddrStr
            boundPort = port
            isRunning = true

            logger(false, "TCP Gateway listening on $bindAddrStr:$port (auth required, max $MAX_CLIENTS clients)")

            acceptJob = scope.launch {
                try {
                    while (isActive && !ss.isClosed) {
                        val socket = ss.accept()
                        if (clients.size >= MAX_CLIENTS) {
                            runCatching {
                                val rejectMsg = errorJson("MAX_CLIENTS", "Server connection limit ($MAX_CLIENTS) reached") + "\n"
                                socket.getOutputStream().write(rejectMsg.toByteArray(Charsets.UTF_8))
                                socket.close()
                            }
                            continue
                        }
                        val remoteIp = socket.inetAddress?.hostAddress ?: "unknown"
                        val id = "$remoteIp:${socket.port}"
                        val session = ClientSession(id, remoteIp, socket.port, socket)
                        clients[id] = session
                        notifyClientList()
                        session.start()
                    }
                } catch (_: Exception) {
                    // Normal on socket.close()
                } finally {
                    stop()
                }
            }
            true
        } catch (e: Exception) {
            logger(true, LogSanitizer.sanitize("Failed to start TCP Gateway on $bindAddrStr:$port: ${e.message}"))
            stop()
            false
        }
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        clients.values.toList().forEach { it.close() }
        clients.clear()
        notifyClientList()
    }

    fun disconnectClient(id: String) {
        clients[id]?.close()
    }

    fun currentClientsMeta(): List<ClientConnectionMeta> {
        return clients.values.map {
            ClientConnectionMeta(
                id = it.id,
                ip = it.ip,
                port = it.port,
                authenticated = it.authenticated,
                connectedAtMs = it.connectedAtMs
            )
        }
    }

    fun broadcastTelemetryToAuthenticatedClients(jsonLine: String) {
        val safe = LogSanitizer.sanitize(jsonLine)
        clients.values.forEach { session ->
            if (session.authenticated) {
                session.send(safe)
            }
        }
    }

    /**
     * Processes a single JSON command line for [clientId] with [SessionAuthState] — factored out
     * so unit tests can verify auth handshake, allowlist enforcement, raw-GATT rejection, and
     * token sanitization deterministically.
     */
    internal suspend fun processClientJsonLine(
        clientId: String,
        sourceIp: String,
        state: SessionAuthState,
        line: String
    ): Pair<String, Boolean> { // Pair(responseJson, shouldCloseConnection)
        if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) {
            return errorJson("LINE_TOO_LARGE", "Command exceeds $MAX_LINE_BYTES byte limit") to true
        }

        val json = try {
            JSONObject(line)
        } catch (e: JSONException) {
            return errorJson("INVALID_JSON", "Malformed JSON syntax") to false
        }

        val cmd = json.optString("cmd", json.optString("command", "")).trim().lowercase()
        if (cmd.isEmpty()) {
            return errorJson("MISSING_CMD", "Missing 'cmd' field") to false
        }

        // Unauthenticated clients may only call 'ping' or 'auth'
        if (!state.authenticated) {
            return when (cmd) {
                "ping" -> JSONObject().apply {
                    put("status", "pong")
                    put("auth_required", true)
                    put("timestamp", System.currentTimeMillis())
                }.toString() to false

                "auth" -> {
                    val sourceKey = "tcp:$sourceIp"
                    if (authManager.isAuthLockedOut(sourceKey)) {
                        return errorJson("AUTH_LOCKED_OUT", "Too many invalid attempts; locked out") to true
                    }
                    val token = json.optString("token", "")
                    val valid = authManager.verifyTokenConstantTime(token)
                    authManager.recordAuthResult(sourceKey, valid)
                    if (valid) {
                        state.authenticated = true
                        state.verifiedToken = token.trim()
                        logger(false, "TCP client authenticated: $clientId")
                        JSONObject().apply {
                            put("status", "authenticated")
                            put("timestamp", System.currentTimeMillis())
                        }.toString() to false
                    } else {
                        logger(true, LogSanitizer.sanitize("TCP auth failed from $sourceIp"))
                        val closeNow = authManager.isAuthLockedOut(sourceKey)
                        errorJson("UNAUTHORIZED", "Invalid authentication token") to closeNow
                    }
                }

                else -> {
                    errorJson("AUTH_REQUIRED", "Send {\"cmd\":\"auth\",\"token\":\"...\"} within ${UNAUTH_TIMEOUT_MS}ms") to true
                }
            }
        }

        // Explicitly reject raw unsafe GATT / stack operations
        if (cmd in DISALLOWED_RAW_COMMANDS) {
            return errorJson("COMMAND_FORBIDDEN", "Raw BLE GATT/stack command '$cmd' is not allowed") to false
        }

        // Authenticated allowlist execution
        val token = state.verifiedToken
        val mgr = bleManagerProvider()
        return when (cmd) {
            "ping" -> JSONObject().apply {
                put("status", "pong")
                put("authenticated", true)
                put("timestamp", System.currentTimeMillis())
            }.toString() to false

            "auth" -> JSONObject().apply {
                put("status", "authenticated")
                put("timestamp", System.currentTimeMillis())
            }.toString() to false

            "status" -> {
                if (mgr == null) {
                    errorJson("UNAVAILABLE", "Charger manager unavailable") to false
                } else {
                    val info = mgr.deviceInfo.value
                    val set = mgr.settings.value
                    JSONObject().apply {
                        put("status", "ok")
                        put("cmd", "status")
                        put("timestamp", System.currentTimeMillis())
                        put("auth_state", mgr.authState.value.name)
                        put("name", info.name)
                        put("model", info.model)
                        put("firmware", info.firmwareVersion)
                        put("total_watts", mgr.totalPowerWatts.value)
                        put("scene_mode", set.sceneMode)
                        put("port_bitmap", set.portSwitchBitmap)
                        put("screen_timeout", set.screenTimeout)
                    }.toString() to false
                }
            }

            "get_ports" -> {
                if (mgr == null) {
                    errorJson("UNAVAILABLE", "Charger manager unavailable") to false
                } else {
                    val arr = JSONArray()
                    mgr.ports.value.values.sortedBy { it.portId }.forEach { p ->
                        arr.put(JSONObject().apply {
                            put("port", p.portId)
                            put("label", p.portLabel)
                            put("voltage_v", p.voltageV)
                            put("current_a", p.currentA)
                            put("power_w", p.powerW)
                            put("protocol", p.protocolName)
                            put("enabled", p.enabled)
                            put("cumulative_wh", p.cumulativeWh)
                            put("countdown_min", p.countdownCode)
                        })
                    }
                    JSONObject().apply {
                        put("status", "ok")
                        put("cmd", "get_ports")
                        put("timestamp", System.currentTimeMillis())
                        put("ports", arr)
                    }.toString() to false
                }
            }

            "set_port" -> {
                if (!json.has("port") || !json.has("enabled")) {
                    errorJson("INVALID_PARAMS", "Requires 'port' (1..4) and 'enabled' (boolean)") to false
                } else {
                    dispatchAndFormat(
                        cmdName = cmd,
                        command = ChargerCommand.SetPortEnabled(json.optInt("port", -1), json.optBoolean("enabled", true)),
                        token = token,
                        sourceIp = sourceIp
                    ) to false
                }
            }

            "set_scene" -> {
                dispatchAndFormat(
                    cmdName = cmd,
                    command = ChargerCommand.SetSceneMode(json.optInt("mode", -1)),
                    token = token,
                    sourceIp = sourceIp
                ) to false
            }

            "set_timeout" -> {
                dispatchAndFormat(
                    cmdName = cmd,
                    command = ChargerCommand.SetScreenTimeout(json.optInt("timeout", -1)),
                    token = token,
                    sourceIp = sourceIp
                ) to false
            }

            "set_countdown" -> {
                dispatchAndFormat(
                    cmdName = cmd,
                    command = ChargerCommand.SetPortCountdown(json.optInt("port", -1), json.optInt("minutes", -1)),
                    token = token,
                    sourceIp = sourceIp
                ) to false
            }

            "set_charge_limit" -> {
                dispatchAndFormat(
                    cmdName = cmd,
                    command = ChargerCommand.SetPortChargeLimit(
                        portId = json.optInt("port", -1),
                        whTarget = json.optDouble("wh_target", 0.0),
                        mode = json.optString("mode", "once"),
                        enabled = json.optBoolean("enabled", true)
                    ),
                    token = token,
                    sourceIp = sourceIp
                ) to false
            }

            "soft_reset" -> dispatchAndFormat(cmd, ChargerCommand.SoftReset, token, sourceIp) to false
            "killswitch" -> dispatchAndFormat(cmd, ChargerCommand.MasterKillswitch, token, sourceIp) to false
            "restore_killswitch" -> dispatchAndFormat(cmd, ChargerCommand.RestoreKillswitch, token, sourceIp) to false
            "undo" -> dispatchAndFormat(cmd, ChargerCommand.UndoLastCommand, token, sourceIp) to false

            else -> errorJson("UNKNOWN_CMD", "Unknown command: $cmd") to false
        }
    }

    private suspend fun dispatchAndFormat(
        cmdName: String,
        command: ChargerCommand,
        token: String?,
        sourceIp: String
    ): String {
        val result = dispatcher.dispatch(
            source = CommandSource.TCP_GATEWAY,
            command = command,
            authToken = token,
            clientKey = "tcp:$sourceIp"
        )
        return when (result) {
            is CommandResult.Executed -> JSONObject().apply {
                put("status", "ok")
                put("cmd", cmdName)
                put("message", result.message)
                put("timestamp", System.currentTimeMillis())
            }.toString()

            is CommandResult.KillswitchExecuted -> JSONObject().apply {
                put("status", if (result.allSucceeded) "ok" else "partial")
                put("cmd", cmdName)
                put("previous_bitmap", result.previousBitmap)
                put("message", result.message)
                val outcomes = JSONArray()
                result.outcomes.forEach { o ->
                    outcomes.put(JSONObject().apply {
                        put("port", o.portId)
                        put("was_enabled", o.wasEnabledBefore)
                        put("succeeded", o.succeeded)
                        put("detail", o.detail)
                    })
                }
                put("outcomes", outcomes)
                put("timestamp", System.currentTimeMillis())
            }.toString()

            is CommandResult.Rejected -> errorJson(result.code, result.message)
        }
    }

    private fun errorJson(code: String, msg: String): String {
        return JSONObject().apply {
            put("status", "error")
            put("code", code)
            put("message", LogSanitizer.sanitize(msg))
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    private fun notifyClientList() {
        onClientListChanged(currentClientsMeta())
    }

    internal data class SessionAuthState(
        @Volatile var authenticated: Boolean = false,
        @Volatile var verifiedToken: String? = null
    )

    private inner class ClientSession(
        val id: String,
        val ip: String,
        val port: Int,
        private val socket: Socket
    ) {
        val connectedAtMs = System.currentTimeMillis()
        private val authState = SessionAuthState()
        val authenticated: Boolean get() = authState.authenticated

        private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val inputStream: InputStream = socket.getInputStream()
        private val outputStream: OutputStream = socket.getOutputStream()

        fun start() {
            sessionScope.launch {
                try {
                    socket.soTimeout = UNAUTH_TIMEOUT_MS
                    while (isActive && !socket.isClosed) {
                        val line = readBoundedLine(inputStream, MAX_LINE_BYTES) ?: break
                        if (line.isBlank()) continue
                        val (response, closeAfter) = processClientJsonLine(id, ip, authState, line)
                        sendSync(response)
                        if (authState.authenticated) {
                            socket.soTimeout = 0 // Authenticated connections can wait indefinitely
                        }
                        if (closeAfter) {
                            break
                        }
                        notifyClientList()
                    }
                } catch (_: SocketTimeoutException) {
                    if (!authState.authenticated) {
                        sendSync(errorJson("AUTH_TIMEOUT", "Authentication handshake timed out (${UNAUTH_TIMEOUT_MS}ms)"))
                        logger(true, "Closed unauthenticated TCP client $id after 5s timeout")
                    }
                } catch (e: Exception) {
                    logger(false, LogSanitizer.sanitize("TCP session $id closed: ${e.message}"))
                } finally {
                    close()
                }
            }
        }

        fun send(message: String) {
            sessionScope.launch { sendSync(message) }
        }

        private fun sendSync(message: String) {
            runCatching {
                synchronized(outputStream) {
                    outputStream.write((message + "\n").toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }
            }.onFailure { close() }
        }

        fun close() {
            if (clients.remove(id) != null) {
                notifyClientList()
            }
            sessionScope.cancel()
            runCatching { socket.close() }
        }
    }

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val MAX_CLIENTS = 4
        const val MAX_LINE_BYTES = 8192
        const val UNAUTH_TIMEOUT_MS = 5000

        private val DISALLOWED_RAW_COMMANDS = setOf(
            "scan", "connect", "disconnect", "read", "write", "notify",
            "reset_bluetooth", "request_mtu", "discover"
        )

        /**
         * Reads a newline-terminated UTF-8 line bounded strictly to [maxBytes] to prevent memory exhaustion.
         */
        internal fun readBoundedLine(input: InputStream, maxBytes: Int): String? {
            val buffer = ByteArrayOutputStream(256)
            while (true) {
                val b = input.read()
                if (b == -1) {
                    return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
                }
                if (b == '\n'.code) {
                    return buffer.toString(Charsets.UTF_8.name()).trimEnd('\r')
                }
                if (buffer.size() >= maxBytes) {
                    // Drain up to newline or return oversized token so caller rejects cleanly
                    return ByteArray(maxBytes + 1).let { String(it, Charsets.UTF_8) }
                }
                buffer.write(b)
            }
        }
    }
}
