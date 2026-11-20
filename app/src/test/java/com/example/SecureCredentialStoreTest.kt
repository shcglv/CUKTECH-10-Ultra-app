package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.cuktech.security.CuktechDeviceCredentials
import com.example.cuktech.security.KeystoreKeyProvider
import com.example.cuktech.security.SecureCredentialStore
import com.example.cuktech.security.XiaomiAccountSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecureCredentialStoreTest {

    private lateinit var context: Context

    private class FakeKeystoreKeyProvider(
        private val shouldFail: Boolean = false
    ) : KeystoreKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()

        override fun getOrCreateKey(alias: String): SecretKey {
            if (shouldFail) {
                throw SecurityException("Simulated Keystore unavailable")
            }
            return keys.getOrPut(alias) {
                val gen = KeyGenerator.getInstance("AES")
                gen.init(256)
                gen.generateKey()
            }
        }

        override fun deleteKey(alias: String) {
            keys.remove(alias)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("cuktech_secure_vault", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun saveAndReloadSessionDevicesAndArbitrarySecretsWithKeystoreProvider() {
        val provider = FakeKeystoreKeyProvider()
        val store = SecureCredentialStore(context, provider)

        assertTrue(store.keystoreHealthy.value)
        assertNull(store.keystoreError.value)

        val session = XiaomiAccountSession(
            userId = "123456",
            ssecurity = "ssec_abc",
            serviceToken = "svc_xyz",
            region = "cn",
            loginTimestamp = 1700000000000L
        )
        assertTrue(store.saveSession(session))

        val device = CuktechDeviceCredentials(
            did = "98765",
            name = "CUKTECH 10 Ultra",
            model = "njcuk.fitting.ad1204",
            mac = "AA:BB:CC:DD:EE:01",
            tokenHex = "00112233445566778899aabb",
            bleKeyHex = "00112233445566778899aabbccddeeff",
            region = "cn"
        )
        assertTrue(store.addOrUpdateDevice(device))
        assertTrue(store.putEncryptedSecret("ha_bearer_token", "ha_jwt_secret_value"))

        val reloaded = SecureCredentialStore(context, provider)
        assertEquals("123456", reloaded.session.value?.userId)
        assertEquals("ssec_abc", reloaded.session.value?.ssecurity)
        assertEquals("AA:BB:CC:DD:EE:01", reloaded.activeDevice.value?.mac)
        assertEquals("ha_jwt_secret_value", reloaded.getEncryptedSecret("ha_bearer_token"))

        reloaded.removeEncryptedSecret("ha_bearer_token")
        assertNull(reloaded.getEncryptedSecret("ha_bearer_token"))
    }

    @Test
    fun failClosedWhenKeystoreUnavailableWithoutWritingPlaintextFallback() {
        val brokenProvider = FakeKeystoreKeyProvider(shouldFail = true)
        val store = SecureCredentialStore(context, brokenProvider)

        val savedSession = store.saveSession(XiaomiAccountSession("u1", "s1", "t1"))
        val savedSecret = store.putEncryptedSecret("api_key", "secret123")
        val savedDevices = store.saveDevices(
            listOf(
                CuktechDeviceCredentials(
                    did = "1",
                    name = "CUKTECH 10 Ultra",
                    model = "njcuk.fitting.ad1204",
                    mac = "AA:BB:CC:DD:EE:02",
                    tokenHex = "00112233445566778899aabb",
                    bleKeyHex = ""
                )
            )
        )

        assertFalse(savedSession)
        assertFalse(savedSecret)
        assertFalse(savedDevices)
        assertFalse(store.keystoreHealthy.value)
        assertNotNull(store.keystoreError.value)

        val prefs = context.getSharedPreferences("cuktech_secure_vault", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("test_fallback_k"))
    }

    @Test
    fun migratesLegacyPlaintextFallbackKeyAndDeletesIt() {
        val prefs = context.getSharedPreferences("cuktech_secure_vault", Context.MODE_PRIVATE)
        val legacyRawKey = ByteArray(32) { (it + 1).toByte() }
        val legacyKey = SecretKeySpec(legacyRawKey, "AES")

        val sessionJson = """{"userId":"legacyUser","ssecurity":"legacySsec","serviceToken":"legacySvc","region":"de","loginTimestamp":42}"""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, legacyKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(sessionJson.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)

        prefs.edit()
            .putString("test_fallback_k", Base64.encodeToString(legacyRawKey, Base64.NO_WRAP))
            .putString("xiaomi_session_enc", Base64.encodeToString(combined, Base64.NO_WRAP))
            .commit()

        val provider = FakeKeystoreKeyProvider()
        val store = SecureCredentialStore(context, provider)

        assertFalse(prefs.contains("test_fallback_k"))
        assertEquals("legacyUser", store.session.value?.userId)
        assertEquals("de", store.session.value?.region)
    }

    @Test
    fun importFromJsonStringValidatesFields() {
        val provider = FakeKeystoreKeyProvider()
        val store = SecureCredentialStore(context, provider)

        val validJson = """{
            "mac":"aa:bb:cc:dd:ee:99",
            "token":"00112233445566778899aabb",
            "ble_key":"ffeeddccbbaa99887766554433221100",
            "name":"Studio Charger"
        }"""
        val imported = store.importFromJsonString(validJson)
        assertNotNull(imported)
        assertEquals("AA:BB:CC:DD:EE:99", imported?.mac)
        assertEquals("Studio Charger", imported?.name)

        // Invalid short token must fail
        val invalidJson = """{"mac":"aa:bb:cc:dd:ee:99","token":"short"}"""
        assertNull(store.importFromJsonString(invalidJson))
        assertNull(store.importFromJsonString("not-json"))
    }
}
