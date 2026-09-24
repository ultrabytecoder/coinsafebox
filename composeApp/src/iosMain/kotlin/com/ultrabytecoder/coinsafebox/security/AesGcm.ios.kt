package com.ultrabytecoder.coinsafebox.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValueOf
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import platform.CommonCrypto.CCCryptor
import platform.CommonCrypto.CCCryptorCreateWithMode
import platform.CommonCrypto.CCCryptorGCMAddAAD
import platform.CommonCrypto.CCCryptorGCMAddIV
import platform.CommonCrypto.CCCryptorGCMDecrypt
import platform.CommonCrypto.CCCryptorGCMEncrypt
import platform.CommonCrypto.CCCryptorGCMFinal
import platform.CommonCrypto.CCCryptorRelease
import platform.CommonCrypto.ccNoPadding
import platform.CommonCrypto.kCCAlgorithmAES
import platform.CommonCrypto.kCCModeGCM
import platform.CommonCrypto.kCCSizeAES256
import platform.CommonCrypto.kCCSuccess
import platform.posix.arc4random_buf

@OptIn(ExperimentalForeignApi::class)
actual object AesGcm {

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16

    actual fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        // The key size is passed to CommonCrypto as the fixed kCCSizeAES256 (32).
        // Validate up front so a shorter/longer key can never cause a native
        // over-read of the key buffer (defense in depth — the KDF always yields 32).
        require(key.size == 32) { "AES-256-GCM requires a 32-byte key, got ${key.size}" }
        val iv = ByteArray(GCM_IV_LENGTH).also {
            arc4random_buf(it.refTo(0), GCM_IV_LENGTH.toULong())
        }

        val ciphertext = ByteArray(plaintext.size)
        val tag = ByteArray(GCM_TAG_LENGTH)
        var result: ByteArray? = null

        val cryptorRef = alloc<CPointerVar<CCCryptor?>>()
        try {
            val createStatus = CCCryptorCreateWithMode(
                0u, // kCCEncrypt = 0
                kCCModeGCM,
                kCCAlgorithmAES,
                ccNoPadding,
                null,
                key.refTo(0),
                kCCSizeAES256.toULong(),
                null, // unused for GCM
                0u,
                0u,
                0u,
                cryptorRef.ptr
            )
            check(createStatus == kCCSuccess) { "Failed to create GCM encryptor (status $createStatus)" }

            val addIVStatus = CCCryptorGCMAddIV(cryptorRef.value!!, iv.refTo(0), GCM_IV_LENGTH.toULong())
            check(addIVStatus == kCCSuccess) { "Failed to set GCM IV (status $addIVStatus)" }

            if (aad.isNotEmpty()) {
                val addAADStatus = CCCryptorGCMAddAAD(cryptorRef.value!!, aad.refTo(0), aad.size.toULong())
                check(addAADStatus == kCCSuccess) { "Failed to set GCM AAD (status $addAADStatus)" }
            }

            var bytesProcessed: UInt = 0u
            val encryptStatus = CCCryptorGCMEncrypt(
                cryptorRef.value!!,
                plaintext.refTo(0),
                plaintext.size.toULong(),
                ciphertext.refTo(0),
                cValueOf(bytesProcessed)
            )
            check(encryptStatus == kCCSuccess) { "GCM encryption failed (status $encryptStatus)" }

            val finalStatus = CCCryptorGCMFinal(cryptorRef.value!!, tag.refTo(0), GCM_TAG_LENGTH.toULong())
            check(finalStatus == kCCSuccess) { "GCM tag generation failed (status $finalStatus)" }

            // Copies bytes into a single output — safe to wipe iv/ciphertext/tag below.
            result = ByteArray(iv.size + ciphertext.size + tag.size)
            iv.copyInto(result!!, 0, 0, iv.size)
            ciphertext.copyInto(result!!, iv.size, 0, ciphertext.size)
            tag.copyInto(result!!, iv.size + ciphertext.size, 0, tag.size)
        } finally {
            if (cryptorRef.value != null) {
                CCCryptorRelease(cryptorRef.value!!)
            }
            iv.wipe()
            ciphertext.wipe()
            tag.wipe()
        }

        return result!!
    }

    actual fun decrypt(key: ByteArray, encrypted: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256-GCM requires a 32-byte key, got ${key.size}" }
        require(encrypted.size > GCM_IV_LENGTH + GCM_TAG_LENGTH) { "Encrypted data too short" }

        val iv = encrypted.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encrypted.copyOfRange(
            GCM_IV_LENGTH,
            encrypted.size - GCM_TAG_LENGTH
        )
        val tag = encrypted.copyOfRange(
            encrypted.size - GCM_TAG_LENGTH,
            encrypted.size
        )

        val plaintext = ByteArray(ciphertext.size)

        val cryptorRef = alloc<CPointerVar<CCCryptor?>>()
        return try {
            val createStatus = CCCryptorCreateWithMode(
                1u, // kCCDecrypt = 1
                kCCModeGCM,
                kCCAlgorithmAES,
                ccNoPadding,
                null,
                key.refTo(0),
                kCCSizeAES256.toULong(),
                null,
                0u,
                0u,
                0u,
                cryptorRef.ptr
            )
            check(createStatus == kCCSuccess) { "Failed to create GCM decryptor (status $createStatus)" }

            val addIVStatus = CCCryptorGCMAddIV(cryptorRef.value!!, iv.refTo(0), GCM_IV_LENGTH.toULong())
            check(addIVStatus == kCCSuccess) { "Failed to set GCM IV (status $addIVStatus)" }

            if (aad.isNotEmpty()) {
                val addAADStatus = CCCryptorGCMAddAAD(cryptorRef.value!!, aad.refTo(0), aad.size.toULong())
                check(addAADStatus == kCCSuccess) { "Failed to set GCM AAD (status $addAADStatus)" }
            }

            var bytesProcessed: UInt = 0u
            val decryptStatus = CCCryptorGCMDecrypt(
                cryptorRef.value!!,
                ciphertext.refTo(0),
                ciphertext.size.toULong(),
                plaintext.refTo(0),
                cValueOf(bytesProcessed)
            )
            check(decryptStatus == kCCSuccess) { "GCM decryption failed (status $decryptStatus)" }

            val finalStatus = CCCryptorGCMFinal(cryptorRef.value!!, tag.refTo(0), GCM_TAG_LENGTH.toULong())
            if (finalStatus != kCCSuccess) {
                plaintext.fill(0)
                throw AesGcmAuthenticationException("GCM authentication tag verification failed (status $finalStatus)")
            }

            plaintext
        } finally {
            if (cryptorRef.value != null) {
                CCCryptorRelease(cryptorRef.value!!)
            }
            iv.wipe()
            ciphertext.wipe()
            tag.wipe()
        }
    }
}