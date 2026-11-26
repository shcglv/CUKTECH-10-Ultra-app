package com.example.cuktech.security

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

data class WebDavConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val username: String = "",
    val hasPassword: Boolean = false,
    val remoteFilename: String = "cuktech10_cloud.cukbackup"
) {
    val isPlaintextHttpWarning: Boolean
        get() = baseUrl.trim().lowercase().startsWith("http://")
}

data class WebDavSyncStatus(
    val busy: Boolean = false,
    val lastSuccessMs: Long = 0L,
    val lastMessage: String? = null,
    val isError: Boolean = false
)

/**
 * WebDAV / Nextcloud cloud backup client reusing password-protected AES-256-GCM `.cukbackup`
 * payloads so cloud servers only ever store zero-knowledge ciphertext blobs.
 */
class WebDavBackupClient @JvmOverloads constructor(
    context: Context,
    private val credentialStore: SecureCredentialStore? = null,
    private val httpClient: OkHttpClient = defaultClient()
) {
    private val prefs = context.applicationContext.getSharedPreferences("cuktech_webdav_prefs", Context.MODE_PRIVATE)

    fun loadConfig(): WebDavConfig {
        val hasPass = !credentialStore?.getEncryptedSecret(SECRET_WEBDAV_PASSWORD).isNullOrBlank()
        return WebDavConfig(
            enabled = prefs.getBoolean("webdav_enabled", false),
            baseUrl = prefs.getString("webdav_url", "").orEmpty(),
            username = prefs.getString("webdav_user", "").orEmpty(),
            hasPassword = hasPass,
            remoteFilename = prefs.getString("webdav_filename", "cuktech10_cloud.cukbackup")
                .orEmpty()
                .ifBlank { "cuktech10_cloud.cukbackup" }
        )
    }

    fun saveConfig(config: WebDavConfig, passwordOverride: String? = null): Result<WebDavConfig> {
        val cleanUrl = config.baseUrl.trim()
        if (cleanUrl.isNotEmpty() && !isValidWebDavUrl(cleanUrl)) {
            return Result.failure(IllegalArgumentException("WebDAV URL must start with https:// or http:// and contain a valid host"))
        }
        val cleanFile = sanitizeFilename(config.remoteFilename)
        var hasPass = loadConfig().hasPassword
        if (passwordOverride != null) {
            val trimmedPass = passwordOverride.trim()
            if (trimmedPass.isEmpty()) {
                credentialStore?.removeEncryptedSecret(SECRET_WEBDAV_PASSWORD)
                hasPass = false
            } else {
                credentialStore?.putEncryptedSecret(SECRET_WEBDAV_PASSWORD, trimmedPass)
                hasPass = true
            }
        }
        prefs.edit()
            .putBoolean("webdav_enabled", config.enabled)
            .putString("webdav_url", cleanUrl)
            .putString("webdav_user", config.username.trim())
            .putString("webdav_filename", cleanFile)
            .apply()

        return Result.success(
            config.copy(
                baseUrl = cleanUrl,
                username = config.username.trim(),
                hasPassword = hasPass,
                remoteFilename = cleanFile
            )
        )
    }

    fun currentPasswordOrNull(): String? = credentialStore?.getEncryptedSecret(SECRET_WEBDAV_PASSWORD)

    suspend fun testConnection(
        baseUrl: String,
        username: String,
        passwordOverride: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(isValidWebDavUrl(baseUrl)) { "Invalid WebDAV URL" }
            val pass = passwordOverride?.takeIf { it.isNotEmpty() } ?: currentPasswordOrNull().orEmpty()
            val reqBuilder = Request.Builder()
                .url(baseUrl.trim())
                .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
                .header("Depth", "0")
            if (username.isNotBlank()) {
                reqBuilder.header("Authorization", Credentials.basic(username.trim(), pass))
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code in 200..299 || resp.code == 207) {
                    "HTTP ${resp.code} OK"
                } else {
                    error("WebDAV HTTP ${resp.code}")
                }
            }
        }.recoverCatching { err ->
            throw IllegalStateException(LogSanitizer.sanitize(err.message ?: "WebDAV error"), err)
        }
    }

    suspend fun uploadEncryptedBackup(
        baseUrl: String,
        username: String,
        passwordOverride: String?,
        remoteFilename: String,
        payload: BackupPayload,
        backupPassphrase: CharArray
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            require(isValidWebDavUrl(baseUrl)) { "Invalid WebDAV URL" }
            require(backupPassphrase.isNotEmpty()) { "Backup passphrase required" }
            val baos = ByteArrayOutputStream()
            SafEncryptedBackupManager.encryptToStream(payload, backupPassphrase, baos)
            val encryptedBytes = baos.toByteArray()

            val targetUrl = buildBackupFileUrl(baseUrl, remoteFilename)
            val pass = passwordOverride?.takeIf { it.isNotEmpty() } ?: currentPasswordOrNull().orEmpty()
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .put(encryptedBytes.toRequestBody("application/octet-stream".toMediaType()))
            if (username.isNotBlank()) {
                reqBuilder.header("Authorization", Credentials.basic(username.trim(), pass))
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code !in 200..299) {
                    error("Upload failed: HTTP ${resp.code}")
                }
            }
            encryptedBytes.size
        }.recoverCatching { err ->
            throw IllegalStateException(LogSanitizer.sanitize(err.message ?: "WebDAV upload failed"), err)
        }
    }

    suspend fun downloadAndDecryptBackup(
        baseUrl: String,
        username: String,
        passwordOverride: String?,
        remoteFilename: String,
        backupPassphrase: CharArray
    ): Result<BackupPayload> = withContext(Dispatchers.IO) {
        runCatching {
            require(isValidWebDavUrl(baseUrl)) { "Invalid WebDAV URL" }
            require(backupPassphrase.isNotEmpty()) { "Backup passphrase required" }
            val targetUrl = buildBackupFileUrl(baseUrl, remoteFilename)
            val pass = passwordOverride?.takeIf { it.isNotEmpty() } ?: currentPasswordOrNull().orEmpty()
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .get()
            if (username.isNotBlank()) {
                reqBuilder.header("Authorization", Credentials.basic(username.trim(), pass))
            }
            val rawBytes = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code !in 200..299) {
                    error("Download failed: HTTP ${resp.code}")
                }
                resp.body?.bytes() ?: error("Empty WebDAV response body")
            }
            SafEncryptedBackupManager.decryptFromStream(ByteArrayInputStream(rawBytes), backupPassphrase)
        }.recoverCatching { err ->
            throw IllegalStateException(LogSanitizer.sanitize(err.message ?: "WebDAV restore failed"), err)
        }
    }

    companion object {
        private const val SECRET_WEBDAV_PASSWORD = "webdav_password"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        fun isValidWebDavUrl(rawUrl: String): Boolean {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank()) return false
            return runCatching {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.lowercase()
                (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }

        fun sanitizeFilename(name: String): String {
            val clean = name.trim().substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            return if (clean.isBlank()) "cuktech10_cloud.cukbackup" else clean
        }

        fun buildBackupFileUrl(baseUrl: String, remoteFilename: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            val safeFile = sanitizeFilename(remoteFilename)
            return if (trimmed.endsWith(".cukbackup", ignoreCase = true)) {
                trimmed
            } else {
                "$trimmed/$safeFile"
            }
        }
    }
}
