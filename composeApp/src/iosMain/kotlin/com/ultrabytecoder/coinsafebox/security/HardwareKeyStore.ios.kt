package com.ultrabytecoder.coinsafebox.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrLabel
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnRef

/**
 * Secure Enclave ECC P-256 key (non-exportable, no user-presence requirement — the key
 * is usable without a biometric prompt). Encryption uses ECIES-AES-GCM (X9.63, cofactor,
 * variable IV) via SecKeyCreateEncryptedData/SecKeyCreateDecryptedData.
 *
 * `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` binds the key to this device install —
 * the wrapped key material is unusable on another device.
 */
@OptIn(ExperimentalForeignApi::class)
actual object HardwareKeyStore {

    private const val KEY_LABEL = "coinsafebox.hw"

    private fun keyAttributes(): CFDictionaryRef? = memScoped {
        val accessControl = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            0u, // no user-presence flag: no biometric prompt
            null
        )
        val attrs = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr
        )
        CFDictionaryAddValue(attrs, kSecAttrTokenID, kSecAttrTokenIDSecureEnclave)
        CFDictionaryAddValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        // CFBridgingRetain returns a +1 reference; the dictionary retains its own
        // copy on CFDictionaryAddValue, so release ours right after to avoid a leak.
        val keySize = CFBridgingRetain(256)
        CFDictionaryAddValue(attrs, kSecAttrKeySizeInBits, keySize)
        CFRelease(keySize)
        CFDictionaryAddValue(attrs, kSecAttrAccessControl, accessControl)
        CFRelease(accessControl)
        val label = CFBridgingRetain(KEY_LABEL)
        CFDictionaryAddValue(attrs, kSecAttrLabel, label)
        CFRelease(label)
        attrs
    }

    private fun findKey(): SecKeyRef? = memScoped {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr
        )
        CFDictionaryAddValue(query, kSecClass, kSecClassKey)
        CFDictionaryAddValue(query, kSecAttrTokenID, kSecAttrTokenIDSecureEnclave)
        val label = CFBridgingRetain(KEY_LABEL)
        CFDictionaryAddValue(query, kSecAttrLabel, label)
        CFRelease(label)
        val returnRef = CFBridgingRetain(true)
        CFDictionaryAddValue(query, kSecReturnRef, returnRef)
        CFRelease(returnRef)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status == errSecSuccess) result.value as SecKeyRef? else null
    }

    private val keyLock = Any()

    private fun getOrCreateKey(): SecKeyRef? = synchronized(keyLock) {
        findKey()?.let { return@synchronized it }
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val attrs = keyAttributes()
            val key = SecKeyCreateRandomKey(attrs, error.ptr)
            attrs?.let { CFRelease(it) }
            if (key == null) {
                // Release the CFErrorRef so a failed creation does not leak (NEW-13).
                error.value?.let { CFRelease(it) }
                return@synchronized findKey()
            }
            key
        }
    }

    actual fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val key = getOrCreateKey()
            ?: throw IllegalStateException("Failed to create Secure Enclave key")
        var publicKey: SecKeyRef? = null
        return try {
            publicKey = SecKeyCopyPublicKey(key)
                ?: throw IllegalStateException("Failed to export Secure Enclave public key")
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
            memScoped {
                val error = alloc<CFErrorRefVar>()
                val plaintextData = CFDataCreate(kCFAllocatorDefault, bound.refTo(0), bound.size.toLong())
                val encryptedData = SecKeyCreateEncryptedData(
                    publicKey!!,
                    kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM,
                    plaintextData,
                    error.ptr
                )
                CFRelease(plaintextData)
                if (encryptedData == null) {
                    error.value?.let { CFRelease(it) }
                    throw IllegalStateException("Secure Enclave encryption failed")
                }
                val length = CFDataGetLength(encryptedData).toInt()
                val result = CFDataGetBytePtr(encryptedData)!!.readBytes(length)
                CFRelease(encryptedData)
                result
            }
        } finally {
            // +1 references from SecKeyCreateRandomKey/SecKeyCopyPublicKey (NEW-8).
            publicKey?.let { CFRelease(it) }
            CFRelease(key)
        }
    }

    actual fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
        val key = findKey()
            ?: throw HardwareKeyInvalidatedException("Secure Enclave key missing")

        return try {
            val plaintext = memScoped {
                val error = alloc<CFErrorRefVar>()
                val ciphertextData = CFDataCreate(kCFAllocatorDefault, encrypted.refTo(0), encrypted.size.toLong())
                val plaintextData = SecKeyCreateDecryptedData(
                    key,
                    kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM,
                    ciphertextData,
                    error.ptr
                )
                CFRelease(ciphertextData)
                if (plaintextData == null) {
                    error.value?.let { CFRelease(it) }
                    throw AesGcmAuthenticationException("Secure Enclave decryption failed")
                }
                val length = CFDataGetLength(plaintextData).toInt()
                val result = CFDataGetBytePtr(plaintextData)!!.readBytes(length)
                CFRelease(plaintextData)
                result
            }
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
            // +1 reference from SecItemCopyMatching in findKey() (NEW-8).
            CFRelease(key)
        }
    }

    actual fun deleteKey() {
        memScoped {
            val query = CFDictionaryCreateMutable(
                kCFAllocatorDefault,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr
            )
            CFDictionaryAddValue(query, kSecClass, kSecClassKey)
            CFDictionaryAddValue(query, kSecAttrTokenID, kSecAttrTokenIDSecureEnclave)
            val label = CFBridgingRetain(KEY_LABEL)
            CFDictionaryAddValue(query, kSecAttrLabel, label)
            CFRelease(label)
            SecItemDelete(query)
            CFRelease(query)
        }
    }

    actual fun purgeCache() {
        // The Secure Enclave key is non-exportable and never cached — nothing to purge.
    }

    actual fun configureInstallId(id: ByteArray) {
        // The Secure Enclave key is already device-bound — no install-id binding needed.
    }
}