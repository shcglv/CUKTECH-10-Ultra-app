package com.example

import com.example.cuktech.security.BackupPayload
import com.example.cuktech.security.InvalidBackupException
import com.example.cuktech.security.SafEncryptedBackupManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafEncryptedBackupTest {

    private fun samplePayload(includeSecrets: Boolean): BackupPayload {
        val prefs = JSONObject().apply {
            put("retentionDays", 30)
            put("appLanguage", "ru")
        }
        val sessions = JSONArray().apply {
            put(JSONObject().apply {
                put("portId", 1)
                put("portLabel", "USB-C1")
                put("whAccumulated", 42.5)
            })
        }
        val secrets = if (includeSecrets) {
            JSONArray().apply {
                put(JSONObject().apply {
                    put("mac", "AA:BB:CC:DD:EE:01")
                    put("tokenHex", "00112233445566778899aabb")
                })
            }
        } else {
            JSONArray().apply { put(JSONObject().put("mac", "SHOULD_BE_STRIPPED")) }
        }
        return BackupPayload(
            createdAtMs = 1710000000000L,
            includesSecrets = includeSecrets,
            preferencesJson = prefs,
            sessionsArray = sessions,
            dailyAggregatesArray = JSONArray(),
            chargeLimitsArray = JSONArray(),
            aliasesArray = JSONArray(),
            profilesArray = JSONArray(),
            devicesSecretsArray = secrets
        )
    }

    @Test
    fun encryptAndDecryptRoundtripPreservesDataAndRespectsIncludeSecretsFlag() {
        val pass = "CorrectHorseBatteryStaple!".toCharArray()
        val baos = ByteArrayOutputStream()
        SafEncryptedBackupManager.encryptToStream(
            payload = samplePayload(includeSecrets = true),
            password = pass,
            outputStream = baos,
            kdfIterations = 10_000
        )
        val rawEnvelope = JSONObject(String(baos.toByteArray(), Charsets.UTF_8))
        assertEquals("cuktech10_backup", rawEnvelope.getString("format"))
        assertEquals(1, rawEnvelope.getInt("version"))
        assertEquals("PBKDF2WithHmacSHA256", rawEnvelope.getString("kdf"))

        val decrypted = SafEncryptedBackupManager.decryptFromStream(
            ByteArrayInputStream(baos.toByteArray()),
            "CorrectHorseBatteryStaple!".toCharArray()
        )
        assertTrue(decrypted.includesSecrets)
        assertEquals(30, decrypted.preferencesJson.getInt("retentionDays"))
        assertEquals(42.5, decrypted.sessionsArray.getJSONObject(0).getDouble("whAccumulated"), 0.001)
        assertEquals("00112233445566778899aabb", decrypted.devicesSecretsArray?.getJSONObject(0)?.getString("tokenHex"))

        // Without secrets
        val noSecretOut = ByteArrayOutputStream()
        SafEncryptedBackupManager.encryptToStream(
            payload = samplePayload(includeSecrets = false),
            password = "pass123".toCharArray(),
            outputStream = noSecretOut,
            kdfIterations = 10_000
        )
        val noSecretDecrypted = SafEncryptedBackupManager.decryptFromStream(
            ByteArrayInputStream(noSecretOut.toByteArray()),
            "pass123".toCharArray()
        )
        assertFalse(noSecretDecrypted.includesSecrets)
        assertNull(noSecretDecrypted.devicesSecretsArray)
    }

    @Test
    fun decryptRejectsWrongPassphraseTamperedPayloadAndUnknownFormat() {
        val baos = ByteArrayOutputStream()
        SafEncryptedBackupManager.encryptToStream(
            payload = samplePayload(includeSecrets = false),
            password = "mySecretPassword".toCharArray(),
            outputStream = baos,
            kdfIterations = 10_000
        )

        assertThrows(InvalidBackupException::class.java) {
            SafEncryptedBackupManager.decryptFromStream(
                ByteArrayInputStream(baos.toByteArray()),
                "wrongPassword".toCharArray()
            )
        }

        val tampered = JSONObject(String(baos.toByteArray(), Charsets.UTF_8)).apply {
            val orig = getString("payload_b64")
            val flipped = (if (orig.first() == 'A') 'B' else 'A') + orig.drop(1)
            put("payload_b64", flipped)
        }.toString().toByteArray(Charsets.UTF_8)

        assertThrows(InvalidBackupException::class.java) {
            SafEncryptedBackupManager.decryptFromStream(
                ByteArrayInputStream(tampered),
                "mySecretPassword".toCharArray()
            )
        }

        val badFormat = """{"format":"other_app","version":1}""".toByteArray(Charsets.UTF_8)
        assertThrows(InvalidBackupException::class.java) {
            SafEncryptedBackupManager.decryptFromStream(
                ByteArrayInputStream(badFormat),
                "mySecretPassword".toCharArray()
            )
        }
    }
}
