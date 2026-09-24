package com.ultrabytecoder.coinsafebox.security

import com.ultrabytecoder.coinsafebox.data.SettingsStore
import com.ultrabytecoder.coinsafebox.domain.repository.PinConfig
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * In-memory [SettingsStore] fake so the tests never touch the real on-disk
 * store. The previous version constructed the platform [SettingsStorage] and
 * backed up/restored the developer's production envelope, which (a) held the
 * wrapped DEK in a JVM String for the test duration and (b) risked destroying
 * the real wallet envelope if a test failed mid-flight.
 */
private class InMemorySettingsStorage : SettingsStore {
    private val map = HashMap<String, String>()
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getString(key: String): String? = map[key]
    override fun remove(key: String) { map.remove(key) }
}

class KeyManagerTest {

    private lateinit var storage: SettingsStore

    @BeforeTest
    fun setUp() {
        storage = InMemorySettingsStorage()
        // HardwareKeyStore is a process-wide singleton; on the JVM target its
        // in-memory fallback key would otherwise persist across tests and pollute
        // a later test that assumes a fresh key. Reset it for isolation.
        HardwareKeyStore.deleteKey()
    }

    @Test
    fun testNewSetupRecordsKdfParameters() {
        val keyManager = KeyManager(storage)
        val pin = "123456".toCharArray()
        try {
            keyManager.generateAndWrapDek(pin, SecurityMethod.PIN)
        } finally {
            pin.wipe()
        }
        val envelopeJson = storage.getString("dek_envelope")
        assertNotNull(envelopeJson)
        // Version 2 envelope records the KDF algorithm and its parameters.
        assertTrue(envelopeJson.contains("\"version\":2"), "envelope must be version 2: $envelopeJson")
        assertTrue(
            envelopeJson.contains("\"kdfAlgorithm\":\"ARGON2ID\"") ||
                envelopeJson.contains("\"kdfAlgorithm\":\"PBKDF2-SHA256\""),
            "envelope must record the KDF algorithm: $envelopeJson"
        )
        assertTrue(
            envelopeJson.contains("\"iterations\":${PinConfig.KDF_TIME_COST_PIN}"),
            "envelope must record the KDF time cost: $envelopeJson"
        )
    }

    @Test
    fun testUnwrapWithCorrectPinSucceeds() {
        val keyManager = KeyManager(storage)
        val pin = "123456".toCharArray()
        val dek = try {
            keyManager.generateAndWrapDek(pin, SecurityMethod.PIN)
        } finally {
            pin.wipe()
        }
        try {
            val unwrapped = keyManager.unwrapDekWithPin("123456".toCharArray(), SecurityMethod.PIN)
            assertNotNull(unwrapped)
            assertTrue(unwrapped.contentEquals(dek))
        } finally {
            dek.wipe()
        }
    }

    @Test
    fun testUnwrapWithWrongPinFails() {
        val keyManager = KeyManager(storage)
        val pin = "123456".toCharArray()
        try {
            keyManager.generateAndWrapDek(pin, SecurityMethod.PIN)
        } finally {
            pin.wipe()
        }
        val unwrapped = keyManager.unwrapDekWithPin("654321".toCharArray(), SecurityMethod.PIN)
        assertEquals(null, unwrapped)
    }
}
