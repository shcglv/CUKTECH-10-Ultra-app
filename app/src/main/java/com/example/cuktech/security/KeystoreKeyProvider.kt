package com.example.cuktech.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Abstraction for AES-256 master key lookup/creation.
 * Production uses AndroidKeyStore exclusively (fail-closed, zero plaintext key fallback).
 * Unit/Robolectric tests can inject an isolated in-memory provider when running outside Android Keystore hardware.
 */
interface KeystoreKeyProvider {
    @Throws(SecurityException::class)
    fun getOrCreateKey(alias: String): SecretKey

    fun deleteKey(alias: String)
}

class AndroidKeyStoreKeyProvider : KeystoreKeyProvider {
    override fun getOrCreateKey(alias: String): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                existing.secretKey
            } else {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
        } catch (e: Exception) {
            throw SecurityException("AndroidKeyStore unavailable for alias '$alias': ${e.message}", e)
        }
    }

    override fun deleteKey(alias: String) {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        }
    }
}
