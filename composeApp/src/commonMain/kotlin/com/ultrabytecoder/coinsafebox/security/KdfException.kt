package com.ultrabytecoder.coinsafebox.security

/**
 * Thrown by [Kdf.derive] when the platform's Argon2id backend cannot satisfy
 * the requested parameters (e.g. libsodium memory-allocation failure under
 * pressure). [KeyManager] catches this during setup and degrades the envelope
 * to the PBKDF2 fallback.
 */
class KdfException(message: String) : Exception(message)
