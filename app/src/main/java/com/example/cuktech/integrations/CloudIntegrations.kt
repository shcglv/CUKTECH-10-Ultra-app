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
    val hasBearerToken: Boolean = false
) {
    val isPlaintextHttpWarning: Boolean
        get() = haWebhookUrl.trim().lowercase().startsWith("http://")
}

/**
 * Home Assistant HTTP Webhook telemetry publisher.
 * Supports encrypted Bearer token storage via Keystore, URL scheme validation, and sanitized errors.
 */
class CloudIntegrations @JvmOverloads constructor(
    context: Context,
    private val credentialStore: SecureCredentialStore? = null,
    @Suppress("UNUSED_PARAMETER") private val onPortSwitchCommand: (portId: Int, enabled: Boolean) -> Unit
) {
    private val prefs = context.applicationContext.getSharedPreferences("cuktech_integrations", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<IntegrationConfig> = _config.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var lastPushMs: Long = 0L

    private fun loadConfig(): IntegrationConfig {
        val hasToken = !credentialStore?.getEncryptedSecret(SECRET_HA_TOKEN).isNullOrBlank()
        return IntegrationConfig(
            haWebhookEnabled = prefs.getBoolean("ha_enabled", false),
            haWebhookUrl = prefs.getString("ha_url", "").orEmpty(),
            hasBearerToken = hasToken
        )
    }

    fun updateConfig(newConfig: IntegrationConfig, bearerToken: String? = null) {
        val cleanedUrl = newConfig.haWebhookUrl.trim()
        if (cleanedUrl.isNotEmpty() && !isValidWebhookUrl(cleanedUrl)) {
            _lastError.value = "Webhook URL must start with https:// or http:// and contain a valid host"
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
        _lastError.value = null
        prefs.edit()
            .putBoolean("ha_enabled", newConfig.haWebhookEnabled)
            .putString("ha_url", cleanedUrl)
            .apply()
        _config.value = newConfig.copy(haWebhookUrl = cleanedUrl, hasBearerToken = hasTok)
    }

    fun onTelemetryUpdate(ports: Map<Int, PortTelemetry>, totalWatts: Double) {
        val now = System.currentTimeMillis()
        if (now - lastPushMs < 10000L) return
        lastPushMs = now

        val cfg = _config.value
        if (cfg.haWebhookEnabled && isValidWebhookUrl(cfg.haWebhookUrl)) {
            scope.launch {
                runCatching {
                    val json = JSONObject().apply {
                        put("total_power_w", totalWatts)
                        for ((pid, p) in ports) {
                            put("port_${pid}_v", p.voltageV)
                            put("port_${pid}_a", p.currentA)
                            put("port_${pid}_w", p.powerW)
                            put("port_${pid}_protocol", p.protocolName)
                            put("port_${pid}_enabled", p.enabled)
                        }
                    }
                    val conn = (URL(cfg.haWebhookUrl).openConnection() as HttpURLConnection).apply {
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
                        it.write(json.toString())
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code !in 200..299) {
                        _lastError.value = LogSanitizer.sanitize(
                            "Webhook HTTP $code (${LogSanitizer.sanitizeUrlForDisplay(cfg.haWebhookUrl)})"
                        )
                    } else {
                        _lastError.value = null
                    }
                }.onFailure { err ->
                    _lastError.value = LogSanitizer.sanitize("Webhook error: ${err.message}")
                }
            }
        }
    }

    companion object {
        private const val SECRET_HA_TOKEN = "ha_bearer_token"

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
