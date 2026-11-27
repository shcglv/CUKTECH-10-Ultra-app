package com.example.cuktech.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.example.cuktech.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.security.MessageDigest

enum class WifiContextStatus {
    UNKNOWN,
    BOUND_HOME_WIFI,
    OTHER_WIFI,
    NO_WIFI
}

data class WifiContextSnapshot(
    val isOnWifi: Boolean = false,
    val currentKey: String = "",
    val displayLabel: String = "",
    val status: WifiContextStatus = WifiContextStatus.UNKNOWN,
    val updatedAtMs: Long = 0L
)

/**
 * Tracks active Wi-Fi network context using ConnectivityManager without requesting
 * location permissions on Android 12+. Uses subnet/gateway fingerprints (and SSID when
 * already readable) so users can bind a Home/Office Wi-Fi network to gate background
 * BLE reconnects and receive alerts when leaving with an active charger load.
 */
class WifiContextMonitor(context: Context) {

    private val appCtx = context.applicationContext
    private val connectivityManager =
        appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager =
        appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _snapshot = MutableStateFlow(WifiContextSnapshot())
    val snapshot: StateFlow<WifiContextSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var boundKey: String = ""

    @Volatile
    private var contextEnabled: Boolean = false

    @Volatile
    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNow()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            refreshNow()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            refreshNow()
        }

        override fun onLost(network: Network) {
            refreshNow()
        }
    }

    fun startMonitoring() {
        refreshNow()
        if (registered) return
        val cm = connectivityManager ?: return
        runCatching {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
            registered = true
        }
    }

    fun stopMonitoring() {
        if (!registered) return
        val cm = connectivityManager ?: return
        runCatching {
            cm.unregisterNetworkCallback(networkCallback)
        }
        registered = false
    }

    fun updateBindingPolicy(enabled: Boolean, boundNetworkKey: String) {
        contextEnabled = enabled
        boundKey = boundNetworkKey.trim()
        val cur = _snapshot.value
        _snapshot.value = cur.copy(
            status = evaluateWifiStatus(
                isOnWifi = cur.isOnWifi,
                currentKey = cur.currentKey,
                boundKey = boundKey,
                wifiContextEnabled = contextEnabled
            )
        )
    }

    fun setSnapshotForTest(testSnapshot: WifiContextSnapshot) {
        _snapshot.value = testSnapshot
    }

    fun refreshNow(): WifiContextSnapshot {
        val cm = connectivityManager
        val activeNet = cm?.activeNetwork
        val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isWifi || activeNet == null) {
            val next = WifiContextSnapshot(
                isOnWifi = false,
                currentKey = "",
                displayLabel = "",
                status = if (contextEnabled) WifiContextStatus.NO_WIFI else WifiContextStatus.UNKNOWN,
                updatedAtMs = System.currentTimeMillis()
            )
            _snapshot.value = next
            return next
        }

        val lp = cm.getLinkProperties(activeNet)
        val ipv4Link = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val ipv4Host = (ipv4Link?.address as? Inet4Address)?.hostAddress
        val prefixLen = ipv4Link?.prefixLength ?: 24
        val gatewayIp = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
            ?: lp?.dnsServers?.firstOrNull { it is Inet4Address }?.hostAddress

        @Suppress("DEPRECATION")
        val rawSsid = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        val (sigKey, label) = computeNetworkSignature(
            gatewayOrDnsIp = gatewayIp,
            localIpv4 = ipv4Host,
            prefixLength = prefixLen,
            rawSsid = rawSsid
        )
        val next = WifiContextSnapshot(
            isOnWifi = true,
            currentKey = sigKey,
            displayLabel = label,
            status = evaluateWifiStatus(
                isOnWifi = true,
                currentKey = sigKey,
                boundKey = boundKey,
                wifiContextEnabled = contextEnabled
            ),
            updatedAtMs = System.currentTimeMillis()
        )
        _snapshot.value = next
        return next
    }

    companion object {
        private const val LEAVE_ALERT_MAX_TELEMETRY_AGE_MS = 10 * 60_000L

        fun sanitizeSsid(rawSsid: String?): String? {
            if (rawSsid.isNullOrBlank()) return null
            val cleaned = rawSsid.trim().removeSurrounding("\"").trim()
            if (cleaned.isBlank() ||
                cleaned.equals("<unknown ssid>", ignoreCase = true) ||
                cleaned.equals("0x", ignoreCase = true)
            ) {
                return null
            }
            return cleaned.take(48)
        }

        fun computeNetworkSignature(
            gatewayOrDnsIp: String?,
            localIpv4: String?,
            prefixLength: Int = 24,
            rawSsid: String? = null
        ): Pair<String, String> {
            val ssid = sanitizeSsid(rawSsid)
            val subnetLabel = buildSubnetLabel(gatewayOrDnsIp ?: localIpv4, prefixLength)
            val rawSeed = listOfNotNull(
                ssid?.let { "ssid:$it" },
                gatewayOrDnsIp?.takeIf { it.isNotBlank() }?.let { "gw:$it" },
                subnetLabel?.let { "sub:$it" }
            ).joinToString("|")

            if (rawSeed.isBlank()) {
                return "wifi:generic" to (ssid ?: "Wi-Fi")
            }

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(rawSeed.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }

            val friendly = when {
                ssid != null && subnetLabel != null -> "$ssid ($subnetLabel)"
                ssid != null -> ssid
                subnetLabel != null -> "Wi-Fi $subnetLabel"
                else -> "Wi-Fi"
            }
            return "wifi:$digest" to friendly
        }

        private fun buildSubnetLabel(ip: String?, prefixLen: Int): String? {
            if (ip.isNullOrBlank()) return null
            val parts = ip.trim().split('.')
            if (parts.size != 4) return null
            return "${parts[0]}.${parts[1]}.${parts[2]}.0/$prefixLen"
        }

        fun evaluateWifiStatus(
            isOnWifi: Boolean,
            currentKey: String,
            boundKey: String,
            wifiContextEnabled: Boolean
        ): WifiContextStatus {
            if (!wifiContextEnabled) return WifiContextStatus.UNKNOWN
            if (!isOnWifi) return WifiContextStatus.NO_WIFI
            val bound = boundKey.trim()
            if (bound.isBlank()) return WifiContextStatus.OTHER_WIFI
            return if (currentKey == bound) WifiContextStatus.BOUND_HOME_WIFI else WifiContextStatus.OTHER_WIFI
        }

        fun shouldPauseBleReconnect(
            prefs: AppPreferences,
            snapshot: WifiContextSnapshot
        ): Boolean {
            if (!prefs.wifiContextEnabled || !prefs.wifiBleOnlyAtHome) return false
            if (prefs.boundWifiKey.isBlank()) return false
            return snapshot.status != WifiContextStatus.BOUND_HOME_WIFI
        }

        fun shouldTriggerLeaveHomeLoadAlert(
            prevStatus: WifiContextStatus,
            nextStatus: WifiContextStatus,
            lastKnownLoadW: Double,
            lastKnownLoadAgeMs: Long,
            prefs: AppPreferences
        ): Boolean {
            if (!prefs.wifiContextEnabled || !prefs.wifiLeaveLoadAlertEnabled) return false
            if (prefs.boundWifiKey.isBlank()) return false
            if (prevStatus != WifiContextStatus.BOUND_HOME_WIFI) return false
            if (nextStatus == WifiContextStatus.BOUND_HOME_WIFI || nextStatus == WifiContextStatus.UNKNOWN) return false
            if (lastKnownLoadAgeMs < 0L || lastKnownLoadAgeMs > LEAVE_ALERT_MAX_TELEMETRY_AGE_MS) return false
            return lastKnownLoadW >= prefs.wifiLeaveLoadThresholdWatts.coerceAtLeast(1.0)
        }
    }
}
