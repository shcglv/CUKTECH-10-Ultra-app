package com.example.cuktech.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ReleaseUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val tagName: String,
    val releaseName: String,
    val changelog: String,
    val htmlUrl: String,
    val publishedAtIso: String,
    val apkName: String?,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long,
    val expectedSha256: String?,
    val checksumSourceUrl: String?,
    val isUpdateAvailable: Boolean
)

sealed class UpdateDownloadState {
    object Idle : UpdateDownloadState()
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : UpdateDownloadState()
    object Verifying : UpdateDownloadState()
    data class ReadyToInstall(val apkFile: File, val sha256Hex: String) : UpdateDownloadState()
    data class Error(val message: String) : UpdateDownloadState()
}

/**
 * Direct GitHub Releases updater with fail-closed SHA-256 verification before offering APK install.
 */
object GitHubApkUpdater {

    const val RELEASES_LATEST_API =
        "https://api.github.com/repos/cramqin/BLEBridge-Cuktech10-Ultra/releases/latest"
    const val RELEASES_PAGE_URL =
        "https://github.com/cramqin/BLEBridge-Cuktech10-Ultra/releases/latest"

    private val SHA256_REGEX = Regex("([0-9a-fA-F]{64})")
    private val SHA256_LABELED_REGEX = Regex("(?:sha-?256|checksum|digest)[^0-9a-fA-F]{0,32}([0-9a-fA-F]{64})", RegexOption.IGNORE_CASE)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun checkLatestRelease(currentVersion: String = BuildConfig.VERSION_NAME): Result<ReleaseUpdateInfo> {
        return runCatching {
            val req = Request.Builder()
                .url(RELEASES_LATEST_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "BLEBridge-Cuktech10-Ultra/$currentVersion")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("GitHub HTTP ${resp.code}")
                }
                val raw = resp.body?.string() ?: error("Empty response")
                val parsed = parseGitHubReleaseJson(raw, currentVersion)
                // If expectedSha256 wasn't inline in asset.digest or release notes, fetch sibling .sha256 asset if present
                if (parsed.expectedSha256.isNullOrBlank() && !parsed.checksumSourceUrl.isNullOrBlank()) {
                    val fetchedSha = fetchChecksumFromUrl(parsed.checksumSourceUrl, parsed.apkName)
                    if (!fetchedSha.isNullOrBlank()) {
                        return@use parsed.copy(expectedSha256 = fetchedSha)
                    }
                }
                parsed
            }
        }
    }

    fun parseGitHubReleaseJson(rawJson: String, currentVersion: String): ReleaseUpdateInfo {
        val json = JSONObject(rawJson)
        val isPrerelease = json.optBoolean("prerelease", false)
        val isDraft = json.optBoolean("draft", false)
        val rawTag = json.optString("tag_name", "").trim()
        val versionClean = rawTag.removePrefix("v").removePrefix("V").trim()
        val isPreTag = versionClean.contains("beta", ignoreCase = true) ||
            versionClean.contains("alpha", ignoreCase = true) ||
            versionClean.contains("rc", ignoreCase = true)

        val assets = json.optJSONArray("assets")
        var apkName: String? = null
        var apkUrl: String? = null
        var apkSize = 0L
        var sha256: String? = null
        var checksumUrl: String? = null

        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val dlUrl = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && apkUrl == null) {
                    apkName = name
                    apkUrl = dlUrl.takeIf { it.startsWith("https://", ignoreCase = true) }
                    apkSize = asset.optLong("size", 0L)
                    val digest = asset.optString("digest", "").removePrefix("sha256:").trim()
                    if (isValidSha256Hex(digest)) {
                        sha256 = digest.lowercase()
                    }
                } else if (
                    (name.endsWith(".sha256", ignoreCase = true) ||
                        name.contains("sha256", ignoreCase = true) ||
                        name.contains("checksum", ignoreCase = true)) &&
                    checksumUrl == null &&
                    dlUrl.startsWith("https://", ignoreCase = true)
                ) {
                    checksumUrl = dlUrl
                }
            }
        }

        val body = json.optString("body", "")
        if (sha256 == null) {
            sha256 = extractSha256FromText(body, apkName)
        }

        val available = !isPrerelease &&
            !isDraft &&
            !isPreTag &&
            versionClean.isNotEmpty() &&
            isNewerVersion(versionClean, currentVersion)

        return ReleaseUpdateInfo(
            currentVersion = currentVersion,
            latestVersion = versionClean.ifEmpty { currentVersion },
            tagName = rawTag,
            releaseName = json.optString("name", rawTag).ifBlank { rawTag },
            changelog = body,
            htmlUrl = json.optString("html_url", RELEASES_PAGE_URL).ifBlank { RELEASES_PAGE_URL },
            publishedAtIso = json.optString("published_at", ""),
            apkName = apkName,
            apkDownloadUrl = apkUrl,
            apkSizeBytes = apkSize,
            expectedSha256 = sha256,
            checksumSourceUrl = checksumUrl,
            isUpdateAvailable = available
        )
    }

    fun extractSha256FromText(text: String, preferredFileName: String? = null): String? {
        if (text.isBlank()) return null
        if (!preferredFileName.isNullOrBlank()) {
            val matchingLine = text.lines().firstOrNull { it.contains(preferredFileName, ignoreCase = true) }
            if (matchingLine != null) {
                SHA256_REGEX.find(matchingLine)?.groupValues?.getOrNull(1)?.let {
                    if (isValidSha256Hex(it)) return it.lowercase()
                }
            }
        }
        SHA256_LABELED_REGEX.find(text)?.groupValues?.getOrNull(1)?.let {
            if (isValidSha256Hex(it)) return it.lowercase()
        }
        return null
    }

    private fun fetchChecksumFromUrl(url: String, apkName: String?): String? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        return runCatching {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val content = resp.body?.string() ?: return null
                extractSha256FromText(content, apkName)
                    ?: SHA256_REGEX.find(content.trim())?.groupValues?.getOrNull(1)
                        ?.takeIf { isValidSha256Hex(it) }?.lowercase()
            }
        }.getOrNull()
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val rClean = remote.removePrefix("v").removePrefix("V").substringBefore("-").trim()
        val lClean = local.removePrefix("v").removePrefix("V").substringBefore("-").trim()
        val rParts = rClean.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = lClean.split(".").mapNotNull { it.toIntOrNull() }
        if (rParts.isEmpty() || lParts.isEmpty()) return false
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyFileSha256(file: File, expectedSha256: String): Boolean {
        if (!file.exists() || !isValidSha256Hex(expectedSha256)) return false
        val actual = computeSha256(file)
        return actual.equals(expectedSha256.trim(), ignoreCase = true)
    }

    fun downloadAndVerifyApk(
        context: Context,
        release: ReleaseUpdateInfo,
        onState: (UpdateDownloadState) -> Unit
    ) {
        val url = release.apkDownloadUrl
        val expectedSha = release.expectedSha256
        if (url.isNullOrBlank() || !url.startsWith("https://", ignoreCase = true)) {
            onState(UpdateDownloadState.Error("Invalid or missing HTTPS APK asset URL."))
            return
        }
        if (expectedSha.isNullOrBlank() || !isValidSha256Hex(expectedSha)) {
            onState(UpdateDownloadState.Error("Release does not publish a valid SHA-256 checksum. Verification blocked."))
            return
        }

        val updatesDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
        // Clean stale APKs before downloading
        updatesDir.listFiles()?.forEach { runCatching { it.delete() } }

        val safeName = (release.apkName ?: "cuktech10_update.apk")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destFile = File(updatesDir, safeName)

        try {
            onState(UpdateDownloadState.Downloading(0L, release.apkSizeBytes))
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "BLEBridge-Cuktech10-Ultra/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onState(UpdateDownloadState.Error("Download failed (HTTP ${resp.code})"))
                    return
                }
                val body = resp.body ?: run {
                    onState(UpdateDownloadState.Error("Empty APK download stream"))
                    return
                }
                val total = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                var downloaded = 0L
                val buf = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            onState(UpdateDownloadState.Downloading(downloaded, total))
                        }
                        output.flush()
                    }
                }
            }

            onState(UpdateDownloadState.Verifying)
            val actualSha = computeSha256(destFile)
            if (!actualSha.equals(expectedSha.trim(), ignoreCase = true)) {
                destFile.delete()
                onState(
                    UpdateDownloadState.Error(
                        "SHA-256 mismatch! File deleted.\nExpected: ${expectedSha.take(16)}…\nActual: ${actualSha.take(16)}…"
                    )
                )
                return
            }

            onState(UpdateDownloadState.ReadyToInstall(destFile, actualSha))
        } catch (e: Exception) {
            runCatching { destFile.delete() }
            onState(UpdateDownloadState.Error("Update failed: ${e.message ?: "unknown error"}"))
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun buildInstallSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildInstallApkIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun isValidSha256Hex(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
