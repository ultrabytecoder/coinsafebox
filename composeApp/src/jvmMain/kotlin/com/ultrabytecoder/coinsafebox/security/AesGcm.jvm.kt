package com.ultrabytecoder.coinsafebox.security

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM implementation via JCA (AES/GCM/NoPadding, BoringSSL-backed on modern
 * JDKs). Output layout: IV (12 bytes) || ciphertext || tag (16 bytes) — same
 * as the Android and iOS implementations.
 */
actual object AesGcm {

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    actual fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        var ciphertext: ByteArray? = null
        var result: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            ciphertext = cipher.doFinal(plaintext)
            // Concatenation copies the bytes — safe to wipe iv/ciphertext below.
            result = iv + ciphertext
            result
        } finally {
            iv.wipe()
            ciphertext?.wipe()
        }
    }

    actual fun decrypt(key: ByteArray, encrypted: ByteArray, aad: ByteArray): ByteArray {
        require(encrypted.size > GCM_IV_LENGTH) { "Encrypted data too short" }
        val iv = encrypted.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encrypted.copyOfRange(GCM_IV_LENGTH, encrypted.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            try {
                cipher.doFinal(ciphertext)
            } catch (e: AEADBadTagException) {
                throw AesGcmAuthenticationException("GCM authentication failed")
            }
        } finally {
            iv.wipe()
            ciphertext.wipe()
        }
    }
}
