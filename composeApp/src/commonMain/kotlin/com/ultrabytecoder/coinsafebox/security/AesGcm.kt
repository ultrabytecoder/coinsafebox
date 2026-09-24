package com.ultrabytecoder.coinsafebox.security

/**
 * Thrown by [AesGcm.decrypt] when the authentication tag does not verify
 * (wrong key or tampered ciphertext). The caller must treat this as an
 * authentication failure, never as plaintext.
 */
class AesGcmAuthenticationException(message: String) : Exception(message)

/**
 * AES-256-GCM authenticated encryption used to wrap/unwrap the database key (DEK)
 * with the PIN-derived KEK. Output layout: IV (12 bytes) || ciphertext || tag (16 bytes).
 *
 * [aad] (additional authenticated data) is authenticated but not encrypted: the
 * DEK wrap binds it to the install ID and wrap version, so a wrapped DEK from
 * another install/version cannot be swapped in.
 *
 * Platform-native implementations:
 * - Android: javax.crypto (BoringSSL backed)
 * - iOS: CommonCrypto CCCryptor GCM
 */
expect object AesGcm {
    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray

    /**
     * @throws AesGcmAuthenticationException when the authentication tag does not verify
     * (wrong key or tampered ciphertext).
     */
    fun decrypt(key: ByteArray, encrypted: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray
}