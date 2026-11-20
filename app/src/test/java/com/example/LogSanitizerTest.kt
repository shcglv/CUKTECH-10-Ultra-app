package com.example

import com.example.cuktech.security.LogSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun sanitizeRedactsTokensHeadersAndWebhookSecrets() {
        val raw = "GET https://ha.example.com/api/webhook/super_secret_hook_123 " +
            "Authorization: Bearer abcDEF.123_~+/= " +
            "ssecurity=mySsecValue serviceToken: \"svcTok999\" auth_token='tok_xyz' " +
            "Cookie: session=12345; user=bob"

        val clean = LogSanitizer.sanitize(raw)

        assertFalse(clean.contains("super_secret_hook_123"))
        assertFalse(clean.contains("abcDEF.123_~+/="))
        assertFalse(clean.contains("mySsecValue"))
        assertFalse(clean.contains("svcTok999"))
        assertFalse(clean.contains("tok_xyz"))
        assertFalse(clean.contains("session=12345"))
        assertTrue(clean.contains("/api/webhook/[REDACTED]"))
        assertTrue(clean.contains("Bearer [REDACTED]"))
    }

    @Test
    fun sanitizePreservesLoopbackWhileMaskingExternalIpAndMacWhenEnabled() {
        val raw = "Connected device AA:BB:CC:DD:EE:FF gateway=127.0.0.1 lan=192.168.1.42"
        val unmaskedNet = LogSanitizer.sanitize(raw, maskNetworkIdentifiers = false)
        assertTrue(unmaskedNet.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(unmaskedNet.contains("127.0.0.1"))
        assertTrue(unmaskedNet.contains("192.168.1.42"))

        val maskedNet = LogSanitizer.sanitize(raw, maskNetworkIdentifiers = true)
        assertFalse(maskedNet.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(maskedNet.contains("AA:BB:XX:XX:XX:FF"))
        assertTrue(maskedNet.contains("127.0.0.1"))
        assertFalse(maskedNet.contains("192.168.1.42"))
        assertTrue(maskedNet.contains("192.168.x.x"))
    }

    @Test
    fun sanitizeUrlForDisplayStripsWebhookAndQuerySecrets() {
        val url = "https://my.ha.local:8123/api/webhook/secret_id_99?token=abc123&mode=1"
        val display = LogSanitizer.sanitizeUrlForDisplay(url)
        assertEquals("https://my.ha.local:8123/api/webhook/[REDACTED]?token=[REDACTED]&mode=1", display)
    }
}
