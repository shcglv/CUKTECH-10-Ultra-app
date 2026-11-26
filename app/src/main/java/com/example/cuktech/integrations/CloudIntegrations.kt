package com.example.cuktech.integrations

import android.content.Context
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.security.LogSanitizer
import com.example.cuktech.security.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class IntegrationConfig(
    val haWebhookEnabled: Boolean = false,
    val haWebhookUrl: String = "",
    val hasBearerToken: Boolean = false,
    val eventWebhooksEnabled: Boolean = true,
    val mqttEnabled: Boolean = false,
    val mqttBrokerUrl: String = "",
    val mqttClientId: String = "cuktech10_android",
    val mqttBaseTopic: String = "cuktech10",
    val mqttUsername: String = "",
    val hasMqttPassword: Boolean = false,
    val haDiscoveryEnabled: Boolean = true,
    val haDiscoveryPrefix: String = "homeassistant"
) {
    val isPlaintextHttpWarning: Boolean
        get() = haWebhookUrl.trim().lowercase().startsWith("http://")
}

/**
 * External ecosystem bridge:
 * - Home Assistant / generic HTTP Webhooks (periodic telemetry + real-time event webhooks)
 * - Dependency-free MQTT 3.1.1 telemetry/event stream + Home Assistant Auto Discovery + inbound port/scene control
 */
class CloudIntegrations @JvmOverloads constructor(
    context: Context,
    private val credentialStore: SecureCredentialStore? = null,
    private val onPortSwitchCommand: (portId: Int, enabled: Boolean) -> Unit,
    private val onSceneModeCommand: (mode: Int) -> Unit = {}
) {
    private val prefs = context.applicationContext.getSharedPreferences("cuktech_integrations", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val mqttClient = MqttBridgeClient { cmd ->
        when (cmd) {
            is MqttInboundCommand.SetPortEnabled -> onPortSwitchCommand(cmd.portId, cmd.enabled)
            is MqttInboundCommand.SetSceneMode -> onSceneModeCommand(cmd.mode)
        }
    }

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<IntegrationConfig> = _config.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val mqttStatus: StateFlow<MqttConnectionStatus> = mqttClient.status

    @Volatile
    private var lastPushMs: Long = 0L

    private fun loadConfig(): IntegrationConfig {
        val hasToken = !credentialStore?.getEncryptedSecret(SECRET_HA_TOKEN).isNullOrBlank()
        val hasMqttPass = !credentialStore?.getEncryptedSecret(SECRET_MQTT_PASS).isNullOrBlank()
        return IntegrationConfig(
            haWebhookEnabled = prefs.getBoolean("ha_enabled", false),
            haWebhookUrl = prefs.getString("ha_url", "").orEmpty(),
            hasBearerToken = hasToken,
            eventWebhooksEnabled = prefs.getBoolean("event_webhooks_enabled", true),
            mqttEnabled = prefs.getBoolean("mqtt_enabled", false),
            mqttBrokerUrl = prefs.getString("mqtt_broker_url", "").orEmpty(),
            mqttClientId = prefs.getString("mqtt_client_id", "cuktech10_android").orEmpty().ifBlank { "cuktech10_android" },
            mqttBaseTopic = prefs.getString("mqtt_base_topic", "cuktech10").orEmpty().ifBlank { "cuktech10" },
            mqttUsername = prefs.getString("mqtt_username", "").orEmpty(),
            hasMqttPassword = hasMqttPass,
            haDiscoveryEnabled = prefs.getBoolean("ha_discovery_enabled", true),
            haDiscoveryPrefix = prefs.getString("ha_discovery_prefix", "homeassistant").orEmpty().ifBlank { "homeassistant" }
        )
    }

    fun updateConfig(newConfig: IntegrationConfig, bearerToken: String? = null, mqttPassword: String? = null) {
        val cleanedUrl = newConfig.haWebhookUrl.trim()
        if (cleanedUrl.isNotEmpty() && !isValidWebhookUrl(cleanedUrl)) {
            _lastError.value = "Webhook URL must start with https:// or http:// and contain a valid host"
            return
        }
        val cleanedMqttUrl = newConfig.mqttBrokerUrl.trim()
        if (newConfig.mqttEnabled && MqttBridgeClient.parseBrokerUri(cleanedMqttUrl) == null) {
            _lastError.value = "MQTT broker URL must use mqtt:// or mqtts:// with a valid host"
            return
        }

        var hasTok = _config.value.hasBearerToken
        if (bearerToken != null) {
            val trimmedTok = bearerToken.trim()
            if (trimmedTok.isEmpty()) {
                credentialStore?.removeEncryptedSecret(SECRET_HA_TOKEN)
                hasTok = false
            } else {
                credentialStore?.putEncryptedSecret(SECRET_HA_TOKEN, trimmedTok)
                hasTok = true
            }
        }

        var hasMqttPass = _config.value.hasMqttPassword
        if (mqttPassword != null) {
            val trimmedPass = mqttPassword.trim()
            if (trimmedPass.isEmpty()) {
                credentialStore?.removeEncryptedSecret(SECRET_MQTT_PASS)
                hasMqttPass = false
            } else {
                credentialStore?.putEncryptedSecret(SECRET_MQTT_PASS, trimmedPass)
                hasMqttPass = true
            }
        }

        val cleanBase = MqttBridgeClient.normalizeBaseTopic(newConfig.mqttBaseTopic)
        val cleanClientId = newConfig.mqttClientId.trim().ifBlank { "cuktech10_android" }
        val cleanDiscoveryPrefix = newConfig.haDiscoveryPrefix.trim().trim('/').ifBlank { "homeassistant" }

        _lastError.value = null
        prefs.edit()
            .putBoolean("ha_enabled", newConfig.haWebhookEnabled)
            .putString("ha_url", cleanedUrl)
            .putBoolean("event_webhooks_enabled", newConfig.eventWebhooksEnabled)
            .putBoolean("mqtt_enabled", newConfig.mqttEnabled)
            .putString("mqtt_broker_url", cleanedMqttUrl)
            .putString("mqtt_client_id", cleanClientId)
            .putString("mqtt_base_topic", cleanBase)
            .putString("mqtt_username", newConfig.mqttUsername.trim())
            .putBoolean("ha_discovery_enabled", newConfig.haDiscoveryEnabled)
            .putString("ha_discovery_prefix", cleanDiscoveryPrefix)
            .apply()

        val saved = newConfig.copy(
            haWebhookUrl = cleanedUrl,
            hasBearerToken = hasTok,
            mqttBrokerUrl = cleanedMqttUrl,
            mqttClientId = cleanClientId,
            mqttBaseTopic = cleanBase,
            mqttUsername = newConfig.mqttUsername.trim(),
            hasMqttPassword = hasMqttPass,
            haDiscoveryPrefix = cleanDiscoveryPrefix
        )
        _config.value = saved

        if (saved.mqttEnabled) {
            connectOrReconnectMqtt()
        } else {
            mqttClient.disconnect(publishOffline = true)
        }
    }

    fun connectOrReconnectMqtt(
        nodeId: String = "cuktech10_ultra",
        deviceName: String = "CUKTECH 10 Ultra"
    ) {
        val cfg = _config.value
        if (!cfg.mqttEnabled || cfg.mqttBrokerUrl.isBlank()) {
            mqttClient.disconnect(publishOffline = true)
            return
        }
        val pass = credentialStore?.getEncryptedSecret(SECRET_MQTT_PASS).orEmpty()
        mqttClient.connect(
            brokerUrl = cfg.mqttBrokerUrl,
            clientId = cfg.mqttClientId,
            baseTopic = cfg.mqttBaseTopic,
            username = cfg.mqttUsername,
            password = pass,
            haDiscoveryEnabled = cfg.haDiscoveryEnabled,
            haDiscoveryPrefix = cfg.haDiscoveryPrefix,
            nodeId = nodeId,
            deviceName = deviceName
        )
    }

    fun publishHaDiscoveryNow(
        nodeId: String = "cuktech10_ultra",
        deviceName: String = "CUKTECH 10 Ultra"
    ) {
        val cfg = _config.value
        if (!cfg.mqttEnabled) return
        scope.launch {
            if (mqttClient.status.value.state != MqttConnectionState.CONNECTED) {
                connectOrReconnectMqtt(nodeId, deviceName)
            } else {
                mqttClient.publishHomeAssistantDiscovery(
                    baseTopic = cfg.mqttBaseTopic,
                    discoveryPrefix = cfg.haDiscoveryPrefix,
                    nodeId = nodeId,
                    deviceName = deviceName
                )
            }
        }
    }

    @JvmOverloads
    fun onTelemetryUpdate(ports: Map<Int, PortTelemetry>, totalWatts: Double, rssiDbm: Int = 0) {
        val now = System.currentTimeMillis()
        if (now - lastPushMs < 10000L) return
        lastPushMs = now

        val cfg = _config.value
        val needsWebhook = cfg.haWebhookEnabled && isValidWebhookUrl(cfg.haWebhookUrl)
        val needsMqtt = cfg.mqttEnabled && mqttClient.status.value.state == MqttConnectionState.CONNECTED
        if (!needsWebhook && !needsMqtt) return

        val telemetryJson = buildTelemetryPayload(ports, totalWatts, rssiDbm)
        if (needsWebhook) {
            scope.launch {
                postWebhookPayload(cfg.haWebhookUrl, telemetryJson.toString())
            }
        }
        if (needsMqtt) {
            scope.launch {
                val base = cfg.mqttBaseTopic
                mqttClient.publish("$base/telemetry", telemetryJson.toString(), retain = false)
                for ((pid, p) in ports) {
                    val portJson = JSONObject().apply {
                        put("port_id", pid)
                        put("v", p.voltageV)
                        put("a", p.currentA)
                        put("w", p.powerW)
                        put("wh", p.cumulativeWh)
                        put("protocol", p.protocolName)
                        put("enabled", p.enabled)
                    }.toString()
                    mqttClient.publish("$base/port/$pid/state", portJson, retain = false)
                }
            }
        }
    }

    fun publishEvent(
        eventType: String,
        title: String,
        detail: String = "",
        portId: Int = 0,
        extra: Map<String, Any> = emptyMap()
    ) {
        val cfg = _config.value
        val sendWebhook = cfg.haWebhookEnabled && cfg.eventWebhooksEnabled && isValidWebhookUrl(cfg.haWebhookUrl)
        val sendMqtt = cfg.mqttEnabled && mqttClient.status.value.state == MqttConnectionState.CONNECTED
        if (!sendWebhook && !sendMqtt) return

        val payloadJson = buildEventPayload(eventType, title, detail, portId, extra).toString()
        scope.launch {
            if (sendWebhook) {
                postWebhookPayload(cfg.haWebhookUrl, payloadJson)
            }
            if (sendMqtt) {
                mqttClient.publish("${cfg.mqttBaseTopic}/events", payloadJson, retain = false)
            }
        }
    }

    private fun postWebhookPayload(targetUrl: String, jsonBody: String) {
        runCatching {
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val token = credentialStore?.getEncryptedSecret(SECRET_HA_TOKEN)
                if (!token.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(jsonBody)
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) {
                _lastError.value = LogSanitizer.sanitize(
                    "Webhook HTTP $code (${LogSanitizer.sanitizeUrlForDisplay(targetUrl)})"
                )
            } else {
                _lastError.value = null
            }
        }.onFailure { err ->
            _lastError.value = LogSanitizer.sanitize("Webhook error: ${err.message}")
        }
    }

    fun shutdown() {
        mqttClient.disconnect(publishOffline = true)
    }

    companion object {
        private const val SECRET_HA_TOKEN = "ha_bearer_token"
        private const val SECRET_MQTT_PASS = "mqtt_password"

        fun buildTelemetryPayload(
            ports: Map<Int, PortTelemetry>,
            totalWatts: Double,
            rssiDbm: Int = 0
        ): JSONObject = JSONObject().apply {
            put("type", "telemetry")
            put("timestamp_ms", System.currentTimeMillis())
            put("total_power_w", totalWatts)
            if (rssiDbm != 0) put("rssi_dbm", rssiDbm)
            for ((pid, p) in ports) {
                put("port_${pid}_v", p.voltageV)
                put("port_${pid}_a", p.currentA)
                put("port_${pid}_w", p.powerW)
                put("port_${pid}_wh", p.cumulativeWh)
                put("port_${pid}_protocol", p.protocolName)
                put("port_${pid}_enabled", p.enabled)
            }
        }

        fun buildEventPayload(
            eventType: String,
            title: String,
            detail: String = "",
            portId: Int = 0,
            extra: Map<String, Any> = emptyMap(),
            timestampMs: Long = System.currentTimeMillis()
        ): JSONObject = JSONObject().apply {
            put("type", "event")
            put("event", eventType)
            put("port_id", portId)
            put("title", LogSanitizer.sanitize(title, maskNetworkIdentifiers = true))
            put("detail", LogSanitizer.sanitize(detail, maskNetworkIdentifiers = true))
            put("timestamp_ms", timestampMs)
            for ((k, v) in extra) {
                put(k, v)
            }
        }

        fun isValidWebhookUrl(url: String): Boolean {
            return try {
                val uri = URI(url.trim())
                val scheme = uri.scheme?.lowercase()
                (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }
    }
}
