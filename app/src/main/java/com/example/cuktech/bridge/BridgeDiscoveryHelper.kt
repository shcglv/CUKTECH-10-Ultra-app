package com.example.cuktech.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

data class DiscoveredBridgeEndpoint(
    val serviceName: String,
    val serviceType: String,
    val host: String,
    val port: Int,
    val baseUrl: String
)

/**
 * Zero-permission LAN mDNS / NSD discovery helper for locating stationary CUKTECH bridges
 * on local Wi-Fi without requiring fine location permissions.
 */
object BridgeDiscoveryHelper {

    val SUPPORTED_SERVICE_TYPES = listOf(
        "_cuktech-bridge._tcp.",
        "_http._tcp."
    )

    fun buildDiscoveredBaseUrl(host: String, port: Int, useHttps: Boolean = false): String {
        val cleanHost = host.trim().trimStart('/').trimEnd('.')
        val scheme = if (useHttps || port == 443) "https" else "http"
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port <= 0 || port == defaultPort) {
            "$scheme://$cleanHost"
        } else {
            "$scheme://$cleanHost:$port"
        }
    }

    fun isLikelyBridgeService(serviceName: String, serviceType: String): Boolean {
        if (serviceType.contains("cuktech", ignoreCase = true)) return true
        val lower = serviceName.lowercase()
        return lower.contains("cuktech") || lower.contains("bridge") || lower.contains("ad1204")
    }

    suspend fun discoverOnLan(
        context: Context,
        timeoutMs: Long = 3_500L
    ): List<DiscoveredBridgeEndpoint> = withContext(Dispatchers.IO) {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return@withContext emptyList()

        val found = CopyOnWriteArrayList<DiscoveredBridgeEndpoint>()
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        for (srvType in SUPPORTED_SERVICE_TYPES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!isLikelyBridgeService(serviceInfo.serviceName.orEmpty(), srvType)) return
                    @Suppress("DEPRECATION")
                    runCatching {
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val hostAddr = resolved.host?.hostAddress
                                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                        resolved.hostAddresses.firstOrNull()?.hostAddress
                                    } else null
                                val port = resolved.port
                                if (!hostAddr.isNullOrBlank() && port in 1..65535) {
                                    val endpoint = DiscoveredBridgeEndpoint(
                                        serviceName = resolved.serviceName.orEmpty().ifBlank { "CUKTECH Bridge" },
                                        serviceType = srvType,
                                        host = hostAddr,
                                        port = port,
                                        baseUrl = buildDiscoveredBaseUrl(hostAddr, port)
                                    )
                                    if (found.none { it.baseUrl == endpoint.baseUrl }) {
                                        found.add(endpoint)
                                    }
                                }
                            }
                        })
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            val started = CompletableDeferred<Boolean>()
            runCatching {
                nsd.discoverServices(srvType, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners.add(listener)
                started.complete(true)
            }.onFailure {
                started.complete(false)
            }
            withTimeoutOrNull(200L) { started.await() }
        }

        delay(timeoutMs.coerceIn(800L, 10_000L))
        listeners.forEach { listener ->
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        found.distinctBy { it.baseUrl }
    }
}
