package com.ultrabytecoder.coinsafebox.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id via BouncyCastle (audited, constant-time, memory-hard).
 */
actual object Kdf {

    actual val supportsArgon2id: Boolean = true

    actual fun derive(
        password: ByteArray,
        salt: ByteArray,
        timeCost: Int,
        derivedKeyLengthBytes: Int,
        memoryKib: Int,
        parallelism: Int
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withIterations(timeCost)
            .withMemoryAsKB(memoryKib)
            .withParallelism(parallelism)
            .build()
        try {
            val generator = Argon2BytesGenerator()
            generator.init(params)
            val out = ByteArray(derivedKeyLengthBytes)
            generator.generateBytes(password, out)
            return out
        } finally {
            // Only the parameters (salt) are cleared here. The caller owns
            // [password] and [salt] and is responsible for wiping them — this
            // function may be called more than once with the same buffers
            // (see KeyManager's Argon2id -> PBKDF2 fallback), so it must not
            // wipe its inputs. BouncyCastle copies the password into its
            // internal memory-hard working set and exposes no public reset(),
            // so those internal copies cannot be zeroed from here either.
            params.clear()
        }
    }
}
