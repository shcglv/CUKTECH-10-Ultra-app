package com.example.cuktech.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class AutomationSecurityConfig(
    val taskerEnabled: Boolean = false,
    val adbBroadcastEnabled: Boolean = false,
    val tcpGatewayEnabled: Boolean = false,
    val tcpAllowLanBind: Boolean = false,
    val tcpPort: Int = 8080,
    val authTokenPreview: String = ""
)

/**
 * Manages opt-in switches and per-install CSPRNG secret token validation for external automation
 * channels (Tasker plugin, ADB/Broadcasts, TCP Gateway).
 *
 * Security defaults:
 * - All external automation channels are DISABLED by default (opt-in).
 * - TCP binds strictly to 127.0.0.1 unless `tcpAllowLanBind` is explicitly enabled.
 * - Tokens are generated via CSPRNG (256-bit) and stored encrypted in SecureCredentialStore.
 * - Verification uses constant-time comparison (`MessageDigest.isEqual`) with auth-failure backoff & command rate-limiting.
 */
class AutomationAuthManager(
    context: Context,
    private val credentialStore: SecureCredentialStore
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val ephemeralFallbackToken: String = generateTokenHex()

    private val authFailures = ConcurrentHashMap<String, AuthFailureTracker>()
    private val commandWindows = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<AutomationSecurityConfig> = _config.asStateFlow()

    private fun loadConfig(): AutomationSecurityConfig {
        val token = getOrCreateToken()
        return AutomationSecurityConfig(
            taskerEnabled = prefs.getBoolean(KEY_TASKER_ENABLED, false),
            adbBroadcastEnabled = prefs.getBoolean(KEY_ADB_ENABLED, false),
            tcpGatewayEnabled = prefs.getBoolean(KEY_TCP_ENABLED, false),
            tcpAllowLanBind = prefs.getBoolean(KEY_TCP_ALLOW_LAN, false),
            tcpPort = prefs.getInt(KEY_TCP_PORT, 8080).coerceIn(1024, 65535),
            authTokenPreview = maskToken(token)
        )
    }

    @Synchronized
    fun getOrCreateToken(): String {
        val existing = credentialStore.getEncryptedSecret(SECRET_KEY_TOKEN)
        if (!existing.isNullOrBlank() && existing.length >= 32) {
            return existing
        }
        val generated = generateTokenHex()
        if (!credentialStore.putEncryptedSecret(SECRET_KEY_TOKEN, generated)) {
            // If keystore itself is temporarily unavailable, fail-closed: return a non-persisted ephemeral token only in memory
            return ephemeralFallbackToken
        }
        return generated
    }

    @Synchronized
    fun rotateToken(): String {
        val generated = generateTokenHex()
        credentialStore.putEncryptedSecret(SECRET_KEY_TOKEN, generated)
        _config.value = _config.value.copy(authTokenPreview = maskToken(generated))
        return generated
    }

    fun setTaskerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TASKER_ENABLED, enabled).apply()
        _config.value = _config.value.copy(taskerEnabled = enabled)
    }

    fun setAdbBroadcastEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ADB_ENABLED, enabled).apply()
        _config.value = _config.value.copy(adbBroadcastEnabled = enabled)
    }

    fun setTcpGatewayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TCP_ENABLED, enabled).apply()
        _config.value = _config.value.copy(tcpGatewayEnabled = enabled)
    }

    fun setTcpAllowLanBind(allowLan: Boolean) {
        prefs.edit().putBoolean(KEY_TCP_ALLOW_LAN, allowLan).apply()
        _config.value = _config.value.copy(tcpAllowLanBind = allowLan)
    }

    fun setTcpPort(port: Int) {
        val valid = port.coerceIn(1024, 65535)
        prefs.edit().putInt(KEY_TCP_PORT, valid).apply()
        _config.value = _config.value.copy(tcpPort = valid)
    }

    fun verifyTokenConstantTime(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = getOrCreateToken()
        if (expected.isBlank()) return false
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        val candidateBytes = candidate.trim().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, candidateBytes)
    }

    fun isAuthLockedOut(sourceKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val tracker = authFailures[sourceKey] ?: return false
        if (nowMs >= tracker.lockedUntilMs) {
            return false
        }
        return true
    }

    fun recordAuthResult(sourceKey: String, success: Boolean, nowMs: Long = System.currentTimeMillis()): Long {
        if (success) {
            authFailures.remove(sourceKey)
            return 0L
        }
        val prev = authFailures[sourceKey]
        val recentCount = if (prev != null && nowMs - prev.lastFailureMs <= AUTH_WINDOW_MS) {
            prev.failures + 1
        } else {
            1
        }
        val backoffMs = if (recentCount >= MAX_AUTH_FAILURES) {
            val exp = (recentCount - MAX_AUTH_FAILURES).coerceAtMost(4)
            BASE_LOCKOUT_MS shl exp
        } else {
            0L
        }
        authFailures[sourceKey] = AuthFailureTracker(
            failures = recentCount,
            lastFailureMs = nowMs,
            lockedUntilMs = nowMs + backoffMs
        )
        return backoffMs
    }

    fun allowCommandExecution(
        sourceKey: String,
        maxPerWindow: Int = 20,
        windowMs: Long = 10_000L,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val queue = commandWindows.getOrPut(sourceKey) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && nowMs - queue.first() > windowMs) {
                queue.removeFirst()
            }
            if (queue.size >= maxPerWindow) {
                return false
            }
            queue.addLast(nowMs)
            return true
        }
    }

    private fun generateTokenHex(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun maskToken(token: String): String {
        return if (token.length >= 12) "${token.take(4)}...${token.takeLast(4)}" else "****"
    }

    private data class AuthFailureTracker(
        val failures: Int,
        val lastFailureMs: Long,
        val lockedUntilMs: Long
    )

    companion object {
        private const val PREFS_NAME = "cuktech_automation_auth"
        private const val SECRET_KEY_TOKEN = "automation_bearer_token_v1"
        private const val KEY_TASKER_ENABLED = "opt_in_tasker"
        private const val KEY_ADB_ENABLED = "opt_in_adb_broadcast"
        private const val KEY_TCP_ENABLED = "opt_in_tcp_gateway"
        private const val KEY_TCP_ALLOW_LAN = "opt_in_tcp_lan_bind"
        private const val KEY_TCP_PORT = "tcp_port"

        private const val MAX_AUTH_FAILURES = 5
        private const val AUTH_WINDOW_MS = 60_000L
        private const val BASE_LOCKOUT_MS = 30_000L
    }
}
