package com.ultrabytecoder.coinsafebox.security.hardware.jna

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/** Opaque Security.framework reference types. */
typealias SecKeyRef = Pointer
typealias SecAccessControlRef = Pointer

/**
 * JNA bindings for the Security.framework subset used by the macOS device-key
 * backend (Secure Enclave key creation/lookup, ECIES encrypt/decrypt,
 * Keychain generic-password items, access control).
 *
 * All functions returning a reference return a +1 (CF_RETAINed) reference that
 * the caller must release with [CoreFoundation.CFRelease].
 */
interface SecurityFramework : Library {

    companion object {
        val INSTANCE: SecurityFramework = Native.load("Security", SecurityFramework::class.java)

        private val lib: NativeLibrary = NativeLibrary.getInstance("Security")

        /** `errSecSuccess` = 0. */
        const val ERR_SEC_SUCCESS: Int = 0

        /** `kSecAttr*` / `kSecClass*` / `kSecValue*` CFString globals. */
        fun cfStringGlobal(name: String): CFStringRef =
            lib.getGlobalVariableAddress(name)!!.getPointer(0)

        // Resolved once at companion init (the globals are process-lifetime constants).
        val K_SEC_CLASS: CFStringRef = cfStringGlobal("kSecClass")
        val K_SEC_CLASS_KEY: CFStringRef = cfStringGlobal("kSecClassKey")
        val K_SEC_CLASS_GENERIC_PASSWORD: CFStringRef = cfStringGlobal("kSecClassGenericPassword")
        val K_SEC_ATTR_TOKEN_ID: CFStringRef = cfStringGlobal("kSecAttrTokenID")
        val K_SEC_ATTR_TOKEN_ID_SECURE_ENCLAVE: CFStringRef = cfStringGlobal("kSecAttrTokenIDSecureEnclave")
        val K_SEC_ATTR_KEY_TYPE: CFStringRef = cfStringGlobal("kSecAttrKeyType")
        val K_SEC_ATTR_KEY_TYPE_EC_SEC_PRIME_RANDOM: CFStringRef = cfStringGlobal("kSecAttrKeyTypeECSECPrimeRandom")
        val K_SEC_ATTR_KEY_SIZE_IN_BITS: CFStringRef = cfStringGlobal("kSecAttrKeySizeInBits")
        val K_SEC_ATTR_LABEL: CFStringRef = cfStringGlobal("kSecAttrLabel")
        val K_SEC_ATTR_ACCESS_CONTROL: CFStringRef = cfStringGlobal("kSecAttrAccessControl")
        val K_SEC_ATTR_ACCESSIBLE: CFStringRef = cfStringGlobal("kSecAttrAccessible")
        val K_SEC_ATTR_ACCESSIBLE_WHEN_UNLOCKED_THIS_DEVICE_ONLY: CFStringRef =
            cfStringGlobal("kSecAttrAccessibleWhenUnlockedThisDeviceOnly")
        val K_SEC_ATTR_SERVICE: CFStringRef = cfStringGlobal("kSecAttrService")
        val K_SEC_ATTR_ACCOUNT: CFStringRef = cfStringGlobal("kSecAttrAccount")
        val K_SEC_VALUE_DATA: CFStringRef = cfStringGlobal("kSecValueData")
        val K_SEC_RETURN_REF: CFStringRef = cfStringGlobal("kSecReturnRef")
        val K_SEC_RETURN_DATA: CFStringRef = cfStringGlobal("kSecReturnData")
        val K_SEC_MATCH_LIMIT: CFStringRef = cfStringGlobal("kSecMatchLimit")
        val K_SEC_MATCH_LIMIT_ONE: CFStringRef = cfStringGlobal("kSecMatchLimitOne")
        val K_SEC_KEY_ALGORITHM_ECIES_ENCRYPTION_COFACTOR_VARIABLE_IV_X963_SHA256_AESGCM: CFStringRef =
            cfStringGlobal("kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM")
    }

    fun SecKeyCreateRandomKey(attributes: CFDictionaryRef?, error: Pointer?): SecKeyRef

    fun SecItemCopyMatching(query: CFDictionaryRef?, result: Pointer): Int

    fun SecItemAdd(query: CFDictionaryRef?, result: Pointer?): Int

    fun SecItemDelete(query: CFDictionaryRef?): Int

    fun SecAccessControlCreateWithFlags(
        allocator: CFAllocatorRef?,
        protection: CFTypeRef?,
        flags: Int,
        error: Pointer?
    ): SecAccessControlRef

    fun SecKeyCopyPublicKey(key: SecKeyRef?): SecKeyRef

    fun SecKeyCreateEncryptedData(
        key: SecKeyRef?,
        algorithm: CFTypeRef?,
        plainText: CFDataRef?,
        error: Pointer?
    ): CFDataRef

    fun SecKeyCreateDecryptedData(
        key: SecKeyRef?,
        algorithm: CFTypeRef?,
        encryptedData: CFDataRef?,
        error: Pointer?
    ): CFDataRef
}
