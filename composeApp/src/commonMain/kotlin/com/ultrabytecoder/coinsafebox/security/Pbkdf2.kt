package com.ultrabytecoder.coinsafebox.security

/** PBKDF2 HMAC variant. */
enum class Pbkdf2Algorithm { SHA256, SHA512 }

/**
 * Platform-native PBKDF2 implementation.
 * - Android: javax.crypto (BoringSSL backed)
 * - iOS: CommonCrypto.CCKeyDerivationPBKDF
 *
 * [Pbkdf2Algorithm.SHA512] is required by BIP-39 seed derivation
 * ([SecureMnemonicCode]).
 */
expect object Pbkdf2 {
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        derivedKeyLengthBytes: Int = 32,
        algorithm: Pbkdf2Algorithm = Pbkdf2Algorithm.SHA256
    ): ByteArray
}
