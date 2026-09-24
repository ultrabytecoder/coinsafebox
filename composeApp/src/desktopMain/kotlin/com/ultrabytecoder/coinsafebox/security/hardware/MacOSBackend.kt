package com.ultrabytecoder.coinsafebox.security.hardware

import com.ultrabytecoder.coinsafebox.security.AesGcm
import com.ultrabytecoder.coinsafebox.security.AesGcmAuthenticationException
import com.ultrabytecoder.coinsafebox.security.HardwareKeyInvalidatedException
import com.ultrabytecoder.coinsafebox.security.hardware.jna.Cf
import com.ultrabytecoder.coinsafebox.security.hardware.jna.CFDataRef
import com.ultrabytecoder.coinsafebox.security.hardware.jna.CFDictionaryRef
import com.ultrabytecoder.coinsafebox.security.hardware.jna.CFStringRef
import com.ultrabytecoder.coinsafebox.security.hardware.jna.CoreFoundation
import com.ultrabytecoder.coinsafebox.security.hardware.jna.SecKeyRef
import com.ultrabytecoder.coinsafebox.security.hardware.jna.SecurityFramework
import com.ultrabytecoder.coinsafebox.security.wipe
import org.kotlincrypto.random.CryptoRand
import java.util.logging.Level
import java.util.logging.Logger

/**
 * macOS device key.
 *
 * Primary: Secure Enclave ECC P-256 key (`kSecAttrTokenIDSecureEnclave`),
 * non-exportable, no user-presence requirement — the same key attributes and
 * ECIES-AES-GCM (X9.63, cofactor, variable IV) algorithm as the iOS
 * implementation ([com.ultrabytecoder.coinsafebox.security.HardwareKeyStore] in
 * iosMain), invoked here through JNA against Security.framework.
 *
 * Fallback: Keychain Generic Password item holding a random 32-byte AES-256
 * key, protected by `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` (no
 * biometric prompt). Used when the Secure Enclave is unavailable (Intel Macs
 * without T2, sandboxed builds without the `com.apple.developer.secure-enclave`
 * entitlement). The AES-GCM wrap itself is done in the JVM via [AesGcm].
 *
 * The Keychain AES key is cached in the JVM heap for the process lifetime —
 * the same residency model as [FileBackend] (documented in desktop-security.md).
 */
class MacOSBackend : HardwareKeyBackend {

    override val id: String = "macos"

    private companion object {
        const val SE_KEY_LABEL = "coinsafebox.hw"
        const val KEYCHAIN_SERVICE = "com.ultrabytecoder.coinsafebox"
        const val KEYCHAIN_ACCOUNT = "device_key"
        const val KEYCHAIN_KEY_SIZE = 32
    }

    private val sec = SecurityFramework.INSTANCE

    private val log = Logger.getLogger("coinsafebox.macos")

    @Volatile
    private var keychainKey: ByteArray? = null

    private val lock = Any()

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        // SE path: find the existing key, create it on first use. Creation
        // fails (null) only when the Secure Enclave is unavailable (Intel Mac
        // without T2, sandbox without entitlement) -> Keychain fallback.
        val seKey = findSeKey() ?: createSeKey()
        if (seKey != null) {
            return try {
                seEncrypt(seKey, plaintext, aad)
            } finally {
                Cf.release(seKey)
            }
        }
        val aesKey = keychainKeyOrCreate()
        return AesGcm.encrypt(aesKey, plaintext, aad)
    }

    override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
        // Decrypt must never create a key: a missing key means the ciphertext
        // is undecryptable -> HardwareKeyInvalidatedException (PIN re-setup).
        val seKey = findSeKey()
        if (seKey != null) {
            return try {
                seDecrypt(seKey, encrypted, aad)
            } finally {
                Cf.release(seKey)
            }
        }
        val aesKey = keychainKeyOrThrow()
        return try {
            AesGcm.decrypt(aesKey, encrypted, aad)
        } catch (e: AesGcmAuthenticationException) {
            throw e
        } catch (e: Exception) {
            throw HardwareKeyInvalidatedException("Device key backend 'macos' failed: ${e.message}")
        }
    }

    override fun deleteKey() {
        synchronized(lock) {
            keychainKey?.wipe()
            keychainKey = null
        }
        deleteSeKey()
        deleteKeychainItem()
    }

    override fun purgeCache() {
        synchronized(lock) {
            keychainKey?.wipe()
            keychainKey = null
        }
    }

    // --- Secure Enclave path -------------------------------------------------

    private fun findSeKey(): SecKeyRef? {
        val query = Cf.dictMutable()
        try {
            Cf.dictAdd(query, SecurityFramework.K_SEC_CLASS, SecurityFramework.K_SEC_CLASS_KEY)
            Cf.dictAdd(query, SecurityFramework.K_SEC_ATTR_TOKEN_ID, SecurityFramework.K_SEC_ATTR_TOKEN_ID_SECURE_ENCLAVE)
            val label = Cf.cfString(SE_KEY_LABEL)
            Cf.dictAdd(query, SecurityFramework.K_SEC_ATTR_LABEL, label)
            Cf.release(label)
            val returnRef = Cf.cfBoolean(true)
            Cf.dictAdd(query, SecurityFramework.K_SEC_RETURN_REF, returnRef)
            Cf.release(returnRef)
            Cf.dictAdd(query, SecurityFramework.K_SEC_MATCH_LIMIT, SecurityFramework.K_SEC_MATCH_LIMIT_ONE)
            val result = Cf.outPtr()
            val status = sec.SecItemCopyMatching(query, result)
            return if (status == SecurityFramework.ERR_SEC_SUCCESS) Cf.readOutRef(result) else null
        } finally {
            Cf.release(query)
        }
    }

    private fun createSeKey(): SecKeyRef? {
        val accessControl = sec.SecAccessControlCreateWithFlags(
            CoreFoundation.kCFAllocatorDefault,
            SecurityFramework.K_SEC_ATTR_ACCESSIBLE_WHEN_UNLOCKED_THIS_DEVICE_ONLY,
            0, // no user-presence flag: no biometric prompt
            null
        ) ?: return null
        val attrs = Cf.dictMutable()
        try {
            Cf.dictAdd(attrs, SecurityFramework.K_SEC_ATTR_TOKEN_ID, SecurityFramework.K_SEC_ATTR_TOKEN_ID_SECURE_ENCLAVE)
            Cf.dictAdd(attrs, SecurityFramework.K_SEC_ATTR_KEY_TYPE, SecurityFramework.K_SEC_ATTR_KEY_TYPE_EC_SEC_PRIME_RANDOM)
            val keySize = Cf.cfNumberSInt32(256)
            Cf.dictAdd(attrs, SecurityFramework.K_SEC_ATTR_KEY_SIZE_IN_BITS, keySize)
            Cf.release(keySize)
            Cf.dictAdd(attrs, SecurityFramework.K_SEC_ATTR_ACCESS_CONTROL, accessControl)
            val label = Cf.cfString(SE_KEY_LABEL)
            Cf.dictAdd(attrs, SecurityFramework.K_SEC_ATTR_LABEL, label)
            Cf.release(label)
            val error = Cf.outPtr()
            val key = sec.SecKeyCreateRandomKey(attrs, error)
            if (key == null) {
                // SE unavailable (Intel Mac without T2, sandbox without the
                // com.apple.developer.secure-enclave entitlement) -> fallback.
                val code = Cf.cfErrorCode(error)
                log.log(Level.INFO, "Secure Enclave key creation failed (CFError $code), using Keychain fallback")
            }
            return key
        } finally {
            Cf.release(attrs)
            Cf.release(accessControl)
        }
    }

    private fun seEncrypt(key: SecKeyRef, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val publicKey = sec.SecKeyCopyPublicKey(key)
            ?: throw IllegalStateException("Failed to export Secure Enclave public key")
        try {
            val bound = if (aad.isNotEmpty()) {
                ByteArray(4 + aad.size + plaintext.size).also { out ->
                    out[0] = (aad.size shr 24).toByte()
                    out[1] = (aad.size shr 16).toByte()
                    out[2] = (aad.size shr 8).toByte()
                    out[3] = aad.size.toByte()
                    aad.copyInto(out, 4)
                    plaintext.copyInto(out, 4 + aad.size)
                }
            } else plaintext
            val data = Cf.cfData(bound)
            try {
                val error = Cf.outPtr()
                val encrypted = sec.SecKeyCreateEncryptedData(
                    publicKey,
                    SecurityFramework.K_SEC_KEY_ALGORITHM_ECIES_ENCRYPTION_COFACTOR_VARIABLE_IV_X963_SHA256_AESGCM,
                    data,
                    error
                ) ?: throw IllegalStateException("Secure Enclave encryption failed (CFError ${Cf.cfErrorCode(error)})")
                try {
                    return Cf.dataToBytes(encrypted)
                } finally {
                    Cf.release(encrypted)
                }
            } finally {
                Cf.release(data)
                if (aad.isNotEmpty()) bound.wipe()
            }
        } finally {
            Cf.release(publicKey)
        }
    }

    private fun seDecrypt(key: SecKeyRef, encrypted: ByteArray, aad: ByteArray): ByteArray {
        val data = Cf.cfData(encrypted)
        try {
            val error = Cf.outPtr()
            val decrypted = sec.SecKeyCreateDecryptedData(
                key,
                SecurityFramework.K_SEC_KEY_ALGORITHM_ECIES_ENCRYPTION_COFACTOR_VARIABLE_IV_X963_SHA256_AESGCM,
                data,
                error
            ) ?: throw AesGcmAuthenticationException("Secure Enclave decryption failed (CFError ${Cf.cfErrorCode(error)})")
            return try {
                val plaintext = Cf.dataToBytes(decrypted)
                if (aad.isNotEmpty()) {
                    try {
                        require(plaintext.size >= 4) { "Decrypted data too short for AAD" }
                        val aadLen = ((plaintext[0].toInt() and 0xFF) shl 24) or
                            ((plaintext[1].toInt() and 0xFF) shl 16) or
                            ((plaintext[2].toInt() and 0xFF) shl 8) or
                            (plaintext[3].toInt() and 0xFF)
                        require(plaintext.size >= 4 + aadLen) { "Truncated AAD" }
                        val storedAad = plaintext.copyOfRange(4, 4 + aadLen)
                        if (!storedAad.contentEquals(aad)) {
                            throw AesGcmAuthenticationException("Hardware AAD mismatch")
                        }
                        plaintext.copyOfRange(4 + aadLen, plaintext.size)
                    } finally {
                        plaintext.fill(0)
                    }
                } else {
                    plaintext
                }
            } finally {
                Cf.release(decrypted)
            }
        } finally {
            Cf.release(data)
        }
    }

    private fun deleteSeKey() {
        val query = Cf.dictMutable()
        try {
            Cf.dictAdd(query, SecurityFramework.K_SEC_CLASS, SecurityFramework.K_SEC_CLASS_KEY)
            Cf.dictAdd(query, SecurityFramework.K_SEC_ATTR_TOKEN_ID, SecurityFramework.K_SEC_ATTR_TOKEN_ID_SECURE_ENCLAVE)
            val label = Cf.cfString(SE_KEY_LABEL)
            Cf.dictAdd(query, SecurityFramework.K_SEC_ATTR_LABEL, label)
            Cf.release(label)
            sec.SecItemDelete(query)
        } finally {
            Cf.release(query)
        }
    }

    // --- Keychain Generic Password fallback ----------------------------------

    /** Loads the cached/stored Keychain key, creating it on first use (encrypt path). */
    private fun keychainKeyOrCreate(): ByteArray = synchronized(lock) {
        keychainKey?.let { return it }
        val key = keychainLookup() ?: createKeychainKey()
        keychainKey = key
        key
    }

    /** Loads the cached/stored Keychain key; throws when missing (decrypt path). */
    private fun keychainKeyOrThrow(): ByteArray = synchronized(lock) {
        keychainKey?.let { return it }
        val stored = keychainLookup()
            ?: throw HardwareKeyInvalidatedException("Device hardware key missing")
        keychainKey = stored
        stored
    }

    private fun keychainLookup(): ByteArray? {
        val query = Cf.dictMutable()
        try {
            Cf.dictAdd(query, SecurityFramework.K_SEC_CLASS, SecurityFramework.K_SEC_CLASS_GENERIC_PASSWORD)
            val service = Cf.cfString(KEYCHAIN_SERVICE)
            Cf.dictAdd(query, SecurityFramework.K_SEC_ATTR_SERVICE, service)
            Cf.release(service)
            val account = Cf.cfString(KEYCHAIN_ACCOUNT)
            Cf.dictAdd(query, SecurityFramework.K_SEC_ATTR_ACCOUNT, account)
            Cf.release(account)
            val returnData = Cf.cfBoolean(true)
            Cf.dictAdd(query, SecurityFramework.K_SEC_RETURN_DATA, returnData)
            Cf.release(returnData)
            Cf.dictAdd(query, SecurityFramework.K_SEC_MATCH_LIMIT, SecurityFramework.K_SEC_MATCH_LIMIT_ONE)
            val result = Cf.outPtr()
            val status = sec.SecItemCopyMatching(query, result)
            if (status != SecurityFramework.ERR_SEC_SUCCESS) return null
            val dataRef = Cf.readOutRef(result) ?: return null
            return try {
                val bytes = Cf.dataToBytes(dataRef)
                if (bytes.size != KEYCHAIN_KEY_SIZE) null else bytes
            } finally {
                Cf.release(dataRef)
            }
        } finally {
            Cf.release(query)
        }
    }

    private fun createKeychainKey(): ByteArray {
        val key = CryptoRand.Default.nextBytes(ByteArray(KEYCHAIN_KEY_SIZE))
        try {
            val query = Cf.dictMutable()
            try {
                Cf.dictAdd(query, SecurityFramework.K_SEC_CLASS, SecurityFramework.K_SEC_CLASS_GENERIC_PASSWORD)
                val service = Cf.cfString(KEYCHAIN_SERVICE)
                Cf.dictAdd(query, SecurityFramework.K_SEC_ATTR_SERVICE, service)
                Cf.release(service)
                val account = Cf.cfString(KEYCHAIN_ACCOUNT)
                Cf.dictAdd(query, SecurityFramework.K_SEC_ATTR_ACCOUNT, account)
                Cf.release(account)
                val data = Cf.cfData(key)
                Cf.dictAdd(query, SecurityFramework.K_SEC_VALUE_DATA, data)
                Cf.release(data)
                // Protection: kSecAttrAccessible is the dictionary KEY; the
                // kSecAttrAccessibleWhenUnlockedThisDeviceOnly constant is its VALUE.
                // (Using the value constant as the key made SecItemAdd ignore the
                // pair, leaving the item at the default accessibility — which is
                // included in iCloud Keychain backups and restorable on other
                // devices, defeating the device-binding property.)
                Cf.dictAdd(
                    query,
                    SecurityFramework.K_SEC_ATTR_ACCESSIBLE,
                    SecurityFramework.K_SEC_ATTR_ACCESSIBLE_WHEN_UNLOCKED_THIS_DEVICE_ONLY
                )
                val status = sec.SecItemAdd(query, null)
                if (status != SecurityFramework.ERR_SEC_SUCCESS) {
                    throw IllegalStateException("Keychain add failed (OSStatus $status)")
                }
                return key
            } finally {
                Cf.release(query)
            }
        } catch (e: Exception) {
            key.wipe()
            throw e
        }
    }

    private fun deleteKeychainItem() {
        val query = Cf.dictMutable()
        try {
            Cf.dictAdd(query, SecurityFramework.K_SEC_CLASS, SecurityFramework.K_SEC_CLASS_GENERIC_PASSWORD)
            val service = Cf.cfString(KEYCHAIN_SERVICE)
            Cf.dictAdd(query, SecurityFramework.K_SEC_ATTR_SERVICE, service)
            Cf.release(service)
            val account = Cf.cfString(KEYCHAIN_ACCOUNT)
            Cf.dictAdd(query, SecurityFramework.K_SEC_ATTR_ACCOUNT, account)
            Cf.release(account)
            sec.SecItemDelete(query)
        } finally {
            Cf.release(query)
        }
    }
}
