package com.example.cuktech.security

/**
 * Redacts sensitive tokens, credentials, webhook URLs, cookies, and optional PII
 * before emitting logs to console UI, logcat, or diagnostic exports.
 */
object LogSanitizer {

    private val BEARER_REGEX = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._\\-~+/=]+")
    private val KV_SECRET_REGEX = Regex(
        "(?i)(ssecurity|serviceToken|passToken|auth_token|token|ble_key|beaconkey|password|passwd|ticket|hash|ick|secret|api_key|webhook_secret)([\"']?\\s*[:=]\\s*[\"']?)([^\\s,;\"'&}\\]]+)"
    )
    private val COOKIE_HEADER_REGEX = Regex("(?i)(Cookie|Set-Cookie)\\s*[:=]\\s*[^\\r\\n]+")
    private val WEBHOOK_PATH_REGEX = Regex("(?i)(https?://[^/\\s]+/api/webhook/)([^\\s/?#]+)")
    private val MAC_REGEX = Regex("\\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b")
    private val IPV4_NON_LOCAL_REGEX = Regex("\\b(?!127\\.0\\.0\\.1\\b)(\\d{1,3}\\.\\d{1,3})\\.(\\d{1,3}\\.\\d{1,3})\\b")

    fun sanitize(message: String, maskNetworkIdentifiers: Boolean = false): String {
        if (message.isBlank()) return message
        var out = message
        out = BEARER_REGEX.replace(out) { "${it.groupValues[1]}[REDACTED]" }
        out = COOKIE_HEADER_REGEX.replace(out) { "${it.groupValues[1]}: [REDACTED]" }
        out = KV_SECRET_REGEX.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]" }
        out = WEBHOOK_PATH_REGEX.replace(out) { "${it.groupValues[1]}[REDACTED]" }
        if (maskNetworkIdentifiers) {
            out = MAC_REGEX.replace(out) { match -> maskMac(match.value) }
            out = IPV4_NON_LOCAL_REGEX.replace(out) { match -> "${match.groupValues[1]}.x.x" }
        }
        return out
    }

    fun maskMac(mac: String): String {
        val clean = mac.trim().uppercase()
        val parts = clean.split(":")
        return if (parts.size == 6) {
            "${parts[0]}:${parts[1]}:XX:XX:XX:${parts[5]}"
        } else if (clean.length >= 4) {
            "XX:${clean.takeLast(4)}"
        } else {
            "[MAC]"
        }
    }

    fun sanitizeUrlForDisplay(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        return WEBHOOK_PATH_REGEX.replace(trimmed) { "${it.groupValues[1]}[REDACTED]" }
            .replace(Regex("([?&](?:token|secret|key|auth)=)([^&#]+)", RegexOption.IGNORE_CASE)) {
                "${it.groupValues[1]}[REDACTED]"
            }
    }
}
