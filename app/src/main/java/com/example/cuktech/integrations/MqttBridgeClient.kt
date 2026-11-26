package com.example.cuktech.integrations

import com.example.cuktech.security.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory

data class MqttBrokerEndpoint(
    val host: String,
    val port: Int,
    val useTls: Boolean
)

sealed class MqttInboundCommand {
    data class SetPortEnabled(val portId: Int, val enabled: Boolean) : MqttInboundCommand()
    data class SetSceneMode(val mode: Int) : MqttInboundCommand()
}

enum class MqttConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class MqttConnectionStatus(
    val state: MqttConnectionState = MqttConnectionState.DISCONNECTED,
    val brokerLabel: String = "",
    val lastError: String? = null,
    val lastPublishedMs: Long = 0L,
    val haDiscoveryPublished: Boolean = false
)

/**
 * Pure-Kotlin dependency-free MQTT 3.1.1 wire codec and TCP/TLS client
 * with Home Assistant MQTT Auto Discovery payload builder and bidirectional command parsing.
 */
class MqttBridgeClient(
    private val onInboundCommand: (MqttInboundCommand) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeLock = Any()

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var outputStream: OutputStream? = null
    private var connectionJob: Job? = null
    private var keepAliveJob: Job? = null

    private val _status = MutableStateFlow(MqttConnectionStatus())
    val status: StateFlow<MqttConnectionStatus> = _status.asStateFlow()

    @Volatile
    private var activeBaseTopic: String = "cuktech10"

    fun connect(
        brokerUrl: String,
        clientId: String,
        baseTopic: String,
        username: String = "",
        password: String = "",
        haDiscoveryEnabled: Boolean = true,
        haDiscoveryPrefix: String = "homeassistant",
        nodeId: String = "cuktech10_ultra",
        deviceName: String = "CUKTECH 10 Ultra"
    ) {
        disconnect(publishOffline = false)
        val endpoint = parseBrokerUri(brokerUrl)
        if (endpoint == null) {
            _status.value = MqttConnectionStatus(
                state = MqttConnectionState.ERROR,
                brokerLabel = brokerUrl.trim(),
                lastError = "Invalid MQTT broker URL (use mqtt://host:1883 or mqtts://host:8883)"
            )
            return
        }
        val cleanBase = normalizeBaseTopic(baseTopic)
        activeBaseTopic = cleanBase
        _status.value = MqttConnectionStatus(
            state = MqttConnectionState.CONNECTING,
            brokerLabel = "${endpoint.host}:${endpoint.port}",
            lastError = null
        )

        connectionJob = scope.launch {
            runCatching {
                val sock = if (endpoint.useTls) {
                    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    sslFactory.createSocket().apply {
                        connect(InetSocketAddress(endpoint.host, endpoint.port), 6_000)
                        soTimeout = 45_000
                    }
                } else {
                    Socket().apply {
                        connect(InetSocketAddress(endpoint.host, endpoint.port), 6_000)
                        soTimeout = 45_000
                    }
                }
                socket = sock
                val os = sock.getOutputStream()
                val ins = sock.getInputStream()
                outputStream = os

                val connectPkt = encodeConnectPacket(
                    clientId = clientId.ifBlank { "cuktech10_android" },
                    username = username,
                    password = password,
                    willTopic = "$cleanBase/status",
                    willPayload = "offline",
                    keepAliveSec = 30
                )
                synchronized(writeLock) {
                    os.write(connectPkt)
                    os.flush()
                }

                // Read CONNACK (packet type 2)
                val ackType = readPacketTypeAndPayload(ins)?.first
                    ?: error("MQTT connection closed before CONNACK")
                if (ackType != 2) {
                    error("Unexpected MQTT packet type $ackType during handshake")
                }

                // Subscribe to <baseTopic>/cmd/#
                val subPkt = encodeSubscribePacket(packetId = 1, topicFilter = "$cleanBase/cmd/#")
                synchronized(writeLock) {
                    os.write(subPkt)
                    os.write(encodePublishPacket("$cleanBase/status", "online", retain = true))
                    os.flush()
                }

                var discoverySent = false
                if (haDiscoveryEnabled) {
                    val discoveryPayloads = buildHomeAssistantDiscoveryPayloads(
                        nodeId = nodeId,
                        deviceName = deviceName,
                        baseTopic = cleanBase,
                        discoveryPrefix = haDiscoveryPrefix
                    )
                    synchronized(writeLock) {
                        for ((topic, json) in discoveryPayloads) {
                            os.write(encodePublishPacket(topic, json, retain = true))
                        }
                        os.flush()
                    }
                    discoverySent = true
                }

                _status.value = MqttConnectionStatus(
                    state = MqttConnectionState.CONNECTED,
                    brokerLabel = "${endpoint.host}:${endpoint.port}",
                    lastError = null,
                    lastPublishedMs = System.currentTimeMillis(),
                    haDiscoveryPublished = discoverySent
                )

                keepAliveJob = launch {
                    while (isActive && _status.value.state == MqttConnectionState.CONNECTED) {
                        delay(20_000L)
                        runCatching {
                            synchronized(writeLock) {
                                outputStream?.write(encodePingReqPacket())
                                outputStream?.flush()
                            }
                        }
                    }
                }

                while (isActive) {
                    val pkt = readPacketTypeAndPayload(ins) ?: break
                    if (pkt.first == 3) {
                        val parsed = parseIncomingPublishPacket(pkt.second)
                        if (parsed != null) {
                            parseInboundCommand(cleanBase, parsed.first, parsed.second)?.let(onInboundCommand)
                        }
                    }
                }
            }.onFailure { err ->
                _status.value = MqttConnectionStatus(
                    state = MqttConnectionState.ERROR,
                    brokerLabel = "${endpoint.host}:${endpoint.port}",
                    lastError = LogSanitizer.sanitize(err.message ?: "MQTT disconnected")
                )
            }
            keepAliveJob?.cancel()
            runCatching { socket?.close() }
            socket = null
            outputStream = null
            if (_status.value.state == MqttConnectionState.CONNECTED) {
                _status.value = _status.value.copy(state = MqttConnectionState.DISCONNECTED)
            }
        }
    }

    fun publish(topic: String, payload: String, retain: Boolean = false): Boolean {
        val os = outputStream ?: return false
        if (_status.value.state != MqttConnectionState.CONNECTED) return false
        return runCatching {
            val pkt = encodePublishPacket(topic, payload, retain)
            synchronized(writeLock) {
                os.write(pkt)
                os.flush()
            }
            _status.value = _status.value.copy(lastPublishedMs = System.currentTimeMillis())
            true
        }.getOrElse { err ->
            _status.value = _status.value.copy(
                state = MqttConnectionState.ERROR,
                lastError = LogSanitizer.sanitize(err.message ?: "MQTT publish failed")
            )
            false
        }
    }

    fun publishHomeAssistantDiscovery(
        baseTopic: String = activeBaseTopic,
        discoveryPrefix: String = "homeassistant",
        nodeId: String = "cuktech10_ultra",
        deviceName: String = "CUKTECH 10 Ultra"
    ): Int {
        if (_status.value.state != MqttConnectionState.CONNECTED) return 0
        val payloads = buildHomeAssistantDiscoveryPayloads(
            nodeId = nodeId,
            deviceName = deviceName,
            baseTopic = normalizeBaseTopic(baseTopic),
            discoveryPrefix = discoveryPrefix
        )
        var sent = 0
        for ((topic, json) in payloads) {
            if (publish(topic, json, retain = true)) sent++
        }
        if (sent > 0) {
            _status.value = _status.value.copy(haDiscoveryPublished = true)
        }
        return sent
    }

    fun disconnect(publishOffline: Boolean = true) {
        keepAliveJob?.cancel()
        keepAliveJob = null
        connectionJob?.cancel()
        connectionJob = null
        val os = outputStream
        if (publishOffline && os != null) {
            runCatching {
                synchronized(writeLock) {
                    os.write(encodePublishPacket("$activeBaseTopic/status", "offline", retain = true))
                    os.write(encodeDisconnectPacket())
                    os.flush()
                }
            }
        }
        runCatching { socket?.close() }
        socket = null
        outputStream = null
        _status.value = MqttConnectionStatus(state = MqttConnectionState.DISCONNECTED)
    }

    private fun readPacketTypeAndPayload(ins: InputStream): Pair<Int, ByteArray>? {
        val firstByte = ins.read()
        if (firstByte < 0) return null
        val packetType = (firstByte ushr 4) and 0x0F
        val remainingLen = decodeRemainingLength(ins)
        val payload = ByteArray(remainingLen)
        var readTotal = 0
        while (readTotal < remainingLen) {
            val n = ins.read(payload, readTotal, remainingLen - readTotal)
            if (n < 0) throw EOFException("Unexpected EOF reading MQTT packet")
            readTotal += n
        }
        return packetType to payload
    }

    companion object {
        fun normalizeBaseTopic(raw: String): String =
            raw.trim().trim('/').ifBlank { "cuktech10" }

        fun parseBrokerUri(rawUrl: String): MqttBrokerEndpoint? {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank()) return null
            val normalized = if ("://" in trimmed) trimmed else "mqtt://$trimmed"
            return runCatching {
                val uri = URI(normalized)
                val scheme = uri.scheme?.lowercase() ?: return null
                val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
                val useTls = scheme == "mqtts" || scheme == "ssl"
                if (scheme !in setOf("mqtt", "mqtts", "tcp", "ssl")) return null
                val defaultPort = if (useTls) 8883 else 1883
                val port = if (uri.port in 1..65535) uri.port else defaultPort
                MqttBrokerEndpoint(host = host, port = port, useTls = useTls)
            }.getOrNull()
        }

        fun encodeUtf8String(text: String): ByteArray {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val len = bytes.size.coerceAtMost(65535)
            return byteArrayOf(((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) +
                bytes.copyOf(len)
        }

        fun encodeRemainingLength(length: Int): ByteArray {
            val out = ArrayList<Byte>(4)
            var x = length.coerceAtLeast(0)
            do {
                var encodedByte = x % 128
                x /= 128
                if (x > 0) {
                    encodedByte = encodedByte or 0x80
                }
                out.add(encodedByte.toByte())
            } while (x > 0)
            return out.toByteArray()
        }

        fun decodeRemainingLength(input: InputStream): Int {
            var multiplier = 1
            var value = 0
            var count = 0
            while (count < 4) {
                val encoded = input.read()
                if (encoded < 0) throw EOFException("EOF in MQTT remaining length")
                value += (encoded and 0x7F) * multiplier
                if ((encoded and 0x80) == 0) return value
                multiplier *= 128
                count++
            }
            error("Malformed MQTT remaining length")
        }

        fun encodeConnectPacket(
            clientId: String,
            username: String = "",
            password: String = "",
            willTopic: String = "",
            willPayload: String = "",
            keepAliveSec: Int = 30
        ): ByteArray {
            val body = ByteArrayOutputStream()
            // Variable header: Protocol Name "MQTT", Level 4 (3.1.1)
            body.write(encodeUtf8String("MQTT"))
            body.write(0x04)

            var flags = 0x02 // Clean Session
            val hasWill = willTopic.isNotBlank()
            val hasUser = username.isNotBlank()
            val hasPass = hasUser && password.isNotEmpty()
            if (hasWill) flags = flags or 0x04 or 0x20 // Will Flag + Will Retain
            if (hasUser) flags = flags or 0x80
            if (hasPass) flags = flags or 0x40
            body.write(flags)

            val ka = keepAliveSec.coerceIn(5, 65535)
            body.write((ka ushr 8) and 0xFF)
            body.write(ka and 0xFF)

            // Payload
            body.write(encodeUtf8String(clientId.ifBlank { "cuktech10" }))
            if (hasWill) {
                body.write(encodeUtf8String(willTopic))
                body.write(encodeUtf8String(willPayload))
            }
            if (hasUser) {
                body.write(encodeUtf8String(username))
            }
            if (hasPass) {
                body.write(encodeUtf8String(password))
            }

            val varAndPayload = body.toByteArray()
            val remLen = encodeRemainingLength(varAndPayload.size)
            return byteArrayOf(0x10.toByte()) + remLen + varAndPayload
        }

        fun encodePublishPacket(topic: String, payload: String, retain: Boolean = false): ByteArray {
            val varAndPayload = ByteArrayOutputStream()
            varAndPayload.write(encodeUtf8String(topic))
            varAndPayload.write(payload.toByteArray(Charsets.UTF_8))
            val bytes = varAndPayload.toByteArray()
            val headerByte = (0x30 or if (retain) 0x01 else 0x00).toByte()
            return byteArrayOf(headerByte) + encodeRemainingLength(bytes.size) + bytes
        }

        fun encodeSubscribePacket(packetId: Int, topicFilter: String): ByteArray {
            val body = ByteArrayOutputStream()
            body.write((packetId ushr 8) and 0xFF)
            body.write(packetId and 0xFF)
            body.write(encodeUtf8String(topicFilter))
            body.write(0x00) // Requested QoS 0
            val bytes = body.toByteArray()
            return byteArrayOf(0x82.toByte()) + encodeRemainingLength(bytes.size) + bytes
        }

        fun encodePingReqPacket(): ByteArray = byteArrayOf(0xC0.toByte(), 0x00)

        fun encodeDisconnectPacket(): ByteArray = byteArrayOf(0xE0.toByte(), 0x00)

        fun parseIncomingPublishPacket(remainingBytes: ByteArray): Pair<String, String>? {
            if (remainingBytes.size < 2) return null
            val topicLen = ((remainingBytes[0].toInt() and 0xFF) shl 8) or (remainingBytes[1].toInt() and 0xFF)
            if (2 + topicLen > remainingBytes.size) return null
            val topic = String(remainingBytes, 2, topicLen, Charsets.UTF_8)
            val payloadOffset = 2 + topicLen
            val payload = if (payloadOffset <= remainingBytes.size) {
                String(remainingBytes, payloadOffset, remainingBytes.size - payloadOffset, Charsets.UTF_8)
            } else ""
            return topic to payload
        }

        fun parseInboundCommand(
            baseTopic: String,
            topic: String,
            payload: String
        ): MqttInboundCommand? {
            val cleanBase = normalizeBaseTopic(baseTopic)
            val trimmedTopic = topic.trim()
            if (!trimmedTopic.startsWith("$cleanBase/cmd/")) return null
            val sub = trimmedTopic.removePrefix("$cleanBase/cmd/")
            val trimmedPayload = payload.trim()

            val portMatch = Regex("^port/([1-4])/set$").find(sub)
            if (portMatch != null) {
                val portId = portMatch.groupValues[1].toIntOrNull() ?: return null
                val enabled = parseBooleanOrJson(trimmedPayload) ?: return null
                return MqttInboundCommand.SetPortEnabled(portId, enabled)
            }

            if (sub == "scene/set") {
                val mode = parseSceneModeOrJson(trimmedPayload) ?: return null
                return MqttInboundCommand.SetSceneMode(mode)
            }

            return null
        }

        private fun parseBooleanOrJson(raw: String): Boolean? {
            if (raw.startsWith("{")) {
                return runCatching {
                    val obj = JSONObject(raw)
                    when {
                        obj.has("enabled") -> obj.getBoolean("enabled")
                        obj.has("state") -> parseBooleanOrJson(obj.getString("state"))
                        else -> null
                    }
                }.getOrNull()
            }
            return when (raw.lowercase()) {
                "on", "1", "true", "enable" -> true
                "off", "0", "false", "disable" -> false
                else -> null
            }
        }

        private fun parseSceneModeOrJson(raw: String): Int? {
            if (raw.startsWith("{")) {
                return runCatching {
                    val obj = JSONObject(raw)
                    obj.optInt("mode", -1).takeIf { it in 1..4 }
                }.getOrNull()
            }
            return when (raw.lowercase()) {
                "1", "ai" -> 1
                "2", "hybrid" -> 2
                "3", "single" -> 3
                "4", "dual" -> 4
                else -> null
            }
        }

        fun buildHomeAssistantDiscoveryPayloads(
            nodeId: String,
            deviceName: String,
            baseTopic: String,
            discoveryPrefix: String = "homeassistant"
        ): List<Pair<String, String>> {
            val cleanPrefix = discoveryPrefix.trim().trim('/').ifBlank { "homeassistant" }
            val cleanBase = normalizeBaseTopic(baseTopic)
            val cleanNode = nodeId.lowercase().replace(Regex("[^a-z0-9_]"), "_").ifBlank { "cuktech10" }

            val deviceObj = JSONObject().apply {
                put("identifiers", JSONArray().apply { put(cleanNode) })
                put("name", deviceName.ifBlank { "CUKTECH 10 Ultra" })
                put("manufacturer", "CUKTECH / Xiaomi")
                put("model", "AD1204 (CUKTECH 10 Ultra)")
            }

            val out = mutableListOf<Pair<String, String>>()

            data class SensorSpec(
                val id: String,
                val name: String,
                val valueTemplate: String,
                val unit: String?,
                val deviceClass: String?,
                val stateClass: String?
            )

            val sensors = listOf(
                SensorSpec("total_power", "Total Power", "{{ value_json.total_power_w }}", "W", "power", "measurement"),
                SensorSpec("c1_power", "USB-C1 Power", "{{ value_json.port_1_w }}", "W", "power", "measurement"),
                SensorSpec("c1_voltage", "USB-C1 Voltage", "{{ value_json.port_1_v }}", "V", "voltage", "measurement"),
                SensorSpec("c1_current", "USB-C1 Current", "{{ value_json.port_1_a }}", "A", "current", "measurement"),
                SensorSpec("c2_power", "USB-C2 Power", "{{ value_json.port_2_w }}", "W", "power", "measurement"),
                SensorSpec("c3_power", "USB-C3 Power", "{{ value_json.port_3_w }}", "W", "power", "measurement"),
                SensorSpec("a_power", "USB-A Power", "{{ value_json.port_4_w }}", "W", "power", "measurement"),
                SensorSpec("c1_protocol", "USB-C1 Protocol", "{{ value_json.port_1_protocol }}", null, null, null),
                SensorSpec("c2_protocol", "USB-C2 Protocol", "{{ value_json.port_2_protocol }}", null, null, null),
                SensorSpec("rssi", "BLE RSSI", "{{ value_json.rssi_dbm }}", "dBm", "signal_strength", "measurement")
            )

            for (s in sensors) {
                val topic = "$cleanPrefix/sensor/$cleanNode/${s.id}/config"
                val cfg = JSONObject().apply {
                    put("name", s.name)
                    put("unique_id", "${cleanNode}_${s.id}")
                    put("state_topic", "$cleanBase/telemetry")
                    put("availability_topic", "$cleanBase/status")
                    put("value_template", s.valueTemplate)
                    if (s.unit != null) put("unit_of_measurement", s.unit)
                    if (s.deviceClass != null) put("device_class", s.deviceClass)
                    if (s.stateClass != null) put("state_class", s.stateClass)
                    put("device", deviceObj)
                }.toString()
                out += topic to cfg
            }

            val portLabels = mapOf(1 to "USB-C1", 2 to "USB-C2", 3 to "USB-C3", 4 to "USB-A")
            for (pid in 1..4) {
                val label = portLabels[pid] ?: "Port $pid"
                val topic = "$cleanPrefix/switch/$cleanNode/port_$pid/config"
                val cfg = JSONObject().apply {
                    put("name", "$label Output")
                    put("unique_id", "${cleanNode}_port_$pid")
                    put("state_topic", "$cleanBase/telemetry")
                    put("command_topic", "$cleanBase/cmd/port/$pid/set")
                    put("availability_topic", "$cleanBase/status")
                    put("value_template", "{{ 'ON' if value_json.port_${pid}_enabled else 'OFF' }}")
                    put("payload_on", "ON")
                    put("payload_off", "OFF")
                    put("device", deviceObj)
                }.toString()
                out += topic to cfg
            }

            return out
        }
    }
}
