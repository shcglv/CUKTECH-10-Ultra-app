package com.example.cuktech.security

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class BackupPayload(
    val createdAtMs: Long,
    val includesSecrets: Boolean,
    val preferencesJson: JSONObject,
    val sessionsArray: JSONArray,
    val dailyAggregatesArray: JSONArray,
    val chargeLimitsArray: JSONArray,
    val aliasesArray: JSONArray,
    val profilesArray: JSONArray,
    val devicesSecretsArray: JSONArray? = null
)

/**
 * Password-protected AES-256-GCM + PBKDF2WithHmacSHA256 encrypted backup serializer/deserializer
 * for Storage Access Framework (SAF) `.cukbackup` files.
 */
object SafEncryptedBackupManager {

    private const val FORMAT_ID = "cuktech10_backup"
    private const val FORMAT_VERSION = 1
    private const val KDF_ALGO = "PBKDF2WithHmacSHA256"
    private const val DEFAULT_KDF_ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

    fun encryptToStream(
        payload: BackupPayload,
        password: CharArray,
        outputStream: OutputStream,
        kdfIterations: Int = DEFAULT_KDF_ITERATIONS
    ) {
        require(password.isNotEmpty()) { "Backup passphrase cannot be empty" }
        val rng = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { rng.nextBytes(it) }

        val innerJson = JSONObject().apply {
            put("created_at_ms", payload.createdAtMs)
            put("includes_secrets", payload.includesSecrets)
            put("preferences", payload.preferencesJson)
            put("sessions", payload.sessionsArray)
            put("daily_aggregates", payload.dailyAggregatesArray)
            put("charge_limits", payload.chargeLimitsArray)
            put("aliases", payload.aliasesArray)
            put("profiles", payload.profilesArray)
            if (payload.includesSecrets && payload.devicesSecretsArray != null) {
                put("devices_secrets", payload.devicesSecretsArray)
            }
        }.toString()

        val keyBytes = deriveKeyBytes(password, salt, kdfIterations)
        try {
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val aad = "$FORMAT_ID:v$FORMAT_VERSION".toByteArray(Charsets.UTF_8)
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(innerJson.toByteArray(Charsets.UTF_8))

            val envelope = JSONObject().apply {
                put("format", FORMAT_ID)
                put("version", FORMAT_VERSION)
                put("created_at_ms", payload.createdAtMs)
                put("includes_secrets", payload.includesSecrets)
                put("kdf", KDF_ALGO)
                put("kdf_iterations", kdfIterations)
                put("salt_b64", Base64.encodeToString(salt, Base64.NO_WRAP))
                put("iv_b64", Base64.encodeToString(iv, Base64.NO_WRAP))
                put("cipher", "AES/GCM/NoPadding")
                put("payload_b64", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            }

            outputStream.write(envelope.toString(2).toByteArray(Charsets.UTF_8))
            outputStream.flush()
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    @Throws(InvalidBackupException::class)
    fun decryptFromStream(
        inputStream: InputStream,
        password: CharArray
    ): BackupPayload {
        if (password.isEmpty()) {
            throw InvalidBackupException("Backup passphrase cannot be empty")
        }
        val rawBytes = inputStream.readBytes()
        if (rawBytes.isEmpty() || rawBytes.size > MAX_BACKUP_BYTES) {
            throw InvalidBackupException("Backup file is empty or exceeds maximum supported size")
        }
        val envelope = try {
            JSONObject(String(rawBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw InvalidBackupException("Invalid backup file JSON structure", e)
        }

        if (envelope.optString("format") != FORMAT_ID) {
            throw InvalidBackupException("Unrecognized backup file format")
        }
        val version = envelope.optInt("version", -1)
        if (version != FORMAT_VERSION) {
            throw InvalidBackupException("Unsupported backup version: $version")
        }
        val kdf = envelope.optString("kdf", KDF_ALGO)
        if (kdf != KDF_ALGO) {
            throw InvalidBackupException("Unsupported KDF algorithm: $kdf")
        }
        val iterations = envelope.optInt("kdf_iterations", DEFAULT_KDF_ITERATIONS)
        if (iterations !in 10_000..2_000_000) {
            throw InvalidBackupException("Invalid KDF iteration count")
        }

        val salt = runCatching { Base64.decode(envelope.getString("salt_b64"), Base64.DEFAULT) }
            .getOrElse { throw InvalidBackupException("Corrupt backup salt", it) }
        val iv = runCatching { Base64.decode(envelope.getString("iv_b64"), Base64.DEFAULT) }
            .getOrElse { throw InvalidBackupException("Corrupt backup IV", it) }
        val ciphertext = runCatching { Base64.decode(envelope.getString("payload_b64"), Base64.DEFAULT) }
            .getOrElse { throw InvalidBackupException("Corrupt backup payload", it) }

        val keyBytes = deriveKeyBytes(password, salt, iterations)
        val plainStr = try {
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val aad = "$FORMAT_ID:v$FORMAT_VERSION".toByteArray(Charsets.UTF_8)
            cipher.updateAAD(aad)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw InvalidBackupException("Wrong passphrase or tampered backup file", e)
        } catch (e: Exception) {
            throw InvalidBackupException("Failed to decrypt backup: ${e.message}", e)
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }

        val inner = try {
            JSONObject(plainStr)
        } catch (e: Exception) {
            throw InvalidBackupException("Corrupt decrypted backup payload", e)
        }

        val includesSecrets = inner.optBoolean("includes_secrets", false)
        return BackupPayload(
            createdAtMs = inner.optLong("created_at_ms", 0L),
            includesSecrets = includesSecrets,
            preferencesJson = inner.optJSONObject("preferences") ?: JSONObject(),
            sessionsArray = inner.optJSONArray("sessions") ?: JSONArray(),
            dailyAggregatesArray = inner.optJSONArray("daily_aggregates") ?: JSONArray(),
            chargeLimitsArray = inner.optJSONArray("charge_limits") ?: JSONArray(),
            aliasesArray = inner.optJSONArray("aliases") ?: JSONArray(),
            profilesArray = inner.optJSONArray("profiles") ?: JSONArray(),
            devicesSecretsArray = if (includesSecrets) inner.optJSONArray("devices_secrets") else null
        )
    }

    private fun deriveKeyBytes(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance(KDF_ALGO)
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
