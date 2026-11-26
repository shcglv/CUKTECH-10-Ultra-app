package com.example.cuktech.bridge

import com.example.cuktech.db.room.TelemetrySampleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class BridgeStatusResult(
    val online: Boolean,
    val version: String = "",
    val activeMac: String = "",
    val bufferedSamples: Int = 0,
    val lastPollMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Optional HTTP/JSON client for pairing the mobile app with a stationary home bridge
 * (e.g., Raspberry Pi / router / old spare phone) so charging periods while the phone
 * left Bluetooth range are backfilled seamlessly without fabricated gaps.
 */
object BridgeSyncClient {

    private const val CONNECT_TIMEOUT_MS = 4_500
    private const val READ_TIMEOUT_MS = 6_000

    fun isValidBridgeUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return false
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            (scheme == "http" || scheme == "https") &&
                !host.isNullOrBlank() &&
                (uri.port == -1 || uri.port in 1..65535)
        }.getOrDefault(false)
    }

    fun normalizeBaseUrl(rawUrl: String): String = rawUrl.trim().trimEnd('/')

    suspend fun fetchStatus(
        endpointUrl: String,
        bearerToken: String? = null
    ): BridgeStatusResult = withContext(Dispatchers.IO) {
        if (!isValidBridgeUrl(endpointUrl)) {
            return@withContext BridgeStatusResult(online = false, errorMessage = "Invalid bridge URL")
        }
        val base = normalizeBaseUrl(endpointUrl)
        val candidates = listOf("$base/api/v1/status", "$base/status")
        var lastErr: String? = null

        for (target in candidates) {
            val attempt = runCatching {
                val body = executeHttpGet(target, bearerToken)
                val obj = JSONObject(body)
                BridgeStatusResult(
                    online = obj.optBoolean("online", true),
                    version = obj.optString("version", obj.optString("bridgeVersion", "")),
                    activeMac = obj.optString("activeMac", obj.optString("mac", "")),
                    bufferedSamples = obj.optInt("bufferedSamples", obj.optInt("buffered_samples", 0)),
                    lastPollMs = obj.optLong("lastPollMs", obj.optLong("last_poll_ms", 0L)),
                    errorMessage = null
                )
            }
            if (attempt.isSuccess) return@withContext attempt.getOrThrow()
            lastErr = attempt.exceptionOrNull()?.message
        }

        BridgeStatusResult(online = false, errorMessage = lastErr ?: "Bridge unreachable")
    }

    suspend fun pullSamplesSince(
        endpointUrl: String,
        bearerToken: String?,
        deviceMac: String,
        sinceMs: Long,
        untilMs: Long = System.currentTimeMillis()
    ): Result<List<TelemetrySampleEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            require(isValidBridgeUrl(endpointUrl)) { "Invalid bridge endpoint URL" }
            val base = normalizeBaseUrl(endpointUrl)
            val encodedMac = URLEncoder.encode(deviceMac.trim(), "UTF-8")
            val target = "$base/api/v1/telemetry/export?mac=$encodedMac&since_ms=$sinceMs&until_ms=$untilMs"
            val json = executeHttpGet(target, bearerToken)
            parseBridgeSamplesJson(json, defaultMac = deviceMac)
        }
    }

    suspend fun sendRemoteCommand(
        endpointUrl: String,
        bearerToken: String?,
        deviceMac: String,
        commandJson: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(isValidBridgeUrl(endpointUrl)) { "Invalid bridge endpoint URL" }
            val base = normalizeBaseUrl(endpointUrl)
            val target = "$base/api/v1/command"
            val payload = JSONObject().apply {
                put("mac", deviceMac.trim())
                put("command", runCatching { JSONObject(commandJson) }.getOrElse { commandJson })
            }.toString()
            executeHttpPost(target, payload, bearerToken)
        }
    }

    fun parseBridgeSamplesJson(
        jsonText: String,
        defaultMac: String = ""
    ): List<TelemetrySampleEntity> {
        val trimmed = jsonText.trim()
        if (trimmed.isBlank()) return emptyList()

        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("samples")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("telemetry")
                    ?: JSONArray()
            }
            else -> return emptyList()
        }

        val out = ArrayList<TelemetrySampleEntity>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val rawTs = when {
                item.has("timestampMs") -> item.optLong("timestampMs", 0L)
                item.has("ts_ms") -> item.optLong("ts_ms", 0L)
                item.has("ts") -> {
                    val v = item.optLong("ts", 0L)
                    if (v in 1L..9_999_999_999L) v * 1000L else v
                }
                else -> 0L
            }
            val portId = when {
                item.has("portId") -> item.optInt("portId", -1)
                item.has("port_id") -> item.optInt("port_id", -1)
                item.has("port") -> item.optInt("port", -1)
                else -> -1
            }
            if (rawTs <= 0L || portId !in 1..4) continue

            val voltage = readDouble(item, "voltageV", "voltage_v", "v")
            val current = readDouble(item, "currentA", "current_a", "a")
            val power = readDouble(item, "powerW", "power_w", "w").let { p ->
                if (p <= 0.0 && voltage > 0.0 && current > 0.0) voltage * current else p
            }
            if (!voltage.isFinite() || !current.isFinite() || !power.isFinite()) continue

            val protocol = item.optString("protocolName", item.optString("protocol", "Idle"))
                .ifBlank { "Idle" }
            val wh = readDouble(item, "cumulativeWh", "cumulative_wh", "wh")
            val mac = item.optString("deviceMac", item.optString("mac", defaultMac))
                .ifBlank { defaultMac }
            val rssi = item.optInt("rssiDbm", item.optInt("rssi", 0))

            out.add(
                TelemetrySampleEntity(
                    timestampMs = rawTs,
                    portId = portId,
                    voltageV = voltage.coerceAtLeast(0.0),
                    currentA = current.coerceAtLeast(0.0),
                    powerW = power.coerceAtLeast(0.0),
                    protocolName = protocol,
                    cumulativeWh = wh.coerceAtLeast(0.0),
                    deviceMac = mac.uppercase().trim(),
                    sourceMode = "BRIDGE_SYNC",
                    rssiDbm = rssi
                )
            )
        }
        return out.sortedBy { it.timestampMs }
    }

    private fun readDouble(obj: JSONObject, vararg keys: String): Double {
        for (k in keys) {
            if (obj.has(k)) {
                val v = obj.optDouble(k, 0.0)
                if (v.isFinite()) return v
            }
        }
        return 0.0
    }

    private fun executeHttpGet(targetUrl: String, bearerToken: String?): String {
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (!bearerToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code: ${text.take(120)}")
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun executeHttpPost(targetUrl: String, jsonBody: String, bearerToken: String?): String {
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (!bearerToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
            }
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w ->
                w.write(jsonBody)
                w.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code: ${text.take(120)}")
            }
            text
        } finally {
            conn.disconnect()
        }
    }
}
